package com.travel.slideshow

import android.os.Build
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
        // Photos and maps also fill the camera cutout area, not just the space below it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }

    // Dialogs (screen capture, permissions, share), the app coming back from the background or a
    // swipe from the edge bring the bars back; full screen returns as soon as the show has focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars(this, !vm.state.value.settingsVisible)
    }

    override fun onResume() {
        super.onResume()
        applySystemBars(this, !vm.state.value.settingsVisible)
    }
}

/**
 * Full screen for the show: status bar and on-screen navigation buttons hidden (a swipe from the edge
 * shows them for a moment). Settings keep them, as a normal screen with a keyboard.
 */
private fun applySystemBars(activity: ComponentActivity, fullScreen: Boolean) {
    val c = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (fullScreen) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
}

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val vm: ShowViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val st by vm.state.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity
    androidx.compose.runtime.LaunchedEffect(st.settingsVisible) {
        activity?.let { applySystemBars(it, !st.settingsVisible) }
    }
    if (st.settingsVisible) {
        SettingsScreen(vm)
    } else {
        SlideshowScreen(vm)
    }
}