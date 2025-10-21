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
APK_PATH_RELEASE="app/build/outputs/apk/release/de1984-v${APP_VERSION}-release.apk"
APK_PATH_RELEASE_ALIGNED="app/build/outputs/apk/release/de1984-v${APP_VERSION}-release-aligned.apk"
APK_PATH_RELEASE_SIGNED="app/build/outputs/apk/release/de1984-v${APP_VERSION}-release-signed.apk"
SCREENSHOT_DIR="screenshots"
KEYSTORE_PATH="release-keystore.jks"
DEBUG_KEYSTORE_PATH="$HOME/.android/debug.keystore"

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

log_big_red() {
    echo -e "\n${RED}🚨🚨🚨 $1 🚨🚨🚨${NC}\n"
}

log_github_reminder() {
    local apk_file="$1"
    echo ""
    echo -e "${GREEN}✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅${NC}"
    echo -e "${GREEN}    ✅✅✅               UPLOAD TO GITHUB RELEASES               ✅✅✅${NC}"
    echo -e "${GREEN}✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅${NC}"
    echo ""
    echo -e "${GREEN}📦 File: ${NC}\033[1m${RED}$(basename "$apk_file")\033[0m"
    echo -e "${BLUE}🔗 Upload to: ${NC}\033[1m${RED}https://github.com/dorumrr/de1984/releases\033[0m"
    echo -e "${NC}⚠️  F-Droid will verify against this signed APK${NC}"
    echo ""
    echo -e "${GREEN}✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅✅${NC}"
    echo ""
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

# Check if an AVD is Android 15 (API 36)
is_android_15_avd() {
    local avd_name="$1"
    local avd_config="$HOME/.android/avd/${avd_name}.avd/config.ini"

    if [ -f "$avd_config" ]; then
        if grep -q "android-36" "$avd_config" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

# Find best emulator (prefer Android 15 Pixel 9a)
find_best_emulator() {
    local avds=($(find_emulators))

    if [ ${#avds[@]} -eq 0 ]; then
        echo ""
        return 1
    fi

    # First priority: Android 15 Pixel 9a (best for testing)
    for avd in "${avds[@]}"; do
        if is_android_15_avd "$avd" && [[ "$avd" =~ [Pp]ixel.*9a ]]; then
            echo "$avd"
            return 0
        fi
    done

    # Second priority: Android 15 Pixel 9 (non-fold)
    for avd in "${avds[@]}"; do
        if is_android_15_avd "$avd" && [[ "$avd" =~ [Pp]ixel.*9 ]] && [[ ! "$avd" =~ [Ff]old ]]; then
            echo "$avd"
            return 0
        fi
    done

    # Third priority: Any Android 15 Pixel (excluding folds)
    for avd in "${avds[@]}"; do
        if is_android_15_avd "$avd" && [[ "$avd" =~ [Pp]ixel ]] && [[ ! "$avd" =~ [Ff]old ]]; then
            echo "$avd"
            return 0
        fi
    done

    # Fourth priority: Any Android 15 device (excluding folds)
    for avd in "${avds[@]}"; do
        if is_android_15_avd "$avd" && [[ ! "$avd" =~ [Ff]old ]]; then
            echo "$avd"
            return 0
        fi
    done

    # Fifth priority: Any Pixel device (excluding folds)
    for avd in "${avds[@]}"; do
        if [[ "$avd" =~ [Pp]ixel ]] && [[ ! "$avd" =~ [Ff]old ]]; then
            echo "$avd"
            return 0
        fi
    done

    # Fallback: First available
    echo "${avds[0]}"
    return 0
}

# Create Android 15 Pixel 9a emulator
create_android_15_pixel() {
    log_header "Creating Android 15 Pixel 9a Emulator"

    local sdk_path=$(find_android_sdk)
    if [ -z "$sdk_path" ]; then
        log_error "Android SDK not found!"
        return 1
    fi

    local avdmanager="$sdk_path/cmdline-tools/latest/bin/avdmanager"
    local sdkmanager="$sdk_path/cmdline-tools/latest/bin/sdkmanager"

    # Check if cmdline-tools exist
    if [ ! -f "$avdmanager" ]; then
        log_error "avdmanager not found!"
        log_info "Please install Android SDK Command-line Tools:"
        log_info "1. Open Android Studio"
        log_info "2. Settings → Appearance & Behavior → System Settings → Android SDK"
        log_info "3. SDK Tools tab → Check 'Android SDK Command-line Tools'"
        log_info "4. Click Apply"
        return 1
    fi

    # Check if Android 15 system image is installed
    log_info "Checking for Android 15 system image..."
    if [ ! -d "$sdk_path/system-images/android-36/google_apis/arm64-v8a" ] && \
       [ ! -d "$sdk_path/system-images/android-36/google_apis/x86_64" ]; then
        log_warn "Android 15 system image not found. Installing..."
        log_info "This may take a few minutes..."

        # Determine architecture
        local arch="x86_64"
        if [[ "$(uname -m)" == "arm64" ]]; then
            arch="arm64-v8a"
        fi

        "$sdkmanager" "system-images;android-36;google_apis;${arch}"

        if [ $? -ne 0 ]; then
            log_error "Failed to install Android 15 system image"
            return 1
        fi
    fi

    log_success "Android 15 system image found"

    # Create AVD with Pixel 9a
    local avd_name="Pixel_9a_API_36"
    log_info "Creating Pixel 9a emulator: $avd_name"

    # Determine architecture
    local arch="x86_64"
    if [[ "$(uname -m)" == "arm64" ]]; then
        arch="arm64-v8a"
    fi

    echo "no" | "$avdmanager" create avd \
        --force \
        --name "$avd_name" \
        --package "system-images;android-36;google_apis;${arch}" \
        --device "pixel_9a"

    if [ $? -eq 0 ]; then
        log_success "Created Android 15 Pixel 9a emulator: $avd_name"
        echo "$avd_name"
        return 0
    else
        log_error "Failed to create emulator"
        return 1
    fi
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
        log_warn "No Android Virtual Devices (AVDs) found!"
        log_info "Would you like to create an Android 15 Pixel 9a emulator? (recommended)"
        read -p "Create Android 15 Pixel 9a emulator? (yes/no): " create_confirm

        if [ "$create_confirm" = "yes" ]; then
            local new_avd=$(create_android_15_pixel)
            if [ -n "$new_avd" ]; then
                requested_emulator="$new_avd"
                avds=("$new_avd")
            else
                log_error "Failed to create emulator"
                exit 1
            fi
        else
            log_info "Please create an emulator manually:"
            log_info "1. Open Android Studio"
            log_info "2. Tools → AVD Manager → Create Virtual Device"
            log_info "3. Choose Pixel 9a and Android 15 (API 36) system image"
            log_info "4. Then run: ./dev.sh install emulator"
            exit 1
        fi
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
        # Use best available emulator (prefer Android 15 Pixel)
        emulator_name=$(find_best_emulator)

        if [ -z "$emulator_name" ]; then
            log_error "No suitable emulator found"
            exit 1
        fi

        if is_android_15_avd "$emulator_name"; then
            log_success "Using Android 15 emulator: $emulator_name ✨"
        else
            log_warn "Using emulator: $emulator_name (not Android 15)"
            log_info "Tip: Create Android 15 Pixel 9a emulator with: ./dev.sh create-emulator"
        fi

        if [ ${#avds[@]} -gt 1 ]; then
            log_info "Other available emulators:"
            for avd in "${avds[@]}"; do
                if [ "$avd" != "$emulator_name" ]; then
                    if is_android_15_avd "$avd"; then
                        echo "  📱 $avd ${GREEN}(Android 15)${NC}"
                    else
                        echo "  📱 $avd"
                    fi
                fi
            done
            log_info "To use specific emulator: ./dev.sh emulator [name]"
        fi
    fi

    log_info "Starting emulator (this may take 30-60 seconds)..."

    # Start emulator in background with output redirected to /dev/null
    "$emulator_cmd" -avd "$emulator_name" -no-snapshot-save -no-audio > /dev/null 2>&1 &
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

# Build unsigned release APK
build_release_apk() {
    log_header "Building Release APK (Unsigned)"

    if [ ! -f "gradlew" ]; then
        log_error "gradlew not found! Are you in the project root?"
        exit 1
    fi

    log_info "Building release APK..."
    ./gradlew assembleRelease --no-daemon

    if [ ! -f "$APK_PATH_RELEASE" ]; then
        log_error "Release APK build failed! File not found: $APK_PATH_RELEASE"
        exit 1
    fi

    local filesize=$(ls -lh "$APK_PATH_RELEASE" | awk '{print $5}')
    log_success "Release APK built successfully!"
    log_info "Location: $APK_PATH_RELEASE"
    log_info "Size: $filesize"
    log_warn "This APK is UNSIGNED and cannot be installed on devices"
    log_info "Use './dev.sh release' to build and sign a release APK"
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

# Build debug APK with detailed info for F-Droid RFP
build_debug_with_info() {
    log_header "Building Debug APK (For Testing & F-Droid RFP)"

    # Build debug APK
    build_debug_apk

    echo ""
    log_success "Debug APK built successfully!"
    echo ""

    # Show file location
    log_header "📦 APK Information"
    echo -e "${GREEN}Location:${NC}"
    echo -e "  ${YELLOW}$(pwd)/$APK_PATH_DEBUG${NC}"
    echo ""

    # Show file size
    local filesize=$(ls -lh "$APK_PATH_DEBUG" | awk '{print $5}')
    echo -e "${GREEN}Size:${NC} $filesize"
    echo ""

    # Calculate SHA-256
    log_info "Calculating SHA-256..."
    local sha256=$(shasum -a 256 "$APK_PATH_DEBUG" | awk '{print $1}')
    echo -e "${GREEN}SHA-256:${NC}"
    echo -e "  ${RED}$sha256${NC}"
    echo ""

    # Get debug keystore SHA-256 (for F-Droid AllowedAPKSigningKeys)
    if [ -f "$DEBUG_KEYSTORE_PATH" ]; then
        log_info "Debug keystore signature..."
        local keystore_sha=$(keytool -list -v -keystore "$DEBUG_KEYSTORE_PATH" -storepass android -keypass android 2>/dev/null | grep "SHA256:" | head -1 | sed 's/.*SHA256: //' | tr -d ':' | tr '[:upper:]' '[:lower:]')
        echo -e "${GREEN}Debug Keystore SHA-256 (for F-Droid YAML):${NC}"
        echo -e "  ${RED}$keystore_sha${NC}"
        echo ""
    fi

    # Show usage information
    log_header "🎯 Usage"
    echo -e "${BLUE}For Local Testing:${NC}"
    echo -e "  adb install -r $APK_PATH_DEBUG"
    echo -e "  ${YELLOW}OR${NC}"
    echo -e "  ./dev.sh install"
    echo ""

    echo -e "${BLUE}For F-Droid RFP (Request For Packaging):${NC}"
    echo -e "  1. Upload this APK to GitHub releases"
    echo -e "  2. Include SHA-256 in your RFP issue"
    echo -e "  3. Reference: https://github.com/dorumrr/de1984/releases"
    echo ""

    echo -e "${BLUE}For Debugging Issues:${NC}"
    echo -e "  • This APK has all logs removed from source code"
    echo -e "  • Stack traces will still show line numbers"
    echo -e "  • Signed with debug keystore (not for production)"
    echo ""

    log_success "✅ Debug APK ready for testing and F-Droid RFP!"
}

# Build F-Droid APK (unsigned release for F-Droid verification)
build_fdroid_apk() {
    log_header "Building F-Droid APK (Unsigned Release)"

    if [ ! -f "gradlew" ]; then
        log_error "gradlew not found! Are you in the project root?"
        exit 1
    fi

    log_info "Building unsigned release APK for F-Droid..."
    log_info "This APK will be signed with debug keystore automatically by Gradle"
    ./gradlew clean assembleRelease --no-daemon

    if [ ! -f "$APK_PATH_RELEASE" ]; then
        log_error "Release APK build failed! File not found: $APK_PATH_RELEASE"
        exit 1
    fi

    local filesize=$(ls -lh "$APK_PATH_RELEASE" | awk '{print $5}')
    log_success "F-Droid APK built successfully!"
    log_info "Location: $APK_PATH_RELEASE"
    log_info "Size: $filesize"

    # Verify it's signed with debug keystore
    log_info "Verifying signature..."
    if [ -f "$DEBUG_KEYSTORE_PATH" ]; then
        local expected_sha=$(keytool -list -v -keystore "$DEBUG_KEYSTORE_PATH" -storepass android -keypass android 2>/dev/null | grep "SHA256:" | head -1 | sed 's/.*SHA256: //' | tr -d ':' | tr '[:upper:]' '[:lower:]')
        log_success "Debug keystore SHA256: $expected_sha"
        log_info "This signature should match AllowedAPKSigningKeys in F-Droid YAML"
    fi

    echo ""
    log_success "✅ This APK is ready for F-Droid reproducible builds!"
    log_github_reminder "$APK_PATH_RELEASE" "fdroid"
}

# Uninstall existing app
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

# Launch app
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

# Show device info
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

# Show app info
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

# Show logs
show_logs() {
    log_header "App Logs"
    
    log_info "Showing logs for: $APP_ID_DEBUG"
    log_info "Press Ctrl+C to stop..."
    echo ""
    
    adb logcat | grep "$APP_ID_DEBUG"
}

# Clear logs
clear_logs() {
    log_header "Clearing Logs"

    adb logcat -c
    log_success "Logs cleared"
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

    log_info "Emulator command: $emulator_cmd"
    echo ""

    local avds=($(find_emulators))
    if [ ${#avds[@]} -eq 0 ]; then
        log_warn "No Android Virtual Devices (AVDs) found!"
        log_info "Create an emulator in Android Studio:"
        log_info "Tools → AVD Manager → Create Virtual Device"
    else
        log_success "Found ${#avds[@]} emulator(s):"
        for i in "${!avds[@]}"; do
            local avd="${avds[$i]}"
            if [ $i -eq 0 ]; then
                echo "  📱 $avd ${GREEN}(default - will be auto-selected)${NC}"
            else
                echo "  📱 $avd"
            fi
        done
        echo ""
        log_info "Default: ${avds[0]} will be started automatically"
        log_info "To start specific emulator: ./dev.sh emulator [name]"
        log_info "To start default: ./dev.sh emulator"
    fi
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

    log_info "Emulator command: $emulator_cmd"
    echo ""

    local avds=($(find_emulators))
    if [ ${#avds[@]} -eq 0 ]; then
        log_warn "No Android Virtual Devices (AVDs) found!"
        log_info "Create Android 15 Pixel 9a emulator with: ./dev.sh create-emulator"
        log_info "Or create manually in Android Studio:"
        log_info "Tools → AVD Manager → Create Virtual Device → Pixel 9a + Android 15"
    else
        local best_avd=$(find_best_emulator)
        log_success "Found ${#avds[@]} emulator(s):"
        for avd in "${avds[@]}"; do
            local marker=""
            if [ "$avd" = "$best_avd" ]; then
                marker=" ${GREEN}(default - will be auto-selected)${NC}"
            fi

            if is_android_15_avd "$avd"; then
                echo -e "  📱 $avd ${GREEN}[Android 15]${NC}$marker"
            else
                echo -e "  📱 $avd$marker"
            fi
        done
        echo ""
        log_info "Start default: ./dev.sh emulator"
        log_info "Start specific: ./dev.sh emulator [name]"

        if ! is_android_15_avd "$best_avd"; then
            echo ""
            log_warn "Tip: Create Android 15 Pixel 9a emulator for better testing:"
            log_info "./dev.sh create-emulator"
        fi
    fi
}

# Check if keystore exists
check_keystore() {
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Keystore not found: $KEYSTORE_PATH"
        log_info "You need to create a keystore first!"
        log_info "Run: ./dev.sh create-keystore"
        log_info "Or see: RELEASE_SIGNING_GUIDE.md for detailed instructions"
        exit 1
    fi
    log_success "Keystore found: $KEYSTORE_PATH"
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

    # Clear passwords from memory
    STORE_PASS=""
    KEY_PASS=""
    STORE_PASS_CONFIRM=""
    KEY_PASS_CONFIRM=""

    if [ -f "$KEYSTORE_PATH" ]; then
        echo ""
        log_success "Keystore created successfully: $KEYSTORE_PATH"
        log_warn "BACKUP THIS FILE! Store it securely!"
        log_info "Next step: ./dev.sh release"
    else
        log_error "Keystore creation failed!"
        exit 1
    fi
}

# Sign release APK
sign_release_apk() {
    log_header "Signing Release APK"

    # Check if unsigned APK exists
    if [ ! -f "$APK_PATH_RELEASE" ]; then
        log_error "Unsigned release APK not found: $APK_PATH_RELEASE"
        log_info "Building release APK first..."
        build_release_apk
        echo ""
    fi

    # Check keystore
    check_keystore

    log_info "Signing APK with keystore: $KEYSTORE_PATH"

    # Prompt for passwords
    read -sp "Enter keystore password: " KEYSTORE_PASSWORD
    echo ""
    read -sp "Enter key password (press Enter if same as keystore): " KEY_PASSWORD
    echo ""

    if [ -z "$KEY_PASSWORD" ]; then
        KEY_PASSWORD="$KEYSTORE_PASSWORD"
    fi

    # Find Android SDK
    local android_sdk=""
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
        android_sdk="$ANDROID_HOME"
    elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
        android_sdk="$ANDROID_SDK_ROOT"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
        android_sdk="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        android_sdk="$HOME/Android/Sdk"
    else
        log_error "Android SDK not found!"
        log_info "Please set ANDROID_HOME or ANDROID_SDK_ROOT environment variable"
        log_info "Or install Android SDK to default location:"
        log_info "  macOS: ~/Library/Android/sdk"
        log_info "  Linux: ~/Android/Sdk"
        exit 1
    fi

    log_info "Using Android SDK: $android_sdk"

    # Find apksigner in build-tools
    local build_tools_dir=$(find "$android_sdk/build-tools" -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1)
    local apksigner="$build_tools_dir/apksigner"

    if [ ! -f "$apksigner" ]; then
        log_error "apksigner not found in Android SDK build-tools"
        log_info "Please install Android SDK build-tools"
        log_info "SDK location: $android_sdk"
        exit 1
    fi

    log_info "Using apksigner: $apksigner"

    # Remove old files
    rm -f "$APK_PATH_RELEASE_SIGNED" "$APK_PATH_RELEASE_ALIGNED"

    log_info "Aligning APK..."
    local zipalign="$build_tools_dir/zipalign"
    if [ ! -f "$zipalign" ]; then
        log_error "zipalign not found"
        exit 1
    fi
    
    "$zipalign" -v -p 4 "$APK_PATH_RELEASE" "$APK_PATH_RELEASE_ALIGNED"
    
    log_info "Signing APK..."
    "$apksigner" sign \
        --ks "$KEYSTORE_PATH" \
        --ks-pass "pass:$KEYSTORE_PASSWORD" \
        --key-pass "pass:$KEY_PASSWORD" \
        --out "$APK_PATH_RELEASE_SIGNED" \
        "$APK_PATH_RELEASE_ALIGNED"
    
    rm -f "$APK_PATH_RELEASE_ALIGNED"

    if [ ! -f "$APK_PATH_RELEASE_SIGNED" ]; then
        log_error "APK signing failed!"
        exit 1
    fi

    # Verify signature
    log_info "Verifying signature..."
    "$apksigner" verify "$APK_PATH_RELEASE_SIGNED"

    local filesize=$(ls -lh "$APK_PATH_RELEASE_SIGNED" | awk '{print $5}')
    log_success "Release APK signed successfully!"
    log_info "Location: $APK_PATH_RELEASE_SIGNED"
    log_info "Size: $filesize"
    log_success "This APK is ready for distribution!"
}

# Get production keystore SHA256
get_production_sha256() {
    if [ ! -f "$KEYSTORE_PATH" ]; then
        log_error "Production keystore not found: $KEYSTORE_PATH"
        return 1
    fi

    # Get SHA256 with colons
    local sha256_with_colons=$(keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" 2>/dev/null | grep "SHA256:" | head -1 | sed 's/.*SHA256: //')

    # Convert to lowercase without colons (for F-Droid YAML)
    local sha256_lowercase=$(echo "$sha256_with_colons" | tr -d ':' | tr '[:upper:]' '[:lower:]')

    echo "$sha256_lowercase"
}

# Show F-Droid instructions
show_fdroid_instructions() {
    local apk_file="$1"
    local sha256="$2"

    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║${NC}  ${YELLOW}📋 F-DROID RELEASE WORKFLOW${NC}"
    echo -e "${GREEN}╠════════════════════════════════════════════════════════════════════╣${NC}"
    echo -e "${GREEN}║${NC}  ${BLUE}Step 1: Rename APK for GitHub${NC}"
    echo -e "${GREEN}║${NC}  cp $(basename "$apk_file") \\"
    echo -e "${GREEN}║${NC}     de1984-v${APP_VERSION}-release.apk"
    echo -e "${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${BLUE}Step 2: Replace APK on GitHub${NC}"
    echo -e "${GREEN}║${NC}  • Go to: https://github.com/dorumrr/de1984/releases/tag/v${APP_VERSION}"
    echo -e "${GREEN}║${NC}  • Click 'Edit' on the release"
    echo -e "${GREEN}║${NC}  • Delete old APK: de1984-v${APP_VERSION}-release.apk"
    echo -e "${GREEN}║${NC}  • Upload new APK: de1984-v${APP_VERSION}-release.apk"
    echo -e "${GREEN}║${NC}  • Click 'Update release'"
    echo -e "${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${BLUE}Step 3: Update F-Droid YAML${NC}"
    echo -e "${GREEN}║${NC}  File: ../f-droid-data/metadata/io.github.dorumrr.de1984.yml"
    echo -e "${GREEN}║${NC}  Update line: AllowedAPKSigningKeys: ${sha256}"
    echo -e "${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${BLUE}Step 4: Commit and Push${NC}"
    echo -e "${GREEN}║${NC}  cd ../f-droid-data"
    echo -e "${GREEN}║${NC}  git add metadata/io.github.dorumrr.de1984.yml"
    echo -e "${GREEN}║${NC}  git commit -m \"Update De1984: Switch to production keystore\""
    echo -e "${GREEN}║${NC}  git push origin add-de1984"
    echo -e "${GREEN}║${NC}"
    echo -e "${GREEN}║${NC}  ${BLUE}Step 5: Retrigger F-Droid CI${NC}"
    echo -e "${GREEN}║${NC}  • Via GitLab web interface: Add comment or click 'Retry'"
    echo -e "${GREEN}║${NC}  • Or empty commit: git commit --allow-empty -m 'Retrigger CI'"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}📝 Production Keystore SHA256 (for F-Droid YAML):${NC}"
    echo -e "${RED}${sha256}${NC}"
    echo ""
}

# Build and sign release APK (complete workflow)
build_and_sign_release() {
    log_header "Building and Signing Release APK (Production)"

    # Check keystore first
    check_keystore

    # Build release APK
    build_release_apk
    echo ""

    # Sign it
    sign_release_apk

    # Clean up intermediate files (keep only the final signed APK)
    rm -f "$APK_PATH_RELEASE"

    echo ""
    log_success "Production release build complete!"
    log_info "Signed APK: $APK_PATH_RELEASE_SIGNED"

    # Get production SHA256
    local production_sha256=$(get_production_sha256)

    if [ -n "$production_sha256" ]; then
        echo ""
        log_info "Production keystore SHA256: $production_sha256"
    fi

    echo ""
    log_info "This APK is ready for:"
    echo "  • F-Droid distribution (after renaming and updating YAML)"
    echo "  • Personal distribution (use as-is)"

    # Show F-Droid workflow instructions
    if [ -n "$production_sha256" ]; then
        show_fdroid_instructions "$APK_PATH_RELEASE_SIGNED" "$production_sha256"
    fi

    # Show GitHub upload reminder
    log_github_reminder "$APK_PATH_RELEASE_SIGNED" "release"
}

# Show welcome screen with command explanations
show_welcome() {
    clear
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC}                                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                ${GREEN}🔷 De1984 Development Script 🔷${NC}                 ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC}                                                                      ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}📋 AVAILABLE COMMANDS:${NC}"
    echo ""
    echo -e "${GREEN}BUILD${NC}    - Build debug APK for local testing"
    echo -e "         ${BLUE}→${NC} Signed with debug keystore"
    echo -e "         ${BLUE}→${NC} For development and testing only"
    echo -e "         ${BLUE}→${NC} Command: ${YELLOW}./dev.sh build${NC}"
    echo ""
    echo -e "${GREEN}INSTALL${NC}  - Uninstall old version and install fresh debug APK"
    echo -e "         ${BLUE}→${NC} Builds, uninstalls, and installs on device/emulator"
    echo -e "         ${BLUE}→${NC} For testing during development"
    echo -e "         ${BLUE}→${NC} Command: ${YELLOW}./dev.sh install [device|emulator]${NC}"
    echo ""
    echo -e "${GREEN}FDROID${NC}   - Build APK for F-Droid reproducible builds"
    echo -e "         ${BLUE}→${NC} Unsigned release APK (signed with debug key by Gradle)"
    echo -e "         ${BLUE}→${NC} Upload this to GitHub releases"
    echo -e "         ${BLUE}→${NC} Reference in F-Droid YAML: de1984-v%v-release.apk"
    echo -e "         ${BLUE}→${NC} Command: ${YELLOW}./dev.sh fdroid${NC}"
    echo ""
    echo -e "${GREEN}RELEASE${NC}  - Build production-signed APK for F-Droid + personal distribution"
    echo -e "         ${BLUE}→${NC} Signed with YOUR production keystore"
    echo -e "         ${BLUE}→${NC} For F-Droid: Rename and upload to GitHub"
    echo -e "         ${BLUE}→${NC} For personal: Use as-is for direct distribution"
    echo -e "         ${BLUE}→${NC} Shows complete F-Droid workflow instructions"
    echo -e "         ${BLUE}→${NC} Command: ${YELLOW}./dev.sh release${NC}"
    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC} ${YELLOW}⚠️  IMPORTANT: F-Droid Workflow${NC}                                     ${BLUE}║${NC}"
    echo -e "${BLUE}╠══════════════════════════════════════════════════════════════════════╣${NC}"
    echo -e "${BLUE}║${NC} • FDROID (testing): Use debug keystore to verify reproducibility    ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC} • RELEASE (production): Use production keystore for actual release  ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC} • After F-Droid confirms 'reproducible is OK', switch to RELEASE    ${BLUE}║${NC}"
    echo -e "${BLUE}║${NC} • RELEASE command shows complete F-Droid workflow instructions      ${BLUE}║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}Type any command above or 'help' for full documentation${NC}"
    echo ""

    # Auto-continue after 5 seconds
    for i in {5..1}; do
        echo -ne "\r${BLUE}Continuing in ${i} seconds... (Press Enter to skip)${NC}"
        read -t 1 -n 1 && break
    done
    echo -e "\r${NC}                                                          "
    echo ""
}

# Show help
show_help() {
    echo "De1984 Development Script"
    echo ""
    echo "Usage: ./dev.sh [command] [options]"
    echo ""
    echo "Development Commands:"
    echo "  build                      - Build debug APK (for local testing)"
    echo "  debug                      - Build debug APK with detailed info (SHA-256, location, F-Droid RFP)"
    echo "  install [device|emulator]  - Build debug, uninstall old, install fresh APK"
    echo "  launch                     - Launch the app"
    echo "  screenshot                 - Take and save screenshot"
    echo "  logs                       - Show app logs (live)"
    echo "  clear-logs                 - Clear device logs"
    echo "  info                       - Show device and app information"
    echo "  uninstall                  - Uninstall app only"
    echo ""
    echo "Build Commands:"
    echo "  fdroid                     - Build APK for F-Droid testing (debug keystore)"
    echo "  release                    - Build production-signed APK (shows F-Droid workflow)"
    echo "  create-keystore            - Create production keystore (first time only)"
    echo ""
    echo "Emulator Commands:"
    echo "  emulator [name]            - Start Android emulator (auto-selects Android 15 Pixel 9a)"
    echo "  list-emulators             - List available emulators"
    echo "  create-emulator            - Create Android 15 Pixel 9a emulator (recommended)"
    echo ""
    echo "Help:"
    echo "  help                       - Show this help"
    echo ""
    echo "Examples:"
    echo "  ./dev.sh build               - Build debug APK for testing"
    echo "  ./dev.sh debug               - Build debug APK with SHA-256 and F-Droid RFP info"
    echo "  ./dev.sh install device      - Install debug APK on physical device"
    echo "  ./dev.sh fdroid              - Build APK for F-Droid testing (debug key)"
    echo "  ./dev.sh create-keystore     - Create production keystore (once)"
    echo "  ./dev.sh release             - Build production APK + show F-Droid workflow"
    echo ""
    echo "F-Droid Workflow:"
    echo "  Phase 1 (Testing):"
    echo "    1. ./dev.sh fdroid         - Build with debug keystore"
    echo "    2. Upload to GitHub and submit to F-Droid"
    echo "    3. Wait for 'reproducible is OK' confirmation"
    echo ""
    echo "  Phase 2 (Production):"
    echo "    1. ./dev.sh create-keystore  - Create production keystore (once)"
    echo "    2. ./dev.sh release          - Build with production key"
    echo "    3. Follow on-screen instructions to:"
    echo "       - Rename APK for GitHub"
    echo "       - Replace APK on GitHub"
    echo "       - Update F-Droid YAML with production SHA256"
    echo "       - Commit and push changes"
    echo "       - Retrigger F-Droid CI"
    echo ""
    echo "Personal Distribution:"
    echo "  1. ./dev.sh release          - Build production-signed APK"
    echo "  2. Distribute de1984-v1.0.0-release-signed.apk directly"
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
        "screenshot")
            check_adb
            check_device "auto"
            take_screenshot
            ;;
        "launch")
            check_adb
            check_device "auto"
            launch_app
            ;;
        "logs")
            check_adb
            check_device "auto"
            show_logs
            ;;
        "clear-logs")
            check_adb
            check_device "auto"
            clear_logs
            ;;
        "info")
            check_adb
            check_device "auto"
            show_device_info
            show_app_info
            ;;
        "build")
            build_all_apks
            ;;
        "debug")
            build_debug_with_info
            ;;
        "fdroid")
            build_fdroid_apk
            ;;
        "release")
            build_and_sign_release
            ;;
        "create-keystore")
            create_keystore
            ;;
        "uninstall")
            check_adb
            check_device "auto"
            uninstall_app
            ;;
        "emulator")
            check_adb
            start_emulator "$target"
            ;;
        "list-emulators")
            list_emulators
            ;;
        "create-emulator")
            local new_avd=$(create_android_15_pixel)
            if [ -n "$new_avd" ]; then
                log_success "Emulator created successfully!"
                log_info "Start it with: ./dev.sh emulator $new_avd"
            fi
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
