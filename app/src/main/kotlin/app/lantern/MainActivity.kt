package app.lantern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import app.lantern.ui.LanternRoot
import app.lantern.ui.LocalPlayer
import app.lantern.ui.PlayerConnection
import app.lantern.ui.theme.LanternTheme

class MainActivity : ComponentActivity() {
    private val player = PlayerConnection()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (docs/decisions/ui-design.md), so the system bars keep light icons whatever the phone's setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            LanternTheme {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayer provides player) { LanternRoot() }
                }
            }
        }
    }

    // The controller lives while the screen is visible. The service keeps playing without it.
    override fun onStart() {
        super.onStart()
        player.connect(this)
    }

    override fun onStop() {
        player.release()
        super.onStop()
    }
}
