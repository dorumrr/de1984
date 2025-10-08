# Incremental XML Views Migration Plan

**Strategy:** Migrate ONE screen at a time, test thoroughly, commit, then move to next.

**Branch:** `feature/xml-views-incremental`

---

## 🎯 **Migration Phases**

### **Phase 0: Setup Infrastructure** (30 min)
- Add Views dependencies (keep Compose)
- Enable ViewBinding
- Create BaseFragment
- **TEST:** App still works with Compose
- **COMMIT:** "Phase 0: Add Views infrastructure alongside Compose"

### **Phase 1: Migrate AcknowledgementsFragment** (1 hour)
- Simplest screen (static data, no ViewModel)
- Create XML layout
- Create Fragment with ViewBinding
- Create LibraryAdapter
- **TEST:** Acknowledgements screen works
- **COMMIT:** "Phase 1: Migrate AcknowledgementsFragment to XML Views"

### **Phase 2: Migrate SettingsFragment** (2 hours)
- Simple screen with ViewModel
- Create XML layout
- Create Fragment with ViewModel
- Test settings persistence
- **TEST:** Settings screen works, saves data
- **COMMIT:** "Phase 2: Migrate SettingsFragment to XML Views"

### **Phase 3: Migrate PackagesFragment** (3 hours)
- Complex screen with RecyclerView
- Create XML layout
- Create Fragment with ViewModel
- Create PackageAdapter with DiffUtil
- Test filter chips
- **TEST:** Packages screen shows data, filters work
- **COMMIT:** "Phase 3: Migrate PackagesFragment to XML Views"

### **Phase 4: Migrate FirewallFragment** (3 hours)
- Most complex screen
- Create XML layout
- Create Fragment with ViewModel
- Create NetworkPackageAdapter
- Test firewall toggle
- **TEST:** Firewall screen works, toggle works
- **COMMIT:** "Phase 4: Migrate FirewallFragment to XML Views"

### **Phase 5: Remove Compose** (30 min)
- Remove Compose dependencies
- Remove Compose files
- Update MainActivity
- **TEST:** Full app works without Compose
- **COMMIT:** "Phase 5: Remove Compose dependencies"

---

## 📋 **Detailed Phase Breakdown**

### **PHASE 0: Setup Infrastructure**

**Goal:** Add Views infrastructure without breaking Compose

**Steps:**
1. Add Views dependencies to `build.gradle.kts`
   ```kotlin
   // Keep Compose dependencies
   // Add Views dependencies
   implementation("androidx.appcompat:appcompat:1.6.1")
   implementation("com.google.android.material:material:1.11.0")
   implementation("androidx.constraintlayout:constraintlayout:2.1.4")
   implementation("androidx.recyclerview:recyclerview:1.3.2")
   implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
   implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
   ```

2. Enable ViewBinding
   ```kotlin
   buildFeatures {
       compose = true
       viewBinding = true
   }
   ```

3. Create `BaseFragment.kt`
   ```kotlin
   abstract class BaseFragment<VB : ViewBinding> : Fragment() {
       private var _binding: VB? = null
       protected val binding get() = _binding!!
       
       abstract fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB
       
       override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
           _binding = getViewBinding(inflater, container)
           return binding.root
       }
       
       override fun onDestroyView() {
           super.onDestroyView()
           _binding = null
       }
   }
   ```

4. **TEST CHECKLIST:**
   - [ ] App builds successfully
   - [ ] App launches without crashes
   - [ ] All Compose screens still work
   - [ ] No new warnings/errors

5. **COMMIT:** `git commit -m "Phase 0: Add Views infrastructure alongside Compose"`

---

### **PHASE 1: Migrate AcknowledgementsFragment**

**Goal:** Migrate simplest screen to prove the pattern works

**Why this screen first?**
- ✅ No ViewModel needed (static data)
- ✅ Simple RecyclerView
- ✅ No complex state management
- ✅ Easy to verify it works

**Steps:**

1. Create `res/layout/fragment_acknowledgements.xml`
2. Create `res/layout/item_library.xml`
3. Create `LibraryAdapter.kt` with DiffUtil
4. Create new `AcknowledgementsFragment.kt` extending `BaseFragment`
5. Add fragment to navigation graph
6. Update navigation to use new fragment

**TEST CHECKLIST:**
- [ ] Screen shows 10 libraries
- [ ] List scrolls smoothly
- [ ] Back button works
- [ ] No crashes
- [ ] No memory leaks

**COMMIT:** `git commit -m "Phase 1: Migrate AcknowledgementsFragment to XML Views"`

---

### **PHASE 2: Migrate SettingsFragment**

**Goal:** Prove ViewModel integration works

**Why this screen second?**
- ✅ Has ViewModel (tests integration)
- ✅ Simple UI (switches, buttons)
- ✅ Tests StateFlow observation
- ✅ Tests data persistence

**Steps:**

1. Create `res/layout/fragment_settings.xml`
2. Create new `SettingsFragment.kt` with ViewModel
3. Implement StateFlow observation
4. Test settings persistence
5. Update navigation

