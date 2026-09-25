# Firewall Backend Behavior Reference

This document defines how each firewall backend works in De1984.

---

## Backend Selection Logic

The app can operate in different modes: **AUTO** (automatic selection) or **MANUAL** (force specific backend). Manual mode is sticky—if something goes wrong, the firewall surfaces the error and waits for user or privilege recovery instead of silently returning to AUTO.

### AUTO Mode (Default)

When in AUTO mode, the app selects the best available backend using this priority order:

1. **iptables** (highest priority)
   - **Requires**: Root access OR Shizuku in root mode
   - **Check**: root or Shizuku permission must be present (a Shizuku-only device must have Shizuku started in root mode), then `iptables --version` is executed and must exit 0
   - **If available**: Use iptables backend ✅
   - **If not available**: Try next backend ⬇️

2. **ConnectivityManager** (medium priority)
   - **Requires**: Shizuku (ADB or root mode) AND Android 13+
   - **Check**: Verify Shizuku is running and Android version >= 33
   - **If available**: Use ConnectivityManager backend ✅
   - **If not available**: Fall back to VPN ⬇️

3. **VPN** (fallback, always available)
   - **Requires**: Only VPN permission (user grants via system dialog)
   - **Not unconditional**: the VPN slot must be free. If a third-party VPN is connected and De1984 has no root/Shizuku, the start is refused with "Another VPN is active" and the firewall goes to `Error` instead of taking the slot
   - **Use as last resort**: When no privileged access available ✅

### Manual Mode

User can force a specific backend from Settings.
**Important**: The dropdown lists every backend. Ones that cannot run on this device are shown disabled, with a line stating what they require, so the user can see why a backend is unavailable rather than wondering where it went.

**Available backends check**:
- **AUTO**: Always available (always shown, and is the default)
- **VPN**: Always available (always shown in dropdown)
- **iptables**: Enabled if root access OR Shizuku in root mode is available
- **ConnectivityManager**: Enabled if Shizuku is available AND Android 13+
- **NetworkPolicyManager**: Enabled if Shizuku is available. No Android version requirement. On ROMs that do not implement `POLICY_REJECT_ALL` it can only block metered background data, not WiFi.

**User selection**:
- **AUTO**: Let the app pick the best available backend (see AUTO Mode above)
- **Force VPN**: Always use VPN backend (even if root/Shizuku available)
- **Force iptables**: Only use iptables (only selectable if root/Shizuku root mode available)
- **Force ConnectivityManager**: Only use ConnectivityManager (only selectable if Shizuku available and Android 13+)
- **Force NetworkPolicyManager**: Only use NetworkPolicyManager (only selectable if Shizuku is available)

**Note**: AUTO never selects NetworkPolicyManager. Its priority chain is iptables → ConnectivityManager → VPN. NetworkPolicyManager is reachable only by choosing it here.

If a manually selected backend becomes unavailable (e.g., Shizuku stops, user revokes root), the firewall enters an **error** state and stays on the chosen backend. The user must restore the required privileges or manually pick another backend. AUTO mode continues to fall back automatically.

### Why This Priority Order?

1. **iptables is best**: Full granular control, no VPN slot occupied, kernel-level blocking
2. **ConnectivityManager is second**: No VPN slot occupied, but no granular control
3. **VPN is fallback**: Works everywhere but occupies VPN slot and shows VPN icon

### Backend Switching

When backend availability changes (e.g., user grants Shizuku, device gets rooted, Shizuku crashes, SuperUser permission is revoked, etc), the app must switch backends seamlessly without creating security breaches.

**Critical Security Rule**: When switching backends, there must be NO gap where apps are unblocked. The transition must be atomic and as fail-safe as possible. VPN and ConnectivityManager decide only apps in De1984's own profile, so while either runs, the rules of apps in other profiles are not enforced.

**Switching scenarios**:

1. **From VPN to iptables** (upgrade):
   - Start iptables backend first, apply all rules
   - Wait for iptables rules to be active
   - Only then stop VPN backend
   - Maintain granular control ✅

2. **From VPN to ConnectivityManager** (upgrade):
   - Start ConnectivityManager backend first, apply all rules (convert partial to full blocking)
   - Wait for ConnectivityManager rules to be active
   - Only then stop VPN backend
   - Convert partial blocking to full blocking ⚠️

3. **From iptables to ConnectivityManager** (downgrade):
   - Start ConnectivityManager backend first, apply all rules (convert partial to full blocking)
   - Wait for ConnectivityManager rules to be active
   - Only then stop iptables backend
   - Convert partial blocking to full blocking ⚠️

