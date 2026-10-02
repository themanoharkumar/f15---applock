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
    val displaySlots = maxOf(maxDigits, pinLength)
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val emptyBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until displaySlots) {
            val isFilled = i < pinLength
            val fillColor = when {
                isError -> errorColor
                isFilled -> primaryColor
                else -> Color.Transparent
            }
            val borderColor = when {
                isError -> errorColor
                isFilled -> primaryColor
                else -> emptyBorderColor
            }
            val dotSize = if (isFilled) 16.dp else 14.dp

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
