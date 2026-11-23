package app.ideacollector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ideacollector.data.AppSettings
import app.ideacollector.github.validRepository
import app.ideacollector.secret.GITHUB_TOKEN
import app.ideacollector.secret.SecretStatus
import collector.data.DataBranch
import collector.data.InboxRequest
import collector.model.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@Composable
internal fun DebugEntry(open: (String) -> Unit) {
    Text("준비 중", Modifier.combinedClickable(onClick = {}, onLongClick = { open(DebugActivity::class.java.name) }))
}

class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CollectorTheme { DebugSettings(application as CollectorApp) } }
    }
}

@Composable
private fun DebugSettings(app: CollectorApp) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var repository by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("24") }
    var secretStatus by remember { mutableStateOf(SecretStatus.MISSING) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(app) {
        settings = app.settings()
        repository = settings.repository
        hours = settings.syncHours.toString()
        secretStatus = app.secrets.status(GITHUB_TOKEN)
    }
    val state by remember(settings.repository) { app.database.dao().observeState(settings.repository) }.collectAsState(null)

    val authCount by remember(settings.repository) { app.sync.authCount(settings.repository) }.collectAsState(0)

    fun action(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "작업에 실패했습니다"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("개발자 설정")
        OutlinedTextField(repository, { repository = it }, label = { Text("저장소 이름 (owner/repo)") }, singleLine = true)
        OutlinedTextField(token, { token = it }, label = { Text("GitHub 토큰") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (authCount > 0) Text("토큰 확인 필요")
        Text(when (secretStatus) {
            SecretStatus.NEEDS_REGISTRATION -> "토큰 다시 등록 필요"
            SecretStatus.AVAILABLE -> "토큰 등록됨"
            SecretStatus.MISSING -> "토큰 미등록"
        })
        OutlinedTextField(hours, { hours = it }, label = { Text("동기화 주기 (시간)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Button(enabled = !busy && validRepository(repository.trim()) && hours.toLongOrNull() in 1L..168L,
            colors = ButtonDefaults.buttonColors(containerColor = strongAccent), onClick = {
                action {
                    val updated = settings.copy(repository = repository.trim(), syncHours = hours.toLong())
                    val cancelled = app.saveSettings(updated)
                    settings = updated
                    val saved = token.isBlank() || app.secrets.set(GITHUB_TOKEN, token.trim())
                    token = ""
                    secretStatus = app.secrets.status(GITHUB_TOKEN)
                    message = when {
                        !saved -> "저장 실패"
                        cancelled > 0 -> "이전 저장소 요청 " + cancelled + "건 취소됨"
                        else -> "저장됨"
                    }
                }
            }) { Text("저장") }
        Button(enabled = !busy && secretStatus != SecretStatus.MISSING, onClick = {
            action {
                app.secrets.clear(GITHUB_TOKEN)
                token = ""
                secretStatus = SecretStatus.MISSING
            }
        }) { Text("토큰 지우기") }
        Text(state?.let { "마지막 동기화 · ${it.syncedAt} · ${it.manifestSha.take(12)}" } ?: "동기화 기록 없음")
        if (state?.status == "APP_UPDATE_REQUIRED") Text("앱 업데이트 필요")
        Button(enabled = !busy && validRepository(settings.repository), onClick = {
            app.schedule.sync()
            message = "동기화 대기 중"
        }) { Text("지금 동기화") }
        Button(enabled = !busy && validRepository(settings.repository), onClick = {
            action {
                app.submit(InboxRequest.Reaction(DataBranch.VERSION, UUID.randomUUID().toString(),
                    Instant.now().toString(), "debug-test-card", Feedback.Kind.STAR, "debug test"))
                message = "테스트 요청 대기 중"
            }
        }) { Text("테스트 요청 보내기") }
        if (message.isNotEmpty()) Text(message)
    }
}
