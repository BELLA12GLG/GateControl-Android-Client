package com.gatecontrol.android.ui.theme

import androidx.compose.ui.graphics.Color

// =============================================================================
// iOS-Inspired Color Palette
// =============================================================================
//
// Two complete color sets mirroring Apple's Settings / System Colors:
//   - Dark : near-black canvas, surface = #1C1C1E (iOS systemGray6 dark equivalent)
//   - Light: pure white surfaces on #F2F2F7 page background (iOS systemGroupedBackground)
//
// Accent colors follow Apple's published system palette (UIColor.systemRed etc.)
// so colored icon tiles and pill badges look native to anyone familiar with iOS.

// ---------------------------------------------------------------------------
// Dark theme (system) — iOS dark mode
// ---------------------------------------------------------------------------
val DarkBg0      = Color(0xFF000000)  // pure black, scaffold root
val DarkBg1      = Color(0xFF000000)  // page background
val DarkBg2      = Color(0xFF1C1C1E)  // grouped list card surface (iOS systemGray6 dark)
val DarkBg3      = Color(0xFF2C2C2E)  // pressed / elevated surface
val DarkBgHover  = Color(0xFF3A3A3C)
val DarkBorder   = Color(0xFF38383A)  // hairline divider inside cards (iOS separator dark)
val DarkBorder2  = Color(0xFF48484A)
val DarkText1    = Color(0xFFFFFFFF)  // primary label
val DarkText2    = Color(0xFF8E8E93)  // secondary label (iOS .secondary)
val DarkText3    = Color(0xFF636366)  // tertiary label
val DarkAccent   = Color(0xFF30D158)  // iOS systemGreen dark
val DarkAccentDim = Color(0xFF248A3D)
val DarkWarn     = Color(0xFFFF9F0A)  // iOS systemOrange dark
val DarkError    = Color(0xFFFF453A)  // iOS systemRed dark
val DarkBlue     = Color(0xFF0A84FF)  // iOS systemBlue dark

// ---------------------------------------------------------------------------
// Light theme — iOS light mode
// ---------------------------------------------------------------------------
val LightBg0      = Color(0xFFFFFFFF)
val LightBg1      = Color(0xFFF2F2F7)  // iOS systemGroupedBackground
val LightBg2      = Color(0xFFFFFFFF)  // grouped list card surface
val LightBg3      = Color(0xFFE5E5EA)  // pressed
val LightBgHover  = Color(0xFFD1D1D6)
val LightBorder   = Color(0xFFC6C6C8)  // iOS separator (~36% opacity over white)
val LightBorder2  = Color(0xFFAEAEB2)
val LightText1    = Color(0xFF000000)
val LightText2    = Color(0xFF8E8E93)
val LightText3    = Color(0xFFAEAEB2)
val LightAccent   = Color(0xFF34C759)  // iOS systemGreen
val LightAccentDim = Color(0xFF248A3D)
val LightWarn     = Color(0xFFFF9500)  // iOS systemOrange
val LightError    = Color(0xFFFF3B30)  // iOS systemRed
val LightBlue     = Color(0xFF007AFF)  // iOS systemBlue

// ---------------------------------------------------------------------------
// Shared iOS system icon-tile colors (used in colored list-row icons,
// independent of light/dark theme — same hex in both modes, which mirrors iOS).
// ---------------------------------------------------------------------------
val IosTileGreen  = Color(0xFF34C759)
val IosTileBlue   = Color(0xFF007AFF)
val IosTileOrange = Color(0xFFFF9500)
val IosTilePurple = Color(0xFFAF52DE)
val IosTileRed    = Color(0xFFFF3B30)
val IosTileTeal   = Color(0xFF5AC8FA)
val IosTilePink   = Color(0xFFFF2D55)
val IosTileIndigo = Color(0xFF5856D6)
val IosTileGray   = Color(0xFF8E8E93)
val IosTileYellow = Color(0xFFFFCC00)
