#!/bin/bash

# De1984 Development Script
# Usage: ./dev.sh [command] [options]

set -euo pipefail

# Configuration
APP_ID="io.github.dorumrr.de1984"
APP_ID_DEBUG="${APP_ID}.debug"

# Extract version from build.gradle.kts (now using hardcoded versionName)
APP_VERSION=$(grep 'versionName = ' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')


APK_PATH_DEBUG="app/build/outputs/apk/debug/de1984-debug-v${APP_VERSION}.apk"
APK_PATH_RELEASE="app/build/outputs/apk/release/de1984-release-v${APP_VERSION}.apk"
APK_PATH_RELEASE_ALIGNED="app/build/outputs/apk/release/de1984-release-v${APP_VERSION}-aligned.apk"
APK_PATH_RELEASE_SIGNED="app/build/outputs/apk/release/de1984-release-v${APP_VERSION}-signed.apk"
SCREENSHOT_DIR="screenshots"
KEYSTORE_PATH="release-keystore.jks"

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
        log_info "Please create an emulator first:"
        log_info "1. Open Android Studio"
        log_info "2. Tools → AVD Manager → Create Virtual Device"
        log_info "3. Choose device and system image"
        log_info "4. Then run: ./dev.sh install emulator"
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
        # Use first available emulator
        emulator_name="${avds[0]}"
        log_info "Using default emulator: $emulator_name"
        if [ ${#avds[@]} -gt 1 ]; then
            log_info "Other available emulators:"
            for i in "${!avds[@]}"; do
                if [ $i -ne 0 ]; then
                    echo "  📱 ${avds[$i]}"
                fi
            done
            log_info "To use specific emulator: ./dev.sh emulator [name]"
        fi
    fi

    log_info "Starting emulator (this may take 30-60 seconds)..."

    # Start emulator in background
    "$emulator_cmd" -avd "$emulator_name" -no-snapshot-save -no-audio &
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

# Build both debug and unsigned release APKs
build_all_apks() {
    log_header "Building Debug and Release APKs"

    build_debug_apk
    echo ""
    build_release_apk

    echo ""
    log_success "Build complete!"
    log_info "Debug APK: $APK_PATH_DEBUG"
    log_info "Release APK (unsigned): $APK_PATH_RELEASE"
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
        log_info "Create an emulator in Android Studio:"
        log_info "Tools → AVD Manager → Create Virtual Device"
    else
        log_success "Found ${#avds[@]} emulator(s):"
        for avd in "${avds[@]}"; do
            echo "  📱 $avd"
        done
        echo ""
        log_info "Start with: ./dev.sh emulator"
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

# Build and sign release APK (complete workflow)
build_and_sign_release() {
    log_header "Building and Signing Release APK"

    # Check keystore first
    check_keystore

    # Build release APK
    build_release_apk
    echo ""

    # Sign it
    sign_release_apk

    echo ""
    log_success "Release build complete!"
    log_info "Signed APK: $APK_PATH_RELEASE_SIGNED"
    log_info "This APK can be distributed to users"
}

# Show help
show_help() {
    echo "De1984 Development Script"
    echo ""
    echo "Usage: ./dev.sh [command] [options]"
    echo ""
    echo "Development Commands:"
    echo "  install [device|emulator]  - Build debug, uninstall old, install fresh APK"
    echo "  launch                     - Launch the app"
    echo "  screenshot                 - Take and save screenshot"
    echo "  logs                       - Show app logs (live)"
    echo "  clear-logs                 - Clear device logs"
    echo "  info                       - Show device and app information"
    echo "  uninstall                  - Uninstall app only"
    echo ""
    echo "Build Commands:"
    echo "  build                      - Build debug and unsigned release APKs"
    echo "  release                    - Build and sign release APK (ready for distribution)"
    echo "  create-keystore            - Create keystore for signing releases"
    echo ""
    echo "Emulator Commands:"
    echo "  emulator [name]            - Start Android emulator (optionally specify which one)"
    echo "  list-emulators             - List available emulators"
    echo ""
    echo "Help:"
    echo "  help                       - Show this help"
    echo ""
    echo "Examples:"
    echo "  ./dev.sh install device      - Install debug APK on physical device"
    echo "  ./dev.sh install emulator    - Install debug APK on emulator"
    echo "  ./dev.sh build               - Build debug + unsigned release APKs"
    echo "  ./dev.sh create-keystore     - Create keystore (first time only)"
    echo "  ./dev.sh release             - Build and sign release APK"
    echo "  ./dev.sh emulator            - Start default emulator"
    echo "  ./dev.sh screenshot          - Take screenshot"
    echo ""
    echo "Release Workflow:"
    echo "  1. ./dev.sh create-keystore  - Create keystore (once)"
    echo "  2. ./dev.sh release          - Build and sign release APK"
    echo "  3. Distribute app-release.apk to users"
    echo ""
}

# Main script logic
main() {
    local command="${1:-help}"
    local target="${2:-auto}"

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
