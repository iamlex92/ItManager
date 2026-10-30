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

val TextMuted = Color(0xFF9AA5B5) // secondary text; 6:1 on cards, unlike Ink400
val LineStrong = Color(0xFF6B7686) // borders that carry meaning (fields, checkboxes, ports without link), 3:1 on cards

val Accent = Color(0xFF4C9EFF)
/** Text and icons on an [Accent] fill: white there is only 2.7:1. */
val OnAccent = Color(0xFF06101F)
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
    val Info = Color(0xFF64D2FF)

    /** Fills for badges and banners; the text on them stays the normal text color. */
    val OkContainer = Color(0xFF10351B)
    val DangerContainer = Color(0xFF3B1210)
    val WarnContainer = Color(0xFF3A2F12)
    val InfoContainer = Color(0xFF0F2F3D)

    /** Text on a [Offline] fill. */
    val OnDanger = Color(0xFF2A0503)
}
