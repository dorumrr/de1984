# Issues #113 and #114: LAN control and list sort
Tier: 3        how hard each step is worked. 1 quick, 2 normal, 3 full. Default 3.

```
   [ ]  not started        [~]  in progress        [x]  done

   A. LAN CONTROL (#113)
   [x] A1  LAN switch is usable whenever iptables is available, firewall on or off
   [x] A2  List rows show the true LAN state on the Wi-Fi icon, iptables only

   B. SORT (#114)
   [x] B1  Firewall and Packages lists sort, and each tab remembers its choice

   C. ROOT RECHECK (#113)
   [x] C1  Root granted after launch is picked up without a restart     cause not known, reproduce first
```

Every step: no tests exist in the repo, so the proof is a build, lint, and a check on my own
emulator (never Doru's). No version bump, no changelog line, never push.
Every new string goes into `values/` and all 7 locales: ar, fr, it, pt, ro, ru, zh.

## A1  LAN switch is usable whenever iptables is available, firewall on or off
Depends: none
Touches: app/src/main/java/io/github/dorumrr/de1984/ui/firewall/FirewallFragmentViews.kt, app/src/main/java/io/github/dorumrr/de1984/presentation/viewmodel/FirewallViewModel.kt, app/src/main/res/

Skill: /phi:fix. Defect: with the firewall off, or on another backend, a rooted device shows
"Requires root access" and a dead LAN switch (`FirewallFragmentViews.kt:1024,1054-1061`), so the
user cannot set LAN in advance.

- Available means `FirewallManager.isIptablesAvailable()` (`FirewallManager.kt:1073`) is true,
  probed when a sheet opens and again when privilege or the running backend changes.
- Every LAN gate site moves together: full sheet colours `:999-1000`, enable and subtitle
  `:1054-1061`, initial enable `:1246`, protection snackbar `:1276`; simple sheet `:1517-1526`
  (today always disabled and set once; it opens when `supportsGranularControl()` is false,
  `:874`, i.e. on ConnectivityManager or NetworkPolicyManager); the flags at `:942-944` and
  `:1023-1024`; batch sheet `:1909-1910`, `:1998`, `:2045`, `:2085`
  (today hides LAN when not on iptables).
- Available but not running on iptables: the switch works and the LAN row shows a new note,
  "Applies only when the firewall uses iptables". Not available: "Requires root access", disabled.
- Refusal rules do not change: `canToggle`, `controlsRefused`, critical and VPN-app protection.

Proves (root granted before launch; debug chain `de1984dbg_output`, `Constants.kt:32`): on a rooted
emulator with the firewall off, the LAN switch of an app whose internet is allowed is enabled with
the note; after a tap, force-stop and relaunch, the sheet still shows LAN blocked; starting the
firewall on iptables gives exactly 3 `-d <range> -j DROP` lines for that uid in
`iptables -S de1984dbg_output` and 2 in `ip6tables -S de1984dbg_output`; switching LAN off gives 0;
the batch sheet shows LAN; the simple sheet (ConnectivityManager) offers LAN with the note; with
root denied, the switch is disabled with "Requires root access".

## A2  List rows show the true LAN state on the Wi-Fi icon, iptables only
Depends: A1
Touches: app/src/main/java/io/github/dorumrr/de1984/ui/firewall/NetworkPackageAdapter.kt, app/src/main/res/

Skill: /phi:feat.

- Only while the RUNNING backend is iptables, the Wi-Fi icon in each row carries a small LAN
  badge. Three true states: Wi-Fi and LAN allowed; Wi-Fi allowed, LAN blocked; both blocked.
- LAN reads as blocked when `lanBlocked` OR `wifiBlocked`: a Wi-Fi block is a uid-wide DROP that
  also catches LAN (`IptablesFirewallBackend.kt:875-878`). There is no "LAN only" state.
- Firewall off or any other backend: the icon is exactly as today. A tap on the Wi-Fi icon still
  toggles Wi-Fi only. The content description says the LAN state.
- Rows already redraw on a backend change (`FirewallFragmentViews.kt:566-569`).

Proves: on iptables, three apps set to (Wi-Fi allowed, LAN allowed), (Wi-Fi allowed, LAN blocked)
and (Wi-Fi blocked) show the three badge states in a screenshot, each paired with its lines in
`iptables -S de1984dbg_output`; on the VPN backend the same rows show the plain icon.

## B1  Firewall and Packages lists sort, and each tab remembers its choice
Depends: A1, A2
Touches: app/src/main/java/io/github/dorumrr/de1984/utils/PackageUtils.kt, app/src/main/java/io/github/dorumrr/de1984/utils/FilterPrefs.kt, app/src/main/java/io/github/dorumrr/de1984/utils/Constants.kt, app/src/main/java/io/github/dorumrr/de1984/domain/model/NetworkPackage.kt, app/src/main/java/io/github/dorumrr/de1984/presentation/viewmodel/FirewallViewModel.kt, app/src/main/java/io/github/dorumrr/de1984/presentation/viewmodel/PackagesViewModel.kt, app/src/main/java/io/github/dorumrr/de1984/ui/firewall/FirewallFragmentViews.kt, app/src/main/java/io/github/dorumrr/de1984/ui/packages/PackagesFragmentViews.kt, app/src/main/res/

Skill: /phi:feat.

- Firewall tab: Name (default), Recently installed, Recently updated, Configured first (rows with
  `hasExplicitRule` first, then by name). Packages tab: Name, Recently installed, Recently updated.
  Time sorts are newest first, unknown time last, ties by name.
- An icon button beside the search box in each tab opens a menu with the current choice checked.
- The choice is a field of the existing filter state (`FirewallFilterState` at
  `NetworkPackage.kt:155`, `PackageFilterState` at `PackagesViewModel.kt:609`), saved by
  `FilterPrefs` in all four load and save functions, with a normaliser: an unknown stored value
  falls back to Name.
- The view models filter first (`FirewallViewModel.kt:508`, `PackagesViewModel.kt:233`); the sort is
  applied where the search filter is applied (`FirewallFragmentViews.kt:687`,
  `PackagesFragmentViews.kt:564`). One sorter serves both tabs and replaces the dead
  `PackageUtils.sortPackages` and `SortCriteria` (`PackageUtils.kt:162-191`, no callers).
- Known behaviour: under Configured first, a row that gains its first rule moves to the top group.

Proves: on the emulator, each option orders both lists as stated; with Firewall on Recently
installed and Packages on Recently updated, both survive force-stop and relaunch; writing an unknown
value into the new sort key of `de1984_prefs` (as root) and relaunching opens on Name.

## C1  Root granted after launch is picked up without a restart
Depends: none
Touches: unknown

Skill: /phi:fix, cause not known. Report from #113: KernelSU, root granted in the manager after the
first launch, iptables stayed unselectable until the app restarted. `MainActivity.onResume` already
calls `forceRecheckRootStatus` and `checkBackendShouldSwitch` (`MainActivity.kt:269-275`).

Proves: on the Magisk emulator, deny root, launch, check iptables is NOT selectable, grant root in
Magisk, go home and back to the same process (PID unchanged, no force-stop): iptables is selectable
in Settings. If it already is, the step reports "not reproduced on Magisk" with that evidence and
changes no code; if code changes, this check runs again on the fix.

## Non-goals
No real "LAN only" mode and no change to how iptables drops traffic. No change to masking
(`asEnforcedBy`) or to the data-layer name sort. No Settings entry for sort. No version bump, no
changelog line, no push.
