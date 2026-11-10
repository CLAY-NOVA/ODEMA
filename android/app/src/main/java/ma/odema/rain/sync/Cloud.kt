package ma.odema.rain.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import ma.odema.core.OdemaJson
import ma.odema.core.ops.CloudConfig
import ma.odema.core.ops.Col
import ma.odema.core.ops.Incident
import ma.odema.core.ops.TeamRole
import ma.odema.rain.OdemaApplication
import ma.odema.rain.localized
import ma.odema.rain.notify.Notifier

data class Member(val uid: String, val name: String, val email: String, val role: TeamRole)

enum class CloudStatus { OFF, SIGNED_OUT, CONNECTING, ONLINE, WAITING_APPROVAL, ERROR }

data class CloudState(
    val config: CloudConfig? = null,
    val status: CloudStatus = CloudStatus.OFF,
    val uid: String? = null,
    val email: String? = null,
    /** Shown on everything this phone records ("updated by"); works without the cloud too. */
    val name: String = "",
    val role: TeamRole = TeamRole.PENDING,
    val members: List<Member> = emptyList(),
    val error: String? = null,
    val lastServerSync: Long? = null,
) {
    val canEdit: Boolean get() = status != CloudStatus.ONLINE || role.canEdit
    val isAdmin: Boolean get() = role == TeamRole.ADMIN
}

/**
 * Team sync through the farm's own Firebase project: every phone keeps a full copy (works
 * offline), sends its changes and receives coworkers' changes within a second or two.
 *
 * Firestore layout: farms/{farmId}/members/{uid} and farms/{farmId}/{collection}/{id} with
 * { json, updatedAt, updatedBy, deleted, uid }. Access is checked by the rules in firebase/.
 */
class Cloud(private val app: OdemaApplication) {
    private val prefs = app.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(CloudState(name = prefs.getString("name", "").orEmpty()))
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private var fbApp: FirebaseApp? = null
    private val listeners = mutableListOf<ListenerRegistration>()
    private var memberListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private val reconciled = mutableSetOf<Col>()

    private val auth get() = fbApp?.let { FirebaseAuth.getInstance(it) }
    private val db get() = fbApp?.let { FirebaseFirestore.getInstance(it) }
    private val farmPath get() = "farms/${_state.value.config?.farmId ?: "odema"}"

    fun start() {
        app.farm.sink = ::push
        app.ops.sink = ::push
        app.ops.userName = { _state.value.name.ifBlank { _state.value.email.orEmpty().substringBefore('@') } }
        val config = prefs.getString("config", null)?.let { runCatching { OdemaJson.decodeFromString(CloudConfig.serializer(), it) }.getOrNull() }
        if (config != null) connect(config)
    }

    fun setName(name: String) {
        prefs.edit().putString("name", name.trim()).apply()
        _state.update { it.copy(name = name.trim()) }
        val uid = _state.value.uid ?: return
        db?.document("$farmPath/members/$uid")?.update("name", name.trim())
    }

    /** Joins the farm described by a share code or google-services.json. Returns false if the text is not one. */
    fun configure(text: String): Boolean {
        val config = CloudConfig.parse(text, app.packageName) ?: return false
        prefs.edit().putString("config", OdemaJson.encodeToString(CloudConfig.serializer(), config)).apply()
        disconnectFirebase()
        connect(config)
        return true
    }

    fun forget() {
        auth?.signOut()
        disconnectFirebase()
        prefs.edit().remove("config").apply()
        ma.odema.rain.work.WorkScheduler.scheduleTeam(app, false)
        _state.update { CloudState(name = it.name) }
    }

    private fun connect(config: CloudConfig) {
        _state.update { it.copy(config = config, status = CloudStatus.SIGNED_OUT, error = null) }
        ma.odema.rain.work.WorkScheduler.scheduleTeam(app, true)
        try {
            val options = FirebaseOptions.Builder()
                .setProjectId(config.projectId)
                .setApiKey(config.apiKey)
                .setApplicationId(config.appId)
                .apply { if (config.storageBucket.isNotBlank()) setStorageBucket(config.storageBucket) }
                .build()
            fbApp = FirebaseApp.getApps(app).firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(app, options, APP_NAME)
            auth!!.addAuthStateListener { a -> onUser(a.currentUser?.uid, a.currentUser?.email) }
        } catch (e: Exception) {
            Log.w(TAG, "firebase init", e)
            _state.update { it.copy(status = CloudStatus.ERROR, error = e.message) }
        }
    }

