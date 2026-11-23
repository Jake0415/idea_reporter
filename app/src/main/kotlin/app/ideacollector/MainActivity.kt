package app.ideacollector

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CollectorTheme {
                var selected by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEachIndexed { index, (label, outline, solid) ->
                            NavigationBarItem(selected == index, { selected = index },
                                icon = { Icon(painterResource(if (selected == index) solid else outline), contentDescription = label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                                ))
                        }
                    }
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (selected == 3 && BuildConfig.DEBUG) {
                                DebugEntry { startActivity(Intent().setClassName(this@MainActivity, it)) }
                            } else {
                                Text("준비 중")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as CollectorApp
        lifecycleScope.launch {
            app.schedule.periodic(app.settings().syncHours)
            app.schedule.sync()
            app.schedule.outbox()
        }
    }
}

private val tabs = listOf(
    Triple("오늘", R.drawable.ic_home_outline, R.drawable.ic_home_solid),
    Triple("추적", R.drawable.ic_track_outline, R.drawable.ic_track_solid),
    Triple("보관함", R.drawable.ic_archive_outline, R.drawable.ic_archive_solid),
    Triple("설정", R.drawable.ic_settings_outline, R.drawable.ic_settings_solid)
)

private val accent = Color(0xFF0095F6)
internal val strongAccent = Color(0xFF0077D9)
private val light = lightColorScheme(
    primary = accent, onPrimary = Color.White, tertiary = Color(0xFF005591),
    background = Color.White, surface = Color.White, onBackground = Color.Black, onSurface = Color.Black,
    surfaceVariant = Color(0xFFF1F1F1), onSurfaceVariant = Color(0xFF474747),
    outline = Color(0xFF737373), outlineVariant = Color(0x14000000),
    error = Color(0xFFA31F26), errorContainer = Color(0xFFFDE7E6), onErrorContainer = Color(0xFFA31F26)
)
private val dark = darkColorScheme(
    primary = accent, onPrimary = Color.White, tertiary = Color(0xFF98CCFF),
    background = Color.Black, surface = Color.Black, onBackground = Color.White, onSurface = Color.White,
    surfaceVariant = Color(0xFF1B1B1B), onSurfaceVariant = Color(0xFF9D9D9D),
    outline = Color(0xFF8E8E8E), outlineVariant = Color(0x24FFFFFF),
    error = Color(0xFFFF9A92), errorContainer = Color(0xFF331311), onErrorContainer = Color(0xFFFF9A92)
)

@Composable
internal fun CollectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
