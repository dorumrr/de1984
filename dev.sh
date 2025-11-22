#!/bin/bash

# De1984 Development Script
# Usage: ./dev.sh [command] [options]

set -euo pipefail

# Configuration
APP_ID="io.github.dorumrr.de1984"
APP_ID_DEBUG="${APP_ID}.debug"

# Extract version from build.gradle.kts (now using hardcoded versionName)
APP_VERSION=$(grep 'versionName = ' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')

APK_PATH_DEBUG="app/build/outputs/apk/debug/de1984-v${APP_VERSION}-debug.apk"
APK_PATH_RELEASE="app/build/outputs/apk/release/de1984-v${APP_VERSION}.apk"
SCREENSHOT_DIR="screenshots"
KEYSTORE_PATH="release-keystore.jks"
KEY_ALIAS="de1984-release-key"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warn() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
}

log_header() {
    echo -e "\n${BLUE}🔷 $1${NC}"
}



# Check if ADB is available
check_adb() {
    if ! command -v adb &> /dev/null; then
        log_error "ADB not found! Please install Android SDK Platform Tools."
        exit 1
    fi
}

# Find Android SDK and emulator command
find_android_sdk() {
    # Common Android SDK locations
    local sdk_paths=(
        "$HOME/Library/Android/sdk"                    # macOS default
        "$HOME/Android/Sdk"                            # Linux default
        "${ANDROID_HOME:-}"                            # Environment variable
        "${ANDROID_SDK_ROOT:-}"                        # Alternative env variable
        "/usr/local/android-sdk"                       # System install
        "/opt/android-sdk"                             # Alternative system install
    )

    for sdk_path in "${sdk_paths[@]}"; do
        if [ -n "$sdk_path" ] && [ -d "$sdk_path/emulator" ]; then
            echo "$sdk_path"
            return 0
        fi
    done

    return 1
}

# Get emulator command path
get_emulator_command() {
    # First try system PATH
    if command -v emulator &> /dev/null; then
        echo "emulator"
        return 0
    fi

    # Try to find Android SDK
    local sdk_path=$(find_android_sdk)
    if [ -n "$sdk_path" ]; then
        local emulator_cmd="$sdk_path/emulator/emulator"
        if [ -x "$emulator_cmd" ]; then
            echo "$emulator_cmd"
            return 0
        fi
    fi

    return 1
}

# Find available emulators
find_emulators() {
    local emulator_cmd=$(get_emulator_command)
    if [ -n "$emulator_cmd" ]; then
        "$emulator_cmd" -list-avds 2>/dev/null | head -10
    else
        echo ""
    fi
}