**TEST CHECKLIST:**
- [ ] Settings screen displays
- [ ] Switches work
- [ ] RadioGroup works
- [ ] Settings persist after restart
- [ ] Navigation to Acknowledgements works
- [ ] No infinite loops
- [ ] No crashes

**COMMIT:** `git commit -m "Phase 2: Migrate SettingsFragment to XML Views"`

---

### **PHASE 3: Migrate PackagesFragment**

**Goal:** Prove complex RecyclerView works

**Why this screen third?**
- ✅ Complex RecyclerView with real data
- ✅ Filter chips
- ✅ Tests adapter updates
- ✅ Tests DiffUtil

**Steps:**

1. Create `res/layout/fragment_packages.xml`
2. Create `res/layout/item_package.xml`
3. Create `PackageAdapter.kt` with DiffUtil
4. Create new `PackagesFragment.kt`
5. Implement filter chips
6. Test data loading

**TEST CHECKLIST:**
- [ ] Packages list shows data
- [ ] Filter chips work (All/User/System/Enabled/Disabled)
- [ ] List updates when filter changes
- [ ] Scrolling is smooth
- [ ] Package icons load
- [ ] Status indicators show correct colors
- [ ] No crashes
- [ ] No memory leaks

**COMMIT:** `git commit -m "Phase 3: Migrate PackagesFragment to XML Views"`

---

### **PHASE 4: Migrate FirewallFragment**

**Goal:** Migrate most complex screen

**Why this screen last?**
- ⚠️ Most complex
- ⚠️ Firewall toggle with VPN permission
- ⚠️ Network package list
- ⚠️ Multiple state updates

**Steps:**

1. Create `res/layout/fragment_firewall.xml`
2. Create `res/layout/item_network_package.xml`
3. Create `NetworkPackageAdapter.kt`
4. Create new `FirewallFragment.kt`
5. Implement firewall toggle with proper state management
6. Implement filter chips
7. Test VPN permission flow

**TEST CHECKLIST:**
- [ ] Firewall screen displays
- [ ] Network packages list shows data
- [ ] Firewall toggle works
- [ ] Toggle state persists
- [ ] VPN permission request works
- [ ] Filter chips work (All/User/System)
- [ ] List updates correctly
- [ ] No infinite loops on toggle
- [ ] No crashes

**COMMIT:** `git commit -m "Phase 4: Migrate FirewallFragment to XML Views"`

---

### **PHASE 5: Remove Compose**

**Goal:** Clean up Compose dependencies

**Steps:**

1. Remove Compose dependencies from `build.gradle.kts`
2. Remove Compose compiler configuration
3. Delete old Compose UI files
4. Update MainActivity to use Views
5. Clean build

**TEST CHECKLIST:**
- [ ] App builds without Compose
- [ ] All screens work
- [ ] Navigation works
- [ ] No crashes
- [ ] APK size reduced

**COMMIT:** `git commit -m "Phase 5: Remove Compose dependencies - Migration complete"`

---

## 🔍 **Testing Protocol for Each Phase**

After each phase, run this checklist:

### **Build Test**
```bash
./gradlew clean assembleDebug
```
- [ ] Build succeeds
- [ ] No new warnings
- [ ] No errors

### **Install Test**
```bash
./dev.sh install
```
- [ ] Installs successfully
- [ ] Launches without crash

### **Manual Test**
- [ ] Navigate to migrated screen
- [ ] Test all interactions
- [ ] Test back navigation
- [ ] Test configuration changes (rotate)
- [ ] Check logcat for errors

### **Memory Test**
- [ ] No memory leaks (check with LeakCanary if needed)
- [ ] Smooth scrolling
- [ ] No ANRs

---

## 🚨 **Rollback Plan**

If any phase fails:

```bash
# Rollback to previous phase
git reset --hard HEAD~1

# Or create a fix
git commit -m "Fix: [describe issue]"
```

---

## 📊 **Progress Tracking**

| Phase | Status | Time Estimate | Actual Time | Commit Hash |
|-------|--------|---------------|-------------|-------------|
| Phase 0: Infrastructure | ⏳ Not Started | 30 min | - | - |
| Phase 1: Acknowledgements | ⏳ Not Started | 1 hour | - | - |
| Phase 2: Settings | ⏳ Not Started | 2 hours | - | - |
| Phase 3: Packages | ⏳ Not Started | 3 hours | - | - |
| Phase 4: Firewall | ⏳ Not Started | 3 hours | - | - |
| Phase 5: Remove Compose | ⏳ Not Started | 30 min | - | - |
| **TOTAL** | **⏳ Not Started** | **10 hours** | **-** | **-** |

---

## 🎯 **Success Criteria**

Migration is complete when:
- [ ] All screens migrated to XML Views
- [ ] All functionality works
- [ ] No Compose dependencies
- [ ] App passes all tests
- [ ] APK builds reproducibly for F-Droid

---

**Ready to start Phase 0?**

