# De1984 - Development Rules & Code Quality Standards

> **Project Implementation universal development principles**

## ⚠️ CRITICAL SAFETY RULES

### CRITICAL SAFETY RULE #1 - PACKAGE OPERATIONS
**Domain**: Android System Management / Root Operations

- **NEVER PERFORM PACKAGE OPERATIONS (disable/enable/uninstall/force-stop) DURING TESTING/DEVELOPMENT**
- **ALWAYS ASK USER PERMISSION** before performing any package management operations
- **RISK**: Targeting wrong packages can brick the operating system
- **APPLIES TO**: All disable, enable, uninstall, force-stop operations on ANY package
- **EXCEPTION**: None - always ask first, even for testing
- **USER RESPONSIBILITY**: Users can perform these operations, but AI must never do them autonomously

**Implementation**: All dangerous operations must show confirmation dialogs with:
- Clear description of the operation
- Package name and type (User/System)
- Different warning levels (friendly for user apps, scary for system apps)
- Explicit "I Know The Risk" confirmation buttons

## 🏗️ Core Development Philosophy

**MAXIMUM MAINTAINABILITY** is the primary goal. Every line of code must be written with future developers in mind, ensuring the codebase remains clean, understandable, and extensible.

**PRAGMATIC APPROACH**: We balance theoretical best practices with practical development needs. Rules should enhance productivity, not hinder it.

## 🎯 DRY (Don't Repeat Yourself)

> **Universal Principle** - Applicable to all projects

**Principle**: Every piece of knowledge must have a single, unambiguous, authoritative representation within the system.

**Implementation Rules**:
- **No code duplication**: Extract common functionality into reusable components
- **Centralized constants**: All magic numbers, strings, and configurations in `utils/Constants.kt`
- **Common utilities**: Shared helper functions in `utils/` package
- **Type safety over strings**: Use enums for type comparisons, not string matching
- **Single source of truth**: All domain types, states, and UI values must use constants

### De1984-Specific Implementation:

**What Must Be Constants**:
- ✅ Package types: `"system"`, `"user"` → `Constants.Packages.TYPE_SYSTEM`, `TYPE_USER`
- ✅ Package states: `"Enabled"`, `"Disabled"` → `Constants.Packages.STATE_ENABLED`, `STATE_DISABLED`
- ✅ Settings keys and values: `"firewall_enabled"`, `"block_all"` → `Constants.Settings.*`
- ✅ Firewall states: `"Blocked"`, `"Allowed"` → `Constants.Firewall.STATE_BLOCKED`, `STATE_ALLOWED`
- ✅ Dialog text: Titles, messages, button labels → `Constants.UI.Dialogs.*`

**What Can Be Contextual** (Not constants):
- ❌ Layout-specific margins/padding in XML that aren't reused in Kotlin code
- ❌ One-off dimension values unique to a single view
- ❌ Component-specific sizes defined directly in XML

**Rule of Thumb**: If a value is used in Kotlin code (not just XML), make it a constant. XML layouts use hardcoded dp values directly.

```kotlin
// ❌ BAD: Magic strings repeated in code
fun updatePackageState(state: String) {
    if (state == "Enabled") { /* ... */ }
}

// ✅ GOOD: Centralized constants (De1984 example)
object Constants {
    object Packages {
        const val STATE_ENABLED = "Enabled"
        const val STATE_DISABLED = "Disabled"
    }
}

// Use in code:
fun updatePackageState(state: String) {
    if (state == Constants.Packages.STATE_ENABLED) { /* ... */ }
}
```

**Note**: De1984 uses hardcoded dp values directly in XML layouts. Constants are only created for values used in Kotlin code.

## 🔧 KISS (Keep It Simple, Stupid)

> **Universal Principle** - Applicable to all projects

**Principle**: Simplicity should be a key goal in design, and unnecessary complexity should be avoided.

**Implementation Rules**:
- **Simple solutions first**: Choose the simplest approach that solves the problem
- **Avoid over-engineering**: Don't build for hypothetical future requirements
- **Clear naming**: Use descriptive, self-documenting names
- **Single responsibility**: Each function/class should do one thing well
- **Minimal dependencies**: Use only necessary external libraries

