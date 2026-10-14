package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// Orki AI Official Design System: Warm Minimalist Ivory & Forest-Green Theme
// Inspired by ChatGPT with organic warmth and restrained editorial elegance.
// =========================================================================

// Core Palette
val IvoryBackground = Color(0xFFFAF9F4)       // Main app background (#FAF9F4)
val ForestGreenPrimary = Color(0xFF245C42)    // Primary forest green (#245C42)
val ForestGreenDeep = Color(0xFF193F30)       // Deep forest green (#193F30)
val SageGreen = Color(0xFFDCE8DA)             // Sage green for accents (#DCE8DA)
val PaleSage = Color(0xFFEEF3EB)              // Pale sage for user bubbles (#EEF3EB)
val PureWhite = Color(0xFFFFFFFF)             // Pure white for elevated surfaces & composer
val CharcoalTextPrimary = Color(0xFF252B27)   // Primary dark text (#252B27)
val SlateTextSecondary = Color(0xFF747B75)    // Muted secondary text (#747B75)
val WarmBorder = Color(0xFFE4E7DF)            // Clean subtle borders (#E4E7DF)
val WarmBorderSubtle = Color(0xFFECEFE7)      // Hairline dividers

// Surface Layers for depth & hierarchy
val SurfaceCanvas = IvoryBackground
val SurfaceCard = PureWhite
val SurfaceElevated = PaleSage
val SurfacePressed = SageGreen.copy(alpha = 0.4f)

// Theme Token Mappings for backward compatibility & component reusability
val DarkCanvas = IvoryBackground              // Canvas background (#FAF9F4)
val DarkSurface = PureWhite                   // Dialogs, cards, drawers (#FFFFFF)
val DarkSurfaceVariant = PaleSage             // Subtle containers, pills (#EEF3EB)
val DarkSurfaceElevated = PureWhite           // Inputs, floating chips (#FFFFFF)
val DarkElevated = PureWhite
val DarkDeepElevated = PaleSage
val DarkSurfaceBorder = WarmBorder            // Border (#E4E7DF)
val DarkBorderSubtle = WarmBorderSubtle       // Divider (#ECEFE7)

// Accent Colors
val GreenBright = ForestGreenPrimary          // Primary CTA button fill (#245C42)
val GreenHighlight = ForestGreenPrimary       // Active state indicator (#245C42)
val GreenPrimaryDark = ForestGreenDeep        // Pressed button state (#193F30)
val GreenMuted = SlateTextSecondary           // Neutral secondary (#747B75)
val GreenBorder = WarmBorder                  // Neutral border (#E4E7DF)
val GreenBorderGlow = SageGreen               // Soft green glow (#DCE8DA)
val GreenSurfaceTint = PaleSage               // Subtle selection tint (#EEF3EB)
val GreenSurfaceElevated = SageGreen          // Selected pills & chips (#DCE8DA)
val GreenTextMuted = SlateTextSecondary       // Captions & secondary descriptions

// Aliases
val EmeraldPrimary = ForestGreenPrimary
val EmeraldPrimaryDark = ForestGreenDeep
val EmeraldAccent = ForestGreenPrimary

// Tier Accents
val AmberPro = Color(0xFFB45309)              // Warm amber for Pro badge
val AmberProLight = Color(0xFFFEF3C7)         // Soft amber pill background
val PurpleIncognito = Color(0xFF6B21A8)       // Incognito badge
val PurpleIncognitoLight = Color(0xFFF3E8FF)  // Incognito pill background

// Text Tokens
val TextPrimary = CharcoalTextPrimary         // Main text (#252B27)
val TextSecondary = SlateTextSecondary        // Secondary text (#747B75)
val TextMuted = Color(0xFF959D96)             // Captions & placeholder hints

// Semantic Colors
val ErrorRed = Color(0xFFDC2626)
val ErrorRedDark = Color(0xFFFEE2E2)
val ErrorRedBorder = Color(0xFFFCA5A5)
