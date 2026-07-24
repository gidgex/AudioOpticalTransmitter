package com.quantum.qbeam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.quantum.qbeam.ui.screens.MainScreen
import com.quantum.qbeam.ui.theme.QBeamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QBeamTheme {
                MainScreen()
            }
        }
    }
}