```kotlin
// ❌ BAD: Over-engineered
class PackageManagerFactoryBuilderProvider {
    fun createPackageManagerFactoryBuilder(): PackageManagerFactoryBuilder = ...
}

// ✅ GOOD: Simple and direct
class PackageRepository(private val packageManager: PackageManager) {
    fun getInstalledPackages(): List<PackageInfo> = ...
}
```

## 🧹 Clean Code Principles

> **Universal Principle** - Applicable to all projects

**Principle**: Code should be readable, understandable, and maintainable by any developer.

**Implementation Rules**:
- **Meaningful names**: Variables, functions, and classes should clearly express their purpose
- **Small functions**: Functions should be small and focused (max 20-30 lines)
- **Clear comments**: Explain WHY, not WHAT (code should be self-documenting)
- **Consistent formatting**: Use consistent indentation, spacing, and style
- **Error handling**: Proper exception handling with meaningful error messages

```kotlin
// ❌ BAD: Unclear and complex
fun p(l: List<String>): List<String> {
    val r = mutableListOf<String>()
    for (i in l) {
        if (i.contains("com.")) {
            r.add(i.substring(i.lastIndexOf(".") + 1))
        }
    }
    return r
}

// ✅ GOOD: Clean and clear
fun extractAppNamesFromPackages(packageNames: List<String>): List<String> {
    return packageNames
        .filter { it.contains("com.") }
        .map { it.substringAfterLast(".") }
}
```

## 📁 Organization & Structure

> **Universal Principle** - Adapt to your project's stack and scale

**Principle**: Code should be logically organized with clear separation of concerns.

**Implementation Rules**:
- **Package structure**: Clear, hierarchical package organization
- **Feature-based modules**: Group related functionality together
- **Separation of concerns**: UI, business logic, and data layers clearly separated
- **Consistent file naming**: Follow established naming conventions
- **Logical grouping**: Related classes and interfaces in the same package
- **Documentation organization**: Keep project root clean by organizing documentation in `docs/` folder

### Documentation File Organization
**Root Level** (Keep these at project root):
- `README.md` - Project overview and quick start
- `RULES.md` - Development rules and standards
- `PRD.md` - Product Requirements Document
- `RELEASE_SIGNING_GUIDE.md` - Release signing instructions

**docs/ Folder** (Move all other documentation here):
- Migration logs and progress tracking
- Implementation plans and audits
- Phase completion summaries
- Technical design documents
- Architecture decision records

### De1984-Specific Structure:

```kotlin
// De1984 Project Structure (Pragmatic Single-Module Approach)
io.github.dorumrr.de1984/
├── ui/                          // UI Layer
│   ├── packages/               // Package Management UI
│   ├── settings/               // Settings UI
│   ├── permissions/            // Permissions UI
│   ├── common/                 // Shared UI components
│   ├── navigation/             // Navigation logic
│   └── theme/                  // Theme definitions
├── domain/                     // Business Logic Layer
│   ├── model/                  // Domain models
│   ├── repository/             // Repository interfaces
│   └── usecase/                // Use cases
├── data/                       // Data Layer
│   ├── repository/             // Repository implementations
│   ├── datasource/             // Data sources
│   ├── database/               // Room database
│   ├── model/                  // Data entities
│   ├── export/                 // Data export functionality
│   └── common/                 // Common data utilities
├── presentation/               // Presentation Layer
│   └── viewmodel/              // ViewModels
├── De1984Dependencies.kt       // Manual Dependency Injection (ServiceLocator)
└── utils/                      // Utility classes
    ├── Constants.kt            // All constants centralized
    ├── Extensions.kt           // Kotlin extensions
    └── PackageUtils.kt         // Package-specific utilities
```

## 🧩 Modularization Strategy

**Principle**: Use appropriate modularization based on project size and team needs.

**Current Approach**: **Single Module** (Pragmatic for current scope)
- **Rationale**: With only 2 main features (Packages + Settings), multi-module complexity outweighs benefits
- **Clear package separation**: Logical separation through package structure
- **Easy navigation**: Developers can quickly understand and navigate the codebase
- **Faster builds**: Single module compilation is faster for small projects

