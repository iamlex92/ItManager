package com.itmanager.app.ui.theme

import androidx.compose.ui.graphics.Color

// Dark-only palette. Neutrals are slightly blue so the map and port panels read as "equipment",
// accents follow system-style hues so status colors are recognisable at a glance.

val Ink950 = Color(0xFF0B0E13) // window
val Ink900 = Color(0xFF11151C) // base surface
val Ink850 = Color(0xFF161B23)
val Ink800 = Color(0xFF1C222C) // cards
val Ink700 = Color(0xFF262E3A) // raised controls
val Ink600 = Color(0xFF343D4B) // outlines
val Ink400 = Color(0xFF6B7686)
val Ink200 = Color(0xFFAEB7C4)
val Ink50 = Color(0xFFE6EDF5)

val Accent = Color(0xFF4C9EFF)
val AccentContainer = Color(0xFF14325A)

/** Semantic colors used for device and port state across the app. */
object StatusColors {
    val Online = Color(0xFF32D74B)
    val Offline = Color(0xFFFF453A)
    val LinkFast = Color(0xFFFF9F0A)
    val LinkDown = Color(0xFF3A4350)
    val Disabled = Color(0xFF242A33)
    val Poe = Color(0xFFFFD60A)
    val Warning = Color(0xFFFF9F0A)
}
