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

**ALSO IMPORTANT**: When fixing a specific issue, make ONLY the minimal changes required to fix that exact issue. Do NOT:
- Refactor or clean up unrelated code
- Change import statements or fully qualified names
- Modify comments unless they are directly causing the issue
- Apply coding style preferences
- Make "improvements" that weren't requested
**If you notice other issues while working, MENTION them to the user and ASK if they want them fixed separately. Never bundle unrelated changes together.** Before making edits, explicitly state: "I will change ONLY lines X, Y, Z to fix [specific issue]" and get confirmation if there's any doubt.

## 🏗️ Core Development Philosophy

**MAXIMUM MAINTAINABILITY** is the primary goal. Every line of code must be written with future developers in mind, ensuring the codebase remains clean, understandable, and extensible.

**PRAGMATIC APPROACH**: We balance theoretical best practices with practical development needs. Rules should enhance productivity, not hinder it.

---

## 🔄 Development Workflow (3-Phase Process) - COMPULSORY!

> **Universal Principle** - Applicable to all projects, especially when working with LLMs

**Principle**: Every development task must follow a structured 3-phase process to ensure quality, maintainability, and correctness.

### Phase 1: Discovery (≥97% Confidence Required)

**Goal**: Achieve near-complete understanding before writing any code.

**Required Actions**:
1. **Use retrieval tools extensively**:
   - `codebase-retrieval` - Find relevant code across the project
   - `git-commit-retrieval` - Understand how similar changes were made
   - `view` - Examine specific files and implementations

