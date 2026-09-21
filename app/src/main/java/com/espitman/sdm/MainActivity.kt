package com.espitman.sdm

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.espitman.sdm.ui.SdmApp
import com.espitman.sdm.ui.SdmLaunchLayer
import com.espitman.sdm.ui.theme.SdmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashView -> splashView.remove() }
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            SdmTheme {
                SdmLaunchLayer {
                    SdmApp()
                }
            }
        }
    }
}
