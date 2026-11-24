package app.ideacollector

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ideacollector.work.*
import collector.llm.*
import collector.mail.MailSendResult
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun budgetAmount(value: String) = value.trim().toBigDecimalOrNull()?.takeIf {
    it.signum() >= 0 && it.stripTrailingZeros().scale() <= 2 && it.toDouble().isFinite()
}

internal val aiKeys = listOf("ANTHROPIC_API_KEY" to R.string.key_anthropic, "OPENAI_API_KEY" to R.string.key_openai)

internal suspend fun modelsFor(key: String, value: String, firstPageOnly: Boolean): List<String> = withContext(Dispatchers.IO) {
    when (key) {
        "ANTHROPIC_API_KEY" -> AnthropicProvider(value).use { it.listModels(firstPageOnly) }
        "OPENAI_API_KEY" -> OpenAiProvider(value).use { it.listModels(firstPageOnly) }
        else -> error("Unsupported provider")
    }.map { it.id }.distinct().sorted()
}

@Composable
private fun resumeCounter(): Int {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
internal fun rememberRunStatus(app: CollectorApp, vararg refresh: Any?): RunStatus? {
    val resumed = resumeCounter()
    var status by remember(app) { mutableStateOf<RunStatus?>(null) }
    LaunchedEffect(app, resumed, *refresh) { status = app.schedule.status() }
    return status
}

internal fun openExactSettings(app: CollectorApp) {
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        app.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
internal fun RunPermissions(app: CollectorApp, status: RunStatus?, notifications: Boolean) {
    val activity = LocalContext.current as Activity
    if (status == null) return
    val resumed = resumeCounter()
    val granted = remember(resumed) { NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    val prefs = remember(app) { app.getSharedPreferences("permission-requests", 0) }
    if (notifications) PermissionRow(R.string.r3_notification, R.string.r3_notification_reason, granted) {
        val requests = prefs.getInt("notifications", 0)
        if (android.os.Build.VERSION.SDK_INT < 33 || (requests > 0 &&
                !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))) {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName))
        } else {
            prefs.edit().putInt("notifications", requests + 1).apply()
            requestNotifications(activity, 52)
        }
    }
    PermissionRow(R.string.r3_exact, R.string.r3_exact_reason, status.exact) { openExactSettings(app) }
    PermissionRow(R.string.r3_battery, R.string.r3_battery_reason, status.ignoringBatteryOptimizations) {
        app.schedule.requestBatteryExemption()
    }
    if (android.os.Build.MANUFACTURER.equals("samsung", true)) {
        Notice(stringResource(R.string.r3_samsung)) { app.schedule.openSamsungBatterySettings() }
    }
    if (!status.exact || !status.ignoringBatteryOptimizations || (notifications && !granted)) Small(R.string.r3_late)
}

@Composable
private fun PermissionRow(label: Int, reason: Int, granted: Boolean, enable: () -> Unit) {
    val name = stringResource(label)
    Text(stringResource(if (granted) R.string.r3_permission_on else R.string.r3_permission_off, name),
        color = if (granted) successContent else warningContent)
    Small(reason)
    if (!granted) OutlinedButton(onClick = enable,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) { Text(stringResource(R.string.r3_enable_named, name)) }
}

@Composable
internal fun RunTimePicker(seconds: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val time = LocalTime.ofSecondOfDay(seconds.toLong())
    ListRow(stringResource(R.string.run_time_setting), time.toString()) {
        TimePickerDialog(context, { _, hour, minute -> onChange(LocalTime.of(hour, minute).toSecondOfDay()) },
            time.hour, time.minute, true).show()
    }
}

@Composable
internal fun scheduleTime(value: ZonedDateTime): String {
    val date = value.toLocalDate()
    val today = ZonedDateTime.now(value.zone).toLocalDate()
    val time = value.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (date) {
        today -> stringResource(R.string.r3_today_at, time)
        today.plusDays(1) -> stringResource(R.string.r3_tomorrow_at, time)
        else -> "${displayDate(date.toString())} $time"
    }
}

