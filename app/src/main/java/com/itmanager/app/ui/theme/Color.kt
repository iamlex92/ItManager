package com.itmanager.app.ui.theme

import androidx.compose.ui.graphics.Color

// "Steel of a rack": the same tokens as the web console (see admin.css).
// Dark palette. Neutrals are slightly blue so the map and port panels read as "equipment",
// accents follow system-style hues so status colors are recognisable at a glance.

val Ink950 = Color(0xFF0D1115) // window
val Ink900 = Color(0xFF12171D) // base surface
val Ink850 = Color(0xFF161D24)
val Ink800 = Color(0xFF1A222B) // cards
val Ink700 = Color(0xFF1E2730) // raised controls
val Ink600 = Color(0xFF2A343E) // outlines
val Ink400 = Color(0xFF6B7686)
val Ink200 = Color(0xFFAEB7C4)
val Ink50 = Color(0xFFE6EDF5)

val TextMuted = Color(0xFF8D99A6) // secondary text; 6:1 on cards, unlike Ink400
val LineStrong = Color(0xFF6B7686) // borders that carry meaning (fields, checkboxes, ports without link), 3:1 on cards

val Accent = Color(0xFF3A8DFF)
/** Text and icons on an [Accent] fill: white there is only 2.7:1. */
val OnAccent = Color(0xFF06101F)
val AccentContainer = Color(0xFF14325A)

/** Semantic colors used for device and port state across the app. */
object StatusColors {
    val Online = Color(0xFF3DDC84)
    val Offline = Color(0xFFFF5A52)
    val LinkFast = Color(0xFFFFB020)
    val LinkDown = Color(0xFF3A4350)
    /** Lamp that is off (no data): the web console's --led-off. */
    val LedOff = Color(0xFF56616C)
    val Disabled = Color(0xFF242A33)
    val Poe = Color(0xFFFFD60A)
    val Warning = Color(0xFFFFB020)
    val Info = Color(0xFF64D2FF)

    /** Fills for badges and banners; the text on them stays the normal text color. */
    val OkContainer = Color(0xFF10351B)
    val DangerContainer = Color(0xFF3B1210)
    val WarnContainer = Color(0xFF3A2F12)
    val InfoContainer = Color(0xFF0F2F3D)

    /** Text on a [Offline] fill. */
    val OnDanger = Color(0xFF2A0503)
}
