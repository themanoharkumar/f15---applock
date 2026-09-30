package com.f15.applock.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.f15.applock.R
import com.f15.applock.device.DeviceManagementDiagnostics
import com.f15.applock.device.DevicePolicyState
import com.f15.applock.domain.model.LockEngineMode
import com.f15.applock.domain.model.SessionTimeout
import com.f15.applock.knox.PolicyStatus
import com.f15.applock.security.BiometricStatus
import com.f15.applock.security.OverallSecurityStatus
import com.f15.applock.security.PinAuthenticator
import com.f15.applock.security.SecurityHealthReport
import com.f15.applock.ui.dialog.SecurityEventLogDialog
import com.f15.applock.viewmodel.SecuritySettingsUiState
import kotlinx.coroutines.launch

/**
 * Security and Monitoring Settings screen with One UI design,
 * incorporating PIN management, biometric settings, background monitoring controls,
 * real-time Accessibility Service setup, security health posture audits, and live diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    uiState: SecuritySettingsUiState,
    onNavigateBack: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onSelectSessionTimeout: (SessionTimeout) -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onRequestUsageAccess: () -> Unit,
    onDismissUsageAccessExplanation: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onDismissAccessibilityExplanation: () -> Unit,
    onRequestBatteryOptimizationSettings: () -> Unit,
    onOpenChangePin: () -> Unit,
    onCloseChangePin: () -> Unit,
    onOpenRemovePin: () -> Unit,
    onCloseRemovePin: () -> Unit,
    onOpenSecurityLogs: () -> Unit,
    onCloseSecurityLogs: () -> Unit,
    onClearSecurityLogs: () -> Unit,
    onCleanupStalePackages: () -> Unit,
    onChangePin: (currentPin: String, newPin: String, (Boolean) -> Unit) -> Unit,
    onRemovePin: (currentPin: String, (Boolean) -> Unit) -> Unit,
    onVerifyCurrentPin: suspend (String) -> Boolean,
    onApplyKnoxProtection: () -> Unit = {},
    onRemoveKnoxProtection: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showTimeoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.security_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 0: Security Health & Integrity Posture (Phase 5)
            SettingsSection(title = stringResource(R.string.section_security_health)) {
                uiState.healthReport?.let { report ->
                    SecurityHealthSummaryRow(report = report)
                }

                SettingsActionItem(
                    title = stringResource(R.string.view_audit_log),
                    subtitle = stringResource(R.string.view_audit_log_desc),
                    icon = Icons.Default.History,
                    trailingText = "${uiState.securityEvents.size} events",
                    trailingTextColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenSecurityLogs
                )

                SettingsActionItem(
                    title = stringResource(R.string.cleanup_stale_apps),
                    subtitle = stringResource(R.string.cleanup_stale_apps_desc),
                    icon = Icons.Default.CleaningServices,
                    onClick = onCleanupStalePackages
                )
            }

            // Section 1: App Detection & Monitoring (Phase 3 & 4)
            SettingsSection(title = stringResource(R.string.section_monitoring)) {
                // Central Lock Engine Status Header
                LockEngineSummaryRow(
                    engineMode = uiState.engineMode,
                    protectedCount = uiState.protectedAppsCount
                )

                // Accessibility Service (Phase 4 Real-Time Protection)
                SettingsActionItem(
                    title = stringResource(R.string.accessibility_title),
                    subtitle = stringResource(R.string.accessibility_desc),
                    icon = Icons.Default.Accessibility,
                    trailingText = if (uiState.isAccessibilityEnabled) stringResource(R.string.accessibility_granted) else stringResource(R.string.accessibility_grant),
                    trailingTextColor = if (uiState.isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    onClick = onRequestAccessibility
                )

                // Usage Access (Phase 3 Fallback)
                SettingsActionItem(
                    title = stringResource(R.string.usage_access_title),
                    subtitle = stringResource(R.string.usage_access_desc),
                    icon = Icons.Default.Security,
                    trailingText = if (uiState.hasUsageAccess) stringResource(R.string.usage_access_granted) else stringResource(R.string.usage_access_grant),
                    trailingTextColor = if (uiState.hasUsageAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    onClick = onRequestUsageAccess
                )

                // Background Monitoring Toggle (UsageStats polling service)
                SettingsToggleItem(
                    title = stringResource(R.string.service_monitoring_title),
                    subtitle = stringResource(R.string.service_monitoring_desc),
                    icon = Icons.Default.RemoveRedEye,
                    checked = uiState.isMonitoringRunning,
                    enabled = uiState.hasUsageAccess,
                    onCheckedChange = onToggleMonitoring
                )

                // Battery Optimization Status
                SettingsActionItem(
                    title = stringResource(R.string.battery_status_title),
                    subtitle = stringResource(R.string.battery_status_desc),
                    icon = if (uiState.isIgnoringBatteryOptimizations) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                    trailingText = if (uiState.isIgnoringBatteryOptimizations) stringResource(R.string.battery_status_unrestricted) else "Optimized",
                    trailingTextColor = if (uiState.isIgnoringBatteryOptimizations) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRequestBatteryOptimizationSettings
                )
            }

            // Section 2: PIN Authentication
            SettingsSection(title = stringResource(R.string.section_pin)) {
                SettingsActionItem(
                    title = stringResource(R.string.change_pin),
                    subtitle = stringResource(R.string.change_pin_desc),
                    icon = Icons.Default.Key,
                    onClick = onOpenChangePin
                )
                SettingsActionItem(
                    title = stringResource(R.string.remove_pin),
                    subtitle = stringResource(R.string.remove_pin_desc),
                    icon = Icons.Default.DeleteOutline,
                    isDestructive = true,
                    onClick = onOpenRemovePin
                )
            }

            // Section 3: Biometrics
            SettingsSection(title = stringResource(R.string.section_biometric)) {
                val isSupported = uiState.biometricStatus is BiometricStatus.Available
                val statusMessage = when (uiState.biometricStatus) {
                    is BiometricStatus.Available -> stringResource(R.string.use_fingerprint_desc)
                    is BiometricStatus.NoneEnrolled -> stringResource(R.string.biometric_none_enrolled)
                    else -> stringResource(R.string.biometric_not_supported)
                }

                SettingsToggleItem(
                    title = stringResource(R.string.use_fingerprint_title),
                    subtitle = statusMessage,
                    icon = Icons.Default.Fingerprint,
                    checked = uiState.isBiometricEnabled && isSupported,
                    enabled = isSupported,
                    onCheckedChange = onToggleBiometric
                )
            }

            // Section 4: Session & Timeout
            SettingsSection(title = stringResource(R.string.section_session)) {
                SettingsActionItem(
                    title = stringResource(R.string.lock_after_title),
                    subtitle = stringResource(uiState.sessionTimeout.labelResId),
                    icon = Icons.Default.LockClock,
                    onClick = { showTimeoutDialog = true }
                )
            }

            // Section 5: Live Diagnostics / Debug Mode (Phase 4 & 5)
            SettingsSection(title = stringResource(R.string.section_diagnostics)) {
                DiagnosticCard(
                    uiState = uiState
                )
            }

            // Section 6: Device Management & Samsung Knox Protection (Phase 6 & 7)
            SettingsSection(title = stringResource(R.string.section_device_management)) {
                DeviceManagementStatusCard(uiState = uiState)
                Spacer(modifier = Modifier.height(12.dp))
                KnoxProtectionDashboardCard(
                    uiState = uiState,
                    onApplyProtection = onApplyKnoxProtection,
                    onRemoveProtection = onRemoveKnoxProtection
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Security Audit Log Dialog
    if (uiState.showSecurityLogDialog) {
        SecurityEventLogDialog(
            events = uiState.securityEvents,
            onDismiss = onCloseSecurityLogs,
            onClearLogs = onClearSecurityLogs
        )
    }

    // Accessibility Explanation Dialog
    if (uiState.showAccessibilityExplanation) {
        AlertDialog(
            onDismissRequest = onDismissAccessibilityExplanation,
            title = {
                Text(
                    text = stringResource(R.string.accessibility_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.accessibility_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissAccessibilityExplanation()
                        onRequestAccessibility()
                    }
                ) {
                    Text(text = stringResource(R.string.go_to_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissAccessibilityExplanation) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    // Usage Access Explanation Dialog
    if (uiState.showUsageAccessExplanation) {
        AlertDialog(
            onDismissRequest = onDismissUsageAccessExplanation,
            title = {
                Text(
                    text = stringResource(R.string.usage_access_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.usage_access_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissUsageAccessExplanation()
                        onRequestUsageAccess()
                    }
                ) {
                    Text(text = stringResource(R.string.usage_access_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUsageAccessExplanation) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    // Timeout Selection Dialog
    if (showTimeoutDialog) {
        TimeoutSelectionDialog(
            currentTimeout = uiState.sessionTimeout,
            onDismiss = { showTimeoutDialog = false },
            onSelect = { selected ->
                onSelectSessionTimeout(selected)
                showTimeoutDialog = false
            }
        )
    }

    // Change PIN Dialog
    if (uiState.showChangePinDialog) {
        ChangePinDialog(
            onDismiss = onCloseChangePin,
            onConfirmChange = onChangePin,
            onVerifyCurrentPin = onVerifyCurrentPin
        )
    }

    // Remove PIN Dialog
    if (uiState.showRemovePinDialog) {
        RemovePinDialog(
            onDismiss = onCloseRemovePin,
            onConfirmRemove = onRemovePin,
            onVerifyCurrentPin = onVerifyCurrentPin
        )
    }
}

@Composable
private fun SecurityHealthSummaryRow(
    report: SecurityHealthReport
) {
    val (statusText, statusColor) = when (report.status) {
        OverallSecurityStatus.SECURE -> stringResource(R.string.health_status_secure) to MaterialTheme.colorScheme.primary
        OverallSecurityStatus.WARNING -> stringResource(R.string.health_status_warning) to MaterialTheme.colorScheme.tertiary
        OverallSecurityStatus.DEGRADED -> stringResource(R.string.health_status_degraded) to MaterialTheme.colorScheme.error
        OverallSecurityStatus.SETUP_REQUIRED -> stringResource(R.string.health_status_setup) to MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Security Posture",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = report.status.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}

@Composable
private fun LockEngineSummaryRow(
    engineMode: LockEngineMode,
    protectedCount: Int
) {
    val (statusText, statusColor) = when (engineMode) {
        LockEngineMode.ACTIVE_ACCESSIBILITY -> stringResource(R.string.lock_engine_active_accessibility) to MaterialTheme.colorScheme.primary
        LockEngineMode.ACTIVE_USAGE_STATS -> stringResource(R.string.lock_engine_active_usage_stats) to MaterialTheme.colorScheme.tertiary
        LockEngineMode.LIMITED -> stringResource(R.string.lock_engine_limited) to MaterialTheme.colorScheme.error
        LockEngineMode.DISABLED -> stringResource(R.string.lock_engine_disabled) to MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.lock_engine_status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.protected_apps_stat_format, protectedCount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DiagnosticCard(
    uiState: SecuritySettingsUiState
) {
    val mon = uiState.monitoringStatus
    val a11yStatus = when {
        uiState.isAccessibilityRunning -> "RUNNING"
        uiState.isAccessibilityEnabled -> "ENABLED (IDLE)"
        else -> "DISABLED"
    }
    val a11yColor = when {
        uiState.isAccessibilityRunning -> MaterialTheme.colorScheme.primary
        uiState.isAccessibilityEnabled -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DiagnosticRow(
            label = stringResource(R.string.diag_monitoring_status),
            value = a11yStatus,
            valueColor = a11yColor
        )
        DiagnosticRow(
            label = stringResource(R.string.diag_detection_source),
            value = mon.detectionSource.name
        )
        DiagnosticRow(
            label = stringResource(R.string.diag_engine_state),
            value = mon.engineState.javaClass.simpleName
        )
        DiagnosticRow(
            label = stringResource(R.string.diag_current_pkg),
            value = mon.currentPackage ?: stringResource(R.string.none)
        )
        DiagnosticRow(
            label = stringResource(R.string.diag_previous_pkg),
            value = mon.previousPackage ?: stringResource(R.string.none)
        )
        DiagnosticRow(
            label = stringResource(R.string.diag_auth_status),
            value = uiState.authorizationStatus,
            valueColor = if (uiState.authorizationStatus == "VALID") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        DiagnosticRow(
            label = stringResource(R.string.diag_last_lock),
            value = mon.lastLockedPackage ?: stringResource(R.string.none)
        )
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailingText: String? = null,
    trailingTextColor: Color = MaterialTheme.colorScheme.primary,
    isDestructive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = trailingTextColor
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun TimeoutSelectionDialog(
    currentTimeout: SessionTimeout,
    onDismiss: () -> Unit,
    onSelect: (SessionTimeout) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.lock_after_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column {
                SessionTimeout.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == currentTimeout,
                            onClick = { onSelect(option) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(option.labelResId),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onConfirmChange: (currentPin: String, newPin: String, (Boolean) -> Unit) -> Unit,
    onVerifyCurrentPin: suspend (String) -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmNewPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (step == 1) stringResource(R.string.verify_current_pin_title) else stringResource(R.string.change_pin),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == 1) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) currentPin = it },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) newPin = it },
                        label = { Text("New PIN (4-8 digits)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmNewPin,
                        onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) confirmNewPin = it },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (step == 1) {
                        coroutineScope.launch {
                            val isValid = onVerifyCurrentPin(currentPin)
                            if (isValid) {
                                step = 2
                                errorMessage = null
                            } else {
                                errorMessage = "Current PIN is incorrect"
                            }
                        }
                    } else {
                        if (newPin.length < PinAuthenticator.MIN_PIN_LENGTH) {
                            errorMessage = "PIN must be at least ${PinAuthenticator.MIN_PIN_LENGTH} digits"
                            return@TextButton
                        }
                        if (newPin != confirmNewPin) {
                            errorMessage = "PINs do not match"
                            return@TextButton
                        }
                        onConfirmChange(currentPin, newPin) { success ->
                            if (!success) {
                                errorMessage = "Failed to update PIN"
                            }
                        }
                    }
                }
            ) {
                Text(text = if (step == 1) "Next" else stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun RemovePinDialog(
    onDismiss: () -> Unit,
    onConfirmRemove: (currentPin: String, (Boolean) -> Unit) -> Unit,
    onVerifyCurrentPin: suspend (String) -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.remove_pin_confirm_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.remove_pin_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) currentPin = it },
                    label = { Text("Enter current PIN to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        val isValid = onVerifyCurrentPin(currentPin)
                        if (isValid) {
                            onConfirmRemove(currentPin) { success ->
                                if (!success) {
                                    errorMessage = "Failed to remove PIN"
                                }
                            }
                        } else {
                            errorMessage = "Current PIN is incorrect"
                        }
                    }
                }
            ) {
                Text(
                    text = stringResource(R.string.remove_pin),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Device Management status card showing provisioning state, status indicator,
 * and expandable diagnostic details.
 */