**Future Considerations**:
- **When to modularize**: If we reach 5+ major features or 10+ developers
- **Migration path**: Current structure can easily be split into feature modules when needed

```kotlin
// Current Single Module Structure (Recommended for small projects)
:app
└── src/main/java/io/github/dorumrr/de1984/
    ├── ui/                      // All UI components
    ├── domain/                  // All business logic
    ├── data/                    // All data handling
    ├── presentation/            // All ViewModels
    ├── De1984Dependencies.kt    // Manual DI (ServiceLocator)
    └── utils/                   // Shared utilities

// Future Multi-Module Structure (When project grows)
:app
├── :feature:packages
├── :feature:settings
├── :core:ui
├── :core:data
└── :core:domain
```

## 🏛️ Architecture Patterns

**Principle**: Use proven architectural patterns for maintainability and testability.

**Implementation Rules**:
- **MVVM Pattern**: Model-View-ViewModel for UI components
- **Repository Pattern**: Abstract data access layer
- **Dependency Injection**: Manual DI using ServiceLocator pattern (De1984Dependencies)
- **Use Cases**: Encapsulate business logic in use cases
- **State Management**: Consistent state management across the app

## 🎨 Visual Consistency & Design Standards

### Core Design Philosophy
**CONSISTENT USER EXPERIENCE** across all sections. Every UI element should follow established patterns to create a cohesive, professional, and intuitive interface.

### Reusable Dialog Components

**Principle**: ALL dialogs must use one of the two standardized reusable dialog components for consistency.

#### When to Use Which Component

| Dialog Type | Component | Reason |
|-------------|-----------|--------|
| Confirmation (Force Stop, Uninstall) | `StandardDialog` | Simple yes/no decision |
| Error messages | `StandardDialog` | Simple message display |
| Info messages | `StandardDialog` | Simple message display |
| Welcome dialogs | `StandardDialog` | Simple two-button choice |
| Permission setup | `PermissionSetupDialog` | Complex status and setup flow |
| Feature onboarding | `PermissionSetupDialog` | Needs status indicators |

#### DO NOT Use

- ❌ `AlertDialog.Builder` directly (use `StandardDialog` instead)
- ❌ `MaterialAlertDialogBuilder` directly (use `StandardDialog` instead)
- ❌ Custom dialog implementations (use one of the two components)
- ❌ `BottomSheetDialog` for simple confirmations (use `StandardDialog`)

**Exception**: `BottomSheetDialog` is appropriate for action sheets with multiple actions (e.g., package actions in Firewall and Packages screens).

## 🌐 Cross-Platform Compatibility (CRITICAL)

> **Universal Principle** - Applicable to all Android projects

**Principle**: ALL code and design MUST ensure functionality and visual consistency across devices, Android versions, and themes.

### MANDATORY Compatibility Requirements

**ALWAYS TEST AND VERIFY**:
- ✅ **Android Versions**: API 21 (Lollipop) through latest (API 34+)
- ✅ **Light/Dark Modes**: All UI elements must adapt correctly to both themes
- ✅ **Screen Sizes**: Phone, tablet, foldable devices
- ✅ **Screen Densities**: ldpi, mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi
- ✅ **Orientation**: Portrait and landscape (where applicable)

### Implementation Rules

#### 1. Icon Compatibility
```kotlin
// ❌ BAD: Hardcoded colors or theme attributes in XML
<vector android:tint="?attr/colorControlNormal">  <!-- NOT compatible with older Android -->
<vector android:fillColor="#FF000000">            <!-- Won't work in dark mode -->

// ✅ GOOD: White base + programmatic tinting
<vector android:fillColor="@android:color/white"> <!-- Base color for tinting -->

// Then tint programmatically based on theme:
private fun getIconColor(): Int {
    val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return when (nightModeFlags) {
        Configuration.UI_MODE_NIGHT_YES -> ContextCompat.getColor(this, android.R.color.white)
        Configuration.UI_MODE_NIGHT_NO -> ContextCompat.getColor(this, android.R.color.black)
        else -> ContextCompat.getColor(this, android.R.color.black)
    }
}
```

