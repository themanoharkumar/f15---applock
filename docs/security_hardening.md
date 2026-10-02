# Phase 8 — Anti-Tamper & Security Hardening Architecture

## Target Device Telemetry
* **Target Hardware**: Samsung Galaxy F15 5G (`SM-E156B` / `m15x`)
* **Android OS**: Android 16 / API 36
* **One UI Version**: 8.5
* **Target Package**: `com.f15.applock`
* **Provisioning State**: Android Enterprise Device Owner (`ACTIVE`)
* **Active Admin Component**: `com.f15.applock/com.f15.applock.device.AppLockDeviceAdminReceiver`

---

## 1. Executive Summary

Phase 8 elevates App Lock from a standalone application blocker to a hardened, tamper-resistant system security component. It addresses runtime integrity threats, privilege revocation, configuration tampering, and collateral policy side-effects through six unified subsystems:

1. **Centralized Security State Machine**: Authoritative, priority-driven evaluation (`RECOVERY_REQUIRED > COMPROMISED > DEGRADED > SECURE`).
2. **Android Keystore-Backed HMAC Integrity**: Cryptographically seals protected app lists, session timeout policies, and biometric preferences against external DataStore manipulation.
3. **Policy Transaction Validator**: Strictly validates all Knox and Android Enterprise policy operations, rejecting wildcards, non-target packages, and global restrictions before they touch platform APIs.
4. **Structured Security Audit Logging**: Bounded (200-event FIFO) audit stream with zero-credential logging guarantees and reactive Compose UI streaming.
5. **Authenticated Administrator Recovery**: No secret gestures or backdoors; all recovery flows require Master PIN verification via `SecureCredentialStore`.
6. **Startup & Boot Reconciliation**: Immediate posture verification and audit logging upon device reboot and cold application startup.

---

## 2. Centralized Security State Machine

### State Hierarchy & Priority Model

Security posture is evaluated using strict fail-closed priority order. An emergency condition immediately supersedes lesser warnings:

```text
Priority 1: RECOVERY_REQUIRED
    ↑ (Supersedes all)
Priority 2: COMPROMISED
    ↑ (Supersedes degraded/healthy)
Priority 3: DEGRADED
    ↑ (Supersedes healthy)
Priority 4: SECURE (All checks passing)
```

| Security State | Trigger Conditions | System Behavior |
| :--- | :--- | :--- |
| **`RECOVERY_REQUIRED`** | • Global restriction active (e.g. `DISALLOW_APPS_CONTROL` or `DISALLOW_UNINSTALL_APPS` applied broadly)<br>• Device Owner privilege lost/revoked after prior active provisioning | Triggers emergency recovery prompts; highlights system baseline failure; urges Master PIN recovery. |
| **`COMPROMISED`** | • Package identity mismatch (not running as `com.f15.applock`)<br>• Hardware HMAC mismatch on protected app list or settings<br>• Unsafe Knox package policy scope detected<br>• Device Owner active under mismatched package identifier | Alerts user to potential tampering; blocks modification of protected packages until recovered; logs critical audit event. |
| **`DEGRADED`** | • Accessibility Service permission granted but service not running<br>• Accessibility permission disabled in system settings<br>• Device Owner unprovisioned (fresh installation)<br>• Master PIN not yet initialized | Displays amber warning banner; prompts user to grant missing permissions or setup PIN; maintains fallback protection where possible. |
| **`SECURE`** | • Package identity verified (`com.f15.applock`)<br>• Device Owner active and authoritative<br>• Knox self-protection policies active and strictly scoped<br>• Accessibility Service actively running<br>• Master PIN configured<br>• HMAC configuration signature valid | Normal operation; full real-time blocking and application self-protection active. |

---

## 3. Cryptographic Configuration Integrity (Android Keystore HMAC)

### Architecture
To prevent attackers or unauthorized tools from altering protected package lists or disabling security flags in local DataStore preferences, configuration state is signed with a private HMAC-SHA256 key.

```text
[Protected Packages] + [Biometric Toggle] + [Session Timeout] + [Schema Version]
                                     ↓
                     Canonical Payload Formatter (V1)
                                     ↓
                         HMAC-SHA256 (Android Keystore)
                                     ↓
                    Private Storage (config_integrity_mac.bin)
```

### Key Invariants
1. **Hardware-Backed Keystore**:
   - Master key is generated via `KeyGenerator` with provider `"AndroidKeyStore"`.
   - Algorithm: `KeyProperties.KEY_ALGORITHM_HMAC_SHA256`.
   - Never exported; non-extractable from hardware secure element/TEE.
2. **Canonical Payload Serialization**:
   - Formatted deterministically: `V1|PKGS=pkgA,pkgB,...|BIO=true|TIMEOUT=60|VER=1`.
   - Package names are strictly sorted alphabetically to guarantee canonical representation regardless of set iteration order.
3. **Syntax & Self-Locking Validation**:
   - Package names must match RFC regex: `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$`.
   - Rejects wildcards (`*`) and self-locking (`com.f15.applock`).
4. **Constant-Time Verification**:
   - Compares computed HMAC against stored token using `MessageDigest.isEqual()` to prevent timing side-channel attacks.
   - If signature verification fails, returns `IntegrityCheckResult.Compromised` and transitions security posture to `COMPROMISED`. Does NOT silently overwrite with defaults.

---

## 4. Policy Transaction Validator