2. **Check code directly**:
   - Implementation details (Kotlin classes, Compose UI, Room DAOs, ViewModels)
   - Architecture patterns (MVVM, Repository, Manual DI)
   - Existing similar features (how they're implemented)

3. **Verify understanding**:
   - Direct relationships (ViewModels → Repositories → DataSources → Database)
   - Dependencies (Kotlin coroutines, Flow, StateFlow, Room)
   - Business rules (firewall backends, package management, permissions)
   - Performance constraints (VPN startup < 2s, database queries < 50ms)
   - Security requirements (no breaches during backend switching, atomic transitions)

4. **Ask for clarification** if:
   - Requirements are contradictory or unclear
   - Multiple implementation approaches are possible
   - Security implications are uncertain
   - Performance impact is unknown

5. **Assess confidence**:
   - Requirements clarity (do I understand WHAT to build?)
   - Context completeness (do I understand HOW to build it?)
   - Risk assessment (what could go wrong?)

### Phase 2: Planning (≥96% Confidence Required)

**Goal**: Create a detailed, reviewable plan before implementation.

**Required Actions**:
1. **Create detailed plan**:
   - Files to create (with full paths and purposes)
   - Files to modify (with specific changes and reasons)
   - Files to delete (with removal justification)
   - Database migrations (if schema changes, with version bump)
   - Implementation order (respect dependencies, avoid breaking changes)
   - Risk mitigation strategies (fallbacks, error handling, testing)

2. **Validate plan**:
   - Business rule compliance (firewall behavior, backend selection, switch dependencies)
   - Security requirements (no breaches, atomic transitions, fail-safe fallbacks)
   - Performance targets achievable (VPN startup, database queries, UI responsiveness)
   - Architecture patterns respected (MVVM, Repository, Manual DI)
   - Cross-platform compatibility (API 21+, light/dark mode, all screen sizes)
   - Constants usage (all magic numbers/strings in `Constants.kt`)

3. **Present plan to user**:
   - Clear summary of changes
   - Rationale for each decision
   - Potential risks and mitigations
   - Wait for approval before proceeding

### Phase 3: Implementation

**Goal**: Execute the plan systematically and verify as you go.

**Required Actions**:
1. **Follow plan systematically**:
   - Respect module boundaries (ui/, domain/, data/, presentation/)
   - Follow Kotlin conventions (naming, formatting, idioms)
   - Use established patterns (MVVM, Repository, StateFlow)
   - Maintain constants in `Constants.kt`

2. **Verify as you go**:
   - Build after each logical change (`./gradlew assembleDebug`)
   - Test in light AND dark mode (MANDATORY)
   - Test on multiple Android versions (API 21, 26, 29+, 34+)
   - Verify performance targets (measure if critical)
   - Check security requirements (no breaches, atomic transitions)

3. **Create task documentation** (see Documentation section below)

### Confidence Scoring

Use this scale to assess your confidence before proceeding:

- **97-100%**: Full understanding, no ambiguity, ready to implement
- **90-96%**: Good understanding, minor gaps won't impact implementation
- **80-89%**: Adequate understanding, document assumptions clearly
- **<80%**: **DO NOT PROCEED** - Ask questions, gather more context

**Rule**: Never proceed to next phase if confidence is below threshold.

---

## 🤖 LLM Optimization

> **Universal Principle** - Applicable to all projects using LLM-assisted development

**Principle**: Keep the codebase LLM-friendly to maximize AI assistance effectiveness.

### Codebase Structure for LLMs

**Maintain**:
- Clear, consistent package structure
- Self-documenting code (meaningful names, clear logic)
- Centralized constants (`Constants.kt`)
- Consistent patterns (MVVM, Repository, StateFlow)

**Suggest optimizations** to minimize token consumption:
- Offer suggestions, **NEVER implement without approval**
- Identify redundant code or documentation
- Suggest consolidation opportunities
- Recommend archiving old documentation

### Exclude from LLM Scanning

**Build artifacts and generated code** (add to `.gitignore` and LLM ignore patterns):
```
build/
.gradle/
.kotlin/
app/build/
.idea/
local.properties
*.apk
*.aab
*.dex
*.class
*.so
*.jar
```

**Token savings**: ~99% reduction (typical Android project: 410 MB → ~500 KB) by excluding build artifacts.

### LLM-Friendly Code Practices

**DO**:
- ✅ Use descriptive names (no abbreviations unless standard)
- ✅ Keep functions small and focused (max 20-30 lines)
- ✅ Document complex logic with comments (explain WHY, not WHAT)
- ✅ Use consistent patterns throughout the codebase
- ✅ Centralize constants and configurations

**DON'T**:
- ❌ Use cryptic variable names (`x`, `tmp`, `data`)
- ❌ Create deeply nested logic (max 3 levels)
- ❌ Mix different patterns in similar code
- ❌ Scatter constants throughout the codebase
- ❌ Leave commented-out code

---

## 🎯 Core Principles

### DRY (Don't Repeat Yourself)
- Extract common functionality into reusable components
- All magic numbers/strings in `utils/Constants.kt`
- Use enums for type comparisons, not string matching
- **Rule**: If used in Kotlin code, make it a constant. XML layouts can use hardcoded dp values.

### KISS (Keep It Simple, Stupid)
- Choose simplest approach that solves the problem
- Don't build for hypothetical future requirements
- Use descriptive, self-documenting names
- Each function/class does one thing well (max 20-30 lines)

### Clean Code
- Meaningful names that express purpose
- Explain WHY in comments, not WHAT
- Proper error handling with meaningful messages

## 📁 Project Structure

**Single-module architecture** (pragmatic for current scope):
```
io.github.dorumrr.de1984/
├── ui/                    // UI Layer (Compose/XML)
├── domain/                // Business Logic (models, repository interfaces, use cases)
├── data/                  // Data Layer (repositories, datasources, Room database)
├── presentation/          // ViewModels
├── De1984Dependencies.kt  // Manual DI (ServiceLocator)
└── utils/                 // Constants.kt, Extensions.kt, utilities
```

**Documentation organization**:
- **Root**: README.md, RULES.md, PRD.md, FIREWALL.md

**Architecture**: MVVM + Repository + Manual DI (ServiceLocator)

## 🎨 UI Standards

### Dialog Components
- **Simple dialogs**: Use `StandardDialog` (confirmations, errors, info)
- **Complex flows**: Use `PermissionSetupDialog` (permission setup, onboarding)
- **Action sheets**: Use `BottomSheetDialog` (multiple actions)
- **Never use**: `AlertDialog.Builder` or `MaterialAlertDialogBuilder` directly

## 🌐 Cross-Platform Compatibility (CRITICAL)

**Test ALL UI changes on**:
- API 21, 26, 29+, 34+ (oldest to newest)
- Light AND dark mode (MANDATORY)
- Phone and tablet screen sizes

### Implementation Rules

**Icons**: White base + programmatic tinting (never hardcoded colors or `android:tint` in XML)
```kotlin
// ✅ GOOD: White base in XML, tint programmatically
<vector android:fillColor="@android:color/white">

val iconColor = if (isDarkMode) Color.WHITE else Color.BLACK
icon.colorFilter = PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
```

**Colors**: Define in `values/colors.xml` (light) and `values-night/colors.xml` (dark)

**Text**: Use `sp` for text sizes, `dp` for dimensions

**Touch targets**: Minimum 48dp for all interactive elements

**Theme attributes**: Use `?attr/colorSurface`, `?attr/colorOnSurface` (never hardcoded colors)

### Material Design 3
- **Color**: Teal theme (`#167C80` primary)
- **Typography**: 22sp titles, 16sp body, 14sp labels
- **Spacing**: 8dp/16dp grid, 12dp radius, 16dp padding
- **Accessibility**: WCAG AA contrast, proper content descriptions

## 🔒 Type Safety

**Use enums over strings**:
- Always use enums for fixed sets (PackageType, PackageState, FirewallBackendType)
- Never compare enum values using `.name` property
- Use constants for string representations that need persistence

**Benefits**: Compile-time safety, IDE autocomplete, safer refactoring

## 🚫 Code Cleanliness

- Remove deprecated code after one release cycle (never keep "just in case")
- No commented-out code (use version control)
- All constants in `utils/Constants.kt`

## 🧪 Build & Test

**Build after each logical change**:
```bash
./gradlew assembleDebug      # After each feature/fix
./gradlew build              # Before committing
./gradlew clean build        # Full verification
```

**Never test dangerous operations** (disable/enable/uninstall/force-stop) without user permission

---

## 📝 Pre-Commit Checklist

**Compatibility (CRITICAL)**:
- [ ] Tested light AND dark mode
- [ ] Tested API 21, 26, 29+, 34+
- [ ] Icons: white base + programmatic tinting (no `android:tint`)
- [ ] Touch targets: minimum 48dp
- [ ] Theme-aware attributes (no hardcoded colors)

**Code Quality**:
- [ ] Build successful (`./gradlew assembleDebug`)
- [ ] Constants in `Constants.kt` (no magic numbers/strings)
- [ ] Enums over strings (type safety)
- [ ] No deprecated code
- [ ] Dialogs use `StandardDialog` or `PermissionSetupDialog`

---

## 📦 ProGuard

**Status**: ✅ Enabled (`app/proguard-rules.pro`)
- ~30-40% APK size reduction
- Auto-handles: ViewModels, Room, View Binding, Kotlin features, Coroutines
- F-Droid compliant (no analytics/tracking)

**Add keep rules only if**: Using reflection, third-party library with reflection, or `ClassNotFoundException` in release

---

# Key Mantras

- 🌐 **Test light/dark modes** - MANDATORY for all UI changes
- 🎨 **Icons**: White base + programmatic tinting
- 📱 **Touch targets**: Minimum 48dp
- 🎯 **Constants over magic numbers**
- 🔒 **Enums over strings**
- 🧹 **Clean over clever**
- ✅ **Build often**