@Composable
internal fun AiKeys(app: CollectorApp, onReady: () -> Unit, onboarding: Boolean = false,
    onModels: (String, List<String>) -> Unit = { _, _ -> }) {
    val prefs = remember(app) { app.getSharedPreferences("key-confirmation", 0) }
    aiKeys.forEach { (key, label) ->
        var editing by rememberSaveable { mutableStateOf(onboarding) }
        var value by remember { mutableStateOf("") }
        var last4 by remember { mutableStateOf<String?>(null) }
        var checked by remember { mutableStateOf(prefs.getString(key, null)) }
        var checkFailed by remember { mutableStateOf(prefs.getBoolean("failed-$key", false)) }
        var failed by remember { mutableStateOf<Int?>(null) }
        var attempted by rememberSaveable { mutableStateOf(false) }
        var delete by remember { mutableStateOf(false) }
        var lastKey by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(key) { last4 = app.secrets.last4(key) }
        fun verify(replace: Boolean) {
            busy = true
            failed = null
            attempted = true
            val draft = value.trim()
            scope.launch {
                try {
                    val secret = if (replace) draft else checkNotNull(app.secrets.get(key))
                    val models = app.modelsForKey(key, secret, firstPageOnly = true)
                    check(models.isNotEmpty())
                    if (replace) check(app.secrets.set(key, secret))
                    last4 = secret.takeLast(4)
                    checked = java.time.Instant.now().toString()
                    checkFailed = false
                    prefs.edit().putString(key, checked).remove("failed-$key").apply()
                    value = ""
                    editing = onboarding
                    onModels(key, models)
                    onReady()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = if (e is LlmAuthException) R.string.r3_key_error else R.string.r3_key_unchecked
                    if (!replace && e is LlmAuthException) {
                        checked = java.time.Instant.now().toString()
                        checkFailed = true
                        prefs.edit().putString(key, checked).putBoolean("failed-$key", true).apply()
                        onModels(key, emptyList())
                        onReady()
                    }
                } finally { busy = false }
            }
        }
        val verifyButton = remember { BringIntoViewRequester() }
        if (!onboarding && checkFailed) {
            val provider = if (key == aiKeys.first().first) R.string.provider_claude else R.string.provider_openai
            Notice(stringResource(R.string.r3_key_failed_replace, stringResource(provider)), true) { editing = true }
        }
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        failed?.let { Notice(stringResource(it), true) }
        if (!onboarding || attempted) last4?.let {
            Small(if (checkFailed) stringResource(R.string.r3_key_suffix_failed, it)
                else stringResource(R.string.r3_key_ok, it))
            checked?.let { at -> Small(runTime(at)) }
        } ?: Small(R.string.r3_key_missing)
        if (editing) {
            if (!onboarding) SecureTokenInput()
            Field(value, { value = it }, label, singleLine = true, secret = true, enabled = !busy,
                modifier = Modifier.onFocusChanged {
                    if (onboarding && it.isFocused) scope.launch { kotlinx.coroutines.delay(350); verifyButton.bringIntoView() }
                },
                onDone = if (onboarding) ({ if (!busy && value.isNotBlank()) verify(true) }) else null)
            if (onboarding) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val url = if (key == aiKeys.first().first) "https://console.anthropic.com/settings/keys"
                    else "https://platform.openai.com/api-keys"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Small(if (key == aiKeys.first().first) R.string.r3_anthropic_format else R.string.r3_openai_format)
                    }
                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                        Text(stringResource(R.string.r3_key_link), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Box(Modifier.bringIntoViewRequester(verifyButton)) {
                Primary(R.string.r3_check, !busy && value.isNotBlank()) { verify(true) }
            }
            if (onboarding && last4 != null && (checked == null || checkFailed)) {
                Primary(R.string.r3_key_recheck, !busy) { verify(false) }
            }
        } else {
            Secondary(R.string.r3_key_replace, alignStart = true) { editing = true }
            if (last4 != null) {
                Primary(R.string.r3_key_recheck, !busy) { verify(false) }
                Primary(R.string.r3_key_delete, !busy) {
                    scope.launch {
                        lastKey = aiKeys.none { (other, _) -> other != key && app.secrets.has(other) }
                        delete = true
                    }
                }
                if (delete) AlertDialog(onDismissRequest = { delete = false },
                    title = { Text(stringResource(R.string.r3_key_delete)) },
                    text = { Text(stringResource(if (lastKey) R.string.r3_key_delete_last else R.string.r3_key_delete_confirm)) },
                    dismissButton = { Secondary(R.string.cancel) { delete = false } },
                    confirmButton = { Primary(R.string.r3_key_delete, !busy) {
                    delete = false
                    busy = true
                    scope.launch {
                        try {
                            app.secrets.clear(key)
                            last4 = null
                            checked = null
                            checkFailed = false
                            prefs.edit().remove(key).remove("failed-$key").apply()
                            onModels(key, emptyList())
                            onReady()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = R.string.r3_key_error }
                        finally { busy = false }
                    }
                } })
            }
        }
    }
}

