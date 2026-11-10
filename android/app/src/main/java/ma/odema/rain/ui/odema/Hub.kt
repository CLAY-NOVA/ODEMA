package ma.odema.rain.ui.odema

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ma.odema.core.ops.Col
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.TeamRole
import ma.odema.rain.R
import ma.odema.rain.report.WeeklyReport
import ma.odema.rain.sync.CloudStatus
import ma.odema.rain.ui.MainViewModel
import ma.odema.rain.ui.components.BackTopBar
import ma.odema.rain.ui.components.ClickRow
import ma.odema.rain.ui.components.SectionHeader
import java.time.LocalDate

/** Today at a glance, on top of the dashboard: what needs attention. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpsTodayCard(vm: MainViewModel, onIncidents: () -> Unit, onWorks: () -> Unit, onStock: () -> Unit, onTeam: () -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val today = LocalDate.now().toEpochDay()
    val d = ops.data
    val open = d.incidents.count { it.open }
    val urgent = d.incidents.count { it.open && it.urgent }
    val late = d.works.count { it.overdue(today) }
    val week = WeeklyReport.weekOf(LocalDate.now())
    val thisWeek = d.works.count { !it.done && it.date in week.first..week.second }
    val low = OpsAnalytics.lowStock(d).size
    val sheet = d.attendance.firstOrNull { it.date == today }
    TileRow { m ->
        StatTile(stringResource(R.string.today_incidents), "$open", m.clickable(onClick = onIncidents),
            sub = if (urgent > 0) stringResource(R.string.today_urgent, urgent) else null, accent = if (open > 0) MaterialTheme.colorScheme.error else null)
        StatTile(stringResource(R.string.today_works_week), "$thisWeek", m.clickable(onClick = onWorks),
            sub = if (late > 0) stringResource(R.string.works_late_count, late) else null, accent = if (late > 0) Color(0xFFE65100) else null)
    }
    TileRow { m ->
        StatTile(stringResource(R.string.today_low_stock), "$low", m.clickable(onClick = onStock), accent = if (low > 0) MaterialTheme.colorScheme.error else null)
        StatTile(stringResource(R.string.today_team), sheet?.let { "${it.present.size + it.casuals}" } ?: "–", m.clickable(onClick = onTeam),
            sub = sheet?.let { stringResource(R.string.today_team_sub, it.present.size, it.casuals) } ?: stringResource(R.string.today_team_none))
    }
}

/** Everything that is not a tab. */
@Composable
fun MoreScreen(vm: MainViewModel, go: (String) -> Unit) {
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.more_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 12.dp))
        SectionHeader(stringResource(R.string.more_farm))
        ClickRow("🗺️ " + stringResource(R.string.od_nav_parcels), subtitle = stringResource(R.string.more_parcels_sub)) { go(MoreDest.PARCELS) }
        ClickRow("🌾 " + stringResource(R.string.od_nav_assolement), subtitle = stringResource(R.string.more_assolement_sub)) { go(MoreDest.ASSOLEMENT) }
        ClickRow("💧 " + stringResource(R.string.assets_title), value = "${ops.data.assets.size}", subtitle = stringResource(R.string.more_assets_sub)) { go(MoreDest.ASSETS) }
        ClickRow("📦 " + stringResource(R.string.stock_title), value = "${ops.data.products.size}", subtitle = stringResource(R.string.more_stock_sub)) { go(MoreDest.STOCK) }
        ClickRow("🚛 " + stringResource(R.string.harvest_title), subtitle = stringResource(R.string.more_harvest_sub)) { go(MoreDest.HARVESTS) }
        ClickRow("👷 " + stringResource(R.string.team_title), value = "${ops.data.staff.count { it.active }}", subtitle = stringResource(R.string.more_team_sub)) { go(MoreDest.TEAM) }
        SectionHeader(stringResource(R.string.more_reports))
        ClickRow("📄 " + stringResource(R.string.report_screen), subtitle = stringResource(R.string.more_report_sub)) { go(MoreDest.REPORT) }
        ClickRow("🕑 " + stringResource(R.string.activity_title), subtitle = stringResource(R.string.more_activity_sub)) { go(MoreDest.ACTIVITY) }
        ClickRow("🌦️ " + stringResource(R.string.od_nav_dashboard), subtitle = stringResource(R.string.more_weather_sub)) { go(MoreDest.DASHBOARD) }
        SectionHeader(stringResource(R.string.more_app))
        ClickRow("☁️ " + stringResource(R.string.sync_title), value = syncLabel(context, cloud.status), subtitle = stringResource(R.string.more_sync_sub)) { go(MoreDest.SYNC) }
        ClickRow("⚙️ " + stringResource(R.string.nav_settings)) { go(MoreDest.SETTINGS) }
        Spacer(Modifier.padding(24.dp))
    }
}

