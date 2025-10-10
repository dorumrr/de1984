# Release Signing Guide

Complete guide for building and distributing De1984 APKs.

---

## 🎯 Understanding APK Types

De1984 supports **TWO distribution methods** with **DIFFERENT signatures**:

### 1️⃣ F-Droid Distribution (Recommended)
- **Command:** `./dev.sh fdroid`
- **Signature:** Debug keystore (standard Android debug key)
- **File:** `de1984-v1.0.0-release.apk`
- **Purpose:** Upload to GitHub for F-Droid reproducible builds
- **Users:** Install from F-Droid app store

### 2️⃣ Personal Distribution
- **Command:** `./dev.sh release`
- **Signature:** Your production keystore (custom key)
- **File:** `de1984-v1.0.0-release-signed.apk`
- **Purpose:** Direct distribution (sideloading)
- **Users:** Manual APK installation

⚠️ **IMPORTANT:** Users **CANNOT** switch between F-Droid and personal versions without uninstalling first!

---

## 🚀 Quick Start

### For F-Droid Distribution

```bash
# Build APK for F-Droid
./dev.sh fdroid

# Upload to GitHub releases
# File: app/build/outputs/apk/release/de1984-v1.0.0-release.apk
```

### For Personal Distribution

```bash
# First time only: Create production keystore
./dev.sh create-keystore

# Build and sign with production key
./dev.sh release

# Distribute directly
# File: app/build/outputs/apk/release/de1984-v1.0.0-release-signed.apk
```

---

## 💻 Available Commands

| Command | Output File | Signature | Purpose |
|---------|-------------|-----------|---------|
| `./dev.sh build` | `de1984-v1.0.0-debug.apk` | Debug key | Local testing only |
| `./dev.sh fdroid` | `de1984-v1.0.0-release.apk` | Debug key | F-Droid distribution |
| `./dev.sh release` | `de1984-v1.0.0-release-signed.apk` | Production key | Personal distribution |
| `./dev.sh install` | (installs debug) | Debug key | Install on device/emulator |

---

## 📋 F-Droid Release Workflow

### First Release (v1.0.0)

```bash
# 1. Update version in app/build.gradle.kts
#    versionCode = 1
#    versionName = "1.0.0"

# 2. Build F-Droid APK
./dev.sh fdroid

# 3. Verify debug keystore signature
keytool -list -v -keystore ~/.android/debug.keystore -storepass android -keypass android | grep SHA256

# 4. Tag and push
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0

# 5. Upload to GitHub releases
# Upload: app/build/outputs/apk/release/de1984-v1.0.0-release.apk
# URL: https://github.com/dorumrr/de1984/releases/tag/v1.0.0

# 6. Update F-Droid YAML
# Binaries: https://github.com/dorumrr/de1984/releases/download/v%v/de1984-v%v-release.apk
# AllowedAPKSigningKeys: c0857326a4d913b819098a99f56c0ddbaac1c9e3523cb4fc3586ff27b3160845
```

### Subsequent Releases (v1.0.1+)

```bash
# 1. Update version in app/build.gradle.kts
#    versionCode = 2
#    versionName = "1.0.1"

# 2. Build F-Droid APK
./dev.sh fdroid

# 3. Tag and push
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin v1.0.1

# 4. Upload to GitHub releases
# Upload: app/build/outputs/apk/release/de1984-v1.0.1-release.apk

# 5. F-Droid auto-detects new version and builds
```

---

## 🔐 Production Release Workflow (Personal Distribution)

### First Release ONLY (v1.0.0)

```bash
# 1. Create production keystore (first time only)
./dev.sh create-keystore
# You'll be asked for:
# - Keystore password (choose strong password)
# - Your name (e.g., "Doru Moraru")
# - Organization (optional, e.g., "De1984")

# 2. Build and sign with production key
./dev.sh release

# 3. Test on device
adb install app/build/outputs/apk/release/de1984-v1.0.0-release-signed.apk

# 4. Tag and push
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0

# 5. Distribute de1984-v1.0.0-release-signed.apk
```

### Subsequent Releases (v1.0.1+)

```bash
# 1. Update version in app/build.gradle.kts
#    versionCode = 2
#    versionName = "1.0.1"

# 2. Build and sign
./dev.sh release

# 3. Test update
adb install -r app/build/outputs/apk/release/de1984-v1.0.1-release-signed.apk

# 4. Tag and push
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin v1.0.1

# 5. Distribute de1984-v1.0.1-release-signed.apk
```

---

## 🔒 Security - CRITICAL

### ⚠️ Backup Your Keystores!

#### Debug Keystore (for F-Droid)
**Location:** `~/.android/debug.keystore`

