# Firewall Backend Behavior Reference

This document defines how each firewall backend works in De1984.

---

## Backend Selection Logic

The app can operate in different modes: **AUTO** (automatic selection) or **MANUAL** (force specific backend) unless it crashes or something goes wrong, then the app returns to AUTO.

### AUTO Mode (Default)

When in AUTO mode, the app selects the best available backend using this priority order:

1. **iptables** (highest priority)
   - **Requires**: Root access OR Shizuku in root mode
   - **Check**: Try to execute `iptables -L` command (is this the best way to check in order to be compatible with most android versions and devices?)
   - **If available**: Use iptables backend ✅
   - **If not available**: Try next backend ⬇️

2. **ConnectivityManager** (medium priority)
   - **Requires**: Shizuku (ADB or root mode) AND Android 13+
   - **Check**: Verify Shizuku is running and Android version >= 33
   - **If available**: Use ConnectivityManager backend ✅
   - **If not available**: Fall back to VPN ⬇️

3. **VPN** (fallback, always available)
   - **Requires**: Only VPN permission (user grants via system dialog)
   - **Always available**: No special requirements
   - **Use as last resort**: When no privileged access available ✅

### Manual Mode

User can force a specific backend from Settings.
**Important**: The dropdown should only show backends that are currently available on the device.

**Available backends check**:
- **VPN**: Always available (always shown in dropdown)
- **iptables**: Only show if root access OR Shizuku in root mode is available
- **ConnectivityManager**: Only show if Shizuku is available AND Android 13+

**User selection**:
- **Force VPN**: Always use VPN backend (even if root/Shizuku available)
- **Force iptables**: Only use iptables (only selectable if root/Shizuku root mode available)
- **Force ConnectivityManager**: Only use ConnectivityManager (only selectable if Shizuku available and Android 13+)

If a previously selected backend becomes unavailable (e.g., Shizuku stops, user revokes root), the app should automatically fall back to AUTO mode and select the best available backend and ensure Firewall works correctly.

### Why This Priority Order?

1. **iptables is best**: Full granular control, no VPN slot occupied, kernel-level blocking
2. **ConnectivityManager is second**: No VPN slot occupied, but no granular control
3. **VPN is fallback**: Works everywhere but occupies VPN slot and shows VPN icon

### Backend Switching

When backend availability changes (e.g., user grants Shizuku, device gets rooted, Shizuku crashes, SuperUser permission is revoked, etc), the app must switch backends seamlessly without creating security breaches.

**Critical Security Rule**: When switching backends, there must be NO gap where apps are unblocked. The transition must be atomic and as fail-safe as possible.

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

- **Initial interval**: 1 second (fast detection of privilege changes)
- **After 10 consecutive successful checks**: Increase to 5 seconds
- **After 20 consecutive successful checks**: Increase to 10 seconds
- **After 30 consecutive successful checks**: Increase to 30 seconds (stable state)
- **On any failure**: Reset to 1 second immediately (fast recovery)

**What to Monitor**:

1. **Root Status** (for iptables backend):
   - Execute `su -c id` command
   - Check for `uid=0` in output
   - Timeout after 3 seconds
   - Cache result to avoid hammering Magisk/SuperSU

2. **Shizuku Status** (for ConnectivityManager/iptables backends):
   - Check if Shizuku binder is available
   - Verify permission is granted
   - Listen to Shizuku lifecycle callbacks (binder received/dead)
   - Check Shizuku UID to determine root mode (UID 0) vs ADB mode (UID 2000)

3. **Backend Health**:
   - Verify backend can still execute commands
   - For iptables: Test `iptables -L` command
   - For ConnectivityManager: Test Shizuku shell command execution
   - For VPN: Check if VPN interface is active

**When to Trigger Fallback**:

- Immediately on **first** health check failure
- Do NOT wait for multiple failures (security-critical)
- Atomic switch to prevent security gap
- If manual mode backend fails, normalize to AUTO mode first

**Monitoring Lifecycle**:

- Start monitoring when firewall starts
- Stop monitoring when firewall stops
- Continue monitoring during backend switches
- Privileged backends: Monitor in PrivilegedFirewallService (foreground service)
- VPN backend: Monitor internally in FirewallVpnService