object MoreDest {
    const val PARCELS = "parcels"; const val ASSOLEMENT = "assolement"; const val ASSETS = "assets"; const val STOCK = "stock"
    const val HARVESTS = "harvests"; const val TEAM = "team"; const val REPORT = "report"; const val ACTIVITY = "activity"
    const val DASHBOARD = "dashboard"; const val SYNC = "sync"; const val SETTINGS = "settings"
}

fun syncLabel(context: Context, status: CloudStatus) = context.getString(when (status) {
    CloudStatus.OFF -> R.string.sync_off
    CloudStatus.SIGNED_OUT -> R.string.sync_signed_out
    CloudStatus.CONNECTING -> R.string.sync_connecting
    CloudStatus.ONLINE -> R.string.sync_online
    CloudStatus.WAITING_APPROVAL -> R.string.sync_waiting
    CloudStatus.ERROR -> R.string.sync_error
})

@Composable
fun ReportScreen(vm: MainViewModel, onBack: () -> Unit) {
    val farm by vm.farmState.collectAsStateWithLifecycle()
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var busy by remember { mutableStateOf(false) }
    val (from, to) = WeeklyReport.weekOf(anchor)
    val report = remember(farm, ops, from) { WeeklyReport.build(farm, ops.data, from, to) }
    val text = remember(report) { WeeklyReport.text(context, farm, ops.data, report) }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.report_screen), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { anchor = anchor.minusWeeks(1) }) { Text("◀") }
                Text(WeeklyReport.title(context, report), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { anchor = anchor.plusWeeks(1) }, enabled = anchor.plusWeeks(1) <= LocalDate.now().plusWeeks(1)) { Text("▶") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { runCatching { WeeklyReport.pdf(context, farm, ops.data, report, cloud.name) }.getOrNull() }
                        busy = false
                        file?.let { WeeklyReport.share(context, it) }
                    }
                }) { Text("📄 " + stringResource(R.string.report_pdf)) }
                OutlinedButton(onClick = { WeeklyReport.shareText(context, text) }) { Text("💬 " + stringResource(R.string.report_whatsapp)) }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ActivityScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (Col, String) -> Unit) {
    val ops by vm.opsState.collectAsStateWithLifecycle()
    val items = remember(ops.data) { OpsAnalytics.activity(ops.data, 80) }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.activity_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (items.isEmpty()) item { EmptyState("🕑", stringResource(R.string.activity_empty)) }
            items(items, key = { "${it.col}-${it.id}" }) { a ->
                val label = if (a.col == Col.ATTENDANCE) stringResource(R.string.activity_attendance, a.label) else a.label
                OpsRow(a.emoji, label, listOf(a.by.ifBlank { "—" }, relativeTime(a.at)).joinToString(" · "), { onOpen(a.col, a.id) })
                HorizontalDivider(Modifier.padding(start = 64.dp))
            }
        }
    }
}

