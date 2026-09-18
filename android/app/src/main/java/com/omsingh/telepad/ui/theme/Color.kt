package com.omsingh.telepad.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────
// Telepad palette — 5 accent colors × (light + dark) Material 3 schemes.
//
// Reasoning behind the choices:
//  - Dark palette is "deep slate" (Tailwind slate-900 family) because Telepad
//    is most-used in dim contexts (typing at night, presentation rooms).
//  - Light palette is warm-neutral (Tailwind zinc) so the touchpad surface
//    doesn't blast white at midday.
//  - Accent picker is a UX win for ~zero engineering cost.
//
// All cross-pairings (background × text) verified ≥ 4.5:1 contrast (AA).
// ─────────────────────────────────────────────────────────────────────

// Brand accents — light & dark variants per accent.
val CyanLight    = Color(0xFF0EA5E9)
val CyanDark     = Color(0xFF38BDF8)
val PurpleLight  = Color(0xFF8B5CF6)
val PurpleDark   = Color(0xFFA78BFA)
val GreenLight   = Color(0xFF10B981)
val GreenDark    = Color(0xFF34D399)
val OrangeLight  = Color(0xFFF97316)
val OrangeDark   = Color(0xFFFB923C)
val RedLight     = Color(0xFFEF4444)
val RedDark      = Color(0xFFF87171)

// Dark mode surfaces (slate)
val DarkBackground     = Color(0xFF0F172A)  // slate-900
val DarkSurface        = Color(0xFF1E293B)  // slate-800
val DarkSurfaceVariant = Color(0xFF334155)  // slate-700
val DarkOutline        = Color(0xFF475569)  // slate-600
val DarkOnSurface      = Color(0xFFF8FAFC)  // slate-50
val DarkOnSurfaceVar   = Color(0xFFCBD5E1)  // slate-300

// Light mode surfaces (zinc)
val LightBackground     = Color(0xFFFAFAF9)  // stone-50
val LightSurface        = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF4F4F5)  // zinc-100
val LightOutline        = Color(0xFFD4D4D8)  // zinc-300
val LightOnSurface      = Color(0xFF18181B)  // zinc-900
val LightOnSurfaceVar   = Color(0xFF52525B)  // zinc-600

// Touchpad surface — even darker than the app background, so the touch zone
// reads as a distinct affordance regardless of theme.
val TrackpadSurfaceDark  = Color(0xFF0B1120)
val TrackpadSurfaceLight = Color(0xFFE7E5E4)
val TrackpadBorderDark   = Color(0xFF1E293B)
val TrackpadBorderLight  = Color(0xFFD6D3D1)

// Status indicators (cross-theme).
val StatusOnline  = Color(0xFF10B981)
val StatusOffline = Color(0xFFEF4444)
val StatusWarning = Color(0xFFFBBF24)
