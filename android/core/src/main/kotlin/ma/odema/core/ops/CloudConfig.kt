package ma.odema.core.ops

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ma.odema.core.OdemaJson
import java.util.Base64

/**
 * Where the team's shared data lives: a Firebase project the farm owns. These values are not
 * secrets (Firebase puts them in every app); access is controlled by sign-in and the security rules.
 */
@Serializable
data class CloudConfig(
    val projectId: String,
    val apiKey: String,
    val appId: String,
    val storageBucket: String = "",
    /** Several farms can share one project; each has its own folder. */
    val farmId: String = "odema",
) {
    /** A one-line code to send to coworkers (WhatsApp, SMS) so their phones join the same farm. */
    fun shareCode(): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(OdemaJson.encodeToString(serializer(), this).toByteArray())

    companion object {
        const val PREFIX = "ODEMA1:"

        /** Accepts a share code or the google-services.json downloaded from the Firebase console. */
        fun parse(text: String, packageName: String): CloudConfig? {
            val t = text.trim()
            val code = Regex("""ODEMA1:[A-Za-z0-9_-]+""").find(t)?.value
            if (code != null) return runCatching {
                val json = String(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)))
                OdemaJson.decodeFromString(serializer(), json)
            }.getOrNull()?.takeIf { it.valid }
            return runCatching { fromGoogleServices(OdemaJson.parseToJsonElement(t).jsonObject, packageName) }.getOrNull()?.takeIf { it.valid }
        }

        private fun fromGoogleServices(root: JsonObject, packageName: String): CloudConfig {
            val project = root["project_info"]!!.jsonObject
            val clients = root["client"]!!.jsonArray.map { it.jsonObject }
            val client = clients.firstOrNull {
                it["client_info"]?.jsonObject?.get("android_client_info")?.jsonObject?.get("package_name")?.jsonPrimitive?.content == packageName
            } ?: clients.first()
            return CloudConfig(
                projectId = project["project_id"]!!.jsonPrimitive.content,
                storageBucket = project["storage_bucket"]?.jsonPrimitive?.content.orEmpty(),
                appId = client["client_info"]!!.jsonObject["mobilesdk_app_id"]!!.jsonPrimitive.content,
                apiKey = client["api_key"]!!.jsonArray.first().jsonObject["current_key"]!!.jsonPrimitive.content,
            )
        }
    }

    val valid: Boolean get() = projectId.isNotBlank() && apiKey.isNotBlank() && appId.isNotBlank() && farmId.matches(Regex("[A-Za-z0-9_-]+"))
}

/** Team roles, from most to least rights. Enforced by the Firestore rules, mirrored in the app. */
enum class TeamRole(val canEdit: Boolean, val canRead: Boolean) {
    ADMIN(true, true), DIRECTION(true, true), CHEF(true, true), SAISIE(true, true), LECTURE(false, true), PENDING(false, false), BLOCKED(false, false);

    val key: String get() = name.lowercase()

    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: PENDING }
}
