# De1984 — Work List

Open GitHub issues, ordered. This file is the queue, not an archive.

**Trust order: current code first, this file second.** Every claim below was checked against code on
the date shown. Verify before acting on any of it.

Status key: `VERIFIED` = read in code, line cited. `NOT VERIFIED` = reasoned, no device run.

The old audit log — findings, hardware test notes, the backend capability matrix, the settled
product decisions, the multi-site invariant traps — was cleared on 2026-08-27. All of it is in git:

```
git show ac1ce21:PLAN.md      # the last full version
git log --follow -p PLAN.md   # everything removed, and when
```

Released: v2.6.6 (versionCode 37), commit `4c6e9f23`, tag `v2.6.6`.

---

# Now

## #61 — Multiuser and work profile

Open since 2025-12-01. 20 comments. The reporter's last word (2025-12-17) was *"all the other
issues persist"*. Too old to stay an umbrella — split it before writing more code.

**Shipped and confirmed by the reporters:** work profiles are detected and listed, external
enable/disable is picked up for user 0, the landscape crash is gone, the work-app popup freeze is
gone, list scrolling is smooth.

**Still open, four pieces:**

### 61a. Work-profile status never refreshes by itself — `FIXED 2026-08-27`

**The bug.** `PackageMonitoringService` compared a set of `(packageName, userId)` pairs. Disabling an
app does not remove it from the device, so that set never changed and an enable or disable was
invisible — it could only ever see apps appearing. Meanwhile `ACTION_PACKAGE_CHANGED`, which covers
this for the profile De1984 runs in (user 0 on a normal install), never reaches another profile.

**Fixed** by also tracking which packages are disabled per profile
(`HiddenApiHelper.readDisabledPackagesFresh`, `PackageMonitoringService.checkForEnabledStateChanges`).
One `pm list packages -d --user N` per profile per poll, not one call per package — that is what
makes it affordable inside the 15-second loop.

Three traps closed while writing it:

- A failed read used to be indistinguishable from "nothing is disabled". `queryDisabledPackages` now
  returns **null on failure**, and a profile whose read fails is skipped rather than recorded.
  Without that, one bad shell call reads as every disabled app having just been enabled.
- `disabledPackagesCache` had no lock and now has two threads. `disabledPackagesLock` added.
- `installedAppsCache` was `@Volatile` only, which publishes the reference and not the contents,
  with four threads reaching `.clear()`. `installedAppsLock` added — taken **only on its own**,
  never while holding `networkPackagesLock`, because the reverse nesting is real and would deadlock.
- Profiles that disappear are pruned from `lastKnownDisabled`, so a work profile removed and
  re-created on the same userId does not report a change that never happened.

**Verified on hardware 2026-08-27** (TrebleDroid GSI, Android 14, work profile as user 10): disabling
NewPipe in user 10 from outside the app logged *"1 newly disabled, 0 newly enabled"* within 20 s;
re-enabling logged the reverse; 40 s idle produced **0** spurious events; 0 crashes. Re-run after the
lock changes with the same result.

**Still open — nothing watches other profiles until the app is opened.** The service is started from
`MainActivity.kt:165` alone. `START_STICKY` restarts it after a kill, but nothing bootstraps it at
boot, so a work-profile app installed or disabled while De1984 is closed was unnoticed until you next
opened it.

**Closed 2026-08-27.** Both boot paths now start the watcher once the firewall is up — `BootWorker`
(Android 12+) and `BootReceiver` (older, and app updates). Only on the success path, and
deliberately: this is a plain background service, and starting one is legal there only because the
firewall has just brought a foreground service up.

**Verified through a real reboot 2026-08-27:** `Cross-profile package watcher started` in the boot
log, `MAINACTIVITY CREATED` count 0, and disabling then re-enabling NewPipe in user 10 was detected
both ways — with the app never opened since the reboot.

One honest limit remains: with the firewall OFF and the app closed, the process is not kept alive by
anything, so nothing watches. That is Android, not a bug here.

