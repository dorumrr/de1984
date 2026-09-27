# De1984: work list

Tier: 3

Open work from the audit of backend switching and package events on 2026-09-26. Every item was
already in v2.7.7. All of it is fixed before release 48 (2.7.8), high first. Line cites are at
`12eed26`; verify before acting.

Status: `[ ]` not started, `[~]` in progress, `[x]` done.

## High

- [x] H1. A switch whose old VPN stop times out also removes the new backend's rules.
  `FirewallManager.kt:691` -> `tearDownSwitchedAwayBackend` (`:873-893`) -> `cleanupAllBackends`
  (`:895-1022`) sweeps every backend, the new one too, and the app shows Running and Healthy.
  Done when: a VPN-to-privileged switch with a stalled VPN stop keeps the new backend's rules, on a
  device, red before and green after.
- [x] H2. The backend monitor restarts a firewall the user stopped.
  `BackendMonitoringService.kt:176-189`, `:213`. Its notification and Retry also stay.
  Done when: stop the firewall while the monitor waits for Shizuku, grant Shizuku, and the firewall
  stays off, on a device.

## Medium

- [x] M1. A switch from a per-network backend to a simple one rewrites partial rules to block-all
  before the new backend starts, and a failed start keeps the rewrite. `FirewallManager.kt:603-605`,
  `:1214`. Done when: a failed switch leaves the stored rules as they were.
- [x] M2. A switch between two privileged backends drops the old backend's stop, so its rules stay
  in force while the app shows Healthy. `PrivilegedFirewallService.kt:183-187`, `:320-328`.
  Done when: the old backend's rules are gone, or reported, after such a switch.
- [x] M3. ConnectivityManager: an app's copy in another profile shows Allowed and "cannot reach"
  while its uid is denied. `FirewallBackend.kt:332-333`, `strings.xml:534`. Needs new wording in
  7 languages. Done when: the row shows the verdict the device enforces.
- [x] M4. With another VPN up and no root or Shizuku, the switch ON and the banner's "Try again" do
  nothing and say nothing. `FirewallViewModel.kt:995-1004`. Done when: the user is told why, and the
  switch shows OFF.
- [x] M5. A VPN consent answer goes down a stale branch: the switch and the start dialog never reset
  `vpnPermissionContext`. `MainActivity.kt:776`, `:792`, `:132-134`, `:917-944`.
  Done when: a refusal or grant from the switch always reaches the view model.
- [x] M6. "Enable VPN" and "Replace VPN" end on "The VPN backend failed". `FirewallManager.kt:2302`,
  `strings.xml:307`. Done when: the banner names the backend that failed, or none.
- [x] M7. Per-package apply failures are only logged, and the rows show the rule as applied.
  `ConnectivityManagerFirewallBackend.kt:332-338`, `:355`; `NetworkPolicyManagerFirewallBackend.kt:541-545`,
  `:556`; `PrivilegedFirewallService.kt:633-635`. Needs new wording in 7 languages.
  Done when: a failed apply is shown to the user.
- [x] M8. Notification 1007 ("switched to keep protecting you") stays after a stop.
  `FirewallManager.kt:2762-2775`. Done when: a stop or a Down clears it.
- [x] M9. The boot failure notification is hard-coded English and always blames VPN permission, and
  its tap runs a raw `VpnService.prepare()`. `BootReceiver.kt:319-322`, `MainActivity.kt:896`.
  Done when: the text is translated and names the real cause, and the tap never takes another
  VPN's slot.

## Docs

- [x] D1. Correct what the audit found stale. `FIREWALL.md:65-72`, `:87-97`, `:130`, `:168`, `:173`,
  `:463`, `:590`, `:596`, `:616-626`, `:671`, `:716-729`; the `FirewallManager.kt:864` KDoc and the
  two KDocs above the wrong functions (`:858-872`, `:1632-1647`); `FirewallToggleReceiver.kt:69`;
  `FirewallVpnService.kt:560-561`; `FirewallHealthPresenter.kt:22-23`; `FirewallHealth.kt:51-53`;
  the `strings.xml:309-310` comment; `HiddenApiHelper.kt:582`, `:682`;
  `PackageMonitoringService.kt:405`; `BootWorker.kt:48`.
