# De1984 — Work List

Open work, ordered by harm to the user. This file is the queue, not an archive.

**Trust order: current code first, this file second.** Every entry below was checked against the code
on 2026-09-14: F10, F11, F8's iptables lines and the Boot Protection bullet at `b225201`, the rest at
`371895e`. Verify before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NOT VERIFIED` = reasoned, not proven.
`NEEDS CONFIRMATION` = depends on Android behaviour the repo cannot show. "Runtime" means seen on a
device.

Everything finished was cleared on 2026-09-14. It is in git:

```
git show 0330f7e:PLAN.md      # the last full version
git log --follow -p PLAN.md   # everything removed, and when
```

Released: v2.7.5 (versionCode 46), commit `79b64a6`, tag `v2.7.5`.

---

# Firewall

## F10. One profile's failed package read leaves that profile's apps unblocked under Block All — `VERIFIED` in code, not at runtime

`getInstalledApplicationsAsUser` returns an empty list for a profile when every read method fails, and
caches nothing (`HiddenApiHelper.kt:529-530`; the cache is written only on success, `:503`, `:520`).
`getPackagesWithNetworkPermissions` merges the profiles with `flatMap` (`:598`), so when only one profile
fails the list is not empty and the retry-then-fail guard (`IptablesFirewallBackend.kt:387-399`) passes.
iptables Block All blocks only listed packages (`:417-460`), so that profile's apps keep their network,
and the apply drops the DROP rules they already had, while the app shows Block All running. Needs a
decision: whether a profile can truly have zero apps with network permissions.

## F11. NetworkPolicyManager and ConnectivityManager backends report success on an empty package list — `VERIFIED` in code, not at runtime

Both loop over `getPackagesWithNetworkPermissions` (`NetworkPolicyManagerFirewallBackend.kt:381-382`,
`ConnectivityManagerFirewallBackend.kt:220-221`) with no empty check, so an empty read writes no policy
and returns success in either default policy, because their loops walk packages, not rules. A start
then shows Running with nothing blocked (inferred from the shared start path): the case `b225201` fixed
for iptables with a retry, then a failure. Proof needs a Shizuku phone for NetworkPolicyManager; the test
phone's ROM cannot run ConnectivityManager.

## F2. ConnectivityManager backend: a rule-less copy overwrites another profile's rule — `VERIFIED` in code, not at runtime

The apply loop walks every profile's packages (`HiddenApiHelper.kt:598`) with no `reachesUser` filter
(`ConnectivityManagerFirewallBackend.kt:250-300`), although this backend reaches only De1984's own
profile (`FirewallBackend.kt:86-92`). `desiredPolicies` is keyed by package name, so the copy read last
wins, and a copy with no rule writes the default policy over the other copy's rule. Profile order is
inferred. Keying by package plus user alone cannot fix it: the command names only a package (F3).

## F1. Reinstalled blocked app escapes the VPN tunnel — `VERIFIED` in code, not at runtime

The rebuild decision compares package names only: `lastAppliedBlockedApps` is a `Set<String>`
(`FirewallVpnService.kt:66`), compared at `:433` against `blockedAppsFor` (`:436`). Uninstall triggers
no rebuild (`packageDataChanged` only refreshes the lists). The reinstall's rule update writes the new
uid (`HandleNewAppInstallUseCase.kt:183`) and does trigger `restartVpn`, which then skips because the
names are equal. That Android binds the old uid at establish is `NEEDS CONFIRMATION`. Likely fix:
compare package plus uid.

## F4. A VPN start that times out leaves the service retrying, unreported — `VERIFIED` in code, not at runtime

`VpnFirewallBackend.start()` itself waits up to 10 s for the tunnel and returns a failure
(`VpnFirewallBackend.kt:52-61`). Both start paths handle that in `start().getOrElse`, report
`START_FAILED` (the enabled setting is written false, `FirewallManager.kt:1552-1555`) and return
without `stop()`: same backend `:544-551`, switch `:619-631`. The wait at `:556-563` and the `stop()` at
`:635-637` run only after `start()` succeeded. The service keeps going: a failed build retries (1 s,
2 s, 5 s, then every 30 s while `isServiceActive`, `FirewallVpnService.kt:369-387`), and a slow build
completes later. Either way a tunnel can come up that nothing reports.

## F8. A cache clear during a package sweep can leave a new app unblocked — `VERIFIED` in code, timing not proven

`clearInstalledAppsCache` (`HiddenApiHelper.kt:540`, comment `:551-558`) nulls `networkPackagesCache`
without `networkPackagesLock`. A sweep already inside the lock publishes its pre-clear list with a fresh
timestamp (`:620-621`), and a caller that waited on the lock takes it whatever its age (`:589-594`).
iptables Block All blocks only packages on that list (`IptablesFirewallBackend.kt:417-460`), so the
apply triggered by an install during a running sweep can leave the new app unblocked until a later
apply. Taking the lock is worse: it is held ~9.5 s for 466 packages and two callers are
BroadcastReceivers (ANR). Fix: a generation counter the sweep checks before publishing.

## F5. The allow-critical firewall toggle does not re-apply rules — `VERIFIED` in code

`setAllowCriticalPackageFirewall` (`SettingsViewModel.kt:559-561`) only saves the pref, and its callers
(`SettingsFragmentViews.kt:454-458`, `:1517-1520`) do nothing else. The backends read the pref at apply
time, and the app has no `OnSharedPreferenceChangeListener`, so the change lands at the next unrelated
rebuild. The Firewall screen repaints from the new value meanwhile.

## F9. Banner can read "The VPN backend failed. De1984 switched to VPN" — `VERIFIED` in code

`startVpnFallbackManually` passes `failedBackendType = FirewallBackendType.VPN`
(`FirewallManager.kt:2305`), and `startVpnFallback` publishes it as `SwitchedToVpn` (`:2052`), shown
with `firewall_switched_message_auto`. Every route reaches it through
`MainActivity.handleVpnFallbackRequest` (`:864-878`): the fallback notification, the VPN conflict
notification (`FirewallManager.kt:2194`) and the banner's ENABLE_VPN and REPLACE_VPN buttons
(`MainActivity.kt:745`), with or without a permission prompt. Commit `7bb0772` fixed only the widget and
tile start.

## F7. `isAnotherVpnActive` reads any VPN as De1984's own while the VPN backend is current — `VERIFIED` in code, not at runtime

`FirewallManager.kt:2529-2545` returns false whenever a VPN transport is up and the active backend type
is VPN, so the conflict branch at `:2626` cannot fire then. With nothing to block, De1984 builds no
tunnel (`FirewallVpnService.kt:322-326`), so a VPN that is up belongs to another app. Whether Android
revokes a never-established De1984 VPN when another app prepares is `NEEDS CONFIRMATION` (second VPN
app).

## F3. Which user the ConnectivityManager command acts on is unknown — `NEEDS CONFIRMATION`

`cmd connectivity set-package-networking-enabled $enabled $packageName` carries no user
(`ConnectivityManagerFirewallBackend.kt:353`, `:560`). Whether Android applies it to user 0, to the
caller's user, or to every user's copy is not in this repo. It decides the F2 fix. The test phone's ROM
cannot run this backend.

---

# Packages and notifications

## P4. One brief safety-data failure unlocks Uninstall for critical apps — `VERIFIED` in code, never triggered

`getCriticality`, `getCategory` and `getAffects` each re-run the load when nothing is cached
(`PackageSafetyLoader.kt:83-108`), and the scan calls all three for each app
(`AndroidPackageDataSource.kt:295-297`, `:604-606`). One brief failure therefore uses all 3 attempts
within milliseconds and the process gives up until restart (`PackageSafetyLoader.kt:64-77`), which the
retry described at `:20-26` meant to prevent. Every row built while the load fails stores UNKNOWN: the
protection that disables Uninstall for ESSENTIAL and IMPORTANT apps (`PackagesFragmentViews.kt:914-944`)
is skipped and Uninstall is enabled (`:946-950`). Only the generic "unknown system package" danger
dialog remains (`:1103`).

## P2. A new-app notification keeps its Block/Allow button after a backend switch — `VERIFIED` in code

Reachability is checked once, when the notification is posted (`NewAppNotificationManager.kt:91-96`).
The write path has no reach check (`ManageNetworkAccessUseCase.kt:13-14`), so after a fallback to VPN a
work-profile notification's button saves a rule the VPN cannot enforce, and the notification closes as
if done (`NotificationActionReceiver.kt:54-58`).

## P5. Uninstalled chip empty text is often untrue — `VERIFIED` in code, wording is a product choice

`PackagesFragmentViews.kt:538-539` shows "No uninstalled system apps" whenever the Uninstalled list is
empty after the ViewModel filters: when Bloatware or a profile filter leaves nothing, and when there is
no root and no Shizuku. In that last case both `pm` reads return nothing and the read returns an empty
list with no error (`AndroidPackageDataSource.kt:577-579`, `:617-624`); nothing checks access before
the read (`PackagesViewModel.kt:203-229`). A search that matches nothing is different: the empty state is
decided before search (`PackagesFragmentViews.kt:523`, search `:560-567`), so it shows a blank list with
no text. New text is needed in 7 languages.

## P6. Lead: an installed disabled system app can look uninstalled — `NOT VERIFIED`

"Uninstalled" is inferred from `versionName == null && !isEnabled && type == SYSTEM` at
`PackagesFragmentViews.kt:881` and `PackageAdapter.kt:248` (`PackagesViewModel.kt:272` applies the same
test only to the pm-listed set, so it cannot mislabel an installed app). List rows get `versionName`
from `getPackageMetadataBatch` (`AndroidPackageDataSource.kt:247`, `:311`), which returns null when the
read fails (`:767-776`). A system app whose manifest has no versionName would match too
(`NEEDS CONFIRMATION`). The row then gets the uninstalled sheet, which hides Enable. 0 apps affected on
the test phone.

## Below the bar: true message, no lost control

- **P1. The no-access dialog opens only in English.** `PackagesFragmentViews.showError` (`:1128-1129`)
  matches the English text "Shizuku or root access required", which no translation contains, so other
  languages get a plain error dialog with the same true text. Every language already gets the
  permission setup dialog, because `SuperuserBannerState.shouldShowBannerForError` matches the word
  "Shizuku" (`SuperuserBannerState.kt:48-54`, then `PackagesFragmentViews.kt:508-510`); English gets
  both dialogs. `state.error` is a String by then (`PackagesViewModel.kt:512-515`), so matching the
  exception type needs the ViewModel to carry it.
- **P3. The Force Stop fallback has no user.** When Shizuku and root both fail,
  `killBackgroundProcesses(packageName)` (`AndroidPackageDataSource.kt:734`) runs with no user parameter
  and its result is ignored. The method returns false and the user is told "Unable to force stop
  package." (`PackageRepositoryImpl.kt:147-152`), which is true. What the call does across profiles and
  on Android 14+ is `NEEDS CONFIRMATION`.

---

# Wording and design — product choices

- The action sheet and dialogs never say which profile they act on (Work, Clone).
- The uninstall and reinstall dialogs (`strings.xml:614`, `:621`, `:625`) and the action sheet's
  uninstall line (`:105`) say "your device" for a profile-only action. The disable dialogs do not.
- Settings export and import do not say they use De1984's own profile (`strings.xml:408-431`).
- Boot Protection promises no network when De1984 fails to start (`strings.xml:740`) and blocking "until
  firewall activates" (`:378`), in all 7 languages. A failed boot start lifts the block
  (`BootWorker.kt:143-155`, `BootReceiver.kt:212-224`), and the script expires itself after about 120 s
  (`FIREWALL.md:391`).
- `fastlane/metadata/android/en-US/changelogs/18.txt` describes old health-check behaviour. It is the
  release note of versionCode 18, a record of that release.

# Needs a person

- **#73 lock/unlock:** scroll the firewall list, lock, unlock, look. adb cannot drive the pattern lock.
- **#91 quick-settings tile:** tap the real tile with "Confirm Firewall Stop" off. Only the receiver it
  hands over to was tested.
- **VPN `onRevoke`:** needs a second VPN app on the test phone.

# Needs a device or the Android source

Each decides a fix above.

- **F1:** block an app on the VPN backend, uninstall and reinstall it, check its traffic.
- **F3:** which user(s) `set-package-networking-enabled` acts on.
- **F7:** another VPN app starting while De1984's VPN has no tunnel.
- **P3:** what `killBackgroundProcesses` reaches, below and on Android 14.
- **P6:** `versionName` for a system app whose manifest has none.

# Backlog

- **#57 — Firewall Profile System.** The only open GitHub issue. A public reply gated it on #61, which
  closed on 2026-08-28. No profile code exists yet.