**If you lose this:**
- Users cannot update from F-Droid
- You must create new F-Droid submission with new signature
- Users must uninstall and reinstall

**Backup:**
```bash
# Backup debug keystore
cp ~/.android/debug.keystore ~/secure-backup/de1984-debug.keystore

# Restore on new machine
cp ~/secure-backup/de1984-debug.keystore ~/.android/debug.keystore
```

#### Production Keystore (for personal distribution)
**Location:** `release-keystore.jks`

**If you lose this:**
- Users cannot update your personal distribution
- You CANNOT sign updates with same signature
- Users must uninstall and reinstall

**What to backup:**
- `release-keystore.jks` file
- Keystore password
- Key alias: `de1984-release-key`

**Backup:**
```bash
# Set restrictive permissions
chmod 600 release-keystore.jks

# Backup to multiple locations
cp release-keystore.jks ~/secure-backup/
cp release-keystore.jks /path/to/encrypted/drive/

# View keystore info
keytool -list -v -keystore release-keystore.jks

# Get SHA256 fingerprint
keytool -list -v -keystore release-keystore.jks | grep SHA256
```

---

## 🔍 Verifying Signatures

### Check Debug Keystore (F-Droid)
```bash
# Get debug keystore SHA256
keytool -list -v -keystore ~/.android/debug.keystore -storepass android -keypass android | grep SHA256

# Expected output:
# SHA256: C0:85:73:26:A4:D9:13:B8:19:09:8A:99:F5:6C:0D:DB:AA:C1:C9:E3:52:3C:B4:FC:35:86:FF:27:B3:16:08:45

# This should match AllowedAPKSigningKeys in F-Droid YAML (lowercase, no colons):
# c0857326a4d913b819098a99f56c0ddbaac1c9e3523cb4fc3586ff27b3160845
```

### Check Production Keystore
```bash
# Get production keystore SHA256
keytool -list -v -keystore release-keystore.jks | grep SHA256

# Verify APK signature
unzip -p app/build/outputs/apk/release/de1984-v1.0.0-release-signed.apk META-INF/*.RSA | keytool -printcert | grep SHA256
```

---

## ✅ Release Checklist

### First Time Setup
- [ ] Decide: F-Droid OR Personal distribution (or both)
- [ ] If F-Droid: Backup `~/.android/debug.keystore`
- [ ] If Personal: `./dev.sh create-keystore` and backup `release-keystore.jks`
- [ ] Save all passwords in password manager

### Every F-Droid Release
- [ ] Update `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Test app thoroughly
- [ ] `./dev.sh fdroid`
- [ ] Verify debug keystore signature matches F-Droid YAML
- [ ] Create and push Git tag
- [ ] Upload `de1984-v1.0.0-release.apk` to GitHub releases
- [ ] Wait for F-Droid to build and publish

### Every Personal Release
- [ ] Update `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Test app thoroughly
- [ ] `./dev.sh release`
- [ ] Test signed APK on device
- [ ] Create and push Git tag
- [ ] Distribute `de1984-v1.0.0-release-signed.apk`

---

## 🆘 Troubleshooting

### "Keystore not found"
```bash
# For production releases
./dev.sh create-keystore
```

### "Signature mismatch" on F-Droid
```bash
# Verify you're using debug keystore
keytool -list -v -keystore ~/.android/debug.keystore -storepass android -keypass android | grep SHA256

# Rebuild with correct signature
./dev.sh fdroid
```

### "Cannot install APK" on device
```bash
# If switching between F-Droid and personal versions
adb uninstall io.github.dorumrr.de1984
adb install app/build/outputs/apk/release/de1984-v1.0.0-release.apk
```

### Lost debug keystore
```bash
# If you have backup
cp ~/secure-backup/de1984-debug.keystore ~/.android/debug.keystore

# If no backup - you'll need to:
# 1. Create new F-Droid submission with new signature
# 2. Users must uninstall and reinstall
```

---

## 📚 Resources

- **Help:** `./dev.sh help`
- **F-Droid Docs:** https://f-droid.org/docs/Reproducible_Builds/
- **Android Signing:** https://developer.android.com/studio/publish/app-signing

---

## 🎯 Summary

| Distribution | Command | File | Signature | Users |
|--------------|---------|------|-----------|-------|
| **F-Droid** | `./dev.sh fdroid` | `de1984-v1.0.0-release.apk` | Debug keystore | F-Droid app |
| **Personal** | `./dev.sh release` | `de1984-v1.0.0-release-signed.apk` | Production keystore | Direct install |
| **Testing** | `./dev.sh build` | `de1984-v1.0.0-debug.apk` | Debug keystore | Development only |

**Choose ONE distribution method and stick with it!** Users cannot switch between F-Droid and personal versions without uninstalling first.
