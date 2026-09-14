# De1984 — Work List

Open work, ordered by harm to the user. This file is the queue, not an archive.

**Trust order: current code first, this file second.** Every line cited below was re-read on
2026-09-14 at `0330f7e`. Verify before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NOT VERIFIED` = reasoned or reported, not re-read
or not run. "Runtime" means seen on a device.

Everything finished was cleared on 2026-09-14. It is in git:

```
git show 0330f7e:PLAN.md      # the last full version
git log --follow -p PLAN.md   # everything removed, and when
```

Released: v2.7.5 (versionCode 46), commit `79b64a6`, tag `v2.7.5`.

---

# Firewall

## F1. Reinstalled blocked app escapes the VPN tunnel — `VERIFIED` in code, not at runtime

The rebuild decision compares package names only: `lastAppliedBlockedApps` is a `Set<String>`
(`FirewallVpnService.kt:66`), compared at `:433` against `blockedAppsFor` (`:436`). A reinstall gives
the app a new uid under the same name, so no rebuild happens and the tunnel keeps the old uid until
something else (network, screen) forces one. Likely fix: compare package plus uid.

## F2. ConnectivityManager backend mixes the same app across profiles — `VERIFIED` in code

`desiredPolicies` and `appliedPolicies` are keyed by package name alone
(`ConnectivityManagerFirewallBackend.kt:260-300`, `:324-358`), so a work-profile rule and a personal
rule for the same package overwrite each other.

## F3. ConnectivityManager backend acts in one user only — `VERIFIED` in code, not at runtime

`cmd connectivity set-package-networking-enabled $enabled $packageName` carries no user
(`ConnectivityManagerFirewallBackend.kt:353`, `:560`), so a De1984 installed in a work profile acts in
user 0. The test phone's ROM cannot run this backend at all.

## F4. A same-backend restart that times out leaves the VPN service retrying — `VERIFIED` in code, not at runtime

`FirewallManager.kt:556-563` reports `START_FAILED` (toggle shows OFF) and returns without stopping
the backend; the switch path at `:635-637` does call `newBackend.stop()`. The service keeps retrying
(1 s, 2 s, 5 s, then every 30 s while `isServiceActive`, `FirewallVpnService.kt:369-387`) and can
bring up a tunnel nothing reports.

## F5. The allow-critical firewall toggle does not re-apply rules — `VERIFIED` in code

`setAllowCriticalPackageFirewall` (`SettingsViewModel.kt:559-561`) only saves the pref, and its two
callers (`SettingsFragmentViews.kt:454-458`, `:1517-1520`) do nothing else. The backends read the pref
at apply time, and the app has no `OnSharedPreferenceChangeListener`, so the change lands at the next
unrelated rebuild.

## F6. Block All empty package read: the user is not told — `VERIFIED` in code, guard never seen firing

`IptablesFirewallBackend.kt:404-406` keeps the chain and leaves the resync armed, but only logs. Nothing
reaches the screen. Forcing a real enumeration failure on hardware was not attempted.

## F7. `isAnotherVpnActive` reads any VPN as De1984's own while the VPN backend is selected — `VERIFIED` in code, not at runtime

`FirewallManager.kt:2529-2545` returns false whenever a VPN transport is up and the active backend type
is VPN. With nothing to block, De1984 builds no tunnel (`FirewallVpnService.kt:322-326`), so the VPN
that is up belongs to another app. That link is reasoned. Proving it needs a second VPN app.

## F8. `networkPackagesCache` is cleared without its lock — `VERIFIED` in code, fix designed

`clearInstalledAppsCache` (`HiddenApiHelper.kt:540`, comment `:551-557`) nulls the cache without
`networkPackagesLock` (`:589`). A sweep already inside the lock republishes its pre-clear list with a
fresh timestamp, so a stale list survives one more TTL. Taking the lock is worse: it is held ~9.5 s for
466 packages and two callers are BroadcastReceivers (ANR). Fix: a generation counter the sweep checks
before publishing.

## F9. Banner can read "VPN failed, so we switched to VPN" — `VERIFIED` in code

`startVpnFallbackManually` passes `failedBackendType = FirewallBackendType.VPN`
(`FirewallManager.kt:2305`), and `startVpnFallback` publishes it as `SwitchedToVpn` (`:2052`). Caller:
`MainActivity.startVpnFallbackAfterPermission` (`MainActivity.kt:954`), after the VPN permission grant.
Commit `7bb0772` fixed only the widget and tile start (comment at `FirewallManager.kt:2080-2086`).

---

# Packages and notifications

## P1. The no-access dialog never opens outside English — `VERIFIED` in code

`PackagesFragmentViews.showError` (`:1128-1129`) matches the English text "Shizuku or root access
required". That phrase is in 6 English strings and in 0 strings of values-fr/it/pt/ro/ru/zh, so in
those languages a no-access failure shows a plain error dialog. The repository already throws a
`SecurityException` for no access (`PackageRepositoryImpl.actionFailed`); match on that instead.

## P2. A new-app notification keeps its Block/Allow button after a backend switch — `VERIFIED` in code

Reachability is checked once, when the notification is posted (`NewAppNotificationManager.kt:91-96`).
`FirewallManager` never touches posted new-app notifications, so after a fallback to VPN a work-profile
notification still offers a button whose rule the VPN cannot reach.

## P3. Force Stop fallback acts on De1984's own profile — `VERIFIED` in code, not at runtime

When both Shizuku and root fail, `activityManager.killBackgroundProcesses(packageName)`
(`AndroidPackageDataSource.kt:734`) runs for the calling user, not the row's `userId`. Not provable on
the Android 14 test phone.

## P4. Safety data gives up after 3 fast failures — `VERIFIED` in code, never triggered

`PackageSafetyLoader.kt:65-76`: after `MAX_LOAD_ATTEMPTS` failures in a burst, every app reads UNKNOWN
until the process restarts, which downgrades the uninstall rails.

## P5. Uninstalled chip empty text is often untrue — `VERIFIED` in code, wording is a product choice

`PackagesFragmentViews.kt:538-539` shows "No uninstalled system apps" whenever the Uninstalled chip
matches nothing, including when Bloatware, a profile or search narrowed it. Reported also for no root
and no Shizuku; that case was not re-checked after `2ec8267`. New text is needed in 7 languages.

## P6. Lead: a failed package-info read makes an installed disabled system app look uninstalled — `NOT VERIFIED`

"Uninstalled" is inferred from `versionName == null && !isEnabled && type == SYSTEM` in 3 places
(`PackagesFragmentViews.kt:881`, `PackageAdapter.kt:248`, `PackagesViewModel.kt:272`). If the version
read fails (`AndroidPackageDataSource.kt:823` returns null), the row gets the uninstalled sheet, which
hides Enable. 0 apps affected on the test phone.

---

# Wording and design — product choices

- The action sheet and dialogs never say which profile they act on (Work, Clone).
- The uninstall, disable and reinstall dialogs say "your device" for a profile-only action.
- Settings export and import do not say they use De1984's own profile.
- `fastlane/metadata/android/en-US/changelogs/18.txt` describes old health-check behaviour. It is a
  shipped release note.

# Needs a person

- **#73 lock/unlock:** scroll the firewall list, lock, unlock, look. adb cannot drive the pattern lock.
- **#91 quick-settings tile:** tap the real tile with "Confirm Firewall Stop" off. Only the receiver it
  hands over to was tested.
- **VPN `onRevoke`:** needs a second VPN app on the test phone.

# Backlog

- **#57 — Firewall Profile System.** The only open GitHub issue. A public reply gated it on #61, which
  closed on 2026-08-28. No profile code exists yet.
