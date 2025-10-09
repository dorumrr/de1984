# Edge-to-Edge Display Fix for All Devices

## Problems Fixed
1. **Dark line/indicator** appearing at the bottom on some devices due to improper handling of system window insets in edge-to-edge mode
2. **Text overlapping icons** in bottom navigation on some devices/Android versions due to insufficient spacing

## Root Causes
1. **Dark line issue:** The app was using `WindowCompat.setDecorFitsSystemWindows(window, false)` to enable edge-to-edge display, but wasn't properly applying window insets to the UI components. This caused:
   - The bottom navigation to extend behind the system navigation bar
   - A dark overlay/scrim to appear on some devices
   - Inconsistent behavior across different Android versions

2. **Text overlap issue:** The bottom navigation had:
   - Fixed height of `80dp` which was too small for icon + label
   - Large icon size (`32dp`) leaving insufficient space for text
   - No internal padding to separate icon from label

## Solution Overview
Implemented proper window insets handling to ensure consistent behavior across all devices and Android versions (API 21+).

## Changes Made

### 1. MainActivity.kt - Window Insets Handling
**File:** `app/src/main/java/io/github/dorumrr/de1984/ui/MainActivity.kt`

Added proper window insets listener that:
- Applies top padding to the toolbar for the status bar
- Applies bottom padding to the bottom navigation for the navigation bar
- Ensures content doesn't overlap with system bars

```kotlin
ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

    // Apply top padding for status bar to the toolbar
    binding.toolbar.setPadding(0, systemBars.top, 0, 0)

    // Apply bottom padding for navigation bar to the bottom navigation
    // Preserve the existing top/bottom padding from layout (8dp each)
    val bottomNavPaddingTop = binding.bottomNavigation.paddingTop
    binding.bottomNavigation.setPadding(
        0,
        bottomNavPaddingTop,
        0,
        bottomNavPaddingTop + systemBars.bottom
    )

    // Don't apply padding to root view
    view.setPadding(0, 0, 0, 0)

    insets
}
```

### 2. Layout Updates - activity_main_views.xml
**File:** `app/src/main/res/layout/activity_main_views.xml`

#### AppBarLayout Changes:
- Changed `android:fitsSystemWindows="true"` to `"false"` (we handle insets manually)

#### Toolbar Changes:
- Added `android:clipToPadding="false"` to prevent clipping when padding is applied

#### BottomNavigationView Changes:
- Changed `android:layout_height="80dp"` to `"wrap_content"` for flexible sizing
- Added `android:minHeight="56dp"` to ensure minimum touch target size
- Added `android:paddingTop="8dp"` and `android:paddingBottom="8dp"` for internal spacing
- Reduced `app:itemIconSize="32dp"` to `"24dp"` for better proportion
- Added `android:clipToPadding="false"` to allow content to extend into padding area
- Added `app:itemRippleColor="@android:color/transparent"` to remove ripple effects
- Kept `app:itemActiveIndicatorStyle="@null"` to remove circular background

### 3. Theme Updates - Transparent System Bars

#### values/themes.xml
**File:** `app/src/main/res/values/themes.xml`

Updated `Theme.De1984.Views` to use transparent system bars:
```xml
<item name="android:statusBarColor">@android:color/transparent</item>
<item name="android:navigationBarColor">@android:color/transparent</item>
<item name="android:windowDrawsSystemBarBackgrounds">true</item>
<item name="android:enforceNavigationBarContrast">false</item>
```

#### values-v29/themes.xml (NEW FILE)
**File:** `app/src/main/res/values-v29/themes.xml`

Created API 29+ specific theme with additional attributes:
```xml
<item name="android:enforceNavigationBarContrast">false</item>
<item name="android:enforceStatusBarContrast">false</item>
```

These attributes prevent Android 10+ from adding automatic contrast scrim/overlay.

#### values-night/themes.xml
**File:** `app/src/main/res/values-night/themes.xml`

Added dark mode version of `Theme.De1984.Views` with same edge-to-edge configuration.

