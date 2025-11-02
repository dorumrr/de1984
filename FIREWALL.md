# Firewall Backend Behavior Reference

This document defines how each firewall backend works in De1984.

---

## Backend Selection Logic

The app can operate in different modes: **AUTO** (automatic selection) or **MANUAL** (force specific backend).

### AUTO Mode (Default)

When in AUTO mode, the app selects the best available backend using this priority order:

1. **iptables** (highest priority)
   - **Requires**: Root access OR Shizuku in root mode
   - **Check**: Try to execute `iptables -L` command
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

User can force a specific backend from Settings (this is missing currently!). **Important**: The dropdown should only show backends that are currently available on the device.

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

When backend availability changes (e.g., user grants Shizuku, device gets rooted, Shizuku crashes), the app must switch backends seamlessly without creating security breaches.

**Critical Security Rule**: When switching backends, there must be NO gap where apps are unblocked. The transition must be atomic and fail-safe.

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
     1. Detect backend failure immediately (monitor Shizuku state, test iptables commands)
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
- Never leave a gap where firewall is down

---

## 1. VPN Backend

**Requirements:** VPN permission only (no root, no Shizuku)

**Characteristics:**
- Shows VPN icon in status bar
- Occupies VPN slot (cannot use another VPN simultaneously)
- Supports granular per-network rules (WiFi/Mobile/Roaming)
- Supports screen-off blocking
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
- Screen-off rule: If enabled and screen is off, app is blocked regardless of network rule

**Allow All mode:**
- Apps without rules: Allowed (bypass VPN)
- Apps with explicit "allow" rule for current network: Allowed (bypass VPN)
- Apps with explicit "block" rule for current network: Blocked (added to VPN)
- Screen-off rule: If enabled and screen is off, app is blocked regardless of network rule

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
- Supports screen-off blocking
- Rules lost on reboot (must be reapplied on boot)

**How it works:**

Uses Linux kernel firewall (iptables/ip6tables) to block network traffic by app UID. Creates firewall rules that drop all IPv4 and IPv6 packets for blocked app UIDs. Multiple apps can share the same UID - if any app with that UID should be blocked, the entire UID gets blocked.

**Switch dependencies:**
- **Roaming requires Mobile**: Same as VPN backend. If user enables Roaming block while Mobile is allowed, Mobile must also be blocked. If user disables Mobile block while Roaming is blocked, Roaming must also be allowed.

**Block All mode:**
- Apps without rules: Blocked (firewall rules added for their UID)
- Apps with explicit "allow" rule for current network: Allowed (no firewall rules)
- Apps with explicit "block" rule for current network: Blocked (firewall rules added)
- Screen-off rule: If enabled and screen is off, app is blocked regardless of network rule

**Allow All mode:**
- Apps without rules: Allowed (no firewall rules)
- Apps with explicit "allow" rule for current network: Allowed (no firewall rules)
- Apps with explicit "block" rule for current network: Blocked (firewall rules added)
- Screen-off rule: If enabled and screen is off, app is blocked regardless of network rule

**Network changes:**

When switching networks, recalculates which UIDs should be blocked based on per-network rules. Removes firewall rules for UIDs that should no longer be blocked. Adds firewall rules for UIDs that should now be blocked. Uses diff-based updates for efficiency.

**Shared UIDs:**

Multiple apps can share the same UID. In Block All mode, the UID is blocked if ANY app with that UID should be blocked. In Allow All mode, the UID is blocked if ANY app with that UID has an explicit block rule for the current network.

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
- Supports screen-off blocking
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
- Screen-off rule: If enabled and screen is off, app is blocked on all networks

**Allow All mode:**
- Apps without rules: Allowed on all networks
- Apps with explicit "allow" rule: Allowed on all networks
- Apps with explicit "block" rule: Blocked on all networks
- Screen-off rule: If enabled and screen is off, app is blocked on all networks

**Network changes:**

Network changes have no effect on blocking decisions since all apps are either blocked everywhere or allowed everywhere. The backend does not recalculate rules when switching between WiFi/Mobile/Roaming.

**Example (Block All):**
- Chrome (no rule) → Blocked everywhere
- Firefox (has "allow" rule) → Allowed everywhere (WiFi, Mobile, Roaming)
- Telegram (has "block" rule) → Blocked everywhere (WiFi, Mobile, Roaming)

Switching between WiFi and Mobile has no effect - the blocking state remains the same.