4. **To VPN (fallback)** - CRITICAL:
   - **Scenario**: iptables or ConnectivityManager backend fails (Shizuku crashes, root lost, etc.)
   - **Security risk**: If we just stop the old backend, ALL apps become unblocked until VPN starts
   - **Safe transition**:
     1. Detect backend failure immediately (monitor Shizuku state, test iptables commands -  make sure here we have the most compatible check method for most android versions and privilege tools)
     2. Start VPN backend FIRST, establish VPN tunnel with all blocked apps
     3. Wait for VPN to be fully established (VPN icon appears, interface is up)
     4. Only then clean up old backend (remove iptables rules, stop ConnectivityManager)
     5. If VPN fails to start, keep trying and show critical warning to user
   - **Fail-safe**: If VPN cannot be established, the firewall is DOWN and user MUST be notified with persistent warning
   - Maintain granular control ✅

**Monitoring for automatic fallback**:
- Continuously monitor Shizuku state (if using ConnectivityManager or iptables with Shizuku)
- Continuously monitor root availability (if using iptables with root)
- If backend becomes unavailable, immediately trigger fallback to VPN
- Never leave a gap where firewall is down if possible

### VPN Fallback Without Permission

When a privileged backend fails and VPN permission is not granted:

1. **Immediate Actions**:
   - Show high-priority notification requesting VPN permission
   - Set firewall state to `Error` with clear warning message
   - Update UI to show "Firewall not running" with error indicator
   - Mark firewall as DOWN (`isFirewallDown = true`)

2. **Background Monitoring**:
   - Continue monitoring for privileged backend availability (root/Shizuku may return)
   - Listen for VPN permission grant (user taps notification)
   - Do NOT attempt to start VPN without permission - it will fail silently

3. **Security Warning**:
   - Firewall is DOWN - all apps are UNBLOCKED
   - User MUST be warned prominently in UI and notification
   - Notification should be persistent until resolved

4. **Recovery Paths**:
   - **Path A**: User grants VPN permission via notification → Automatically start VPN backend
   - **Path B**: Privileged backend becomes available again → Automatically switch to privileged backend
   - **Path C**: User opens app and manually starts firewall → Request VPN permission via system dialog

**Critical Rule**: Never silently fail. If firewall cannot be started, user must be explicitly warned that protection is OFF.

### Privilege Monitoring Strategy

**Adaptive Monitoring Intervals**:

The app uses adaptive health check intervals to balance responsiveness and battery efficiency:

- **Initial interval**: 15 seconds (`BACKEND_HEALTH_CHECK_INTERVAL_INITIAL_MS`)
- **After 10 consecutive successful checks**: Increase to 60 seconds (`BACKEND_HEALTH_CHECK_INTERVAL_STABLE_MS`). This is the stable state and the only step-up - there are no further tiers
- **On any failure**: Reset to 15 seconds immediately and reset the success counter to 0 (fast recovery)
- **On a health-check exception**: neither the interval nor the counter is reset, and no fallback is triggered - exceptions are treated as possibly transient

**What to Monitor**:

1. **Root Status** (for iptables backend):
   - Run `id` on the existing cached libsu root shell, never a fresh `su -c id` - spawning a new `su` is what triggers Magisk's toast on every check
   - Treat root as valid only if the output contains `uid=0`
   - Only when no live cached root shell exists does it fall back to `Shell.getShell()`, which may create one (30 second timeout, set in `De1984Application`)
   - Cache the result: `checkRootStatus()` skips the check once `ROOTED_WITH_PERMISSION`; health monitoring calls `forceRecheckRootStatus()` to bypass that cache and catch revocation

2. **Shizuku Status** (for ConnectivityManager/iptables backends):
   - Check if Shizuku binder is available
   - Verify permission is granted
   - Listen to Shizuku lifecycle callbacks (binder received/dead)
   - Check Shizuku UID to determine root mode (UID 0) vs ADB mode (UID 2000)

3. **Backend Health**:
   - Verify backend can still execute commands
   - For iptables: Test `iptables --version` (exit code 0)
   - For ConnectivityManager: Test Shizuku shell command execution
   - For VPN: Check if VPN interface is active

**When to Trigger Fallback**:

- Immediately on **first** health check failure
- Do NOT wait for multiple failures (security-critical)
- Atomic switch to prevent security gap
- If manual mode backend fails, keep firewall in ERROR state (no automatic fallback)

**Monitoring Lifecycle**:

- Start monitoring when firewall starts
- Stop monitoring when firewall stops
- Continue monitoring during backend switches
- Privileged backends: Monitor in PrivilegedFirewallService (foreground service)
- VPN backend: Monitor internally in FirewallVpnService

### Screen-off switch (every backend)

Each app has an "Allow while screen off" switch. Turned off, the app is blocked whenever the screen is off, by the same mechanism and with the same limits as that backend's other blocks (apps a backend leaves open stay open, and NetworkPolicyManager may block metered background data only; see each backend's section). While the screen is on the switch adds no block. It is De1984's switch, not Android's per-app "Background data" setting; on NetworkPolicyManager, though, a block is a per-UID policy, and its metered-only fallback is the same value that setting writes. The app sheet shows it unless the app is fully blocked, protected or refused. Every backend blocks per UID, so one app's setting applies to its whole UID (see "Shared UIDs" in section 2).

