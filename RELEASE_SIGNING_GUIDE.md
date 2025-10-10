# Release Signing Guide

Complete guide for building and distributing De1984 APKs.

---

## 🎯 Understanding APK Types

De1984 supports **TWO distribution methods** with **DIFFERENT signatures**:

### 1️⃣ F-Droid Distribution (Recommended)
- **Command:** `./dev.sh release` (production keystore)
- **Signature:** Your production keystore (custom key)
- **File:** `de1984-v1.0.0-release.apk`
- **Purpose:** Upload to GitHub for F-Droid reproducible builds
- **Users:** Install from F-Droid app store
- **Note:** F-Droid verifies reproducibility, then publishes YOUR signed APK

### 2️⃣ Personal Distribution (Alternative)
- **Command:** `./dev.sh release`
- **Signature:** Your production keystore (custom key)
- **File:** `de1984-v1.0.0-release-signed.apk`
- **Purpose:** Direct distribution (sideloading)
- **Users:** Manual APK installation
- **Note:** Same signature as F-Droid version

### 🔄 F-Droid Workflow Evolution

**Initial Testing Phase:**
1. Use debug keystore to verify reproducible builds work
2. F-Droid confirms: "reproducible is OK"

**Production Phase:**
1. Switch to production keystore
2. Rebuild and sign with production key
3. Replace APK on GitHub
4. Update F-Droid YAML with production SHA256
5. Retrigger F-Droid CI pipeline

---

## 🚀 Quick Start

### For F-Droid Distribution

**Phase 1: Testing (Debug Keystore)**
```bash
# Build with debug keystore for testing
./dev.sh fdroid

# Upload to GitHub and submit to F-Droid
# Wait for "reproducible is OK" confirmation
```

**Phase 2: Production (After F-Droid Confirms)**
```bash
# First time only: Create production keystore
./dev.sh create-keystore

# Build and sign with production key
./dev.sh release

# The command will show complete instructions for:
# - Renaming APK for GitHub
# - Replacing APK on GitHub
# - Updating F-Droid YAML with production SHA256
# - Committing and pushing changes
# - Retriggering F-Droid CI
```

### For Personal Distribution

```bash
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
| `./dev.sh release` | `de1984-v1.0.0-release-signed.apk` | Production key | F-Droid + Personal distribution |
| `./dev.sh install` | (installs debug) | Debug key | Install on device/emulator |
| `./dev.sh create-keystore` | `release-keystore.jks` | N/A | Create production keystore (once) |

**Note:** For F-Droid, rename `de1984-v1.0.0-release-signed.apk` to `de1984-v1.0.0-release.apk` before uploading to GitHub.

---

## 📋 F-Droid Release Workflow

### Phase 1: Initial Testing (Debug Keystore)

**Purpose:** Verify reproducible builds work before using production keystore.

```bash
# 1. Update version in app/build.gradle.kts
#    versionCode = 1
#    versionName = "1.0.0"

# 2. Build with debug keystore (for testing)
./dev.sh fdroid

# 3. Tag and push
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0

# 4. Upload to GitHub releases
# Upload: app/build/outputs/apk/release/de1984-v1.0.0-release.apk

# 5. Create F-Droid YAML with debug keystore SHA256
# AllowedAPKSigningKeys: c0857326a4d913b819098a99f56c0ddbaac1c9e3523cb4fc3586ff27b3160845

# 6. Wait for F-Droid feedback: "reproducible is OK"
```

### Phase 2: Production Signing (After F-Droid Confirms)

**Purpose:** Switch to production keystore for actual release.

**The `./dev.sh release` command handles everything and shows complete instructions!**

```bash
# 1. Create production keystore (first time only)
./dev.sh create-keystore

