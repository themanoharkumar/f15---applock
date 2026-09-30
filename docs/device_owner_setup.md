# Device Owner Setup — F15 App Lock

## Developer Documentation

This document explains how to provision the F15 App Lock application as a **Device Owner** (Device Policy Controller / DPC) on the Samsung Galaxy F15 5G for development and testing purposes.

---

## ⚠️ CRITICAL SAFETY WARNING

> **Device Owner provisioning changes the device-management state of your Android device.**
>
> - Use a **test device** or ensure the device is **fully backed up** before proceeding.
> - Do NOT provision a device containing data you cannot afford to lose.
> - Device Owner provisioning may require a **factory reset** in certain scenarios to remove.
> - Always maintain a recovery procedure.

---

## 1. Prerequisites

Before provisioning, ensure:

| Requirement | Details |
|---|---|
| **Device** | Samsung Galaxy F15 5G (SM-E156B) |
| **Android Version** | Android 16 / API 36 |
| **One UI** | 8.5 |
| **ADB** | Android Debug Bridge installed and accessible from terminal |
| **USB Debugging** | Enabled in Developer Options on the device |
| **Device Connected** | Device connected via USB and authorized for ADB |
| **App Installed** | `com.f15.applock` APK installed on the device |
| **No Existing DPC** | No other Device Owner already provisioned on the device |
| **No User Accounts** | Remove all Google/Samsung accounts from the device before provisioning (required by Android) |

### Verify ADB Connection

```bash
adb devices
```

Expected output:
```
List of devices attached
XXXXXXXXXXXXXXX    device
```

If the device shows as `unauthorized`, check the USB debugging authorization prompt on the device screen.

---

## 2. Build & Install

```bash
# Build the debug APK
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Verify Package Installation

```bash
adb shell pm list packages | findstr f15.applock
```

Expected:
```
package:com.f15.applock
```

---

## 3. Remove All User Accounts (Required)

Android requires that **no user accounts** (Google, Samsung, etc.) are present on the device before Device Owner provisioning.

On the device:
1. Go to **Settings → Accounts and backup → Manage accounts**
2. Remove **ALL** accounts (Google, Samsung, etc.)
3. Verify no accounts remain

Alternatively via ADB (check first):
```bash
adb shell pm list accounts
```

---

## 4. Device Owner Provisioning

### Set Device Owner via ADB

```bash
adb shell dpm set-device-owner com.f15.applock/.device.AppLockDeviceAdminReceiver
```

### Expected Success Output

```
Success: Device owner set to package com.f15.applock
Active admin set to component {com.f15.applock/com.f15.applock.device.AppLockDeviceAdminReceiver}
```

### Common Failure Reasons

| Error | Cause | Solution |
|---|---|---|
| `Not allowed to set the device owner because there are already some accounts on the device` | Google/Samsung accounts exist | Remove all accounts first |
| `Not allowed to set the device owner because the device has already been set up` | Device already provisioned | Factory reset or remove existing DPC |
| `Trying to set the device owner, but device owner is already set` | Another app is already Device Owner | Remove existing Device Owner first |
| `Unknown admin` | APK not installed or component name wrong | Reinstall APK, verify component name |

---

## 5. Verification

### Verify Device Owner Status

```bash
adb shell dpm list-owners
```

Expected:
```
Device Owner (User 0): ComponentInfo{com.f15.applock/com.f15.applock.device.AppLockDeviceAdminReceiver}
```

### Verify via App

1. Launch App Lock
2. Authenticate with PIN / biometric
3. Navigate to **Settings → Device Management** section
4. Verify status shows: **Device Owner Active**
5. Expand **Device Management Diagnostics** to see full details

### Verify via dumpsys

```bash
adb shell dumpsys device_policy | findstr "Device Owner"
```

---

## 6. How to Remove Device Owner (Development)

During development, you may need to remove Device Owner status.

### Option A: Remove via ADB (Preferred for Development)

```bash
adb shell dpm remove-active-admin com.f15.applock/.device.AppLockDeviceAdminReceiver
```

If the above doesn't work (Device Owner cannot be removed as a simple admin):

```bash
adb shell dpm remove-active-admin --user 0 com.f15.applock/.device.AppLockDeviceAdminReceiver
```

### Option B: Clear Device Owner programmatically

If the app includes a development-mode clear function:
```bash
adb shell am broadcast -a com.f15.applock.CLEAR_DEVICE_OWNER
```

> **Note:** This broadcast is only available in debug builds and requires explicit implementation.

### Option C: Factory Reset

As a last resort:
1. Back up all data
2. Factory reset the device via **Settings → General management → Reset → Factory data reset**
3. This removes ALL data and the Device Owner

---

## 7. What Happens if Provisioning Fails

If provisioning fails:

1. **The device is NOT modified.** Failed provisioning leaves the device in its previous state.
2. **The app continues to function normally** using its existing AccessibilityService-based enforcement.
3. **No data is lost.** Provisioning failure does not affect device data.
4. Review the error message and address the specific cause (see Common Failure Reasons above).

---

## 8. Recovery Procedure

If you need to recover from a problematic Device Owner state:

### Step 1: Try ADB Removal
```bash
adb shell dpm remove-active-admin com.f15.applock/.device.AppLockDeviceAdminReceiver
```

### Step 2: Force Remove (if Step 1 fails)
```bash
# Clear the app data
adb shell pm clear com.f15.applock

# Uninstall the app
adb uninstall com.f15.applock
```

### Step 3: Factory Reset (if Steps 1-2 fail)
This is the guaranteed recovery method. It removes all data.

---

## 9. Development Workflow

```
Build APK
   ↓
Install APK (adb install -r)
   ↓
Verify package (adb shell pm list packages | findstr f15)
   ↓
Remove accounts (if not already done)
   ↓
Provision Device Owner (adb shell dpm set-device-owner ...)
   ↓
Launch App Lock
   ↓
Verify Device Owner status in Settings → Device Management
   ↓
Test normal App Lock functionality (PIN, biometric, app locking)
   ↓
Enable management policies (Phase 7+)
```

---

## 10. Notes

- Device Owner provisioning is **persistent across reboots**. You do not need to re-provision after restarting the device.
- The app reconciles its Device Owner state with the platform on every launch and after every reboot.
- DataStore cached state is never trusted over `DevicePolicyManager.isDeviceOwnerApp()`.
- The existing AccessibilityService-based enforcement continues to work independently of Device Owner status.
- Samsung Knox integration (Phase 7) will add additional management policies on top of the Device Owner foundation.