---

## 1. VPN Backend

**Requirements:** VPN permission only (no root, no Shizuku)

**Characteristics:**
- Shows VPN icon in status bar
- Occupies VPN slot (cannot use another VPN simultaneously)
- Supports granular per-network rules (WiFi/Mobile/Roaming)
- Survives reboot (service restarts automatically)

**How it works:**

Apps that should be blocked are added to the VPN tunnel. Their traffic goes through the VPN where packets are dropped. Apps that should be allowed are NOT added to the VPN, so they bypass it completely and use the normal network connection.

**Critical rule:** If zero apps need blocking, the VPN must NOT be started at all. If we fiddle with switches and we reach to all Allowed, same thing, no need to have firewall up. Android's default behavior is to route ALL apps through the VPN if no apps are explicitly added (blocked) and starting a VPN with zero apps would accidentally block everything.

**Switch dependencies:**
- **Roaming requires Mobile**: Roaming is a state of mobile data when outside home network. If user enables Roaming block while Mobile is allowed, Mobile must also be blocked. If user disables Mobile block while Roaming is blocked, Roaming must also be allowed.
- **Logic**: Roaming cannot be blocked independently - it's always "Mobile + Roaming" or neither.

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

Uses Linux kernel firewall (iptables/ip6tables) to block network traffic by app UID. Creates firewall rules that drop all IPv4 and IPv6 packets for blocked app UIDs. Multiple apps can share the same UID - if any app with that UID should be blocked, the entire UID gets blocked. (explained again later in this document)

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

**Shared UIDs:**

Multiple apps can share the same UID. The firewall handles shared UIDs as follows:

- **System-critical and VPN app exemption**: If ANY app with a UID is system-critical or a VPN app, the ENTIRE UID is exempted from blocking (all apps with that UID are allowed). This prevents bypass vulnerabilities where non-critical apps share UIDs with system packages.

- **Block All mode**: For non-exempted UIDs, the UID is blocked if ANY app with that UID should be blocked (no explicit allow rule).

- **Allow All mode**: For non-exempted UIDs, the UID is blocked if ANY app with that UID has an explicit block rule for the current network.

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

Uses Android system commands to enable or disable networking for entire apps. This is all-or-nothing: an app is either allowed on ALL networks or blocked on ALL networks. Cannot block an app on WiFi while allowing it on Mobile.

**Why no granular control:**

The ConnectivityManager firewall chain API operates at the app level, not the network interface level. When you disable networking for an app, Android blocks it from accessing ANY network interface (WiFi, Mobile, VPN, Ethernet, etc.). There is no API to selectively block only certain network types. This is a fundamental limitation of the Android ConnectivityManager API.

**Switch dependencies:**
- **No WiFi/Mobile/Roaming switches**: Since this backend cannot do per-network blocking, the UI should NOT show separate WiFi/Mobile/Roaming switches. Instead, show a single "Block Network" toggle that blocks ALL networks.
- **Migration from granular backends**: When switching from VPN or iptables (which have separate switches), convert rules using this logic:
  - **Partially blocked** (1-2 networks blocked): Treat as **fully blocked** (block all networks)
  - **Partially allowed** (1-2 networks allowed): Treat as **fully allowed** (allow all networks)
  - **Fully blocked** (all 3 networks blocked): Keep as fully blocked
  - **Fully allowed** (all 3 networks allowed): Keep as fully allowed

**Block All mode:**
- Apps without rules: Blocked on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks

**Allow All mode:**
- Apps without rules: Allowed on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks

**Network changes:**

Network changes have no effect on blocking decisions since all apps are either blocked everywhere or allowed everywhere. The backend does not recalculate rules when switching between WiFi/Mobile/Roaming.

**Example (Block All):**
- Chrome (no rule) → Blocked everywhere
- Firefox (has "allow" rule) → Allowed everywhere (WiFi, Mobile, Roaming)
- Telegram (has "block" rule) → Blocked everywhere (WiFi, Mobile, Roaming)