- [x] D2. Doc lines the fixes found stale. `FIREWALL.md:53`, `:161`, `:168`, `:596`, `:643-671` (manual
  mode falls back to VPN; the VPN health check reads flags, not the interface), `:731-748` (notification
  section: the switched notice is ongoing, `firewall_alerts_channel` missing); `FirewallHealth.kt`
  SwitchedToVpn KDoc ("the only path that sets it").

## Low (next release)

Found while fixing H1-M9; none is caused by those fixes. Line cites are at `8d9ab3f`.

- [ ] L1. Orphan warnings: a live StopFailed is wiped by `reportFirewallHealthy` and overwritten by
  SwitchedToVpn (`FirewallManager.kt:1953`); never re-checked; dropped when the next start fails;
  null-type label; third-backend proof dropped. The Down title "your apps are unblocked" shows while a
  dead privileged backend's kernel rules still block; a fallback's orphan is titled "Firewall did not
  stop" though the user never stopped.
- [ ] L2. Stored wish drift: a failed tile stop keeps it true; a failed start or a declined consent
  writes it false but leaves the switch ON and `_isFirewallDown` armed (`FirewallViewModel.kt:1064`),
  so a later root/Shizuku gain restarts a firewall the user declined (`FirewallManager.kt:2886`);
  StopFailed clears the down flag, so a privilege gain does not restore.
- [ ] L3. Health and services: a same-backend restart and the legacy VPN fallback start no health
  monitoring (`FirewallManager.kt:585`, `startVpnFallback`); AUTO keeps a dead VPN when the plan is
  VPN again (`:2901`); the service's failure report runs in the scope `stopSelf` cancels
  (`PrivilegedFirewallService.kt:583`); a tunnel back on the service's own retry is never reported
  (`FirewallVpnService.kt:368`); health-job switch self-cancel, teardown-end race, dropped stop for an
  in-flight backend, leaked health loop; monitor timer, "waiting"/"Still using VPN" texts, 3 s toast.
- [ ] L4. VPN conflict and consent: `isAnotherVpnActive` blind with a dead own VPN and to split-tunnel
  VPNs; a conflict while a privileged backend runs shows Error/OFF; the conflict banner outlives the
  other VPN; Enable/Replace VPN granted after protection came back starts VPN over it;
  `handleVpnConflict`'s start ignores a stop that lands meanwhile; De1984 retook the slot after its
  tunnel was revoked; a widget/tile start while a "would not stop" warning is live does nothing
  (`reportFirewallDown` early return); the conflict notice is debounced 30 s, so a repeat tap in that
  window posts nothing new.
- [ ] L5. Manual mode: never switches back after privileges return (`handlePrivilegeChange`
  stillViable return); a cold start races Shizuku and shows START_FAILED briefly; a privilege answer
  between two plans shows "switched to VPN" while a privileged backend runs.
- [ ] L6. Notification text and life: 1007 keeps naming the old backend and "another VPN is active"
  after it leaves; the upgrade notice says "Root access detected ... Mode set to AUTO" on a Shizuku
  gain (`strings.xml:278`); "switched to AUTO mode" (`strings.xml:308`) while the stored mode stays
  manual; 1010 "Tap to start it again" only opens the app, stays next to 1009 when a tile stop's
  teardown fails later, and can land after a quick restart; channel names are hard-coded English;
  Android 12+ boot (BootWorker) and the process-start restart have no consent pre-check (START_FAILED,
  10 s waits); the old "Boot Failure" channel stays; the long-press toast on a following row.
- [ ] L7. ConnectivityManager and profiles: a work copy stays denied after the app is uninstalled from
  De1984's profile only, and stop does not clear it (`ConnectivityManagerFirewallBackend.kt:240`, `:534`,
  `:595`); a work-only app sharing a sharedUserId with an own-profile app stays "cannot reach".
- [ ] L8. UI leftovers: sheets keep per-network controls across a switch; rows paint raw partial
  flags on simple backends; migration read-then-write race; `MainActivity.kt:217`
  REQUEST_VPN_PERMISSION has no sender.
- [ ] L9. Apply warning gaps: iptables swallows batch errors, so its partial failures raise no
  warning (`IptablesFirewallBackend.kt:506-524`); NetworkPolicyManager keeps a cached "applied"
  after the user changes Android's own data switch for that app.