---

## 1. VPN Backend

**Requirements:** VPN permission only (no root, no Shizuku)

**Characteristics:**
- Shows VPN icon in status bar
- Occupies VPN slot (cannot use another VPN simultaneously)
- Supports granular per-network rules (WiFi/Mobile/Roaming)
- Builds its app list only from De1984's own profile: no app from a work, clone or other profile is added, whatever its rule says
- Survives reboot (service restarts automatically)

**How it works:**

Apps that should be blocked are added to the VPN tunnel. Their traffic goes through the VPN where packets are dropped. Apps that should be allowed are NOT added to the VPN, so they bypass it completely and use the normal network connection. Android routes the whole UID of an added app into the tunnel, so De1984 decides per UID: it adds every app of a blocked UID and none of an allowed one (see "Shared UIDs" in section 2).

**Critical rule:** If zero apps need blocking, the VPN must NOT be started at all. If we fiddle with switches and we reach to all Allowed, same thing, no need to have firewall up. Android's default behavior is to route ALL apps through the VPN if no apps are explicitly added (blocked) and starting a VPN with zero apps would accidentally block everything.

**Switch dependencies:**
- **Roaming requires Mobile**: Roaming is a state of mobile data when outside home network. If user enables Roaming block while Mobile is allowed, Mobile must also be blocked. If user disables Mobile block while Roaming is blocked, Roaming must also be allowed.
- **Logic**: Roaming cannot be blocked independently - it's always "Mobile + Roaming" or neither.

Both modes below decide only apps in De1984's own profile (see Characteristics).

**Block All mode:**
- Apps without rules: Blocked (added to VPN, traffic dropped)
- Apps with explicit "allow" rule for current network: Allowed (bypass VPN)
- Apps with explicit "block" rule for current network: Blocked (added to VPN)

**Allow All mode:**
- Apps without rules: Allowed (bypass VPN)
- Apps with explicit "allow" rule for current network: Allowed (bypass VPN)
- Apps with explicit "block" rule for current network: Blocked (added to VPN)

**Network changes:**

When switching between WiFi, Mobile, or Roaming, the VPN recalculates which apps should be blocked based on their per-network rules. It then rebuilds the VPN tunnel with the new app list. The new VPN is established BEFORE closing the old one to prevent Android from killing the service.

**Example (Block All, WiFi):**
- Chrome (no rule) → Blocked
- Firefox (WiFi=allowed, Mobile=blocked) → Allowed on WiFi
- Telegram (WiFi=blocked, Mobile=allowed) → Blocked on WiFi

When switching to Mobile data, Firefox becomes blocked and Telegram becomes allowed. The VPN rebuilds with the new configuration.

---

## 2. iptables Backend

**Requirements:** Root access (or Shizuku in root mode)

**Characteristics:**
- No VPN icon
- Does not occupy VPN slot (can use real VPN)
- Supports granular per-network rules (WiFi/Mobile/Roaming)
- Rules lost on reboot (must be reapplied on boot)
- App must be active even after a restart to ensure Firewall is protecting (if it was enabled)

**How it works:**

Uses Linux kernel firewall (iptables/ip6tables) to block network traffic by app UID. Creates firewall rules that drop all IPv4 and IPv6 packets for blocked app UIDs. Apps that share a UID share one verdict (see "Shared UIDs" below).

**Switch dependencies:**
- **Roaming requires Mobile**: Same as VPN backend. If user enables Roaming block while Mobile is allowed, Mobile must also be blocked. If user disables Mobile block while Roaming is blocked, Roaming must also be allowed.

**MODES: CRITICAL TO ALWAYS TAKE INTO CONSIDERATION** 
**Block All mode:**
- Apps without rules: Blocked (firewall rules added for their UID)
- Apps with explicit "allow" rule for current network: Allowed (no firewall rules)
- Apps with explicit "block" rule for current network: Blocked (firewall rules added)

**Allow All mode:**
- Apps without rules: Allowed (no firewall rules)
- Apps with explicit "allow" rule for current network: Allowed (no firewall rules)
- Apps with explicit "block" rule for current network: Blocked (firewall rules added)

**Network changes:**

When switching networks, recalculates which UIDs should be blocked based on per-network rules. Removes firewall rules for UIDs that should no longer be blocked. Adds firewall rules for UIDs that should now be blocked. Uses diff-based updates for efficiency.

**Shared UIDs (every backend):**

Multiple apps can share the same UID, and Android enforces every backend's block per UID: iptables and NetworkPolicyManager act on the UID itself, and a ConnectivityManager command or a VPN tunnel entry names one app but Android applies it to that app's whole UID. So each UID gets one verdict, decided the same way on every backend. Each backend's Block All and Allow All bullets describe an ordinary app alone in its UID; a protected app, and every app that shares its UID, follow this section instead. It applies only to UIDs a backend decides: VPN and ConnectivityManager decide only apps in De1984's own profile, and ConnectivityManager and NetworkPolicyManager never act on a system UID (see their sections).