**One correction kept from the audit:** `PackageMonitoringService` is not the only code that
*enumerates* profiles — `HiddenApiHelper.getUsers` has 12 call sites. It is only the code that
*watches* them over time. And `PackageChangedReceiver.kt:71` does derive a `userId` from `EXTRA_UID`,
so it is not blind to other profiles — the gap is delivery, not handling.

### 61b. `getInstalledApplicationsAsUser` fails for the work profile — `FIXED 2026-08-28`

**It was never flaky.** It fails on **every single call**, on this device, and always has. The old
description said "observed twice" because that is how often anyone happened to look.

**Why nobody knew:** the catch logged `e.message`, and the exception is an
`InvocationTargetException` whose own message is **null** — the reason lives in `cause`. So every
failure for months printed a bare `null`. Fixed by `describeReflectionFailure`, which walks to the
root cause. With that one change the answer appeared immediately:

```
RemoteException:
  at com.android.server.pm.ComputerEngine.enforceCrossUserPermission(ComputerEngine.java:2908)
  at com.android.server.pm.ComputerEngine.getInstalledApplications(ComputerEngine.java:4664)
```

**The app does not hold `android.permission.INTERACT_ACROSS_USERS` and never asks for it.** The
manifest mentions that permission once, at line 164, but as a `android:permission` a receiver
REQUIRES OF SENDERS — not something the app holds. So the reflection call can never succeed for
another profile, on any device, and the root/Shizuku shell fallback does all the real work.

`hiddenApiAvailable` is misleading here: it only records that HiddenApiBypass initialised, which says
nothing about whether the call is permitted.

**Proven fix, tested on hardware 2026-08-28 and then reverted pending a decision.** The permission is
`prot=signature|privileged|development`, and the `development` flag means a root or Shizuku
`pm grant` can hold it. Declared it, granted it, and:

```
before   Hidden API ... failed → "Found 216 apps for user 10 via root shell (synthetic)"
after    "✅ Found 216 apps for user 10 via hidden API",  shell calls for user 10: 0
```

Three things it buys:

1. No `pm list packages` shell per profile per enumeration.
2. **Real `ApplicationInfo` instead of synthetic.** The shell path builds work-profile entries with
   `createSyntheticApplicationInfo`, copying the PERSONAL profile's copy — the audit finding that an
   app differing between profiles has its rules computed from the wrong manifest. That disappears.
3. The whole "flaky work profile" story stops being true.

**Safe by construction:** ungranted, behaviour is exactly what it is today, because the fallbacks
already handle it.

**Shipped 2026-08-28.** `INTERACT_ACROSS_USERS` is declared in the manifest and the app grants it to
itself in `HiddenApiHelper.ensureCrossUserPermission` — root first, then Shizuku, once per process,
skipped entirely if already held. Called only for a profile other than De1984's own, which never needed it.

**Verified on hardware from a revoked state:**

```
after install    INTERACT_ACROSS_USERS: granted=false
                 ✅ Cross-user permission granted via root
   +99 ms        ✅ Found 216 apps for user 10 via hidden API
```

**The grant takes effect inside the same process** — no restart, no relaunch, the very next call
succeeds. That was the open question and it is answered.

After a full UI sweep: `via root shell (synthetic)` count **0**, `pm list packages -U` count **0**.
The synthetic path is not merely avoided, it is unused. Cross-profile enable/disable detection (61a)
re-verified on the new data path, both directions. Firewall rules unchanged, 0 crashes, lint at
baseline.

**Degrades safely.** No root and no Shizuku means the grant fails, the permission stays absent, and
every caller falls back exactly as before. This cannot make a non-privileged device worse.

**What it means for users:** `INTERACT_ACROSS_USERS` now appears on the F-Droid listing. It reads as
alarming for a privacy app and will draw questions — the honest answer is that it is the only way to
ask Android about work-profile apps directly, and the alternative was copying answers from the
personal profile's copy of each app.

**Also kept:** `describeReflectionFailure`, because a diagnostic that prints `null` is worse than
none. It is what found this.

