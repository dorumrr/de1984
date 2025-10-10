# Dev Script Changes Summary

## 🎯 What Was Changed

### 1. New `dev.sh` Commands

#### Added `fdroid` Command
```bash
./dev.sh fdroid
```
- Builds **unsigned release APK** for F-Droid reproducible builds
- Signed with **debug keystore** (standard Android debug key)
- Output: `app/build/outputs/apk/release/de1984-v1.0.0-release.apk`
- **Purpose:** Upload to GitHub releases for F-Droid verification
- Shows debug keystore SHA256 for verification

#### Updated `release` Command
```bash
./dev.sh release
```
- Builds and signs with **production keystore**
- Output: `app/build/outputs/apk/release/de1984-v1.0.0-release-signed.apk`
- **Purpose:** Personal distribution (NOT for F-Droid)
- Shows clear warning about different signature

#### Updated `build` Command
```bash
./dev.sh build
```
- Builds **debug APK** for local testing
- Output: `app/build/outputs/apk/debug/de1984-v1.0.0-debug.apk`
- **Purpose:** Development and testing only

### 2. Welcome Screen

Running `./dev.sh` without arguments now shows:
- **5-second welcome screen** with clear explanations
- **Command descriptions** with visual formatting
- **Important warnings** about F-Droid vs Production signing
- **Auto-continues** after 5 seconds (or press Enter to skip)

### 3. Enhanced Output Messages

#### F-Droid Build Output
```
✅ This APK is ready for F-Droid reproducible builds!
📦 File: de1984-v1.0.0-release.apk
🔗 Upload to: https://github.com/dorumrr/de1984/releases
⚠️  F-Droid YAML should reference this file
⚠️  Binaries: .../de1984-v%v-release.apk
```

#### Production Release Output
```
✅ Production release build complete!
📦 File: de1984-v1.0.0-release-signed.apk
🔗 Upload to: https://github.com/dorumrr/de1984/releases
⚠️  This is for personal distribution only
⚠️  NOT for F-Droid (different signature)
```

---

## 📚 Documentation Updates

### 1. RELEASE_SIGNING_GUIDE.md

**Completely rewritten** with:
- Clear explanation of F-Droid vs Production distribution
- Separate workflows for each distribution method
- Debug keystore backup instructions
- Production keystore backup instructions
- Signature verification commands
- Troubleshooting section
- Complete checklists

**Key Sections:**
- 🎯 Understanding APK Types
- 🚀 Quick Start (F-Droid vs Personal)
- 📋 F-Droid Release Workflow
- 🔐 Production Release Workflow
- 🔒 Security - CRITICAL (Keystore backups)
- 🔍 Verifying Signatures
- ✅ Release Checklist
- 🆘 Troubleshooting

### 2. README.md

**Updated Development Workflow section** with:
- Clear command explanations
- Table showing APK types and purposes
- F-Droid vs Personal distribution explanation
- Warning about signature incompatibility
- Link to detailed RELEASE_SIGNING_GUIDE.md

**New Table:**
| Command | Output File | Signature | Purpose |
|---------|-------------|-----------|---------|
| `./dev.sh build` | `de1984-v1.0.0-debug.apk` | Debug key | Local testing only |
| `./dev.sh fdroid` | `de1984-v1.0.0-release.apk` | Debug key | F-Droid distribution |
| `./dev.sh release` | `de1984-v1.0.0-release-signed.apk` | Production key | Personal distribution |

---

## 🔑 Key Concepts Explained

### Debug Keystore (F-Droid)
- **Location:** `~/.android/debug.keystore`
- **SHA256:** `c0857326a4d913b819098a99f56c0ddbaac1c9e3523cb4fc3586ff27b3160845`
- **Used by:** `./dev.sh build` and `./dev.sh fdroid`
- **Purpose:** F-Droid reproducible builds
- **Must backup:** Yes! If lost, users can't update from F-Droid

### Production Keystore (Personal)
- **Location:** `release-keystore.jks`
- **SHA256:** Your custom signature
- **Used by:** `./dev.sh release`
- **Purpose:** Personal distribution
- **Must backup:** Yes! If lost, users can't update your personal distribution

### Why Two Different Signatures?

**F-Droid Distribution:**
1. You build APK with debug keystore
2. Upload to GitHub releases
3. F-Droid builds from source (also with debug keystore)
4. F-Droid compares: Your APK vs Their build
5. If match → F-Droid publishes YOUR APK (with debug signature)