- **System-critical and VPN app exemption** (applies while Settings > "Allow Firewall Critical Packages" is OFF, the default): if ANY app with a UID is system-critical or a VPN app, the ENTIRE UID is exempted from blocking. An app whose details cannot be read is not recognised as a VPN app, so its UID is not exempted. Blocking such a UID would cut the protected app too; the price is that an ordinary app sharing it cannot be blocked either. When that setting is ON the exemption is dropped — explicit rules on such UIDs are applied — and only UIDs with no rule at all are still left allowed in Block All mode.

- **A UID with at least one enabled rule**: its rules decide for every app in it, and the Block All default no longer applies. The UID is blocked wherever ANY of its rules blocks: per network on iptables and VPN, on every network on ConnectivityManager and NetworkPolicyManager, and while the screen is off when a rule's screen-off switch is off. An app with no rule of its own follows its neighbours' rules, in both modes.

- **A UID with no rule**: the default policy decides. Blocked in Block All mode, allowed in Allow All mode. The exception is a protected UID with the setting ON, which Block All leaves allowed (see the first bullet).

An app whose own switches differ from what a neighbour's rule makes its UID do shows the shared-UID note in its sheet.

**Example (Block All, WiFi):**
- Chrome (UID 10100, no rule) → Blocked
- Firefox (UID 10101, WiFi=allowed) → Allowed on WiFi
- Telegram (UID 10102, WiFi=blocked) → Blocked on WiFi

When switching to Mobile, Firefox becomes blocked and Telegram becomes allowed. Firewall rules are updated accordingly.

---

## 3. ConnectivityManager Backend

**Requirements:** Shizuku + Android 13+

**Characteristics:**
- No VPN icon
- Does not occupy VPN slot (can use real VPN)
- Does NOT support granular rules (all-or-nothing blocking only)
- Rules lost on reboot (must be reapplied on boot)

**How it works:**

Uses Android system commands to enable or disable networking for an app. Android applies each command to the app's whole UID, and to that app in every profile (measured on Android 15 and 16), so De1984 sends the same command to every app of a UID and names only apps in its own profile (see "Shared UIDs" in section 2). This is all-or-nothing: an app is either allowed on ALL networks or blocked on ALL networks. Cannot block an app on WiFi while allowing it on Mobile.

**Why no granular control:**

The ConnectivityManager firewall chain API operates at the app level, not the network interface level. When you disable networking for an app, Android blocks it from accessing ANY network interface (WiFi, Mobile, VPN, Ethernet, etc.). There is no API to selectively block only certain network types. This is a fundamental limitation of the Android ConnectivityManager API.

**Switch dependencies:**
- **No WiFi/Mobile/Roaming switches**: This backend cannot do per-network blocking, so the UI does not offer separate WiFi/Mobile/Roaming controls. The single-app sheet and the multi-select sheet each show one "Internet Access" toggle that sets all three flags together; the single-app sheet also has the screen-off switch (see "Screen-off switch" above). The list row still draws three network icons as indicators, but a tap on any of them applies to all three.
- **Migration from granular backends**: When switching from VPN or iptables (which have separate switches), convert rules using this logic:
  - **Partially blocked** (1-2 networks blocked): Treat as **fully blocked** (block all networks)
  - **Mixed** (some networks blocked, some allowed): Treat as **fully blocked** — `migrateRulesToSimple` sets all three flags to `true` whenever any one of them is blocked. Migration never converts a rule to fully allowed.
  - **Fully blocked** (all 3 networks blocked): Keep as fully blocked
  - **Fully allowed** (all 3 networks allowed): Keep as fully allowed

Both modes below decide only apps in De1984's own profile (see "How it works").

**Block All mode:**
- Apps without rules: Blocked on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks

**Allow All mode:**
- Apps without rules: Allowed on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks

**Network changes:**

Rules are re-applied on every network change: `PrivilegedFirewallService` observes the network type and calls `applyRules`. The network type does **not** affect the outcome for this backend. `applyRules` evaluates `rule.isBlockedOnAnyNetwork()`, so an app is blocked if its rule blocks on WiFi, Mobile **or** Roaming, whichever network is live.

This is what makes "all-or-nothing" true in practice. A non-uniform rule — one that survived a switch from VPN or iptables where `migrateRulesToSimple` did not run — used to leave the app blocked on one network and open on another, while the single Internet Access toggle said blocked either way. It now resolves toward blocking. LAN is excluded from that test: it is a separate axis that only iptables enforces.

**Example (Block All):**
- Chrome (no rule) → Blocked everywhere
- Firefox (has "allow" rule) → Allowed everywhere (WiFi, Mobile, Roaming)
- Telegram (has "block" rule) → Blocked everywhere (WiFi, Mobile, Roaming)

For a uniform rule, switching between WiFi and Mobile has no effect - the blocking state remains the same.

