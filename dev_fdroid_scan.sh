#!/bin/bash

# F-Droid Build & Scanner Script for De1984
# Usage: ./dev_fdroid_scan.sh [build|scan|all] [specific-apk-path]
#
# Commands:
#   build  - Clean and build debug + release APKs
#   scan   - Scan existing APKs in app/build/outputs/apk/
#   all    - Build then scan (default)
#   [path] - Scan specific APK file

set -e

# Set up environment
export PATH="/Library/Frameworks/Python.framework/Versions/3.12/bin:$PATH"

# Auto-detect ANDROID_HOME if not set
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [ -d "$HOME/Android/Sdk" ]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    else
        echo "⚠️  ANDROID_HOME not set and SDK not found in default locations"
        echo "   Please set ANDROID_HOME environment variable"
    fi
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🔍 F-Droid Build & Scanner for De1984${NC}"
echo "========================================"
echo ""

# Check prerequisites
check_prerequisites() {
    local missing_tools=()

    if ! command -v fdroid &> /dev/null; then
        missing_tools+=("fdroidserver")
    fi

    if ! command -v strings &> /dev/null; then
        missing_tools+=("binutils (strings command)")
    fi

    if ! command -v unzip &> /dev/null; then
        missing_tools+=("unzip")
    fi

    if [ ${#missing_tools[@]} -gt 0 ]; then
        echo -e "${RED}❌ Missing required tools:${NC}"
        for tool in "${missing_tools[@]}"; do
            echo "   - $tool"
        done
        echo ""
        echo "To install missing tools:"
        echo "  fdroidserver: pip3 install fdroidserver"
        echo "  binutils: brew install binutils (macOS) or apt-get install binutils (Linux)"
        echo ""
        exit 1
    fi

    echo -e "${GREEN}✅ All prerequisites installed${NC}"
    echo ""
}

check_prerequisites

# Function to clean build outputs
clean_build() {
    echo -e "${YELLOW}🧹 Cleaning build outputs...${NC}"
    ./gradlew clean
    rm -rf app/build/outputs/apk/
    echo -e "${GREEN}✅ Clean complete!${NC}"
    echo ""
}

# Function to build APKs
build_apks() {
    echo -e "${YELLOW}🔨 Building Debug APK...${NC}"
    if ./gradlew assembleDebug; then
        echo -e "${GREEN}✅ Debug build complete!${NC}"
    else
        echo -e "${RED}❌ Debug build failed!${NC}"
        exit 1
    fi
    echo ""

    echo -e "${YELLOW}🔨 Building Release APK...${NC}"
    if ./gradlew assembleRelease; then
        echo -e "${GREEN}✅ Release build complete!${NC}"
    else
        echo -e "${RED}❌ Release build failed!${NC}"
        exit 1
    fi
    echo ""
}

# Function to find all APKs
find_apks() {
    find app/build/outputs/apk/ -name "*.apk" -type f 2>/dev/null | sort
}

# Function to scan single APK
scan_apk() {
    local apk_path="$1"
    local apk_name=$(basename "$apk_path")
    local apk_type="unknown"

    if [[ "$apk_path" == *"debug"* ]]; then
        apk_type="debug"
    elif [[ "$apk_path" == *"release"* ]]; then
        apk_type="release"
    fi

    echo -e "${BLUE}📱 Scanning: ${apk_name} (${apk_type})${NC}"
    echo "   Path: $apk_path"
    echo "   Size: $(du -h "$apk_path" | cut -f1)"
    echo ""

    local has_issues=false

    # Run F-Droid scanner
    echo "Running F-Droid scanner..."
    if ! fdroid scanner "$apk_path"; then
        echo -e "${RED}❌ F-Droid scanner found issues!${NC}"
        has_issues=true
    fi

    # Additional URL scanning (like GitLab F-Droid bot)
    echo ""
    echo "Checking for tracking URLs..."

    # Extract and scan APK contents thoroughly (fixed unzip hanging)
    local temp_dir=$(mktemp -d)
    echo "   Extracting APK contents..."

    # Force overwrite without prompts (-o flag) and be quiet (-q flag)
    if unzip -o -q "$apk_path" -d "$temp_dir" 2>/dev/null; then
        echo "   ✅ APK extracted successfully"
    else
        echo "   ⚠️ APK extraction failed, using direct scan"
    fi

    # Check for Google tracking URLs in all files
    echo "   Scanning extracted files..."
    local google_urls=""
    if [ -d "$temp_dir" ]; then
        google_urls=$(find "$temp_dir" -type f \( -name "*.dex" -o -name "*.arsc" \) -exec strings {} \; 2>/dev/null | grep -E "(issuetracker\.google\.com|googleapis\.com|google\.com/.*track)" | head -5)
    fi

    # Fallback to direct APK scan if extraction failed
    if [ -z "$google_urls" ]; then
        echo "   Fallback: Direct APK scan..."
        google_urls=$(strings "$apk_path" 2>/dev/null | grep -E "(issuetracker\.google\.com|googleapis\.com|google\.com/.*track)" | head -3)
    fi

    if [ -n "$google_urls" ]; then
        echo -e "${RED}🚩 Found tracking URLs:${NC}"
        echo "$google_urls" | while read -r url; do
            echo -e "   ${RED}• $url${NC}"
        done
        has_issues=true
    else
        echo -e "${GREEN}✅ No tracking URLs found${NC}"
    fi

    # Check classes.dex specifically (like GitLab bot)
    if [ -f "$temp_dir/classes.dex" ]; then
        echo "   Scanning classes.dex..."
        local dex_urls=$(strings "$temp_dir/classes.dex" 2>/dev/null | grep -E "(issuetracker\.google\.com|googleapis\.com|google\.com/.*track)" | head -3)
        if [ -n "$dex_urls" ]; then
            echo -e "${RED}🚩 Found URLs in classes.dex:${NC}"
            echo "$dex_urls" | while read -r url; do
                echo -e "   ${RED}• $url${NC}"
            done
            has_issues=true
        fi
    fi

    # Check resources.arsc
    if [ -f "$temp_dir/resources.arsc" ]; then
        echo "   Scanning resources.arsc..."
        local resource_urls=$(strings "$temp_dir/resources.arsc" 2>/dev/null | grep -E "(issuetracker\.google\.com|googleapis\.com|google\.com/.*track)" | head -3)
        if [ -n "$resource_urls" ]; then
            echo -e "${RED}🚩 Found URLs in resources.arsc:${NC}"
            echo "$resource_urls" | while read -r url; do
                echo -e "   ${RED}• $url${NC}"
            done
            has_issues=true
        fi
    fi

    # Cleanup
    rm -rf "$temp_dir"

    echo ""
    if [ "$has_issues" = false ]; then
        echo -e "${GREEN}✅ ${apk_name}: CLEAN - F-Droid compliant!${NC}"
        return 0
    else
        echo -e "${RED}❌ ${apk_name}: Issues found!${NC}"
        return 1
    fi
    echo ""
}

# Function to scan all APKs
scan_all_apks() {
    local apks=($(find_apks))

    if [ ${#apks[@]} -eq 0 ]; then
        echo -e "${RED}❌ No APKs found in app/build/outputs/apk/${NC}"
        echo "   Run with 'build' or 'all' to build APKs first"
        exit 1
    fi

    echo -e "${BLUE}📋 Found ${#apks[@]} APK(s) to scan:${NC}"
    for apk in "${apks[@]}"; do
        echo "   - $(basename "$apk")"
    done
    echo ""

    local clean_count=0
    local total_count=${#apks[@]}

    for apk in "${apks[@]}"; do
        if scan_apk "$apk"; then
            ((clean_count++))
        fi
    done

    echo "=================================="
    echo -e "${BLUE}📊 F-Droid Scan Summary:${NC}"
    echo -e "   Clean APKs: ${GREEN}${clean_count}${NC}/${total_count}"

    if [ $clean_count -eq $total_count ]; then
        echo -e "${GREEN}🎉 All APKs are F-Droid compliant!${NC}"
        return 0
    else
        echo -e "${RED}⚠️  Some APKs have issues!${NC}"
        return 1
    fi
}

# Main command logic
COMMAND="${1:-all}"

case "$COMMAND" in
    "build")
        clean_build
        build_apks
        echo -e "${GREEN}🎯 Build complete! Use './dev_fdroid_scan.sh scan' to scan APKs${NC}"
        ;;

    "scan")
        scan_all_apks
        ;;

    "all")
        clean_build
        build_apks
        scan_all_apks
        ;;

    "help"|"-h"|"--help")
        echo "F-Droid Build & Scanner Script for De1984"
        echo ""
        echo "Usage: $0 [command] [options]"
        echo ""
        echo "Commands:"
        echo "  build     - Clean and build debug + release APKs"
        echo "  scan      - Scan existing APKs in app/build/outputs/apk/"
        echo "  all       - Build then scan all APKs (default)"
        echo "  help      - Show this help message"
        echo ""
        echo "Examples:"
        echo "  $0                    # Build and scan all APKs"
        echo "  $0 build              # Only build APKs"
        echo "  $0 scan               # Only scan existing APKs"
        echo "  $0 path/to/app.apk    # Scan specific APK file"
        echo ""
        echo "Requirements:"
        echo "  - fdroidserver installed (pip3 install fdroidserver)"
        echo "  - binutils for strings command (brew install binutils)"
        echo "  - Android SDK with ANDROID_HOME set"
        echo "  - Gradle project in current directory"
        echo ""
        echo "What this script does:"
        echo "  1. Builds debug and release APKs"
        echo "  2. Runs F-Droid scanner to check for non-FLOSS code"
        echo "  3. Scans for tracking URLs (Google, analytics, etc.)"
        echo "  4. Checks classes.dex and resources.arsc for issues"
        echo "  5. Reports F-Droid compliance status"
        ;;

    *)
        # If argument looks like a file path, scan it directly
        if [ -f "$COMMAND" ]; then
            echo -e "${BLUE}📱 Scanning specific APK: $(basename "$COMMAND")${NC}"
            echo ""
            scan_apk "$COMMAND"
        else
            echo -e "${RED}❌ Unknown command: $COMMAND${NC}"
            echo "Use '$0 help' for usage information"
            exit 1
        fi
        ;;
esac