# Find best emulator (prefer Pixel 9a, then first available)
find_best_emulator() {
    local avds=($(find_emulators))

    if [ ${#avds[@]} -eq 0 ]; then
        echo ""
        return 1
    fi

    # First priority: Pixel 9a
    for avd in "${avds[@]}"; do
        if [[ "$avd" =~ [Pp]ixel.*9a ]]; then
            echo "$avd"
            return 0
        fi
    done

    # Fallback: First available
    echo "${avds[0]}"
    return 0
}

# Start emulator automatically
start_emulator() {
    local requested_emulator="${1:-}"
    log_header "Starting Android Emulator"

    # Find emulator command
    local emulator_cmd=$(get_emulator_command)
    if [ -z "$emulator_cmd" ]; then
        log_error "Android emulator command not found!"
        log_info "Searched common locations for Android SDK..."

        # Try to detect Android Studio installation
        if [ -d "/Applications/Android Studio.app" ] || [ -d "$HOME/Applications/Android Studio.app" ]; then
            log_info "Android Studio detected. Please:"
            log_info "1. Open Android Studio"
            log_info "2. Tools → AVD Manager → Start an emulator"
            log_info "3. Then run: ./dev.sh install emulator"
        else
            log_info "Please install Android Studio or Android SDK, then:"
            log_info "1. Create an AVD (Android Virtual Device)"
            log_info "2. Add Android SDK to PATH, or"
            log_info "3. Start emulator from Android Studio"
        fi
        exit 1
    fi

    log_success "Found emulator command: $emulator_cmd"

    # Get list of available emulators
    local avds=($(find_emulators))

    if [ ${#avds[@]} -eq 0 ]; then
        log_error "No Android Virtual Devices (AVDs) found!"
        log_info "Please create an emulator:"
        log_info "1. Open Android Studio"
        log_info "2. Tools → AVD Manager → Create Virtual Device"
        log_info "3. Choose Pixel 9a and Android 15 (API 36) system image"
        log_info "4. Then run: ./dev.sh install"
        exit 1
    fi

    # Choose emulator
    local emulator_name
    if [ -n "$requested_emulator" ]; then
        # Check if requested emulator exists
        local found=false
        for avd in "${avds[@]}"; do
            if [ "$avd" = "$requested_emulator" ]; then
                found=true
                break
            fi
        done

        if [ "$found" = true ]; then
            emulator_name="$requested_emulator"
            log_info "Using requested emulator: $emulator_name"
        else
            log_error "Emulator '$requested_emulator' not found!"
            log_info "Available emulators:"
            for avd in "${avds[@]}"; do
                echo "  📱 $avd"
            done
            exit 1
        fi
    else
        # Use best available emulator (prefer Pixel 9a)
        emulator_name=$(find_best_emulator)

        if [ -z "$emulator_name" ]; then
            log_error "No suitable emulator found"
            exit 1
        fi

        # Check if it's a Pixel 9a
        if [[ "$emulator_name" =~ [Pp]ixel.*9a ]]; then
            log_success "Using Pixel 9a emulator: $emulator_name"
        else
            log_success "Using emulator: $emulator_name"
            log_warn "Note: Pixel 9a emulator not found, using first available"
        fi
    fi

    log_info "Starting emulator fresh (this may take 30-60 seconds)..."

    # Start emulator in background with output redirected to /dev/null
    # -wipe-data: Start with fresh data (factory reset)
    # -no-snapshot-save: Don't save state on exit
    # -no-audio: Disable audio for faster startup
    "$emulator_cmd" -avd "$emulator_name" -wipe-data -no-snapshot-save -no-audio > /dev/null 2>&1 &
    local emulator_pid=$!

    log_info "Emulator starting with PID: $emulator_pid"
    log_info "Waiting for emulator to boot..."

    # Wait for emulator to be ready (max 3 minutes)
    local timeout=180
    local elapsed=0

    while [ $elapsed -lt $timeout ]; do
        if adb devices | grep -q "emulator.*device$"; then
            log_success "Emulator is ready!"

            # Wait a bit more for full boot
            log_info "Waiting for system to fully boot..."
            sleep 10

            # Check if boot is complete
            local boot_complete=$(adb shell getprop sys.boot_completed 2>/dev/null || echo "0")
            if [ "$boot_complete" = "1" ]; then
                log_success "Emulator fully booted and ready!"
                return 0
            fi
        fi

        sleep 5
        elapsed=$((elapsed + 5))
        echo -n "."
    done

    echo ""
    log_error "Emulator failed to start within $timeout seconds"
    log_info "You can check emulator status with: adb devices"
    exit 1
}

# Check if device/emulator is connected
check_device() {
    local target="$1"

    if [ "$target" = "device" ]; then
        if ! adb devices | grep -q "device$"; then
            log_error "No physical device connected!"
            log_info "Please connect your device and enable USB debugging."
            exit 1
        fi
        log_success "Physical device detected"
    elif [ "$target" = "emulator" ]; then
        if ! adb devices | grep -q "emulator"; then
            log_warn "No emulator running, attempting to start one..."
            start_emulator
        else
            log_success "Emulator detected"
        fi
    else
        # Auto-detect
        if adb devices | grep -q "device$"; then
            log_success "Physical device detected"
        elif adb devices | grep -q "emulator"; then
            log_success "Emulator detected"
        else
            log_warn "No device or emulator connected, attempting to start emulator..."
            start_emulator
        fi
    fi
}

# Build debug APK
build_debug_apk() {
    log_header "Building Debug APK"

    if [ ! -f "gradlew" ]; then
        log_error "gradlew not found! Are you in the project root?"
        exit 1
    fi

    log_info "Building debug APK..."
    ./gradlew assembleDebug --no-daemon

    if [ ! -f "$APK_PATH_DEBUG" ]; then
        log_error "Debug APK build failed! File not found: $APK_PATH_DEBUG"
        exit 1
    fi

    local filesize=$(ls -lh "$APK_PATH_DEBUG" | awk '{print $5}')
    log_success "Debug APK built successfully!"
    log_info "Location: $APK_PATH_DEBUG"
    log_info "Size: $filesize"
}

# Build debug APK only
build_all_apks() {
    log_header "Building Debug APK"

    build_debug_apk

    echo ""
    log_success "Debug build complete!"
    log_info "Debug APK: $APK_PATH_DEBUG"
    log_info "This APK is signed with debug key and ready for development/testing"
}



# Uninstall existing app (internal - used by install command)
uninstall_app() {
    log_header "Uninstalling Existing App"

    # Check if debug version is installed
    if adb shell pm list packages | grep -q "$APP_ID_DEBUG"; then
        log_info "Uninstalling debug version: $APP_ID_DEBUG"
        adb uninstall "$APP_ID_DEBUG" || log_warn "Failed to uninstall debug version"
    else
        log_info "Debug version not installed"
    fi

    # Check if release version is installed
    if adb shell pm list packages | grep -q "^package:$APP_ID$"; then
        log_info "Uninstalling release version: $APP_ID"
        adb uninstall "$APP_ID" || log_warn "Failed to uninstall release version"
    else
        log_info "Release version not installed"
    fi

    log_success "Uninstall complete"
}

# Install APK
install_apk() {
    log_header "Installing Fresh APK"

    log_info "Installing: $APK_PATH_DEBUG"
    adb install "$APK_PATH_DEBUG"

    log_success "App installed successfully!"
    log_info "Package: $APP_ID_DEBUG"
    log_info "Note: Home screen shortcut will be created on first launch"
}

# Update APK (reinstall without losing data)
update_apk() {
    log_header "Updating APK (Preserving Data)"

    log_info "Updating: $APK_PATH_DEBUG"
    log_info "Using -r flag to preserve app data..."
    adb install -r "$APK_PATH_DEBUG"

    log_success "App updated successfully!"
    log_info "Package: $APP_ID_DEBUG"
    log_info "All app data and settings preserved"
}

# Launch app (internal - used by install command)
launch_app() {
    log_header "Launching App"

    log_info "Starting De1984..."
    adb shell am start -n "$APP_ID_DEBUG/io.github.dorumrr.de1984.ui.MainActivity"

    log_success "App launched!"
    log_info "Home screen shortcut created automatically on first launch"
}

# Take screenshot
take_screenshot() {
    log_header "Taking Screenshot"
    
    # Create screenshots directory if it doesn't exist
    mkdir -p "$SCREENSHOT_DIR"
    
    # Generate timestamp for filename
    local timestamp=$(date +"%Y%m%d_%H%M%S")
    local filename="de1984_${timestamp}.png"
    local filepath="$SCREENSHOT_DIR/$filename"
    
    log_info "Capturing screenshot..."
    adb exec-out screencap -p > "$filepath"
    
    if [ -f "$filepath" ]; then
        log_success "Screenshot saved: $filepath"
        
        # Show file size
        local filesize=$(ls -lh "$filepath" | awk '{print $5}')
        log_info "File size: $filesize"
        
        # Try to open screenshot (macOS)
        if command -v open &> /dev/null; then
            log_info "Opening screenshot..."
            open "$filepath"
        fi
    else
        log_error "Failed to save screenshot"
        exit 1
    fi
}

# Show device info (internal - used by install command)
show_device_info() {
    log_header "Device Information"

    local device_model=$(adb shell getprop ro.product.model 2>/dev/null || echo "Unknown")
    local android_version=$(adb shell getprop ro.build.version.release 2>/dev/null || echo "Unknown")
    local api_level=$(adb shell getprop ro.build.version.sdk 2>/dev/null || echo "Unknown")
    local lineage_version=$(adb shell getprop ro.lineage.version 2>/dev/null || echo "Not LineageOS")

    echo "📱 Device: $device_model"
    echo "🤖 Android: $android_version (API $api_level)"
    echo "🔷 LineageOS: $lineage_version"
    echo ""
}

# Show app info (internal - used by install command)
show_app_info() {
    log_header "App Information"

    if adb shell pm list packages | grep -q "$APP_ID_DEBUG"; then
        local version=$(adb shell dumpsys package "$APP_ID_DEBUG" | grep "versionName" | head -1 | cut -d'=' -f2 || echo "Unknown")
        local install_time=$(adb shell dumpsys package "$APP_ID_DEBUG" | grep "firstInstallTime" | head -1 | cut -d'=' -f2 || echo "Unknown")

        echo "📦 Package: $APP_ID_DEBUG"
        echo "🏷️  Version: $version"
        echo "📅 Installed: $install_time"
        log_success "Debug app is installed"
    else
        log_warn "Debug app is not installed"
    fi
    echo ""
}

# List available emulators
list_emulators() {
    log_header "Available Android Emulators"

    local emulator_cmd=$(get_emulator_command)
    if [ -z "$emulator_cmd" ]; then
        log_error "Android emulator command not found!"
        log_info "Please install Android SDK or Android Studio"
        return 1
    fi

    local avds=($(find_emulators))
    if [ ${#avds[@]} -eq 0 ]; then
        log_warn "No Android Virtual Devices (AVDs) found!"
        log_info "Create manually in Android Studio:"
        log_info "Tools → AVD Manager → Create Virtual Device → Pixel 9a + Android 15"
    else
        local best_avd=$(find_best_emulator)
        log_success "Found ${#avds[@]} emulator(s):"
        for avd in "${avds[@]}"; do
            local marker=""
            if [ "$avd" = "$best_avd" ]; then
                marker=" ${GREEN}(default)${NC}"
            fi
            echo -e "  📱 $avd$marker"
        done
        echo ""
        log_info "Start: ./dev.sh emulator [name]"
    fi
}

# Check if keystore exists
check_keystore() {
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Keystore not found: $KEYSTORE_PATH"
        log_info "You need to create a keystore first."
        log_info "IF YOU ALREADY HAVE ONE FOR THIS PROJECT, DROP IT ON ROOT!"
        log_info "Otherwise, run: ./dev.sh create-keystore"
        log_info "Make sure you BACKUP the keystore afterwards!"
        exit 1
    fi
    log_success "Keystore found: $KEYSTORE_PATH"
}

# Validate keystore.properties configuration
validate_keystore_properties() {
    local KEYSTORE_PROPS="keystore.properties"

    if [ ! -f "$KEYSTORE_PROPS" ]; then
        log_error "Missing: $KEYSTORE_PROPS"
        echo ""
        log_info "The keystore.properties file is required for release builds."
        log_info "This file should contain:"
        echo "  • storeFile=release-keystore.jks"
        echo "  • storePassword=your_store_password"
        echo "  • keyAlias=de1984-release-key"
        echo "  • keyPassword=your_key_password"
        echo ""
        log_info "To fix this:"
        echo "  1. If you have an existing keystore:"
        echo "     ./dev.sh populate-keystore-properties"
        echo ""
        echo "  2. If you need to create a new keystore:"
        echo "     ./dev.sh create-keystore"
        echo ""
        exit 1
    fi

    # Validate required properties
    local missing_props=()

    # Check if properties exist and are not empty
    local store_file=$(grep "^storeFile=" "$KEYSTORE_PROPS" | cut -d'=' -f2-)
    local store_password=$(grep "^storePassword=" "$KEYSTORE_PROPS" | cut -d'=' -f2-)
    local key_alias=$(grep "^keyAlias=" "$KEYSTORE_PROPS" | cut -d'=' -f2-)
    local key_password=$(grep "^keyPassword=" "$KEYSTORE_PROPS" | cut -d'=' -f2-)

    if [ -z "$store_file" ]; then
        missing_props+=("storeFile")
    fi
    if [ -z "$store_password" ]; then
        missing_props+=("storePassword")
    fi
    if [ -z "$key_alias" ]; then
        missing_props+=("keyAlias")
    fi
    if [ -z "$key_password" ]; then
        missing_props+=("keyPassword")
    fi

    if [ ${#missing_props[@]} -gt 0 ]; then
        log_error "Invalid $KEYSTORE_PROPS - missing properties:"
        for prop in "${missing_props[@]}"; do
            echo "  • $prop"
        done
        echo ""
        log_info "To fix this:"
        echo "  ./dev.sh populate-keystore-properties"
        echo ""
        exit 1
    fi

    log_success "keystore.properties is valid"
}

# Populate keystore.properties with credentials
populate_keystore_properties() {
    log_header "Populate keystore.properties"

    # Check if keystore exists
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Keystore not found: $KEYSTORE_PATH"
        log_info "You need to create a keystore first!"
        log_info "Run: ./dev.sh create-keystore"
        exit 1
    fi

    local KEYSTORE_PROPS="keystore.properties"

    # Backup existing file if it exists
    if [ -f "$KEYSTORE_PROPS" ]; then
        log_warn "Existing $KEYSTORE_PROPS found"
        read -p "Do you want to overwrite it? (yes/no): " confirm
        if [ "$confirm" != "yes" ]; then
            log_info "Operation cancelled"
            exit 0
        fi
        cp "$KEYSTORE_PROPS" "${KEYSTORE_PROPS}.backup"
        log_info "Backed up to ${KEYSTORE_PROPS}.backup"
    fi

    echo ""
    log_info "Please enter your keystore credentials:"
    echo ""

    # Prompt for passwords
    read -sp "Enter keystore password: " STORE_PASS
    echo ""
    read -sp "Enter key password (press Enter if same as keystore): " KEY_PASS
    echo ""

    if [ -z "$KEY_PASS" ]; then
        KEY_PASS="$STORE_PASS"
    fi

    # Verify credentials by trying to list the keystore
    log_info "Verifying credentials..."
    if ! keytool -list -keystore "$KEYSTORE_PATH" -storepass "$STORE_PASS" -alias de1984-release-key -keypass "$KEY_PASS" &>/dev/null; then
        log_error "Invalid credentials! Could not access keystore."
        log_info "Please check your passwords and try again."
        # Clear passwords from memory
        STORE_PASS=""
        KEY_PASS=""
        exit 1
    fi

    log_success "Credentials verified!"

    # Create keystore.properties file
    cat > "$KEYSTORE_PROPS" << EOF
# Keystore configuration for release signing
# This file is gitignored - never commit it to version control!
# Generated by dev.sh on $(date)

storeFile=release-keystore.jks
storePassword=$STORE_PASS
keyAlias=de1984-release-key
keyPassword=$KEY_PASS
EOF

    # Clear passwords from memory
    STORE_PASS=""
    KEY_PASS=""

    if [ -f "$KEYSTORE_PROPS" ]; then
        echo ""
        log_success "Created $KEYSTORE_PROPS"
        echo ""
        log_warn "IMPORTANT: Backup this file securely!"
        echo "  • $KEYSTORE_PROPS (credentials)"
        echo ""
        log_info "You can now build releases with:"
        echo "  • ./dev.sh release"
        echo "  • ./gradlew assembleRelease"
    else
        log_error "Failed to create $KEYSTORE_PROPS"
        exit 1
    fi
}

# Create keystore
create_keystore() {
    log_header "Creating Release Keystore"

    if [ -f "$KEYSTORE_PATH" ]; then
        log_warn "Keystore already exists: $KEYSTORE_PATH"
        read -p "Do you want to overwrite it? (yes/no): " confirm
        if [ "$confirm" != "yes" ]; then
            log_info "Keystore creation cancelled"
            exit 0
        fi
        log_warn "Backing up existing keystore to ${KEYSTORE_PATH}.backup"
        cp "$KEYSTORE_PATH" "${KEYSTORE_PATH}.backup"
    fi

    log_info "This will create a new keystore for signing release APKs"
    log_warn "IMPORTANT: Keep this keystore and password safe!"
    log_warn "You'll need them to sign future updates"
    echo ""

    log_info "You'll be asked for:"
    echo "  1. Keystore password (minimum 6 characters)"
    echo "  2. Key password (minimum 6 characters, can be same as keystore password)"
    echo "  3. Your name and organization details"
    echo ""

    # Prompt for keystore password with validation
    while true; do
        read -s -p "Enter keystore password (min 6 chars): " STORE_PASS
        echo ""
        if [ ${#STORE_PASS} -lt 6 ]; then
            log_error "Password must be at least 6 characters!"
            continue
        fi
        read -s -p "Confirm keystore password: " STORE_PASS_CONFIRM
        echo ""
        if [ "$STORE_PASS" != "$STORE_PASS_CONFIRM" ]; then
            log_error "Passwords don't match!"
            continue
        fi
        break
    done

    # Prompt for key password with validation
    echo ""
    read -p "Use same password for key? (yes/no, default: yes): " USE_SAME
    if [ "$USE_SAME" = "no" ]; then
        while true; do
            read -s -p "Enter key password (min 6 chars): " KEY_PASS
            echo ""
            if [ ${#KEY_PASS} -lt 6 ]; then
                log_error "Password must be at least 6 characters!"
                continue
            fi
            read -s -p "Confirm key password: " KEY_PASS_CONFIRM
            echo ""
            if [ "$KEY_PASS" != "$KEY_PASS_CONFIRM" ]; then
                log_error "Passwords don't match!"
                continue
            fi
            break
        done
    else
        KEY_PASS="$STORE_PASS"
    fi

    echo ""
    log_info "Generating keystore..."
    echo ""

    # Generate keystore
    keytool -genkey -v \
        -keystore "$KEYSTORE_PATH" \
        -alias de1984-release-key \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$STORE_PASS" \
        -keypass "$KEY_PASS"

    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Keystore creation failed!"
        # Clear passwords from memory
        STORE_PASS=""
        KEY_PASS=""
        STORE_PASS_CONFIRM=""
        KEY_PASS_CONFIRM=""
        exit 1
    fi

    echo ""
    log_success "Keystore created successfully: $KEYSTORE_PATH"

    # Create keystore.properties file (standard Android approach)
    local KEYSTORE_PROPS="keystore.properties"
    log_info "Creating $KEYSTORE_PROPS file..."

    cat > "$KEYSTORE_PROPS" << EOF
# Keystore configuration for release signing
# This file is gitignored - never commit it to version control!
# Generated by dev.sh on $(date)

storeFile=release-keystore.jks
storePassword=$STORE_PASS
keyAlias=de1984-release-key
keyPassword=$KEY_PASS
EOF

    if [ -f "$KEYSTORE_PROPS" ]; then
        log_success "Created $KEYSTORE_PROPS"
        echo ""
        log_warn "IMPORTANT: Backup these files securely!"
        echo "  • $KEYSTORE_PATH (keystore file)"
        echo "  • $KEYSTORE_PROPS (credentials)"
        echo ""
        log_info "With keystore.properties, you can now build releases with:"
        echo "  • ./dev.sh release (uses dev.sh script)"
        echo "  • ./gradlew assembleRelease (direct Gradle build)"
    else
        log_error "Failed to create $KEYSTORE_PROPS"
    fi

    # Clear passwords from memory
    STORE_PASS=""
    KEY_PASS=""
    STORE_PASS_CONFIRM=""
    KEY_PASS_CONFIRM=""

    echo ""
    log_info "Next step: ./dev.sh release"
}

# Get production keystore SHA256
get_production_sha256() {
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Production keystore not found: $KEYSTORE_PATH"
        return 1
    fi

    # Read password from keystore.properties
    local KEYSTORE_PROPS="keystore.properties"
    if [ ! -f "$KEYSTORE_PROPS" ]; then
        log_error "keystore.properties not found"
        return 1
    fi

    local STORE_PASSWORD=$(grep "^storePassword=" "$KEYSTORE_PROPS" | cut -d'=' -f2-)

    if [ -z "$STORE_PASSWORD" ]; then
        log_error "storePassword not found in keystore.properties"
        return 1
    fi

    # Get SHA256 with colons (using password from keystore.properties)
    local sha256_with_colons=$(keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass "$STORE_PASSWORD" 2>/dev/null | grep "SHA256:" | head -1 | sed 's/.*SHA256: //')

    # Convert to lowercase without colons (for F-Droid YAML)
    local sha256_lowercase=$(echo "$sha256_with_colons" | tr -d ':' | tr '[:upper:]' '[:lower:]')

    echo "$sha256_lowercase"
}

# Build and sign release APK (complete workflow)
build_and_sign_release() {
    log_header "Building and Signing Release APK (Production)"

    # Validate keystore.properties first
    validate_keystore_properties

    # Check keystore exists
    check_keystore

    # Build release APK (Gradle signs it automatically with keystore.properties)
    log_info "Building release APK..."
    ./gradlew assembleRelease --no-daemon

    if [ ! -f "$APK_PATH_RELEASE" ]; then
        log_error "Release APK build failed! File not found: $APK_PATH_RELEASE"
        exit 1
    fi

    # Verify signature
    local android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$android_sdk" ]; then
        android_sdk="$HOME/Library/Android/sdk"
    fi
    local build_tools_dir=$(find "$android_sdk/build-tools" -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)
    local apksigner="$build_tools_dir/apksigner"

    if [ -f "$apksigner" ]; then
        log_info "Verifying signature..."
        "$apksigner" verify "$APK_PATH_RELEASE"
        log_success "APK signature verified!"
    fi

    local filesize=$(ls -lh "$APK_PATH_RELEASE" | awk '{print $5}')
    echo ""
    log_success "Production release build complete!"
    log_info "Signed APK: $APK_PATH_RELEASE"
    log_info "Size: $filesize"

    # Get production SHA256
    local production_sha256=$(get_production_sha256)

    if [ -n "$production_sha256" ]; then
        echo ""
        log_info "Production keystore SHA256: $production_sha256"
    fi
    echo ""
}

# Start comprehensive logging
start_comprehensive_logging() {
    log_header "Starting Comprehensive Logging"
    log_info "This will track ALL user actions and system events"
    log_info "Press Ctrl+C to stop logging"
    echo ""

    # Clear logcat first
    adb logcat -c

    log_success "📡 Comprehensive logging started!"
    echo ""
    echo -e "${YELLOW}Legend:${NC}"
    echo -e "  🔘 = User actions (taps, toggles, navigation)"
    echo -e "  📡 = System events (network, screen)"
    echo -e "  📱 = MainActivity lifecycle"
    echo -e "  🔧 = Root/Shizuku events"
    echo -e "  🔍 = Status checks"
    echo -e "  ✅ = Success"
    echo -e "  ❌ = Error"
    echo ""
    echo -e "${BLUE}════════════════════════════════════════════════════════════════${NC}"
    echo ""

    # Start comprehensive logging with all filters
    adb logcat | grep -E "(USER ACTION|SYSTEM EVENT|MAINACTIVITY|FirewallManager|PrivilegedFirewallService|RootManager|ShizukuManager|NetworkStateMonitor|ScreenStateMonitor|FirewallFragment|PackagesFragment|SettingsFragment)" --line-buffered
}

# Show welcome screen with command explanations
show_welcome() {
    clear
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}                ${GREEN}🔷 De1984 Development Script 🔷${NC}                 ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}Common Commands:${NC}"
    echo ""
    echo -e "  ${GREEN}build${NC}     - Build debug APK for local testing"
    echo -e "  ${GREEN}install${NC}   - Build and install debug APK on device/emulator"
    echo -e "  ${GREEN}update${NC}    - Build and update APK ${YELLOW}(preserves app data)${NC}"
    echo -e "  ${GREEN}release${NC}   - Build production-signed APK ${GREEN}(for ALL public distribution)${NC}"
    echo ""
    echo -e "${YELLOW}Emulator:${NC}"
    echo ""
    echo -e "  ${GREEN}emulator${NC}  - Start Android 15 Pixel 9a emulator"
    echo ""
    echo -e "${YELLOW}Keystore:${NC}"
    echo ""
    echo -e "  ${GREEN}create-keystore${NC}              - Create production keystore (first time)"
    echo -e "  ${GREEN}populate-keystore-properties${NC} - Populate keystore.properties"
    echo ""
    echo -e "${YELLOW}Help:${NC}"
    echo ""
    echo -e "  ${GREEN}help${NC}      - Show full command list"
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${YELLOW}⚠️  Production Releases:${NC} Always use ${GREEN}./dev.sh release${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════════════${NC}"
    echo ""
}

# Show help
show_help() {
    echo "De1984 Development Script"
    echo ""
    echo "Usage: ./dev.sh [command] [options]"
    echo ""
    echo "Commands:"
    echo "  build                        - Build debug APK"
    echo "  install [device|emulator]    - Build and install debug APK (auto-starts emulator)"
    echo "  update [device|emulator]     - Build and update APK (preserves app data)"
    echo "  release                      - Build production-signed APK"
    echo "  screenshot                   - Take and save screenshot"
    echo ""
    echo "Emulator:"
    echo "  emulator [name]              - Start Android emulator (auto-selects Pixel 9a)"
    echo ""
    echo "Keystore:"
    echo "  create-keystore              - Create production keystore (first time)"
    echo "  populate-keystore-properties - Create keystore.properties"
    echo "  validate-keystore-properties - Validate keystore.properties"
    echo ""
    echo "Debugging:"
    echo "  logs                         - Start comprehensive logging (tracks all user actions)"
    echo ""
    echo "Examples:"
    echo "  ./dev.sh install             - Build and install on device/emulator"
    echo "  ./dev.sh update              - Build and update (keeps data)"
    echo "  ./dev.sh release             - Build production APK"
    echo "  ./dev.sh emulator            - Start Pixel 9a emulator"
    echo "  ./dev.sh logs                - Watch comprehensive logs"
    echo ""
}

# Main script logic
main() {
    local command="${1:-welcome}"
    local target="${2:-auto}"

    # Show welcome screen if no command or if explicitly requested
    if [ "$command" = "welcome" ] || [ -z "$1" ]; then
        show_welcome
        # After welcome, show help
        show_help
        exit 0
    fi

    case "$command" in
        "install")
            check_adb
            check_device "$target"
            show_device_info
            build_debug_apk
            uninstall_app
            install_apk
            launch_app
            show_app_info
            ;;
        "update")
            check_adb
            check_device "$target"
            show_device_info
            build_debug_apk
            update_apk
            launch_app
            show_app_info
            ;;
        "screenshot")
            check_adb
            check_device "auto"
            take_screenshot
            ;;
        "build")
            build_all_apks
            ;;
        "fdroid"|"fdroid-release")
            log_error "The 'fdroid' and 'fdroid-release' commands have been REMOVED!"
            echo ""
            echo -e "${YELLOW}Why were they removed?${NC}"
            echo "  • They were unnecessary and confusing"
            echo "  • F-Droid expects YOUR production-signed APK on GitHub"
            echo "  • F-Droid builds unsigned APK themselves from source"
            echo "  • You never need to build unsigned APK yourself"
            echo ""
            echo -e "${GREEN}What should you use instead?${NC}"
            echo "  • For ALL public distribution: ${YELLOW}./dev.sh release${NC}"
            echo ""
            echo -e "${BLUE}See FDROID_REPRODUCIBLE_BUILDS_EXPLAINED.md for details${NC}"
            exit 1
            ;;
        "release")
            build_and_sign_release
            ;;
        "create-keystore")
            create_keystore
            ;;
        "populate-keystore-properties")
            populate_keystore_properties
            ;;
        "validate-keystore-properties")
            validate_keystore_properties
            ;;
        "emulator")
            check_adb
            start_emulator "$target"
            ;;
        "logs")
            check_adb
            start_comprehensive_logging
            ;;
        "help"|"-h"|"--help")
            show_help
            ;;
        *)
            log_error "Unknown command: $command"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# Run main function with all arguments
main "$@"