**Audit follow-up 2026-08-28 — the first version had three real faults, all fixed:**

- **It blocked the main thread.** `PackageMonitoringService.onStartCommand` runs on the main thread
  and calls `startMonitoring()`, which calls `getCurrentInstalledPackages()` **synchronously** before
  launching its coroutine. A blocking `pm grant` there is an ANR on first launch. The grant now runs
  on its own IO scope and the caller never waits.
- **It latched a failure forever.** The flag was set before the attempt, and `Shell.getCachedShell()`
  is null until RootManager has actually opened a root shell — so at boot the single attempt was
  skipped and the process never tried again. **Reproduced live:** the first attempt logged *"not
  granted - using the shell fallback, will retry"*, and the retry succeeded **35 s later**. The old
  code would have spent that whole process on synthetic data. Now retries every 30 s until granted,
  which also covers root re-enabled in Magisk or Shizuku started mid-session.
- **`pm grant` had no `--user`**, so it targeted the shell's own user rather than the one De1984 runs
  in. Now `pm grant --user <myUserId>`.

Also: `getApplicationInfoAsUser` and `getPackageInfoAsUser` use the same cross-user APIs and never
asked for the permission — a process reaching those first got nothing. Both now do, which is cheap
because the granted case is a single volatile read.

**Re-verified from a revoked state:** grant retried and succeeded, `via hidden API` in use,
`via root shell (synthetic)` count **0**, firewall rules unchanged, 0 crashes, 0 main-thread stalls,
lint at baseline.

### 61c. Firewall takes too long to become active — `FIXED 2026-08-27` (16.1 s → 6.2 s of backend work)

Reporter measured 1 m 35 s and compared AFWall+ at 6 s for 181 apps.

**The condition matters.** All of the expensive work sits inside `if (isBlockAllDefault)`. On a
device set to "allow all" it never runs at all — which is why an earlier 1.62 s measurement here was
meaningless. The reporter suspected this themselves: one of their log files was named *"Firewall
Block All By Default May Have Something To Do With It"*.

**Measured on hardware** (110 packages with network permissions across 2 profiles, 103–106 rules
written, Block All on):

| | start time | getPackageInfo cache |
|---|---|---|
| before both fixes | 8.35 s | 53 hit / 587 miss |
| after the cache-key fix | — | 622 hit / **0 miss** |
| after the exempt-uid fix | **7.34 s** (repeated 7.35, 7.34) | 1018 hit / 0 miss |

**Fix 1 — the cache that never worked.** `getPackageInfoAsUser` keys on `"userId:flags:packageName"`,
so the flags are part of the key. The cached sweep asked with `GET_PERMISSIONS or GET_SERVICES`
(4100); all **seven** copies of `hasVpnService` asked with `GET_SERVICES` alone (4). Different key,
guaranteed miss, one binder call per app inside a filter over every package. The comment at
`HiddenApiHelper.kt:433` claimed the flags were chosen so the entry IS shared — **the code never did
that**, and trusting the comment would have hidden it. All seven now ask for both.

**Fix 2 — the same rule computed twice, opposite ways round.** Block All built the critical/VPN uid
set when "allow critical" was ON; `isUidExempted` re-derived the same test per uid when it was OFF,
each call reopening SharedPreferences and rescanning every package. The two sets are complements, so
one pass now serves both (`uidsWithCriticalOrVpn`, `allowCriticalEnabled`), at all three call sites.

**Fix 3 — the real one. Everything ran TWICE.** The two fixes above bought ~1 s of 8.35 s, because
neither touched the actual cost. Reading the timing log showed the rule application running from
start to finish twice per firewall start, computing the same 84 internet + 7 LAN uids and writing
them to the kernel both times.

**Root cause: two objects owned one chain.** `PrivilegedFirewallService` built its own
`IptablesFirewallBackend`; `FirewallManager` had a different one. That class is stateful about a
single kernel chain — `chainNeedsResync`, `blockedUids`, `blockedLanUids`. Each instance arrived
with the resync flag armed, rewrote the whole chain, and cleared only its own copy. **The flag could
never help across two instances**, so every start paid for two full rewrites, forever.