**System UIDs are never blocked by the Shizuku backends:**

Android packs a UID as `userId * 100000 + appId`, and refuses a firewall policy for any appId outside `10000..19999`. ConnectivityManager answers `Can't set package firewall rule for system app <pkg> with appId <n>`; NetworkPolicyManager throws `cannot apply policy to UID <uid>`. De1984 skips them before spending a Shizuku process, so a rule on such a package has no effect on these backends. iptables blocks by UID and is not subject to this limit. The per-pass log reports how many packages were skipped. Measured on an Android 14 GSI: 31 packages across 7 system UIDs.

---

## 4. NetworkPolicyManager Backend

**Requirements:** Shizuku. No Android version requirement.

**Characteristics:**
- No VPN icon
- Does not occupy VPN slot (can use real VPN)
- Does NOT support granular rules (all-or-nothing blocking only)
- Blocks are stored by Android outside the app and **survive reboot and uninstall**. A clean stop restores them — see "What survives uninstalling De1984"
- Never selected by AUTO. Reachable only by choosing it manually in Settings

**How it works:**

Reaches `INetworkPolicyManager` over the Shizuku binder by reflection and calls `setUidPolicy(uid, policy)`. Blocking is per UID, not per package, so apps sharing a UID share a verdict (see "Shared UIDs" in section 2).

**The blocking value is decided at runtime, not hard-coded:**

| Constant | Value | Meaning |
|---|---|---|
| `POLICY_NONE` | `0x0` | no policy |
| `POLICY_REJECT_METERED_BACKGROUND` | `0x1` | AOSP. Metered background data only — **does not block WiFi** |
| `POLICY_ALLOW_METERED_BACKGROUND` | `0x4` | AOSP. An **allowance**, never a block |
| `POLICY_REJECT_ALL` | `0x40000` | Not in AOSP. Added by LineageOS-type ROMs. Blocks WiFi and Mobile |

`calibrateBlockingPolicy` picks between them by writing `POLICY_REJECT_ALL` to one app UID and reading it back, then confirming with `dumpsys netpolicy` that the ROM actually decodes it as `REJECT_ALL`. Storing the value is not enough — a ROM that stores it without knowing the constant enforces nothing. Anything short of a confirmed `REJECT_ALL` falls back to `POLICY_REJECT_METERED_BACKGROUND`, and **WiFi is then not blocked at all**. `0x4` is rejected outright if a ROM ever returns it for a blocking write: granting an allowance to an app the UI calls blocked would be worse than failing.

Calibration runs only on an app UID. On a system UID the write throws, and a throw would pin the weakest policy for the whole process.

**The original-policy record:**

`POLICY_NONE` does not mean "no opinion" — writing it erases whatever was there, including Android's own "Restrict background data" setting and a ROM's per-app restrictions. So this backend records what each UID held **before** it was touched, in `npm_original_policies`, and flushes that record to disk **before** the first write. A UID absent from the record is not ours and is left alone. On stop, each UID is set back to its recorded value; an entry is dropped only once the policy reads back as `POLICY_NONE`, proving there is nothing left to undo. Entries that fail to restore stay on disk for the next attempt rather than being forgotten.

**Block All mode:**
- Apps without rules: Blocked on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks

**Allow All mode:**
- Apps without rules: Allowed on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks

**Network changes:**

Like ConnectivityManager, the live network type does not affect the outcome. `applyRules` evaluates `rule.isBlockedOnAnyNetwork()`, so an app is blocked if its rule blocks on WiFi, Mobile **or** Roaming.

**System UIDs:** the same limit described in section 3 applies here. `setUidPolicy` throws `cannot apply policy to UID <uid>` for any appId outside `10000..19999`, and those packages are skipped before a Shizuku process is spent.

**Known gap:** UI availability is checked with `hasShizukuPermission` plus reflection reaching the service. Whether a ROM implements `POLICY_REJECT_ALL` is only discovered later, during calibration, so a device can offer this backend and then silently degrade to metered-background-only blocking. The fallback is logged as a warning; nothing surfaces it in the UI.

---

## Boot Protection

Optional, **root only**. Blocks all network traffic from the moment the kernel is up until De1984 starts, closing the window where apps can talk before any backend exists.

**How it works.** A script is installed at `/data/adb/post-fs-data.d/de1984_boot_protection.sh` — Magisk's directory, not the app's. At boot it creates a `de1984_boot` chain that accepts loopback and a small set of system UIDs, and DROPs everything else. It uses raw `iptables` because `post-fs-data` runs long before Android's framework.

**Toggling it reboots the device**, on both enable and disable. This is deliberate, chosen so that on-disk state and live state can never disagree. The confirmation dialog says so and lists ADB recovery steps.

**Two safety guards, both verified on hardware:**
- **Self-expiry.** The chain removes itself about 120 seconds after boot whether or not De1984 ever starts. Measured at +72 s on a test device.
- **Self-delete.** If the APK is gone from every user profile, the script deletes itself and exits without installing anything.