#### 2. Color Resources
```xml
<!-- ✅ GOOD: Define colors for both light and dark modes -->
<!-- values/colors.xml (Light mode) -->
<color name="text_primary">#000000</color>
<color name="background">#FFFFFF</color>

<!-- values-night/colors.xml (Dark mode) -->
<color name="text_primary">#FFFFFF</color>
<color name="background">#1E1E1E</color>
```

#### 3. Text Sizes
```xml
<!-- ✅ GOOD: Use sp for text, dp for dimensions -->
<TextView
    android:textSize="16sp"     <!-- Scales with user's font size preference -->
    android:padding="16dp" />   <!-- Fixed dimension -->
```

#### 4. Touch Targets
```xml
<!-- ✅ GOOD: Minimum 48dp for all interactive elements -->
<ImageView
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:padding="12dp"      <!-- Icon can be smaller, but touch area is 48dp -->
    android:background="?attr/selectableItemBackgroundBorderless" />
```

#### 5. Theme-Aware Attributes
```xml
<!-- ✅ GOOD: Use theme attributes that adapt automatically -->
<View
    android:background="?attr/colorSurface"           <!-- Adapts to theme -->
    android:textColor="?attr/colorOnSurface" />       <!-- Adapts to theme -->

<!-- ❌ BAD: Hardcoded colors -->
<View
    android:background="#FFFFFF"                      <!-- Only works in light mode -->
    android:textColor="#000000" />                    <!-- Only works in light mode -->
```

### Testing Checklist (MANDATORY Before Committing)

**For EVERY UI change, verify**:
- [ ] Tested in light mode - all elements visible and properly styled
- [ ] Tested in dark mode - all elements visible and properly styled
- [ ] Tested on API 21 (Lollipop) - oldest supported version
- [ ] Tested on API 26 (Oreo) - mid-range version
- [ ] Tested on API 29+ (Q+) - modern versions with dark mode
- [ ] Tested on API 34+ (latest) - newest features
- [ ] All icons are visible in both themes
- [ ] All text is readable with proper contrast (WCAG AA)
- [ ] All touch targets are minimum 48dp
- [ ] No hardcoded colors that break in dark mode
- [ ] No theme attributes that break on older Android versions

### Common Pitfalls to Avoid

**❌ NEVER DO THIS**:
```kotlin
// Hardcoded colors
view.setBackgroundColor(Color.WHITE)  // Breaks in dark mode

// Theme attributes in vector drawables (API compatibility issues)
android:tint="?attr/colorControlNormal"

// Hardcoded black/white in icons
android:fillColor="#000000"  // Invisible in dark mode

// Small touch targets
android:layout_width="24dp"  // Too small for touch
android:layout_height="24dp"
```

**✅ ALWAYS DO THIS**:
```kotlin
// Use theme-aware colors
val backgroundColor = ContextCompat.getColor(context, R.color.background)
view.setBackgroundColor(backgroundColor)

// Programmatic icon tinting
icon.mutate()
icon.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)

// White base in vector drawables
android:fillColor="@android:color/white"

// Proper touch targets
android:layout_width="48dp"
android:layout_height="48dp"
```

### Material Design 3 Compliance
**Principle**: Strict adherence to Material Design 3 guidelines with LineageOS branding.

**Implementation Rules**:
- **Color System**: LineageOS teal theme (`#167C80` primary, `#4DB6AC` dark primary)
- **Typography**: Material 3 scales (22sp titles, 16sp body, 14sp labels)
- **Spacing**: 8dp/16dp grid system, 12dp radius, 16dp padding
- **Components**: Material Design Components (MDC) for Android with XML layouts
- **View Binding**: Use View Binding for type-safe view access
- **Theme**: Light/dark mode with consistent color mapping

### Accessibility Requirements
- **Touch Targets**: Minimum 48dp for all interactive elements
- **Contrast**: WCAG AA compliance for all text and backgrounds
- **Focus Indicators**: Clear focus states for keyboard navigation
- **Screen Reader**: Proper content descriptions for all UI elements

## 🎯 Pragmatic Development Guidelines

### When to Apply Rules Strictly vs. Flexibly

