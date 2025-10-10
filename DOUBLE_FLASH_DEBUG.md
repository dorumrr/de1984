# Double Flash Debug Guide

## Problem Description
When switching between Firewall and Packages tabs, there's a double flash where:
1. First, User packages are shown
2. Then, the filtered packages are shown

This creates a jarring visual experience.

## How to Debug

### Step 1: Install the Debug Build
```bash
./gradlew installDebug
```

### Step 2: Clear Logcat
```bash
adb logcat -c
```

### Step 3: Start Logcat Monitoring
```bash
adb logcat -s PackagesViewModel:D PackagesFragmentViews:D GetPackagesUseCase:D PackageRepository:D FilterChipsHelper:D | tee logcat_output.txt
```

### Step 4: Reproduce the Issue
1. Open the app
2. Go to Firewall tab
3. Switch to Packages tab (observe the flash)
4. Note what you see

### Step 5: Analyze the Logs

Look for this sequence in the logs:

```
PackagesViewModel: >>> ViewModel INIT - About to call loadPackages()
PackagesViewModel: >>> loadPackages called with filter: type=User, state=null
GetPackagesUseCase: >>> getFilteredByState: type=User, state=null
PackageRepository: >>> getPackagesByType called: USER
PackageRepository: >>> DataSource emitted: X entities
PackageRepository: >>> After mapping: Y packages
PackageRepository: >>> getPackages() emitted: Y packages (before type filter)
PackageRepository: >>> After type filter (USER): Z packages
GetPackagesUseCase: >>> baseFlow emitted: Z packages
PackagesViewModel: >>> loadPackages onEach: received Z packages
PackagesViewModel: >>> EMITTING STATE: Z packages, filter: type=User, state=null
PackagesFragmentViews: updateUI CALLED: Z packages, loading=false, typeFilter=User, stateFilter=null
```

### What to Look For:

1. **How many times does "loadPackages called" appear?**
   - Should be: 1 time (on init)
   - If more: Something is triggering extra loads

2. **How many times does "EMITTING STATE" appear?**
   - Should be: 1 time (with the correct filter)
   - If more: Multiple state emissions are happening

3. **How many times does "updateUI CALLED" appear?**
   - Should be: 1 time (with the correct packages)
   - If more: UI is updating multiple times

4. **Are there any "setPackageTypeFilter called" logs?**
   - Should be: 0 times (no filter changes on init)
   - If present: Something is changing the filter after init

5. **Check the filter values in each emission:**
   - All should show: `type=User, state=null`
   - If different: Filter is changing between emissions

## Common Causes

### Cause 1: Room Flow Emitting Twice
Room database Flows emit when:
- First collected (initial data)
- Database changes (insert/update/delete)

**Check:** Look for any database writes between the two emissions

### Cause 2: Filter Change Triggering Reload
The fragment might be calling `setPackageTypeFilter()` after init

**Check:** Look for "setPackageTypeFilter called" in logs

### Cause 3: Fragment Recreation
The fragment might be getting recreated, causing ViewModel to reinitialize

**Check:** Look for multiple "ViewModel INIT" logs

### Cause 4: StateFlow Collection Starting Late
The fragment might start collecting the StateFlow after data has already loaded

**Check:** Compare timestamps of "EMITTING STATE" vs "updateUI CALLED"

## Expected Behavior

**Correct sequence (no double flash):**
```
1. ViewModel INIT
2. loadPackages called (filter: User)
3. DataSource emits packages
4. Repository filters by type
5. UseCase emits filtered packages
6. ViewModel emits state (1 time)
7. Fragment updateUI (1 time)
8. UI shows packages (1 time)
```

**Incorrect sequence (double flash):**
```
1. ViewModel INIT
2. loadPackages called (filter: User)
3. DataSource emits packages
4. Repository filters by type
5. UseCase emits filtered packages
6. ViewModel emits state (1st time) ← First flash
7. Fragment updateUI (1st time)
8. [SOMETHING TRIGGERS ANOTHER LOAD]
9. loadPackages called again
10. ViewModel emits state (2nd time) ← Second flash
11. Fragment updateUI (2nd time)
```

## Next Steps

After collecting the logs, share them and I'll identify the exact cause and implement the fix.

