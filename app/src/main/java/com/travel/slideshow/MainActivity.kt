package com.travel.slideshow

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.travel.slideshow.ui.AppTheme
import com.travel.slideshow.ui.SlideshowScreen
import com.travel.slideshow.ui.SettingsScreen
import com.travel.slideshow.viewmodel.ShowViewModel

class MainActivity : ComponentActivity() {
    private val vm: ShowViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    override fun onStop() {
        vm.onAppBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val vm: ShowViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val st by vm.state.collectAsState()
    if (st.settingsVisible) {
        SettingsScreen(vm)
    } else {
        SlideshowScreen(vm)
    }
}