### Preventing Collateral Policy Application
The Phase 7.1 post-mortem revealed that applying global device management restrictions or Knox blocklists without strict package scoping can inadvertently lock the entire device or prevent third-party app uninstallation.

`PolicyTransactionValidator` intercepts all policy transactions before platform calls:

```text
Policy Request (Target Package, Policy Method)
                     ↓
       [PolicyTransactionValidator]
   ├── 1. Package string empty? → REJECT
   ├── 2. Contains wildcard '*'? → REJECT
   ├── 3. Target != 'com.f15.applock'? → REJECT
   ├── 4. List size > 1? → REJECT
   └── 5. Restriction in {DISALLOW_APPS_CONTROL, DISALLOW_UNINSTALL_APPS}? → REJECT
                     ↓
       [Approved] → Apply to Knox / DPM
```

### Pre-Transaction Enforcement
- **Knox `addPackagesToForceStopBlackList`**: Verified to contain exclusively `listOf("com.f15.applock")`.
- **Knox `setApplicationUninstallationDisabled`**: Verified to target exclusively `"com.f15.applock"`.
- **DPM `setUninstallBlocked`**: Verified to target exclusively `"com.f15.applock"`.
- **DPM `addUserRestriction`**: Rejects `UserManager.DISALLOW_APPS_CONTROL` and `UserManager.DISALLOW_UNINSTALL_APPS` from ever being added globally.

---

## 5. Structured Security Audit Logging

### Storage & Eviction Bounds
- In-memory bounded FIFO queue limited to **200 entries** (`MAX_EVENTS = 200`).
- When capacity is reached, oldest entries are evicted automatically.
- Thread-safe updates managed via `ConcurrentLinkedDeque` and published reactively via `StateFlow<List<SecurityEvent>>`.

### Zero-Credential Logging Guarantee
The audit logger enforces strict input sanitization:
- Never accepts or logs PIN digits, passwords, biometric authentication tokens, or cryptographic keys.
- Sanitizes package names, trims whitespace, and limits message lengths.
- Tracks operational metadata:
  - `timestamp`: Epoch milliseconds + formatted human-readable time.
  - `severity`: `INFO`, `WARNING`, `ERROR`, `CRITICAL`.
  - `component`: `DeviceOwner`, `SamsungKnox`, `PolicyValidator`, `Accessibility`, `ConfigIntegrity`, `Authentication`, `Recovery`.
  - `result`: `SUCCESS`, `FAILURE`, `REJECTED`, `COMPROMISED`.
  - `packageName`: Target application identifier.
  - `details`: High-level operational message.

---

## 6. Authenticated Administrator Recovery Mode

### Architectural Safeguards
- **NO Backdoors**: No hidden gestures, bypass PINs (e.g. `0000` or `1234`), or developer escape hatches.
- **Master PIN Authentication**: Administrator recovery requires verification against `SecureCredentialStore.verifyPin()`.
- **Brute-Force Protection**: Inherits PIN lockout delays from `SecureCredentialStore` / `AuthenticationManager`.

### Recovery Operations
When authenticated, `SecurityRecoveryManager.executeRecovery()` performs:
1. **User Restriction Clearing**: Explicitly invokes `dpm.clearUserRestriction(admin, DISALLOW_APPS_CONTROL)` and `DISALLOW_UNINSTALL_APPS` to resolve any unintended global blocks.
2. **Knox Policy Reset & Re-Scope**: Re-queries Samsung Knox `ApplicationPolicy` and clears rogue blocklists.
3. **HMAC Signature Re-Generation**: Inspects active package list, prunes invalid entries, and signs a clean canonical HMAC token using the hardware Keystore.
4. **Posture Re-Evaluation**: Runs `SecurityStateManager.evaluateSecurityPosture()` and transitions system to `SECURE` or `DEGRADED`.
5. **Audit Event Recording**: Logs recovery success or failure with `SecurityEventType.BOOT_RECONCILIATION` / `AUTHENTICATION_SUCCESS`.

---

## 7. Startup & Boot Reconciliation

When the device reboots or the application process restarts:
1. `BootReceiver` receives `ACTION_BOOT_COMPLETED`.
2. Invokes `SecurityStateManager.getInstance(context).reconcileOnStartup()`.
3. Verifies:
   - Device Owner active state (`DeviceOwnerManager.verifyDeviceOwnerIntegrity()`).
   - Knox self-protection policies active (`KnoxManager.getAppProtectionStatus()`).
   - Configuration HMAC validity (`AppLockPreferences.verifyIntegrity()`).
   - Accessibility Service running state (`AppLockAccessibilityService.isServiceRunning`).
4. Logs an authoritative `BOOT_RECONCILIATION` audit event documenting the startup security posture.

---

## 8. Security Limitations & Operational Boundaries

1. **Android Enterprise Revocation**: If a user runs `adb shell dpm remove-active-admin` or factory resets the device from recovery mode, Device Owner authority is terminated by the OS. The app detects this on next launch and flags `RECOVERY_REQUIRED` / `COMPROMISED`.
2. **Knox License Constraints**: Without an activated Samsung Knox Platform for Enterprise (KPE) commercial license, Knox SDK calls return `LicenseRequired`. Native Android Enterprise Device Owner APIs (`setUninstallBlocked`) serve as the primary enforcement layer.
3. **Samsung One UI Background Policy**: On One UI 8.5, background accessibility services can occasionally be stopped by aggressive OS RAM management. App Lock prompts the user to grant "Unrestricted" battery status to minimize termination.