The split was already intended and only half-done: `start()` and `stop()` on the manager's instance
just fire an Intent at the service (there is a comment saying exactly that), while `startInternal()`
and `stopInternal()` do the real work. `applyRules` was never split that way and did the kernel work
on whichever object it was called on.

**Fixed** by making it a single lazily-created singleton, `De1984Dependencies.iptablesBackend`,
injected into `FirewallManager` and read by the service. Exactly one place constructs that class
now.

| | pass 1 | pass 2 | backend total |
|---|---|---|---|
| before | 5.9 s | 10.4 s (resync) | **16.1 s** |
| after | 5.7 s | **0.59 s** (diff path) | **6.2 s** |

**62% less backend work.** Pass 2 finally takes the diff path — what the resync flag was always for.

**A skip-if-unchanged check was written, measured, and dropped.** The idea was to have the second
pass read the chain and skip the write when it already matched. It never fired (with one instance,
pass 2 takes the diff path before reaching it) and its chain read cost ~1 s on every resync. The
case it was written for — an app update finding the chain already correct — does not arise either,
because the cold-start sweep tears the chain down first. Measured, not reasoned.

**Audit follow-up 2026-08-27:** `De1984Application.kt:133` was a sixth construction site, missed on
the first pass. It calls `stopInternal()`, which deletes the kernel chains AND clears the state — on
a throwaway the deletion still happened while the live object kept `blockedUids` populated and
`chainNeedsResync=false`, so its next apply would diff against a deleted chain and write nothing.
Silent total bypass. Now uses the shared instance.

### 61d. List position resets — `CANNOT REPRODUCE 2026-08-27`, see #73 below.

Three of four triggers verified on hardware. Only lock/unlock is untested, and it needs a human.

---

# Next

## #73 — Firewall list jumps to top — `CANNOT REPRODUCE 2026-08-27`

Reported standalone and again inside #61. **Three of four triggers tested on hardware and all hold
the scroll position:** switching away and back (HOME), switching tabs, and making a rule change from
a list row. That third one also covers the #61 reporter's separate complaint that *"when changes are
made in firewall, the page refreshes and reloads to the top"*.

**Why it is already fixed.** A guard exists whose own comment describes this bug: *"Nothing this
screen renders has changed. Rebuilding here reset the scroll position and re-read every visible icon,
on every unrelated settings write."* It fired in the log as *"nothing relevant changed - leaving the
list alone"*. The adapter reassignment at `FirewallFragmentViews.kt:516` — which two earlier versions
of this entry named as the cause — sits **behind** that guard and is never reached. Both reporters
were on v2.6.1/v2.6.2 in December; the fix landed after them.

Tab switching cannot lose the position for a second reason: `MainActivity.loadFragment` uses
`add`/`show`/`hide`, never `replace`, so the fragment view is not destroyed.

**Untested: lock/unlock**, which is the reporter's headline case. It could not be driven from adb —
the test device has a pattern lock. Needs a human to scroll, lock, unlock and look.

## Landscape state loss — no issue number, ours — `FIXED 2026-08-28`

Opened by our own #77 fix and found by the audit after it. Rotation no longer rebuilds the activity
(`configChanges`), but a **language change** and a **dark-mode change** still do, and this app has a
language switcher — so all of these stayed reachable.

**Fixed earlier (2026-08-27):** the launch intent is no longer replayed on a rebuild, and
`vpnPermissionContext` survives one. Those were the dangerous pair.

**Fixed 2026-08-28:**

- **Dialogs leaked.** None of the three fragments had ANY cleanup override. `onDestroyView` now
  dismisses `currentDialog`, `progressDialog` and `rebootingDialog`, wrapped because dismissing a
  dialog whose window has gone throws. Without it a batch job carried on behind a progress box the
  user could no longer see, and the recreated fragment's own dismiss was a no-op against a null.
