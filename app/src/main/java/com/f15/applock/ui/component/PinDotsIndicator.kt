package com.f15.applock.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Modern One UI styled PIN dots indicator showing filled and unfilled dots.
 * Dynamically scales to show between 4 and 8 digits gracefully.
 */
@Composable
fun PinDotsIndicator(
    pinLength: Int,
    maxDigits: Int = 4,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    // If the user types beyond 4 digits (up to 8), dynamically expand the indicator
    val displaySlots = maxOf(maxDigits, pinLength)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until displaySlots) {
            val isFilled = i < pinLength

            val dotSize by animateDpAsState(
                targetValue = if (isFilled) 16.dp else 14.dp,
                animationSpec = tween(durationMillis = 150),
                label = "dotSize"
            )

            val fillColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.error
                    isFilled -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                },
                animationSpec = tween(durationMillis = 150),
                label = "fillColor"
            )

            val borderColor by animateColorAsState(
                targetValue = when {
                    isError -> MaterialTheme.colorScheme.error
                    isFilled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                animationSpec = tween(durationMillis = 150),
                label = "borderColor"
            )

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(fillColor)
                    .border(2.dp, borderColor, CircleShape)
            )
        }
    }
}
