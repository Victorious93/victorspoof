package com.victorious.spoof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.victorious.spoof.ui.VictorSpoofRoot
import com.victorious.spoof.ui.VictorSpoofTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent?.getStringExtra(EXTRA_SCREEN)
        setContent { VictorSpoofTheme { VictorSpoofRoot(startScreen = screen) } }
    }
    companion object { const val EXTRA_SCREEN = "screen" }
}