# 2. Build and sign with production key
./dev.sh release
```

**The release command will:**
- ✅ Build and sign APK with production keystore
- ✅ Display production SHA256 for F-Droid YAML
- ✅ Show step-by-step instructions for:
  - Renaming APK for GitHub upload
  - Replacing APK on GitHub releases
  - Updating F-Droid YAML with production SHA256
  - Committing and pushing changes
  - Retriggering F-Droid CI pipeline

**Just follow the on-screen instructions!** Everything you need is displayed after the build completes.

### Subsequent Releases (v1.0.1+)

```bash
# 1. Update version in app/build.gradle.kts
#    versionCode = 2
#    versionName = "1.0.1"

# 2. Build with production key
./dev.sh release

# 3. Rename for GitHub
cp app/build/outputs/apk/release/de1984-v1.0.1-release-signed.apk \
   app/build/outputs/apk/release/de1984-v1.0.1-release.apk

# 4. Tag and push
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin v1.0.1

# 5. Upload to GitHub releases
# Upload: app/build/outputs/apk/release/de1984-v1.0.1-release.apk

# 6. F-Droid auto-detects new version and builds
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
- [ ] Create production keystore: `./dev.sh create-keystore`
- [ ] Backup `release-keystore.jks` to multiple secure locations
- [ ] Save keystore password in password manager
- [ ] Save key alias: `de1984-release-key`

### Phase 1: Initial F-Droid Submission (Debug Keystore)
- [ ] Update `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Test app thoroughly
- [ ] `./dev.sh fdroid` (builds with debug keystore)
- [ ] Create and push Git tag
- [ ] Upload `de1984-v1.0.0-release.apk` to GitHub releases
- [ ] Create F-Droid YAML with debug keystore SHA256
- [ ] Submit to F-Droid
- [ ] Wait for feedback: "reproducible is OK"

### Phase 2: Switch to Production Keystore
- [ ] `./dev.sh release` (build with production keystore)
- [ ] Get production SHA256: `keytool -list -v -keystore release-keystore.jks`
- [ ] Rename APK: `de1984-v1.0.0-release-signed.apk` → `de1984-v1.0.0-release.apk`
- [ ] Delete old debug-signed APK from GitHub
- [ ] Upload new production-signed APK to GitHub
- [ ] Update F-Droid YAML `AllowedAPKSigningKeys` with production SHA256
- [ ] Commit and push F-Droid YAML changes
- [ ] Retrigger F-Droid CI pipeline
- [ ] Verify F-Droid build passes

### Every Subsequent F-Droid Release
- [ ] Update `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Test app thoroughly
- [ ] `./dev.sh release` (production keystore)
- [ ] Rename APK for GitHub upload
- [ ] Create and push Git tag
- [ ] Upload `de1984-v1.0.x-release.apk` to GitHub releases
- [ ] F-Droid auto-detects and builds new version

### Every Personal Distribution Release
- [ ] Update `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Test app thoroughly
- [ ] `./dev.sh release`
- [ ] Test signed APK on device
- [ ] Distribute `de1984-v1.0.0-release-signed.apk` directly

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
| **F-Droid (Initial)** | `./dev.sh fdroid` | `de1984-v1.0.0-release.apk` | Debug keystore | Testing only |
| **F-Droid (Production)** | `./dev.sh release` | `de1984-v1.0.0-release.apk`* | Production keystore | F-Droid app |
| **Personal** | `./dev.sh release` | `de1984-v1.0.0-release-signed.apk` | Production keystore | Direct install |
| **Testing** | `./dev.sh build` | `de1984-v1.0.0-debug.apk` | Debug keystore | Development only |

*Rename from `de1984-v1.0.0-release-signed.apk` before uploading to GitHub.

### F-Droid Workflow Summary

1. **Initial submission:** Use debug keystore to verify reproducible builds
2. **After "reproducible is OK":** Switch to production keystore
3. **All future releases:** Use production keystore

📖 **Complete guide:** [FDROID_PRODUCTION_SIGNING_WORKFLOW.md](FDROID_PRODUCTION_SIGNING_WORKFLOW.md)
