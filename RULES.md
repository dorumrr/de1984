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
- ✅ Spacing values: `16.dp`, `24.dp` → `Constants.UI.SPACING_STANDARD`, `SPACING_LARGE`
- ✅ Package types: `"system"`, `"user"` → `Constants.Packages.TYPE_SYSTEM`, `TYPE_USER`
- ✅ Package states: `"Enabled"`, `"Disabled"` → `Constants.Packages.STATE_ENABLED`, `STATE_DISABLED`
- ✅ Icon sizes: `24.dp`, `32.dp` → `Constants.UI.ICON_SIZE_SMALL`, `ICON_SIZE_MEDIUM`
- ✅ UI dimensions: Bottom sheet radius, drag handles, borders

**What Can Be Contextual** (Not constants):
- ❌ Content padding: `PaddingValues(16.dp)` - LazyColumn/LazyRow specific
- ❌ Arrangement spacing: `Arrangement.spacedBy(16.dp)` - Layout specific
- ❌ Directional padding: `.padding(horizontal = 16.dp, vertical = 8.dp)` - Context specific
- ❌ Component-specific sizes that aren't reused elsewhere

**Rule of Thumb**: If a value appears 2+ times or has semantic meaning, make it a constant.

```kotlin
// ❌ BAD: Repeated magic numbers
@Composable
fun HeaderComponent() {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(modifier = Modifier.size(32.dp))
}

@Composable
fun CardComponent() {
    Spacer(modifier = Modifier.height(16.dp))
    Icon(modifier = Modifier.size(32.dp))
}

// ✅ GOOD: Centralized constants (De1984 example)
object Constants {
    object UI {
        val SPACING_STANDARD = 16.dp
        val ICON_SIZE_MEDIUM = 32.dp
    }
}

@Composable
fun HeaderComponent() {
    Spacer(modifier = Modifier.height(Constants.UI.SPACING_STANDARD))
    Icon(modifier = Modifier.size(Constants.UI.ICON_SIZE_MEDIUM))
}
```

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

```kotlin
// MVVM Implementation Example
class PackageListViewModel constructor(
    private val packageRepository: PackageRepository,
    private val packageUseCase: GetPackagesUseCase
) : ViewModel() {

    private val _packages = MutableStateFlow<List<PackageInfo>>(emptyList())
    val packages: StateFlow<List<PackageInfo>> = _packages.asStateFlow()

    fun loadPackages() {
        viewModelScope.launch {
            try {
                val result = packageUseCase.execute()
                _packages.value = result
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}
```

## 🎨 Visual Consistency & Design Standards

### Core Design Philosophy
**CONSISTENT USER EXPERIENCE** across all sections. Every UI element should follow established patterns to create a cohesive, professional, and intuitive interface.

### Material Design 3 Compliance
**Principle**: Strict adherence to Material Design 3 guidelines with LineageOS branding.

**Implementation Rules**:
- **Color System**: LineageOS teal theme (`#167C80` primary, `#4DB6AC` dark primary)
- **Typography**: Material 3 scales (22sp titles, 16sp body, 14sp labels)
- **Spacing**: 8dp/16dp grid system, 12dp radius, 16dp padding
- **Components**: Material 3 Compose components only
- **Theme**: Light/dark mode with consistent color mapping

### Accessibility Requirements
- **Touch Targets**: Minimum 48dp for all interactive elements
- **Contrast**: WCAG AA compliance for all text and backgrounds
- **Focus Indicators**: Clear focus states for keyboard navigation
- **Screen Reader**: Proper content descriptions for all UI elements

## 🎯 Pragmatic Development Guidelines

### When to Apply Rules Strictly vs. Flexibly

**STRICT ENFORCEMENT** (Non-negotiable):
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

**De1984 Structure** (`utils/Constants.kt`):
```kotlin
object Constants {
    object UI {
        // Spacing (8dp grid system)
        val SPACING_TINY = 4.dp
        val SPACING_SMALL = 8.dp
        val SPACING_STANDARD = 16.dp
        val SPACING_LARGE = 24.dp

        // Icon sizes
        val ICON_SIZE_SMALL = 24.dp
        val ICON_SIZE_MEDIUM = 32.dp

        // Bottom sheet
        val BOTTOM_SHEET_CORNER_RADIUS = 28.dp
        val DRAG_HANDLE_WIDTH = 32.dp
        val DRAG_HANDLE_HEIGHT = 4.dp

        // Borders
        val BORDER_WIDTH_THIN = 1.dp
    }

    object Packages {
        // Package types (lowercase for consistency)
        const val TYPE_SYSTEM = "system"
        const val TYPE_USER = "user"

        // Package states
        const val STATE_ENABLED = "Enabled"
        const val STATE_DISABLED = "Disabled"
    }

    object App {
        const val NAME = "De1984"
        const val PACKAGE_NAME = "io.github.dorumrr.de1984"
    }
}
```

**Adding New Constants**:
1. Determine the appropriate category (UI, Packages, App, etc.)
2. Use descriptive names that indicate purpose and unit
3. Group related constants together
4. Add comments for non-obvious values

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
- ✅ Jetpack Compose (@Composable, lambdas, state)
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
- 🎯 **Constants over magic numbers** - Every hardcoded value should have a name
- 🔒 **Enums over strings** - Type safety prevents runtime errors
- 🧹 **Clean over clever** - Readable code beats clever code
- 🚀 **Simple over complex** - Solve today's problems, not tomorrow's hypotheticals
- ✅ **Build often** - Catch errors early, fix them immediately
- 📦 **Test release builds** - Minification can hide issues that only appear in release