- **Wrong-tab flash.** `setupBottomNavigation()` force-selects Firewall, which commits a fragment
  transaction, and only afterwards was the saved tab read. The bar is now put on the restored tab
  before anything draws. **Verified:** rebuild while on Settings comes back on Settings.
- **Side insets — our own damage from enabling landscape.** Only `systemBars.top` was applied and the
  root was padded to zero, so a 3-button navigation bar in landscape sat over the toolbar and the
  right edge of the list. Now `left`/`right` too; bottom is left to BottomNavigationView, which
  applies it itself. Portrait is unaffected — both are 0 there.
- **Multi-select lost.** Saved as "packageName|userId" strings and restored through a new
  `restoreSelection` on both adapters. **Verified on hardware:** select 3, force a rebuild, "3
  selected" with all three still ticked.

  Two false starts worth remembering: `observeSettingsState` ALWAYS rebuilds the adapter on its first
  emission (`previousObservedShowIcons` starts null, so `iconsChanged` is true) and that branch calls
  `exitSelectionMode()` first. Restoring in `onViewCreated` was wiped ~100 ms later, measured twice.
  The restore is now held and applied at the end of that rebuild branch.

## Filter chips reset themselves on every rebuild — `FIXED 2026-08-28`

Found while testing the above, and **a regression this project made worse**: the chips have always
lost their state on a rebuild, but while filters lived only in memory it was invisible. Once #71
persisted them, the bogus value was written to **disk** — so one language or dark-mode change
permanently cleared a filter the user had set.

**Measured:** `internet_only` pref `true` before a rebuild, `false` after, with two
"USER ACTION: Internet-only filter changed: false" in the log that no user performed.

**Root cause, from a captured stack rather than a guess:**

```
ViewGroup.dispatchRestoreInstanceState -> CompoundButton.onRestoreInstanceState ->
Chip.setChecked -> onCheckedChanged -> onPermissionFilterSelected(false)
```

Android restores the saved view hierarchy onto the chips and their listeners cannot tell that apart
from a tap. Every chip is inflated from the same layout and shares the id `filter_chip`, so the
restored state lands on the wrong chips as well.

**Fixed** with `isSaveEnabled = false` on every chip at creation. The chips are not the source of
truth — the ViewModel is — so the view tree has no business restoring them.

**Three earlier attempts failed and are worth recording**, because all three assumed the callback came
from BUILDING the chips: detaching listeners before `removeAllViews`, guarding construction with
`isUpdatingProgrammatically`, and adding the view before attaching its listener. None touch state
restore. The stack trace found it in one go; three guesses had not.

Kept anyway, as defence rather than fix: listeners are now detached before removal, and construction
runs inside a guard. Note the guard alone could never have worked — the type-chip listener sets
`isUpdatingProgrammatically = false` inside itself, disarming it mid-build.

**Verified:** pref `true` before and after a rebuild, 0 bogus user actions (was 2).

## #91 — Quick tile — `FIXED 2026-08-27`, one half already covered

Two separate complaints in one issue.

**"Stuck at Starting for about 10 seconds."** The tile shows "Starting…" until the firewall reports
Running, so it was displaying the real start time. Cut by the 61c work: Block All backend work
16.1 s → 6.2 s, and the start's own wait went from a fixed 500 ms to returning at ~408 ms. **Not
re-measured from the tile itself** — the tile only renders that number.

**"Stopping brings up the whole UI."** Deliberate, and the rule was written in TWO places —
`FirewallTileService.onClick()` and `FirewallToggleReceiver.onReceive()` both opened MainActivity.

Now a setting, **`Confirm Firewall Stop`, default ON** so nothing changes for existing users.
Off: the tile and the widget stop the firewall directly and post a notification saying so — a tile
tap that silently drops all protection is the thing the confirmation existed to prevent, so it is
never silent.

The predicate lives once, `FirewallManager.shouldConfirmStop()`, asked by both callers. The stop
itself happens in one place, the receiver; the tile just hands it over.