## How It Works

### Edge-to-Edge Flow:
1. **Theme declares transparent system bars** → System bars become transparent
2. **MainActivity enables edge-to-edge** → Content can draw behind system bars
3. **Window insets listener applies padding** → UI components respect system bar areas
4. **clipToPadding="false"** → Content can still draw in padding area (for visual effects)

### Cross-Device Compatibility:

#### API 21-28 (Android 5.0 - 9.0):
- Uses base theme with transparent bars
- `enforceNavigationBarContrast` not available (ignored)
- Manual insets handling ensures proper spacing

#### API 29+ (Android 10+):
- Uses values-v29 theme
- `enforceNavigationBarContrast="false"` prevents automatic scrim
- `enforceStatusBarContrast="false"` prevents status bar scrim
- Full edge-to-edge experience

#### Dark Mode:
- Separate theme configuration in values-night
- Maintains same edge-to-edge behavior
- Proper contrast for dark backgrounds

## Testing Checklist

Test on the following to ensure consistency:

### Device Types:
- [ ] Phones with physical navigation buttons
- [ ] Phones with gesture navigation
- [ ] Phones with 3-button navigation
- [ ] Tablets

### Android Versions:
- [ ] Android 5.0-6.0 (API 21-23)
- [ ] Android 7.0-8.1 (API 24-27)
- [ ] Android 9.0 (API 28)
- [ ] Android 10+ (API 29+)

### Display Modes:
- [ ] Light mode
- [ ] Dark mode
- [ ] Different screen sizes
- [ ] Different screen densities

### Expected Behavior:
✅ No dark line/indicator at bottom
✅ Bottom navigation sits above system navigation bar
✅ Status bar is transparent with teal toolbar visible
✅ Navigation bar is transparent with white bottom nav visible
✅ No circular background behind selected icon
✅ Full-width rectangular selection background
✅ **No text overlapping icons** - proper spacing between icon and label
✅ **Proper touch targets** - minimum 56dp height maintained
✅ Consistent spacing on all devices

## Key Attributes Reference

### Window Insets:
- `WindowCompat.setDecorFitsSystemWindows(window, false)` - Enable edge-to-edge
- `ViewCompat.setOnApplyWindowInsetsListener()` - Handle insets manually
- `WindowInsetsCompat.Type.systemBars()` - Get system bar insets

### Layout Attributes:
- `android:fitsSystemWindows` - Auto-apply insets (we set to false)
- `android:clipToPadding` - Allow drawing in padding area
- `app:itemActiveIndicatorStyle="@null"` - Remove Material 3 active indicator
- `app:itemRippleColor="@android:color/transparent"` - Remove ripple effect

### Theme Attributes:
- `android:statusBarColor` - Status bar background color
- `android:navigationBarColor` - Navigation bar background color
- `android:windowDrawsSystemBarBackgrounds` - Enable custom system bar colors
- `android:enforceNavigationBarContrast` - Prevent automatic scrim (API 29+)
- `android:enforceStatusBarContrast` - Prevent status bar scrim (API 29+)
- `android:windowLightNavigationBar` - Use dark icons for light nav bar
- `android:windowLightStatusBar` - Use dark icons for light status bar

## Troubleshooting

### If dark line still appears:
1. Check device Android version
2. Verify theme is applied correctly in AndroidManifest.xml
3. Check if device manufacturer added custom overlays
4. Try toggling gesture navigation vs button navigation

### If content overlaps system bars:
1. Verify window insets listener is being called
2. Check padding values in debug mode
3. Ensure `clipToPadding="false"` is set correctly

### If colors are wrong:
1. Check theme inheritance (Material3.Light vs Material3.Dark)
2. Verify color resources are defined
3. Check night mode configuration

## References
- [Android Edge-to-Edge Guide](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [WindowInsets Documentation](https://developer.android.com/reference/androidx/core/view/WindowInsetsCompat)
- [Material Design 3 Bottom Navigation](https://m3.material.io/components/navigation-bar/overview)

