package com.quantum.qbeam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Quantum palette
val Void = Color(0xFF05010F)
val VoidLight = Color(0xFF120A24)
val ElectricViolet = Color(0xFF7C4DFF)
val QuantumCyan = Color(0xFF18FFFF)
val EntangleMagenta = Color(0xFFFF4D9D)
val PhotonYellow = Color(0xFFFFE94D)
val Mist = Color(0xFFCFC9E6)

private val QuantumColors = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = Color.White,
    secondary = QuantumCyan,
    onSecondary = Void,
    tertiary = EntangleMagenta,
    background = Void,
    onBackground = Mist,
    surface = VoidLight,
    onSurface = Mist,
    surfaceVariant = VoidLight,
    outline = ElectricViolet,
)

@Composable
fun QBeamTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = QuantumColors, // always dark — quantum void aesthetic
        typography = Typography(),
        content = content
    )
}