**Verified on hardware 2026-08-27:**

- Default ON, setting visible beside "Confirm Rule Changes".
- ON: broadcast → *"opening app for stop confirmation"*, chain untouched at 3 rules.
- OFF: broadcast → *"confirmation is off - stopping directly"*, chain 3 → 0, notification id 1010
  posted on `firewall_alerts_channel` with the right title and body.
- Strings in all 7 locales. Lint back to baseline (7 errors, 221 warnings) after removing a
  redundant SDK guard I had introduced.

**Not verified:** driving the actual quick-settings tile. The QS panel could not be driven from adb,
so the tile's own branch is code-reviewed while the receiver branch it delegates to is tested.

# Found while auditing, not yet acted on

## Block All fails OPEN when a package read fails — `FIXED 2026-08-27`, guard untested in anger

`getInstalledApplicationsAsUser` returns `emptyList()` on total failure (`HiddenApiHelper.kt:372`)
rather than signalling an error. In Block All mode the backend blocks what it enumerates, so an empty
enumeration means **nothing is blocked** — the user believes everything is blocked and it is not.

The window is not rare: `INSTALLED_APPS_CACHE_TTL` is 5 s, so the re-fetch that could fail happens
constantly. This is **not** caused by any recent change; three receivers plus a UI path already
cleared that cache from their own threads.

This is stronger than 61b's downgrade assumed. 61b concluded the worst case was "a transient empty
list"; this is what a transient empty list actually costs in Block All mode.

**Why the existing guard did not cover it.** The resync already refuses to rewrite on an empty
answer — but only when the RULE list is empty too. In Block All the user typically has rules, so
with 22 rules and a failed enumeration it never fired.

**Fixed 2026-08-27.** The Block All branch now treats an empty `allPackages` as a failed read, not a
real one: no device has zero packages with network permissions. It keeps whatever the chain already
holds, leaves `chainNeedsResync` armed so the next apply retries, and logs an error.

Only the Block All branch needed it. The allow-all branch drives its loop from the RULES, and uses
`allPackages` solely for exemptions — so an empty list there means fewer exemptions and therefore
MORE blocking, which is already fail-closed.

**Verified:** Block All start unaffected — 110 packages found, 106 rules written, guard fired 0
times. **Not verified:** the guard actually firing. Forcing a real enumeration failure on hardware
was not attempted. The user is still not TOLD when this happens; surfacing it is a further step.

## The firewall could stay DOWN after an app update — `FIXED 2026-08-27`

Seen on hardware, on committed code, with nothing uncommitted. After `adb install -r` while the
firewall was running, `FirewallManager` logged `FIREWALL DOWN (START_FAILED, backend=null): apps are
UNBLOCKED`, the chain was empty, and it **stayed** that way until the app was opened by hand.

**Two faults, not one.** `start()` on the privileged backends only posts an Intent and returns; the
manager then waited a single fixed `delay(500)` and checked `isActive()` once. If that check failed
it called `stop()` — **tearing down the service that was still coming up**. So a slow start became a
guaranteed failure, and nothing retried.

**Measured after the fix, twice, on a real app update:**

```
reinstall 1   IPTABLES became active after 609ms
reinstall 2   IPTABLES became active after 613ms
```

**Both over 500 ms.** Under the old code both would have failed and told the user the firewall was on
while it was not. Not a rare race — it happened on every update.

**Fixed** by `FirewallManager.awaitBackendActive`: poll every 200 ms, give up only after 8 s. The
healthy path got faster too, returning at ~408 ms instead of always sleeping 500 ms.

## The same-backend restart path had no active-check — `FIXED 2026-08-27`

The restart branch called `start()` and went straight on to `applyRulesToBackend()`. Since `start()`
is fire-and-forget for the privileged backends, rules could be written before the chain existed —
`-A de1984_output` against a chain `-N de1984_output` had not created yet. That is the ordering race
the switch path already documented, on a path that simply never got the guard.