**Recovery if it ever goes wrong:**
```
adb shell
su
rm /data/adb/post-fs-data.d/de1984_boot_protection.sh
reboot
```

The switch reads the **script on disk**, not the preference, so clearing app data cannot leave the UI disagreeing with reality.

---

## What survives uninstalling De1984

Backends do not all store their blocks in the same place, so uninstalling has different consequences depending on which one was running. **Stopping the firewall first always cleans up correctly** — this only concerns uninstalling while it is on.

| Backend | Where blocks live | Survives uninstall | Survives reboot |
|---|---|---|---|
| NetworkPolicyManager | `/data/system/netpolicy.xml` | **Yes** | **Yes** |
| ConnectivityManager | live system state | Yes | No |
| iptables | kernel | Yes | No |
| VPN | the tunnel | No | No |

**NetworkPolicyManager is the one that matters.** Its blocks are stored by Android in a system file outside the app. Uninstall De1984 with an app blocked and that app has no internet **permanently** — nothing in Android's UI explains it, and it survives reboot. Verified on hardware.

Android never tells an app it is being uninstalled, so De1984 cannot clean up after itself.

**Turn the firewall off before uninstalling.**

To clear a block left behind, per affected UID:
```
adb shell cmd netpolicy add    restrict-background-blacklist <uid>
adb shell cmd netpolicy remove restrict-background-blacklist <uid>
```
Both commands are needed; `remove` alone is refused when the UID is not on that list.

---

## Firewall State Machine

The firewall operates as a state machine with well-defined states and transitions. This ensures consistent behavior and proper UI synchronization.

### States

1. **`Stopped`**: No backend running, firewall is OFF
   - User has disabled firewall
   - No backend service is active
   - No firewall rules are applied
   - All apps have unrestricted network access

2. **`Starting(backend)`**: Backend service launched, waiting for active confirmation
   - Backend service has been started (Intent sent)
   - Waiting for backend to report active via `isActive()`
   - UI should show loading indicator
   - This is a transient state (should resolve within 1-2 seconds)

3. **`Running(backend)`**: Backend active and its start confirmed
   - Backend service is running and `isActive()` returns true
   - The start applied the rules. On iptables, ConnectivityManager and NetworkPolicyManager a later re-apply that fails (a rule, network or screen change) is only logged, so `Running` can stand over a change that is not enforced
   - Health monitoring is active
   - UI toggle should be ON

4. **`Error(message, lastBackend)`**: Backend failed, firewall is DOWN
   - Backend failed to start or crashed
   - No firewall rules are active
   - All apps are UNBLOCKED (security risk!)
   - UI must show prominent error warning
   - User must be notified

There is no separate switching state. A backend switch reuses `Starting`: `startFirewall` sets `Starting(oldBackendType)`, starts the new backend before stopping the old one so there is no security gap, then sets `Running(newBackendType)`. If the new backend fails while the old one is still active, the state returns to `Running(oldBackend)` rather than going to `Error`.

### State Transitions

```
Stopped → Starting: User enables firewall
Starting → Running: Backend confirms active (isActive() returns true)
Starting → Error: Backend fails to start (timeout, permission denied, crash)

Running → Starting: Privilege change detected OR backend failure detected
Running → Stopped: User disables firewall
Running → Error: Backend crashes unexpectedly

Starting → Running(new): New backend active, old backend stopped successfully
Starting → Running(old): New backend fails, but the old backend is still active (rollback)
Starting → Error: New backend fails and no backend is left running

Error → Starting: Recovery attempt (privilege restored, VPN permission granted, user retry)
Error → Stopped: User explicitly stops firewall
```

### UI Synchronization Rules

**Toggle State**:
- ON: When state is `Running` **or** `Starting` - the switch flips immediately on tap, before the backend is confirmed
- OFF: When state is `Stopped` or `Error`
- The toggle is never disabled and there is no loading indicator: the toolbar shows only the "Firewall Active" / "Firewall OFF" badge pair, driven by the same ON/OFF flag.

**Status Display**:
- `Stopped`: "Firewall OFF" badge
- `Starting`: "Starting..." with loading indicator
- `Running`: "Firewall Active" badge + backend type
- `Error`: "Firewall not running" with error icon + error message

**User Actions**:
- User can always toggle OFF (stop firewall)
- User can toggle ON only from `Stopped` or `Error` states
- User cannot interact during `Starting` (prevent race conditions)

### State Persistence

**SharedPreferences Keys**:
- `KEY_FIREWALL_ENABLED`: User intent (should firewall be running?)
- `KEY_VPN_SERVICE_RUNNING`: Is VPN service active?
- `KEY_PRIVILEGED_SERVICE_RUNNING`: Is privileged service active?
- `KEY_PRIVILEGED_BACKEND_TYPE`: Which privileged backend is active?