**STRICT ENFORCEMENT** (Non-negotiable):
- **Cross-platform compatibility**: MUST work across all Android versions (API 21+), light/dark modes, and screen sizes
- **Icon tinting**: NEVER use hardcoded colors or `android:tint` attributes - always use programmatic tinting
- **Theme compatibility**: ALL UI elements must adapt to light/dark modes correctly
- **Touch targets**: Minimum 48dp for all interactive elements
- **DRY violations**: Always extract magic numbers and duplicate code
- **Architecture patterns**: MVVM + Repository must be maintained
- **Clean Code**: Meaningful names and small functions are essential
- **Constants usage**: All UI values must use `Constants.UI.*`

**FLEXIBLE APPLICATION** (Context-dependent):
- **Modularization**: Single module is acceptable for small projects
- **Over-engineering**: Don't build for hypothetical future requirements
- **Utility organization**: Prefer fewer, well-organized files over many small ones

### Decision Framework
When facing architectural decisions, ask:
1. **Does this improve maintainability for multiple developers?**
2. **Does this reduce cognitive load when reading code?**
3. **Is the complexity justified by the current project scope?**
4. **Can this be easily refactored later if needed?**

If answers are mostly "yes", implement it. If mostly "no", choose the simpler approach.

---

## 🔒 Type Safety & Consistency

> **Universal Principle** - Applicable to all statically-typed languages

**Principle**: Use compile-time type safety wherever possible to prevent runtime errors.

**Implementation Rules**:

### ✅ Type Comparisons
```kotlin
// ❌ BAD: String-based type comparison (fragile, error-prone)
if (item.type.name == "PRIMARY") {
    // Handle primary type
}

// ✅ GOOD: Enum-based comparison (type-safe, compile-time checked)
if (item.type == ItemType.PRIMARY) {
    // Handle primary type
}

// De1984 example:
if (packageInfo.type == PackageType.SYSTEM) {
    // Handle system package
}
```

### ✅ String Consistency
```kotlin
// ❌ BAD: Mixed string representations
const val TYPE_SYSTEM = "System"  // Capitalized
type = when (type.lowercase()) {  // Requires runtime conversion
    "system" -> PackageType.SYSTEM
}

// ✅ GOOD: Consistent lowercase representation
const val TYPE_SYSTEM = "system"  // Lowercase
type = when (type) {              // Direct comparison
    Constants.Packages.TYPE_SYSTEM -> PackageType.SYSTEM
}
```

### ✅ Enum Usage
- **Always use enums** for fixed sets of values (PackageType, PackageState, etc.)
- **Never compare** enum values using `.name` property
- **Use constants** for string representations that need to be persisted/serialized

**Why This Matters**:
- Compile-time safety catches errors before runtime
- IDE autocomplete helps prevent typos
- Refactoring is safer (rename operations work correctly)
- No case-sensitivity issues

---

## 🚫 Deprecated Code Policy

> **Universal Principle** - Applicable to all projects

**Principle**: Remove deprecated code promptly to maintain codebase cleanliness.

**Implementation Rules**:
- **No deprecated code** should remain in the codebase for more than one release cycle
- **Migration path**: Provide clear alternatives when deprecating
- **Documentation**: Explain why code was deprecated and what to use instead
- **Clean removal**: Don't leave commented-out deprecated code

```kotlin
// ❌ BAD: Leaving deprecated code indefinitely
@Deprecated("Use getFilteredByState instead")
fun getFiltered(filter: String): Flow<List<Package>> {
    // Old implementation that should be removed
}

// ✅ GOOD: Remove deprecated code after migration
// Deprecated code removed - all callers migrated to getFilteredByState()
```

**When to Remove**:
1. Immediately if no code uses it
2. After one release if migration is complete
3. Never keep "just in case" - use version control instead

---

## 📦 Constants Organization

> **Universal Principle** - Adapt file location to your project structure

**Principle**: All constants must be organized in a central location with clear categorization.

---

## 🧪 Testing & Quality Assurance

> **Universal Principle** - Adapt commands to your build system

**Principle**: Build/test after each significant change to catch errors early.

