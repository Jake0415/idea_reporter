package app.ideacollector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun DebugEntry(open: (String) -> Unit) {
    Nav(R.string.debug) { open(DebugActivity::class.java.name) }
}

class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CollectorTheme { DebugSettings(application as CollectorApp) { finish() } } }
    }
}

@Composable
private fun DebugSettings(app: CollectorApp, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Primary(R.string.local_refresh) { scope.launch { app.sync.refresh() } }
        Secondary(R.string.back) { done() }
    }
}