**State Recovery on App Restart**:
1. Read `KEY_FIREWALL_ENABLED` to determine user intent
2. Check if any backend service is actually running (`isActive()`)
3. Synchronize state:
   - If should be running but not active → Restart firewall
   - If should be stopped but active → Stop backend
   - If backend type mismatch → Restart with correct backend
4. Emit correct `firewallState` to UI

### No network

When the device has no usable network, blocking rules stay in force rather than being lifted. Nothing can connect with no network, so holding them costs nothing, and lifting them opened a window on reconnect where every app was unblocked until the next rule pass landed. Transports the app has no separate switch for — Ethernet, USB and Bluetooth tethering — are treated as WiFi, not as "no network".

---

## App Initialization and State Recovery

When the app starts (or returns from background), it must recover the correct firewall state.

### Initialization Sequence

1. **Check User Intent**:
   - Read `KEY_FIREWALL_ENABLED` from SharedPreferences
   - This tells us if user wants firewall running

2. **Detect Running Backends**:
   - Check VPN backend: `VpnFirewallBackend.isActive()`
   - Check iptables backend: `IptablesFirewallBackend.isActive()`
   - Check ConnectivityManager backend: `ConnectivityManagerFirewallBackend.isActive()`
   - Check NetworkPolicyManager backend: `NetworkPolicyManagerFirewallBackend.isActive()`

3. **Synchronize State**:
   - **Case A**: Firewall should be running AND backend is active
     - Set `currentBackend` to detected backend
     - Set `_activeBackendType` to detected backend type
     - Set `_firewallState` to `Running(backendType)`
     - Start health monitoring

   - **Case B**: Firewall should be running BUT no backend is active
     - Backend crashed or was killed
     - Attempt to restart firewall with best available backend
     - Set `_firewallState` to `Starting(backendType)`

   - **Case C**: Firewall should be stopped BUT backend is active
     - `initializeBackendState` adopts any detected backend as `Running` regardless of `KEY_FIREWALL_ENABLED`: intent is only read after all 5 detection attempts fail, so this case is never reached there
     - `De1984Application.cleanupOrphanedFirewallRules` instead clears iptables / ConnectivityManager / NetworkPolicyManager *rules* at startup when the firewall is disabled; it does not stop the service, does not touch VPN, and does not change `_firewallState`

   - **Case D**: Firewall should be stopped AND no backend is active
     - Normal stopped state
     - Set `_firewallState` to `Stopped`

4. **Start Monitoring**:
   - Start privilege monitoring (root + Shizuku status)
   - Start backend health monitoring (if firewall running)
   - Start network/screen state monitoring (if needed by backend)

5. **Update UI**:
   - Emit current `firewallState` to UI
   - UI observes StateFlow and updates toggle/badges accordingly

### Edge Cases

**App killed while firewall running**:
- VPN service survives (foreground service)
- Privileged service survives (foreground service)
- On app restart: Detect running service and reconnect (Case A)

**Device rebooted**:
- All services are killed
- BootReceiver starts firewall if `KEY_FIREWALL_ENABLED` is true
- App initializes later and detects running service (Case A)

**Backend crashed**:
- Health monitoring detects failure
- In AUTO mode `handleBackendFailure` re-runs the planner, which normally picks the next privileged backend rather than VPN
- In a manual mode there is no fallback at all: the firewall is left down, `_isFirewallDown` is set and a notification is shown
- State transitions: `Running` → `Starting` → `Running(newBackend)`, or `Error`. There is no `Switching` state — `FirewallState` is only `Stopped`, `Starting`, `Running`, `Error`

**Backend type mismatch**:
- SharedPreferences says iptables, but VPN is running
- This can happen if backend failed and fell back to VPN
- Accept the running backend, update SharedPreferences
- Continue monitoring for privilege restoration

**Firewall down but user intent preserved**:
- `KEY_FIREWALL_ENABLED` is true but `_firewallState` is `Error`
- `_isFirewallDown` flag is true
- When privileges are restored, automatically attempt recovery
- This allows seamless recovery without user intervention

**Both "down" and "stuck" at once**:
- Killing Shizuku can raise `FirewallHealth.Down` and `FirewallHealth.StopFailed` together
- `KEY_FIREWALL_ENABLED` is the tiebreak: with intent OFF, `StopFailed` wins
- Reason: on iptables, ConnectivityManager and NetworkPolicyManager the rules outlive the
  backend that wrote them, so "your apps are unblocked" is false there
- Two cases are NOT suppressed: intent ON (orphan after a backend switch), and a start
  attempt, which has already proved nothing is enforcing

**VPN permission needed from the widget or the tile**:
- Both route through `FirewallToggleReceiver`, and a `BroadcastReceiver` cannot open the
  VPN dialog: with targetSdk 34, Android 14 refuses the launch with `BAL_BLOCK`
- The receiver reports `Down(VPN_PERMISSION_REQUIRED)` instead, which raises the VPN
  fallback notification. Tapping a notification is a gesture Android accepts
