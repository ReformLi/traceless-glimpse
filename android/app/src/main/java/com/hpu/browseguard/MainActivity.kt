package com.hpu.browseguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hpu.browseguard.ui.HomeRoute
import com.hpu.browseguard.ui.theme.BgvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BgvTheme {
                HomeRoute()
            }
        }
    }
}
