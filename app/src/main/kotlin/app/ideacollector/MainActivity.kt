package app.ideacollector

import android.content.Intent
import android.os.Bundle
import app.ideacollector.github.validRepository
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

class MainActivity : ComponentActivity() {
    private var destination by androidx.compose.runtime.mutableStateOf<Route?>(null)
    private val screens by lazy {
        val app = application as CollectorApp
        ViewModelProvider(this, viewModelFactory { initializer { ScreensViewModel(app.database.dao()) } })[ScreensViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) destination = routeFrom(intent)
        enableEdgeToEdge()
        setContent {
            CollectorTheme {
                CollectorScreens(application as CollectorApp, screens, destination) { destination = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination = routeFrom(intent) ?: Route(Page.TODAY).takeIf { intent.getBooleanExtra("openToday", false) }
    }

    override fun onStart() {
        super.onStart()
        val app = application as CollectorApp
        lifecycleScope.launch {
            val settings = app.settings()
            if (validRepository(settings.repository)) {
                app.schedule.periodic(settings.syncHours)
                app.schedule.sync()
                app.recoverOutbox()
            }
        }
    }
}

internal fun routeFrom(intent: Intent): Route? {
    val uri = intent.data ?: return null
    if (uri.scheme != "ideacollector" || uri.pathSegments.size != 1) return null
    val id = uri.pathSegments.single()
    return when (uri.host) {
        "card" -> Route(Page.CARD, id)
        "track" -> Route(Page.TRACK_DETAIL, id)
        else -> null
    }
}
private val accent = Color(0xFF0095F6)
internal val strongAccent = Color(0xFF0077D9)
internal val warningBackground: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2E2408) else Color(0xFFFFF4D6)
internal val warningContent: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF2C14E) else Color(0xFF6B4700)
internal val successContent: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7CD08A) else Color(0xFF1E6B2E)
private val light = lightColorScheme(
    primary = accent, onPrimary = Color.White, tertiary = Color(0xFF005591),
    secondaryContainer = Color(0xFFE5F1FE), onSecondaryContainer = Color(0xFF0058B0),
    background = Color.White, surface = Color.White, onBackground = Color.Black, onSurface = Color.Black,
    surfaceVariant = Color(0xFFF1F1F1), onSurfaceVariant = Color(0xFF474747),
    outline = Color(0xFF737373), outlineVariant = Color(0x14000000),
    error = Color(0xFFA31F26), errorContainer = Color(0xFFFDE7E6), onErrorContainer = Color(0xFFA31F26)
)
private val dark = darkColorScheme(
    primary = accent, onPrimary = Color.White, tertiary = Color(0xFF98CCFF),
    secondaryContainer = Color(0xFF0058B0), onSecondaryContainer = Color.White,
    background = Color.Black, surface = Color.Black, onBackground = Color.White, onSurface = Color.White,
    surfaceVariant = Color(0xFF1B1B1B), onSurfaceVariant = Color(0xFF9D9D9D),
    outline = Color(0xFF8E8E8E), outlineVariant = Color(0x24FFFFFF),
    error = Color(0xFFFF9A92), errorContainer = Color(0xFF331311), onErrorContainer = Color(0xFFFF9A92)
)

@Composable
internal fun CollectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        typography = androidx.compose.material3.Typography(
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
        ),
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(5.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(22.dp)
        ),
        content = content
    )
}