- `VpnPermissionActivity` therefore has no launcher today - see PLAN.md

---

## Backend Health Check Behavior

Health checks ensure the firewall backend remains functional and triggers fallback when needed.

### Health Check Execution

**For Privileged Backends** (iptables, ConnectivityManager, NetworkPolicyManager):
- Executed in PrivilegedFirewallService (foreground service)
- Runs on adaptive interval: 15s initially, rising to 60s after 10 consecutive successful checks
- Checks backend availability via `checkAvailability()` method

**For VPN Backend**:
- Monitored internally in FirewallVpnService
- Checks VPN interface status
- Monitors network state changes
- FirewallManager also runs its health loop on the VPN backend, on the same adaptive interval, for privilege-gain detection: in AUTO mode it re-checks root/Shizuku, and if `computeStartPlan()` now picks a privileged backend it stops VPN and switches automatically. In manual VPN mode the check is skipped and the user's choice is kept.

### Health Check Logic

1. **Execute Check**:
   - For iptables: Force re-check root status, then test `iptables --version`
   - For ConnectivityManager: Test Shizuku shell command execution
   - For NetworkPolicyManager: Test Shizuku shell command execution

2. **On Success**:
   - Increment consecutive success counter
   - Increase check interval if threshold reached (adaptive)
   - Continue monitoring

3. **On Failure**:
   - Log failure with details
   - Reset consecutive success counter to 0
   - Reset check interval to 15 seconds (fast recovery)
   - Trigger `handleBackendFailure()`
   - Stop health monitoring (new backend will start its own)

### Failure Handling

When health check fails:

1. **Determine Current Mode**:
   - If manual mode: Stay in ERROR and wait for privileges/user action
   - If AUTO mode: Keep AUTO mode

2. **Compute Fallback Plan**:
   - Use `computeStartPlan()` to determine best available backend
   - Planner considers all available backends and permissions

3. **Execute Fallback**:
   - **If planner selects non-VPN backend**: Call `startFirewall()` directly
   - **If planner selects VPN backend**:
     - Check VPN permission
     - If granted: Start VPN automatically
     - If not granted: Show notification, set state to `Error`, mark firewall as DOWN

4. **Update State**:
   - On success: `_firewallState` = `Running(newBackend)`
   - On failure: `_firewallState` = `Error`, `_isFirewallDown` = true

### Retry Strategy

**No automatic retries on health check failure**:
- Health checks already run frequently (15-60 seconds)
- Immediate fallback is more secure than retrying failed backend
- User can manually retry from error state

**Automatic recovery when privileges return**:
- Privilege monitoring detects when root/Shizuku becomes available
- Automatically attempts to switch back to better backend
- This provides seamless recovery without user intervention

---

## Notification Strategy

Notifications inform users about firewall state changes and issues.

### Notification Types

1. **Foreground Service Notifications** (persistent):
   - VPN backend: "De1984 Firewall Active (VPN)"
   - iptables backend: "De1984 Firewall Active (iptables)"
   - ConnectivityManager backend: "De1984 Firewall Active (ConnectivityManager)"
   - These are required by Android for foreground services
   - Cannot be dismissed while service is running

2. **VPN Fallback Notification** (high priority):
   - Shown when privileged backend fails and VPN permission not granted
   - Title: "Firewall Protection Lost"
   - Message: "Root/Shizuku access lost. Grant VPN permission to restore protection."
   - Action: "Enable VPN" (opens app and requests permission)
   - Auto-cancel: Yes (dismissed when user taps)

3. **Backend Monitoring Notification** (low priority):
   - Shown when firewall falls back to VPN at boot (Shizuku not ready)
   - Title: "Waiting for Shizuku"
   - Message: "Firewall using VPN. Will switch to iptables when Shizuku is ready."
   - Action: "Retry Now" (attempts backend switch)
   - Dismissible: Yes
   - Auto-stops after 5 minutes or when Shizuku becomes available

4. **Silent Notifications** (no sound/vibration):
   - Backend switch success: "Firewall switched to [backend]"
   - Only shown if user has notifications enabled
   - Low priority, auto-dismiss after 5 seconds

### Notification Rules

**When to show notifications**:
- ✅ Backend failure with VPN permission needed (high priority)
- ✅ Firewall falls back to VPN at boot (low priority, dismissible)
- ✅ Foreground service running (required by Android)
- ✅ Automatic backend switch success (default priority, on the `firewall_alerts_channel`): "Firewall Upgraded" / "Switched from VPN to <backend>", auto-cancel
- ❌ Health check failures (logged only, no notification spam)

**Notification channels**:
- `firewall_service`: Foreground service notifications (importance: LOW)
- `vpn_fallback`: VPN permission requests (importance: HIGH)
- `backend_monitoring`: Backend monitoring status (importance: LOW)

**User control**:
- Users can disable notification channels in Android settings
- Foreground service notifications cannot be fully disabled (Android requirement)
- App continues to work even if notifications are disabled

