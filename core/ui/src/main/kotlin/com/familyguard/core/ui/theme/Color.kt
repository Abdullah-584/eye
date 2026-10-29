package com.familyguard.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// FamilyGuard palette: calm blue-green primary (calm, not punitive), with a
// warm amber reserved for "attention needed" states in later milestones.
internal val Teal40 = Color(0xFF1F6F6B)
internal val Teal80 = Color(0xFF80D5CF)
internal val TealGrey40 = Color(0xFF4A6362)
internal val TealGrey80 = Color(0xFFB0CCCA)
internal val Sand40 = Color(0xFF7A5900)
internal val Sand80 = Color(0xFFE9C26A)

internal val LightColors = lightColorScheme(
    primary = Teal40,
    secondary = TealGrey40,
    tertiary = Sand40,
)

internal val DarkColors = darkColorScheme(
    primary = Teal80,
    secondary = TealGrey80,
    tertiary = Sand80,
)
