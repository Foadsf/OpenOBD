package io.github.foadsf.openobd

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.foadsf.openobd.ui.AppRoot
import io.github.foadsf.openobd.ui.OpenObdTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light icons on dark bars, including the 3-button navigation bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF161A21.toInt()),
        )
        super.onCreate(savedInstanceState)
        val session = (application as OpenObdApp).session
        setContent {
            val conn by session.conn.collectAsState()
            // While connected the screen stays on, like a dashboard.
            LaunchedEffect(conn is Conn.Ready) {
                if (conn is Conn.Ready) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            OpenObdTheme { AppRoot(session) }
        }
    }

    override fun onDestroy() {
        // Leaving the app (not just rotating) closes the link so the adapter is free for others.
        if (isFinishing) (application as OpenObdApp).session.disconnect()
        super.onDestroy()
    }
}
