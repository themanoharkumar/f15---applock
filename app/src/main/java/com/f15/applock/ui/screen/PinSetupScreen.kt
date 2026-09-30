package com.f15.applock.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.f15.applock.R
import com.f15.applock.security.PinAuthenticator
import com.f15.applock.ui.component.PinDotsIndicator
import com.f15.applock.ui.component.PinKeypad
import com.f15.applock.viewmodel.AuthUiState
import com.f15.applock.viewmodel.PinSetupStep

/**
 * First-run PIN configuration screen with 2-step confirmation and Samsung One UI aesthetic.
 */
@Composable
fun PinSetupScreen(
    uiState: AuthUiState,
    onDigitEntered: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onNextStep: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(
                        if (uiState.setupStep == PinSetupStep.CREATE) R.string.create_pin_title else R.string.confirm_pin_title
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(
                        if (uiState.setupStep == PinSetupStep.CREATE) R.string.create_pin_desc else R.string.confirm_pin_desc
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // PIN Dots Indicator
                PinDotsIndicator(
                    pinLength = uiState.enteredPin.length,
                    maxDigits = if (uiState.setupStep == PinSetupStep.CONFIRM) uiState.firstEnteredPin.length else 4,
                    isError = uiState.errorMessage != null
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Error message
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Keypad & Action button section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Action Button (Continue or Confirm)
                val canSubmit = when (uiState.setupStep) {
                    PinSetupStep.CREATE -> uiState.enteredPin.length >= PinAuthenticator.MIN_PIN_LENGTH
                    PinSetupStep.CONFIRM -> uiState.enteredPin.length == uiState.firstEnteredPin.length
                }

                AnimatedVisibility(
                    visible = canSubmit,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Button(
                        onClick = {
                            if (uiState.setupStep == PinSetupStep.CREATE) {
                                onNextStep()
                            } else {
                                onConfirm()
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(50.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.setupStep == PinSetupStep.CREATE) R.string.confirm else R.string.confirm
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                PinKeypad(
                    onDigitClick = onDigitEntered,
                    onBackspaceClick = onBackspace,
                    onClearClick = onClear
                )
            }
        }
    }
}