    private fun disconnectFirebase() {
        detach()
        memberListener?.remove(); memberListener = null
        fbApp?.let { runCatching { it.delete() } }
        fbApp = null
    }

    suspend fun signIn(email: String, password: String) {
        val a = auth ?: error("not configured")
        a.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun signUp(name: String, email: String, password: String) {
        val a = auth ?: error("not configured")
        if (name.isNotBlank()) setName(name)
        val result = a.createUserWithEmailAndPassword(email.trim(), password).await()
        // The administrator rule only trusts verified addresses.
        runCatching { result.user?.sendEmailVerification()?.await() }
    }

    /** After clicking the verification link: refresh the sign-in so the rules see the verified address. */
    suspend fun recheck() {
        val user = auth?.currentUser ?: return
        user.reload().await()
        user.getIdToken(true).await()
        onUser(user.uid, user.email)
    }

    suspend fun resetPassword(email: String) {
        auth?.sendPasswordResetEmail(email.trim())?.await()
    }

    fun signOut() {
        auth?.signOut()
    }

    private fun onUser(uid: String?, email: String?) {
        detach()
        memberListener?.remove(); memberListener = null
        if (uid == null) {
            _state.update { it.copy(status = CloudStatus.SIGNED_OUT, uid = null, email = null, role = TeamRole.PENDING, members = emptyList()) }
            return
        }
        _state.update { it.copy(status = CloudStatus.CONNECTING, uid = uid, email = email, error = null) }
        val firestore = db ?: return
        val ref = firestore.document("$farmPath/members/$uid")
        app.scope.launch {
            runCatching {
                val snap = ref.get().await()
                if (!snap.exists()) {
                    ref.set(mapOf("name" to _state.value.name.ifBlank { email.orEmpty().substringBefore('@') }, "email" to email.orEmpty(),
                        "role" to TeamRole.PENDING.key, "createdAt" to System.currentTimeMillis())).await()
                }
                if (TeamRole.of(ref.get().await().getString("role")) == TeamRole.PENDING) {
                    // Succeeds only for the admin e-mail(s) written in the security rules.
                    runCatching { ref.update("role", TeamRole.ADMIN.key).await() }
                }
            }.onFailure { e -> _state.update { it.copy(status = CloudStatus.ERROR, error = e.message) } }
            memberListener = ref.addSnapshotListener { doc, e ->
                if (e != null) { _state.update { it.copy(status = CloudStatus.ERROR, error = e.message) }; return@addSnapshotListener }
                val role = TeamRole.of(doc?.getString("role"))
                doc?.getString("name")?.takeIf { it.isNotBlank() && _state.value.name.isBlank() }?.let { n ->
                    prefs.edit().putString("name", n).apply(); _state.update { it.copy(name = n) }
                }
                _state.update { it.copy(role = role, status = if (role.canRead) CloudStatus.ONLINE else CloudStatus.WAITING_APPROVAL) }
                if (role.canRead && listeners.isEmpty()) attach() else if (!role.canRead) detach()
                watchMembers(role == TeamRole.ADMIN || role == TeamRole.DIRECTION)
            }
        }
    }

    private fun watchMembers(on: Boolean) {
        membersListener?.remove(); membersListener = null
        if (!on) return
        membersListener = db?.collection("$farmPath/members")?.addSnapshotListener { snap, _ ->
            val members = snap?.documents.orEmpty().map {
                Member(it.id, it.getString("name").orEmpty(), it.getString("email").orEmpty(), TeamRole.of(it.getString("role")))
            }.sortedBy { it.name.lowercase() }
            _state.update { it.copy(members = members) }
        }
    }

    suspend fun setRole(uid: String, role: TeamRole) {
        db?.document("$farmPath/members/$uid")?.update("role", role.key)?.await()
    }

    private fun attach() {
        val firestore = db ?: return
        reconciled.clear()
        for (col in Col.entries) {
            var initial = true
            listeners += firestore.collection("$farmPath/${col.key}").addSnapshotListener { snap, e ->
                if (e != null || snap == null) {
                    if (e != null) _state.update { it.copy(error = e.message) }
                    return@addSnapshotListener
                }
                for (change in snap.documentChanges) {
                    val doc = change.document
                    if (doc.metadata.hasPendingWrites()) continue
                    val deleted = doc.getBoolean("deleted") == true || change.type == DocumentChange.Type.REMOVED
                    val json = if (deleted) null else doc.getString("json") ?: continue
                    applyRemote(col, doc.id, json)
                    if (!initial && change.type == DocumentChange.Type.ADDED && json != null && col == Col.INCIDENTS &&
                        doc.getString("uid") != _state.value.uid
                    ) notifyIncident(json)
                }
                initial = false
                if (!snap.metadata.isFromCache && reconciled.add(col)) {
                    // First contact with the server this session: send what only this phone has (or has newer).
                    val server = snap.documents.associate { it.id to (it.getLong("updatedAt") ?: 0L) }
                    if (col.isFarm) {
                        app.farm.localDocs(col).filterKeys { it !in server }.forEach { (id, json) -> push(col, id, json) }
                    } else {
                        app.ops.localDocs(col).filter { (id, v) -> id !in server || v.first > (server[id] ?: 0L) }
                            .forEach { (id, v) -> push(col, id, v.second) }
                    }
                    _state.update { it.copy(lastServerSync = System.currentTimeMillis()) }
                }
            }
        }
    }

    private fun detach() {
        listeners.forEach { it.remove() }
        listeners.clear()
        membersListener?.remove(); membersListener = null
    }

    private fun applyRemote(col: Col, id: String, json: String?) {
        if (col.isFarm) app.farm.applyRemote(col, id, json) else app.ops.applyRemote(col, id, json)
    }

    /** Sends one local change. Firestore queues it while offline and delivers it later. */
    fun push(col: Col, id: String, json: String?) {
        val s = _state.value
        if (s.status != CloudStatus.ONLINE || !s.role.canEdit) return
        val firestore = db ?: return
        firestore.document("$farmPath/${col.key}/$id").set(
            mapOf(
                "json" to (json ?: ""),
                "deleted" to (json == null),
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to app.ops.userName(),
                "uid" to s.uid,
            ),
        ).addOnFailureListener { e -> _state.update { it.copy(error = e.message) } }
    }

    private fun notifyIncident(json: String) {
        val incident = runCatching { OdemaJson.decodeFromString(Incident.serializer(), json) }.getOrNull() ?: return
        Notifier.postIncident(app.localized(app.repository.state.value.appSettings.language), incident, app.ops.state.value.data)
    }

    /**
     * Background check (no listener running): fetches breakdowns reported since the last check
     * and notifies them. Returns how many were new.
     */
    suspend fun checkInBackground(): Int {
        if (_state.value.config == null) return 0
        val uid = auth?.currentUser?.uid ?: return 0
        val since = prefs.getLong("lastCheck", System.currentTimeMillis() - 3_600_000)
        val snap = db?.collection("$farmPath/${Col.INCIDENTS.key}")?.whereGreaterThan("updatedAt", since)?.get(Source.SERVER)?.await() ?: return 0
        var count = 0
        for (doc in snap.documents) {
            val json = doc.getString("json")?.takeIf { doc.getBoolean("deleted") != true }
            app.ops.applyRemote(Col.INCIDENTS, doc.id, json)
            if (json != null && doc.getString("uid") != uid && (doc.getLong("updatedAt") ?: 0) > since) {
                val incident = runCatching { OdemaJson.decodeFromString(Incident.serializer(), json) }.getOrNull()
                if (incident != null && incident.reportedAt > since) { notifyIncident(json); count++ }
            }
        }
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        return count
    }

    companion object {
        private const val TAG = "OdemaCloud"
        private const val APP_NAME = "odema"
    }
}