Found by the audit of the shared-instance change. **Fixed with the same `awaitBackendActive` helper
as the update gap above**, so both start paths now wait the same way and the rule lives in one place.

## `networkPackagesCache` is cleared without its lock — `VERIFIED 2026-08-27`, deliberately not fixed

`clearInstalledAppsCache` nulls `networkPackagesCache`/`networkPackagesCacheTime` while every writer
holds `networkPackagesLock` (`HiddenApiHelper.kt:419`, `:451`). A sweep already inside the lock
finishes afterwards and republishes its pre-clear result with a fresh timestamp, so the stale list
survives one more TTL — defeating the refresh that was just requested.

**Taking the lock would be worse than the bug.** That block is held for the whole computation,
measured at 9,499 ms for 466 packages, and two callers of `clearInstalledAppsCache` are
BroadcastReceivers, where a wait that long is an ANR. The correct fix is a **generation counter** the
sweep checks before publishing. Left as a task rather than done badly; there is a comment at the call
site saying so.

---

# Needs an answer before any work

## #97 — Zorin Connect blocked while allowed

**Only the iptables backend reads `lanBlocked`** — 84 mentions in `IptablesFirewallBackend`, **zero**
in `VpnFirewallBackend`. The LAN toggle also says *"Requires root access"*. Zorin Connect needs the
desktop to reach **into** the phone, and a VPN-based firewall cannot pass incoming connections at
all. Ask the reporter which backend is active and whether they have root or Shizuku before treating
this as a bug.

---

# Backlog — feature requests

- **#96 — Filter for bloatware apps**, plus exporting package names to a text file.
- **#83 — Act on the new-app notification.** Half exists: `PackageMonitoringService` already sends
  the notification. The ask is allow/block buttons on it, and tapping through to that app's rules.
- **#57 — Firewall profile system.** Gated on #61 by a public reply already posted.

---

# Closed here

- **#77 — Landscape mode.** Both `screenOrientation="portrait"` locks removed (commit `6597752`),
  then `configChanges` added to both activities so a rotation is handled in place instead of
  rebuilding the activity. **Verified on hardware 2026-08-27** (TrebleDroid GSI, Android 14):
  display reached `854x480`, five tab switches, 0 crashes; after `configChanges` the rotation
  produced **no** `MAINACTIVITY CREATED`/`DESTROYED` at all and the active filter survived it.
  Safe because the app has no `layout-land`, no `values-land`, no `swXXXdp` folder, and never reads
  `Configuration.orientation`. The issue title asks for a *setting*; this follows the system
  auto-rotate instead — **confirm that is enough before closing on GitHub**. See *Landscape state
  loss* above for what the change exposed.

- **#71 — Small filter improvements.** All three: the checked icon is hidden
  (`checkedIconVisible="false"`), the accidental double gap is gone (the chip's own
  `layout_marginEnd` removed, `chipSpacing="7dp"` on the group kept), and both screens remember
  their filters through `utils/FilterPrefs.kt`. **Verified on hardware 2026-08-27** — set "User",
  left the screen, returned: filter and chips both held.

  Three bugs the follow-up audit found in that new code, all fixed before it shipped: the packages
  flow re-emits, so the filter captured when the load job started went stale and was being written
  back over the user's live selection; the deep-link path passes `foundPkg.type.toString()`
  ("USER", the enum name) which no chip mapper reads back, and persisting it would have made a
  wrong-looking chip permanent; and restoring the "Uninstalled" state filter would have emptied the
  Packages screen at every launch, because `PackagesViewModel` then collected plain `invoke()` which never
  contains uninstalled system packages. The last two are now normalised at the `FilterPrefs`
  boundary, so any future caller is covered too.

- **#84 — iptables rules not applied for all apps.** Duplicate DROP rules accumulated in the chain,
  so allowing an app removed one copy and left the rest. Fixed in v2.6.6 by rebuilding the chain to
  match the rules exactly and reading it back from the kernel. Closed 2026-08-27. The reporter's own
  case was **never reproduced here** — reopen if it returns.