**Personal Distribution:**
1. You build APK with production keystore
2. Sign with YOUR private key
3. Distribute directly to users
4. Users install YOUR signed APK

**Users CANNOT switch** between F-Droid and personal versions because:
- Different signatures = Android treats them as different apps
- Must uninstall one to install the other

---

## 🎨 Visual Improvements

### Welcome Screen
```
╔══════════════════════════════════════════════════════════════════════╗
║                                                                      ║
║                🔷 De1984 Development Script 🔷                       ║
║                                                                      ║
╚══════════════════════════════════════════════════════════════════════╝

📋 AVAILABLE COMMANDS:

BUILD    - Build debug APK for local testing
         → Signed with debug keystore
         → For development and testing only
         → Command: ./dev.sh build

INSTALL  - Uninstall old version and install fresh debug APK
         → Builds, uninstalls, and installs on device/emulator
         → For testing during development
         → Command: ./dev.sh install [device|emulator]

FDROID   - Build APK for F-Droid reproducible builds
         → Unsigned release APK (signed with debug key by Gradle)
         → Upload this to GitHub releases
         → Reference in F-Droid YAML: de1984-v%v-release.apk
         → Command: ./dev.sh fdroid

RELEASE  - Build production-signed APK for personal distribution
         → Signed with YOUR production keystore
         → For direct distribution (NOT for F-Droid)
         → Different signature than F-Droid version
         → Command: ./dev.sh release
```

### Color-Coded Output
- 🔵 **Blue:** Info messages
- 🟢 **Green:** Success messages
- 🟡 **Yellow:** Warnings
- 🔴 **Red:** Errors and important notices

---

## ✅ Testing Checklist

### Test F-Droid Build
```bash
./dev.sh fdroid
# Should output:
# - de1984-v1.0.0-release.apk
# - Debug keystore SHA256
# - GitHub upload instructions
```

### Test Production Build
```bash
./dev.sh create-keystore  # First time only
./dev.sh release
# Should output:
# - de1984-v1.0.0-release-signed.apk
# - Warning about NOT for F-Droid
# - GitHub upload instructions
```

### Test Welcome Screen
```bash
./dev.sh
# Should show:
# - Welcome screen with command explanations
# - 5-second countdown
# - Help documentation
```

### Test Help
```bash
./dev.sh help
# Should show:
# - All available commands
# - Examples
# - F-Droid workflow
# - Production workflow
```

---

## 🚀 Next Steps

1. **Test the script:**
   ```bash
   ./dev.sh          # See welcome screen
   ./dev.sh help     # See full help
   ./dev.sh fdroid   # Build F-Droid APK
   ```

2. **Verify documentation:**
   - Read RELEASE_SIGNING_GUIDE.md
   - Read README.md development section
   - Ensure everything is clear

3. **Backup keystores:**
   ```bash
   # Backup debug keystore
   cp ~/.android/debug.keystore ~/secure-backup/

   # Backup production keystore (if created)
   cp release-keystore.jks ~/secure-backup/
   ```

4. **Commit changes:**
   ```bash
   git add dev.sh RELEASE_SIGNING_GUIDE.md README.md
   git commit -m "feat: Add fdroid command and update release documentation

   - Add ./dev.sh fdroid for F-Droid reproducible builds
   - Add welcome screen with 5-second explanation
   - Completely rewrite RELEASE_SIGNING_GUIDE.md
   - Update README.md with new workflow
   - Clarify F-Droid vs Production distribution
   - Add signature verification and backup instructions"
   ```

---

## 📝 Summary

**What users see now:**
1. Run `./dev.sh` → Clear welcome screen explaining all commands
2. Run `./dev.sh fdroid` → Build APK for F-Droid with clear instructions
3. Run `./dev.sh release` → Build production APK with warnings
4. Read RELEASE_SIGNING_GUIDE.md → Complete understanding of both workflows

**Key improvements:**
- ✅ Clear separation of F-Droid vs Production builds
- ✅ Visual welcome screen with explanations
- ✅ Enhanced output messages with GitHub upload reminders
- ✅ Complete documentation rewrite
- ✅ Signature verification commands
- ✅ Keystore backup instructions
- ✅ Troubleshooting guide

**Result:** Developers and maintainers now have a clear, documented workflow for both F-Droid and personal distribution!