**Implementation Rules**:
- **Incremental builds**: Build after each logical change (not after every line)
- **Test dangerous operations**: Never test package operations on real devices without user permission
- **Verify imports**: Ensure no duplicate imports after adding new ones
- **Check constants usage**: Verify constants are imported and used correctly

**Build Strategy** (De1984 - Gradle/Android):
```bash
# After each feature/fix
./gradlew assembleDebug

# Before committing
./gradlew build

# Full verification
./gradlew clean build
```

---

## 📝 Code Review Checklist

**Before Committing Code**:

### Compatibility (CRITICAL)
- [ ] Tested in light mode - all elements visible
- [ ] Tested in dark mode - all elements visible
- [ ] No hardcoded colors that break in dark mode
- [ ] Icons use white base + programmatic tinting (no `android:tint` attributes)
- [ ] All touch targets are minimum 48dp
- [ ] Text uses `sp` units, dimensions use `dp` units
- [ ] Theme-aware attributes used (`?attr/colorSurface`, not hardcoded colors)
- [ ] Tested on API 21 (oldest supported version)
- [ ] Tested on API 29+ (dark mode support)

### Code Quality
- [ ] No hardcoded spacing values (use `Constants.UI.*`)
- [ ] No string-based type comparisons (use enum comparisons)
- [ ] No deprecated code remaining
- [ ] No duplicate imports
- [ ] All TODOs have context and version tags
- [ ] Build successful
- [ ] No new warnings introduced
- [ ] Constants properly organized in `Constants.kt`
- [ ] Type safety maintained (enums over strings)
- [ ] Confirmation dialogs for dangerous operations

### UI Components
- [ ] All dialogs use `StandardDialog` or `PermissionSetupDialog` (no direct `AlertDialog.Builder` or `MaterialAlertDialogBuilder`)
- [ ] Simple dialogs (confirmations, errors, info) use `StandardDialog`
- [ ] Complex permission flows use `PermissionSetupDialog`
- [ ] Action sheets use `BottomSheetDialog` (not dialogs)

---

## 📦 ProGuard / Minification

> **Status**: ✅ Enabled and working (v1.0.0+)

### Configuration
- **File**: `app/proguard-rules.pro`
- **Minification**: Enabled in release builds
- **Result**: ~30-40% APK size reduction

### What's Covered
The ProGuard configuration properly handles:
- ✅ Manual DI (ViewModels, factories, ServiceLocator)
- ✅ Room Database (entities, DAOs, generated classes)
- ✅ View Binding (generated binding classes)
- ✅ Kotlin features (sealed classes, data classes, enums)
- ✅ OkHttp (SSL/TLS for update checker)
- ✅ Coroutines (Flow, StateFlow, continuations)

### F-Droid Compliance
- Strips all analytics/tracking code
- Removes debug logging in release builds
- No telemetry or usage statistics

### Adding New Features
**No ProGuard changes needed for**:
- New ViewModels (auto-handled by ViewModel rules)
- New data classes ending in `*UiState` (auto-handled)
- New sealed classes ending in `*State`, `*Result`, `*Error` (auto-handled)
- New enums (auto-handled)

**Add keep rules only if**:
- Using reflection with custom classes
- Adding third-party library with reflection
- Getting `ClassNotFoundException` in release builds

---

# ALWAYS REMEMBER
**When in doubt, look at existing code and follow the same patterns!**

**Key Mantras**:
- 🌐 **Compatibility first** - ALWAYS test light/dark modes and multiple Android versions
- 🎨 **Icons need tinting** - White base + programmatic tinting, NEVER hardcoded colors
- 📱 **Touch targets matter** - Minimum 48dp for all interactive elements
- 🎯 **Constants over magic numbers** - Every hardcoded value should have a name
- 🔒 **Enums over strings** - Type safety prevents runtime errors
- 🧹 **Clean over clever** - Readable code beats clever code
- 🚀 **Simple over complex** - Solve today's problems, not tomorrow's hypotheticals
- ✅ **Build often** - Catch errors early, fix them immediately
- 📦 **Test release builds** - Minification can hide issues that only appear in release
- 🌓 **Test both themes** - Every UI change must work in light AND dark mode