/** Team sync: join the farm's cloud, sign in, approve coworkers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(vm: MainViewModel, onBack: () -> Unit) {
    val cloud by vm.cloudState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(cloud.name) }
    var code by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { u ->
            val text = runCatching { context.contentResolver.openInputStream(u)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
            message = context.getString(if (vm.cloud.configure(text)) R.string.sync_joined else R.string.sync_bad_code)
        }
    }
    fun run(block: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            runCatching { block() }.onFailure { message = it.message ?: it.toString() }
            busy = false
        }
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.sync_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyMedium)
            FormText(stringResource(R.string.sync_your_name), name, { name = it })
            OutlinedButton(onClick = { vm.cloud.setName(name) }, enabled = name.trim() != cloud.name) { Text(stringResource(R.string.action_save)) }
            HorizontalDivider()
            Text(stringResource(R.string.sync_status, syncLabel(context, cloud.status)), style = MaterialTheme.typography.titleSmall)
            cloud.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            when (cloud.status) {
                CloudStatus.OFF -> {
                    Text(stringResource(R.string.sync_join_help), style = MaterialTheme.typography.bodyMedium)
                    FormText(stringResource(R.string.sync_code), code, { code = it }, singleLine = false)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { message = context.getString(if (vm.cloud.configure(code)) R.string.sync_joined else R.string.sync_bad_code) }, enabled = code.isNotBlank()) {
                            Text(stringResource(R.string.sync_join))
                        }
                        OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text(stringResource(R.string.sync_pick_json)) }
                    }
                    Text(stringResource(R.string.sync_admin_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CloudStatus.SIGNED_OUT, CloudStatus.ERROR -> if (cloud.uid == null) {
                    FormText(stringResource(R.string.sync_email), email, { email = it }, keyboard = KeyboardType.Email)
                    androidx.compose.material3.OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.sync_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && email.isNotBlank() && password.length >= 6, onClick = { run { vm.cloud.signIn(email, password) } }) { Text(stringResource(R.string.sync_sign_in)) }
                        OutlinedButton(enabled = !busy && email.isNotBlank() && password.length >= 6 && name.isNotBlank(), onClick = { run { vm.cloud.signUp(name, email, password) } }) {
                            Text(stringResource(R.string.sync_sign_up))
                        }
                        TextButton(enabled = email.isNotBlank(), onClick = { run { vm.cloud.resetPassword(email); message = context.getString(R.string.sync_reset_sent) } }) {
                            Text(stringResource(R.string.sync_forgot))
                        }
                    }
                    Text(stringResource(R.string.sync_signup_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {}
            }
            if (cloud.uid != null) {
                Text(listOfNotNull(cloud.email, Labels.team(context, cloud.role)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                if (cloud.status == CloudStatus.WAITING_APPROVAL) {
                    Text(stringResource(R.string.sync_waiting_help), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.sync_verify_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !busy, onClick = { run { vm.cloud.recheck() } }) { Text("↻ " + stringResource(R.string.sync_check_again)) }
                }
                if (cloud.status == CloudStatus.ONLINE) Text(stringResource(R.string.sync_online_help), style = MaterialTheme.typography.bodyMedium)
                if (!cloud.role.canEdit && cloud.status == CloudStatus.ONLINE) Text(stringResource(R.string.sync_read_only), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { vm.cloud.signOut() }) { Text(stringResource(R.string.sync_sign_out)) }
            }
            cloud.config?.let { config ->
                HorizontalDivider()
                Text(stringResource(R.string.sync_share_help), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.sync_share_text, config.shareCode())), null))
                    }) { Text("📤 " + stringResource(R.string.sync_share)) }
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ODEMA", config.shareCode()))
                        message = context.getString(R.string.sync_copied)
                    }) { Text(stringResource(R.string.sync_copy)) }
                    TextButton(onClick = { vm.cloud.forget() }) { Text(stringResource(R.string.sync_leave), color = MaterialTheme.colorScheme.error) }
                }
                Text(stringResource(R.string.sync_project, config.projectId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (cloud.isAdmin || cloud.role == TeamRole.DIRECTION) {
                HorizontalDivider()
                SectionHeader(stringResource(R.string.sync_members))
                cloud.members.forEach { m ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("${m.name.ifBlank { m.email }} · ${Labels.team(context, m.role)}", fontWeight = FontWeight.Medium)
                        Text(m.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cloud.isAdmin && m.uid != cloud.uid) FormChips(null,
                            listOf(TeamRole.DIRECTION, TeamRole.CHEF, TeamRole.SAISIE, TeamRole.LECTURE, TeamRole.BLOCKED, TeamRole.ADMIN).map { it to Labels.team(context, it) }, m.role,
                        ) { role -> run { vm.cloud.setRole(m.uid, role) } }
                    }
                }
            }
        }
    }
}
