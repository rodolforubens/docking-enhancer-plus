package com.odininputmirror

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odininputmirror.ui.MirrorScreen
import com.odininputmirror.ui.MirrorViewModel
import com.odininputmirror.ui.theme.DockingEnhancerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DockingEnhancerTheme {
                val vm: MirrorViewModel = viewModel(factory = MirrorViewModel.factory(applicationContext))
                MirrorScreen(viewModel = vm)
            }
        }
    }
}
