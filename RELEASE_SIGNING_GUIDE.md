# Release Signing Guide

---

## 🚀 Quick Start

```bash
# First time only
./dev.sh create-keystore

# Every release
./dev.sh release
```

**Output:** `app/build/outputs/apk/release/app-release.apk`

---

## 💻 Commands

| Command | What It Does |
|---------|--------------|
| `./dev.sh create-keystore` | Create keystore (first time only) |
| `./dev.sh release` | Build and sign release APK |
| `./dev.sh build` | Build debug + unsigned release |

---

## 🎯 Release Workflow

### First Release ONLY (v1.0.0)

```bash
# 1. Create keystore (first time only)
./dev.sh create-keystore
# You'll be asked for:
# - Keystore password (choose strong password)
# - Your name (e.g., "Doru Moraru")
# - Organization (optional, e.g., "De1984")

# 2. Build and sign
./dev.sh release

# 3. Test on device
./dev.sh install device

# 4. Tag and push
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0

# 5. Distribute app-release.apk
```

### Subsequent Releases (v1.0.1+)

```bash
# 1. Update version in app/build.gradle.kts
#    versionCode = 2
#    versionName = "1.0.1"

# 2. Build and sign
./dev.sh release

# 3. Test update
./dev.sh install device

# 4. Tag and push
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin v1.0.1

# 5. Distribute app-release.apk
```

---

## 🔒 Security - CRITICAL

### ⚠️ Backup Your Keystore!

**If you lose `release-keystore.jks`, you CANNOT update your app!**

**What to backup:**
- `release-keystore.jks` file
- Keystore password
- Key alias: `de1984-release-key`

**Security:**
```bash
# Set restrictive permissions
chmod 600 release-keystore.jks

# View keystore info
keytool -list -v -keystore release-keystore.jks
```

---

## ✅ Checklist

### First Time ONLY
- [ ] `./dev.sh create-keystore`
- [ ] Backup keystore to 3 locations
- [ ] Save passwords in password manager

### Every Release
- [ ] Update `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Update version in Settings screen
- [ ] Test app thoroughly
- [ ] Run `./dev_fdroid_scan.sh` (F-Droid compliance)
- [ ] `./dev.sh release`
- [ ] Test signed APK: `./dev.sh install device`
- [ ] Create and push Git tag
- [ ] Distribute APK (Github Release, etc...)

---

## 📚 Resources

- **Help:** `./dev.sh help`