Switching between WiFi and Mobile has no effect - the blocking state remains the same.

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

3. **`Running(backend)`**: Backend active and confirmed working
   - Backend service is running and `isActive()` returns true
   - Firewall rules are applied and enforced
   - Health monitoring is active
   - UI toggle should be ON

4. **`Switching(from, to)`**: Transitioning between backends (atomic)
   - New backend is starting while old backend is still active
   - Ensures no security gap during transition
   - Old backend stops only after new backend is confirmed active
   - UI should show loading indicator with backend change message

5. **`Error(message, lastBackend)`**: Backend failed, firewall is DOWN
   - Backend failed to start or crashed
   - No firewall rules are active
   - All apps are UNBLOCKED (security risk!)
   - UI must show prominent error warning
   - User must be notified

### State Transitions

```
Stopped → Starting: User enables firewall
Starting → Running: Backend confirms active (isActive() returns true)
Starting → Error: Backend fails to start (timeout, permission denied, crash)

Running → Switching: Privilege change detected OR backend failure detected
Running → Stopped: User disables firewall
Running → Error: Backend crashes unexpectedly

Switching → Running: New backend active, old backend stopped successfully
Switching → Error: New backend fails to start (keep old backend if still active)

Error → Starting: Recovery attempt (privilege restored, VPN permission granted, user retry)
Error → Stopped: User explicitly stops firewall
```

### UI Synchronization Rules

**Toggle State**:
- ON: Only when state is `Running`
- OFF: When state is `Stopped` or `Error`
- Disabled: When state is `Starting` or `Switching` (show loading)

**Status Display**:
- `Stopped`: "Firewall OFF" badge
- `Starting`: "Starting..." with loading indicator
- `Running`: "Firewall Active" badge + backend type
- `Switching`: "Switching backend..." with loading indicator
- `Error`: "Firewall not running" with error icon + error message

**User Actions**:
- User can always toggle OFF (stop firewall)
- User can toggle ON only from `Stopped` or `Error` states
- User cannot interact during `Starting` or `Switching` (prevent race conditions)

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
     - Orphaned backend service (shouldn't happen)
     - Stop the backend service
     - Set `_firewallState` to `Stopped`

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
- Triggers automatic fallback to VPN
- State transitions: `Running` → `Switching` → `Running(VPN)` or `Error`

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

---

## Backend Health Check Behavior

Health checks ensure the firewall backend remains functional and triggers fallback when needed.

### Health Check Execution

**For Privileged Backends** (iptables, ConnectivityManager, NetworkPolicyManager):
- Executed in PrivilegedFirewallService (foreground service)
- Runs on adaptive interval (1s → 30s based on stability)
- Checks backend availability via `checkAvailability()` method

**For VPN Backend**:
- Monitored internally in FirewallVpnService
- Checks VPN interface status
- Monitors network state changes
- No external health checks needed

### Health Check Logic

1. **Execute Check**:
   - For iptables: Force re-check root status, then test `iptables -L`
   - For ConnectivityManager: Test Shizuku shell command execution
   - For NetworkPolicyManager: Test Shizuku shell command execution

2. **On Success**:
   - Increment consecutive success counter
   - Increase check interval if threshold reached (adaptive)
   - Continue monitoring

3. **On Failure**:
   - Log failure with details
   - Reset consecutive success counter to 0
   - Reset check interval to 1 second (fast recovery)
   - Trigger `handleBackendFailure()`
   - Stop health monitoring (new backend will start its own)

### Failure Handling

When health check fails:

1. **Determine Current Mode**:
   - If manual mode: Normalize to AUTO mode
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
- Health checks already run frequently (1-30 seconds)
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
- ❌ Automatic backend switch success (silent, no notification)
- ❌ Health check failures (logged only, no notification spam)

**Notification channels**:
- `firewall_service`: Foreground service notifications (importance: LOW)
- `vpn_fallback`: VPN permission requests (importance: HIGH)
- `backend_monitoring`: Backend monitoring status (importance: LOW)

**User control**:
- Users can disable notification channels in Android settings
- Foreground service notifications cannot be fully disabled (Android requirement)
- App continues to work even if notifications are disabled