@Composable
internal fun GitHubKey(app: CollectorApp, onboarding: Boolean, operation: (suspend () -> Unit) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(onboarding) }
    var stored by remember { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    LaunchedEffect(app) { stored = app.secrets.has("GITHUB_TOKEN") }
    Text(stringResource(R.string.token), style = MaterialTheme.typography.titleMedium)
    if (!onboarding || touched) Small(if (stored) R.string.r3_key_saved else R.string.r3_key_missing)
    if (onboarding) Small(R.string.r3_github)
    if (editing) {
        if (!onboarding) SecureTokenInput()
        Field(value, { value = it; touched = true }, R.string.token, singleLine = true, secret = true)
        Primary(R.string.save, value.isNotBlank()) { operation {
            check(app.secrets.set("GITHUB_TOKEN", value.trim()))
            value = ""
            stored = true
            editing = onboarding
        } }
    } else Secondary(R.string.r3_key_replace, alignStart = true) { editing = true }
    if (stored && (!onboarding || touched)) Secondary(R.string.r3_key_delete) { operation {
        app.secrets.clear("GITHUB_TOKEN")
        stored = false
    } }
}

@Composable
internal fun MailSettings(app: CollectorApp, operation: (suspend () -> Unit) -> Unit,
    onboarding: Boolean = false, next: (((() -> Unit)?) -> Unit)? = null,
    skip: (((() -> Unit)?) -> Unit)? = null, done: () -> Unit = {}) {
    var recipient by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(app.mailEnabled) }
    LaunchedEffect(app) {
        configured = app.hasMailCredentials()
    }
    if (!onboarding) Small(if (configured && enabled) R.string.r3_mail_on else R.string.r3_mail_off)
    SecureTokenInput()
    fun addressError(value: String) = R.string.r3_mail_address_invalid.takeIf {
        value.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()
    }
    Field(recipient, { recipient = it; message = null }, R.string.key_mail_to, true, enabled = !busy, email = true, error = addressError(recipient))
    Field(sender, { sender = it; message = null }, R.string.key_smtp_username, true, enabled = !busy, email = true, error = addressError(sender))
    Field(password, { password = it; message = null }, R.string.key_smtp_password, true, true, enabled = !busy)
    Link("https://support.google.com/accounts/answer/185833", stringResource(R.string.r3_mail_help))
    val valid = (configured || listOf(recipient, sender, password).all { it.isNotBlank() }) &&
        listOf(recipient, sender).all { it.isBlank() || android.util.Patterns.EMAIL_ADDRESS.matcher(it.trim()).matches() }
    suspend fun save() {
        app.secrets.setAll(app.mailDraft(recipient, sender, password))
        recipient = ""
        sender = ""
        password = ""
        configured = true
    }
    val saveAction: () -> Unit = {
        busy = true
        operation {
            try {
                save()
                app.mailEnabled = true
                enabled = true
                done()
            } finally { busy = false }
        }
    }
    val running by app.operationRunning.collectAsState()
    if (running) Small(R.string.r3_action_running)
    Primary(R.string.r3_mail_test, !busy && valid && !running) {
        val enteredRecipient = recipient
        val enteredSender = sender
        val enteredPassword = password
        app.appScope.launch {
            if (!app.operationRunning.compareAndSet(expect = false, update = true)) return@launch
            busy = true
            message = null
            try {
                val draft = app.mailDraft(enteredRecipient, enteredSender, enteredPassword)
                message = if (app.testMail(draft) == MailSendResult.SENT)
                    R.string.r3_mail_test_ok else R.string.r3_mail_failed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = R.string.r3_mail_failed
            } finally {
                app.finishOperation()
                busy = false
            }
        }
    }
    if (onboarding && next != null) WizardNext(!busy && valid && message == R.string.r3_mail_test_ok, saveAction, next)
    else Primary(R.string.save, !busy && valid, saveAction)
    val skipAction: () -> Unit = { if (!busy) operation {
        app.mailEnabled = false
        enabled = false
        done()
    } }
    if (onboarding && skip != null) WizardNext(!busy, skipAction, skip)
    else Secondary(R.string.r3_mail_skip, alignStart = true, onClick = skipAction)
    message?.let { Notice(stringResource(it), it != R.string.r3_mail_test_ok) }
}
