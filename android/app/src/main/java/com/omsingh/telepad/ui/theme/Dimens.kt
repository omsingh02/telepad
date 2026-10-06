package com.omsingh.telepad.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The spacing scale. Everything is laid out on these steps so screens feel related. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp

    /** Margin between content and the screen edge on a phone. */
    val screen = 16.dp

    /** Smallest comfortable touch target. */
    val touchTarget = 48.dp
}

val TelepadShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The touch surface is the app's signature shape: large, soft and unmistakable. */
val PadShape = RoundedCornerShape(28.dp)