@Composable
private fun DeviceManagementStatusCard(uiState: SecuritySettingsUiState) {
    var showDiagnostics by remember { mutableStateOf(false) }

    val state = uiState.devicePolicyState
    val diagnostics = uiState.deviceManagementDiagnostics

    val statusColor = when (state) {
        is DevicePolicyState.DeviceOwnerActive -> Color(0xFF4CAF50)
        is DevicePolicyState.DeviceAdminActive -> Color(0xFFFFA726)
        is DevicePolicyState.NotProvisioned -> MaterialTheme.colorScheme.onSurfaceVariant
        is DevicePolicyState.DeviceOwnerUnavailable -> MaterialTheme.colorScheme.error
    }

    val statusLabel = when (state) {
        is DevicePolicyState.DeviceOwnerActive -> stringResource(R.string.device_mgmt_owner_active)
        is DevicePolicyState.DeviceAdminActive -> stringResource(R.string.device_mgmt_admin_active)
        is DevicePolicyState.NotProvisioned -> stringResource(R.string.device_mgmt_not_provisioned)
        is DevicePolicyState.DeviceOwnerUnavailable -> stringResource(R.string.device_mgmt_unavailable)
    }

    val statusDesc = when (state) {
        is DevicePolicyState.DeviceOwnerActive -> stringResource(R.string.device_mgmt_owner_active_desc)
        is DevicePolicyState.DeviceAdminActive -> stringResource(R.string.device_mgmt_admin_active_desc)
        is DevicePolicyState.NotProvisioned -> stringResource(R.string.device_mgmt_not_provisioned_desc)
        is DevicePolicyState.DeviceOwnerUnavailable -> state.reason
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status header with indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Text(
                        text = statusDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Expandable diagnostics
            if (diagnostics != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDiagnostics = !showDiagnostics }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.device_mgmt_diagnostics),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = showDiagnostics) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DiagnosticRow(stringResource(R.string.device_mgmt_package), diagnostics.packageName)
                        DiagnosticRow(stringResource(R.string.device_mgmt_admin_component), diagnostics.adminComponent)
                        DiagnosticRow(
                            stringResource(R.string.device_mgmt_device_owner),
                            if (diagnostics.isDeviceOwner) stringResource(R.string.device_mgmt_yes) else stringResource(R.string.device_mgmt_no)
                        )
                        DiagnosticRow(
                            stringResource(R.string.device_mgmt_device_admin),
                            if (diagnostics.isDeviceAdmin) stringResource(R.string.device_mgmt_yes) else stringResource(R.string.device_mgmt_no)
                        )
                        DiagnosticRow(
                            stringResource(R.string.device_mgmt_owner_package),
                            diagnostics.deviceOwnerPackage ?: stringResource(R.string.device_mgmt_none)
                        )
                        DiagnosticRow(stringResource(R.string.device_mgmt_api_level), diagnostics.apiLevel.toString())
                        DiagnosticRow(stringResource(R.string.device_mgmt_android_version), diagnostics.androidVersion)
                        DiagnosticRow(stringResource(R.string.device_mgmt_app_version), diagnostics.appVersionName)
                        DiagnosticRow(stringResource(R.string.device_mgmt_device_model), diagnostics.deviceModel)
                        DiagnosticRow(stringResource(R.string.device_mgmt_state), diagnostics.state.displayLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Knox & Device Owner Application Protection Dashboard Card.
 * Displays live, verified protection status for App Lock itself.
 */
@Composable
private fun KnoxProtectionDashboardCard(
    uiState: SecuritySettingsUiState,
    onApplyProtection: () -> Unit,
    onRemoveProtection: () -> Unit
) {
    val knoxState = uiState.knoxPolicyState
    val isDO = knoxState.isDeviceOwner
    val isKnox = knoxState.isKnoxAvailable

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.section_knox_protection),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.knox_protection_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Device Owner row
            KnoxIndicatorRow(
                label = stringResource(R.string.device_mgmt_device_owner),
                statusText = if (isDO) "Active" else "Inactive",
                color = if (isDO) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
            )

            // Samsung Knox row
            KnoxIndicatorRow(
                label = stringResource(R.string.knox_title),
                statusText = if (isKnox) (knoxState.knoxVersion ?: "Available") else "Unavailable",
                color = if (isKnox) Color(0xFF4CAF50) else Color(0xFFFFA726)
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Text(
                text = stringResource(R.string.applock_protection_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            // Force Stop Protection
            PolicyStatusRow(
                label = stringResource(R.string.policy_force_stop),
                status = knoxState.forceStopProtection
            )

            // Uninstall Protection
            PolicyStatusRow(
                label = stringResource(R.string.policy_uninstall),
                status = knoxState.uninstallProtection
            )

            // Disable Protection
            PolicyStatusRow(
                label = stringResource(R.string.policy_disable),
                status = knoxState.disableProtection
            )

            // Admin Removability
            PolicyStatusRow(
                label = stringResource(R.string.policy_admin_removable),
                status = knoxState.adminRemovableProtection
            )

            // Battery Protection
            PolicyStatusRow(
                label = stringResource(R.string.policy_battery),
                status = knoxState.batteryProtection
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Action Buttons
            Button(
                onClick = onApplyProtection,
                modifier = Modifier.fillMaxWidth(),
                enabled = isDO
            ) {
                Text(
                    text = stringResource(R.string.apply_protection_button),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            OutlinedButton(
                onClick = onRemoveProtection,
                modifier = Modifier.fillMaxWidth(),
                enabled = isDO
            ) {
                Text(
                    text = stringResource(R.string.remove_protection_button),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun KnoxIndicatorRow(
    label: String,
    statusText: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun PolicyStatusRow(
    label: String,
    status: PolicyStatus
) {
    val (color, text) = when (status) {
        is PolicyStatus.Applied -> Pair(Color(0xFF4CAF50), "Active")
        is PolicyStatus.Unsupported -> Pair(Color(0xFFFFA726), "Unsupported")
        is PolicyStatus.LicenseRequired -> Pair(Color(0xFFFFA726), "License Required")
        is PolicyStatus.Failed -> Pair(Color(0xFFE53935), "Failed")
        is PolicyStatus.NotApplied -> Pair(Color(0xFF9E9E9E), "Not Configured")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

