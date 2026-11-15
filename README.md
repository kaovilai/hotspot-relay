<img src="assets/icon.svg" width="64" height="64" alt="">

# Hotspot Relay — automatic iPhone hotspot on Android

Keeps an Android phone connected to an iPhone's Personal Hotspot without any
taps. When the Android phone is away from Wi-Fi, it wakes the iPhone up over
Bluetooth; an iOS Shortcut reacts to that Bluetooth connection by switching
Personal Hotspot on; the app then joins the hotspot, retrying until it works.
Several iPhones can be configured: the list is an ordered priority list, and the
loop rotates to the next one (round-robin when the limit is 1).

- Android app: Kotlin + Jetpack Compose, foreground service, no ads, no network
  calls.
- Updates: published as signed GitHub Releases, so [Obtainium](https://github.com/ImranR98/Obtainium)
  installs and updates it.

## The loop

```
 no Wi-Fi                                 hotspot joined
    |                                           ^
    v                                           |
 Android --bluetooth(HID keyboard)--> iPhone ----+
    |                                 |
    |                                 +-- Shortcut: Bluetooth connected -> Set Personal Hotspot: On
    |
    +-- Wi-Fi: network suggestion + one-off request, retrying with backoff
```

1. **Android has no Wi-Fi.** The app leaves you alone while you are on any other
   Wi-Fi network; it only steps in when there is nothing to be on.
2. **Trigger the iPhone.** Android initiates a classic Bluetooth link to the
   paired iPhone by registering itself as a Bluetooth **HID keyboard device** and
   connecting while the iPhone is the HID host. This is deliberate: stock
   Android ships with the HFP-client profile disabled
   (`profile_supported_hfpclient=false`) and the A2DP/headset `connect()` calls
   are system APIs, so HID device is the one public, stock-supported way for an
   Android app to initiate a connection *to* an iPhone.
3. **iPhone switches the hotspot on.** A Shortcuts personal automation fires on
   "Bluetooth → when iPhone connects to <your Android phone>" and runs the
   *Set Personal Hotspot: On* shortcut in [`ios/`](ios/).
4. **Android joins.** The app registered your hotspot as a Wi-Fi network
   suggestion (one system approval) and issues a short targeted connection
   request, retrying 15s → 30s → 60s → 120s → 300s until it is associated.
5. **Tidy up.** Once the hotspot is joined the app drops the HID link, which
   restores your phone's own Bluetooth keyboard/mouse support and detaches the
   keyboard from the iPhone. Out of range, Wi-Fi drops and the loop starts again.

## Install

### Obtainium (recommended)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app → URL `https://github.com/kaovilai/hotspot-relay`
   → source *GitHub releases* (defaults are fine).
3. Obtainium tracks the APK asset on each release. Same signing key every time,
   so updates install over the previous version.

Or grab the APK from [Releases](../../releases/latest).

## One-time setup

### On the Android phone

1. Open the app, tap **Grant permissions** and allow Nearby devices, Location,
   and notifications. Location is only needed to read the current Wi-Fi name —
   Android redacts SSIDs otherwise.
2. Enable Bluetooth, then tap **Pair with iPhone (keyboard mode)**. The first tap
   registers the phone as a keyboard; if it says "Try again in a second", tap
   once more — the phone becomes discoverable and advertises a keyboard. On the
   iPhone: **Settings → Bluetooth → tap this phone** and confirm the pairing
   code. The iPhone should list it as a keyboard-like device.
   **Repeat this for every iPhone you add** — an unpaired iPhone is skipped.
3. Add one entry per iPhone: **Add iPhone**, then fill in the iPhone's
   Bluetooth name (or MAC), **hotspot SSID** and **hotspot password**. The
   SSID is exactly the iPhone's device name — **Settings → General → About →
   Name** (on a phone named `Alice's iPhone` the hotspot is `Alice's iPhone`,
   not `Alice's iPhone's iPhone`). Use **↑ / ↓** to set priority and the switch
   to enable or disable an entry.
4. Set **Move to the next iPhone after N failed attempts**: `1` behaves as
   round-robin, higher values give the first iPhone a longer turn (priority).
5. Tap **Allow hotspot auto-join for all iPhones** and approve the system
   dialog. This registers every complete entry as a network suggestion so
   Android can auto-join later.
6. Tap **Allow background battery use** and allow the exemption, otherwise
   Android may throttle the service.
7. Tap **Start**. A low-priority notification shows what the loop is doing and
   which iPhone it is currently targeting.

### Multiple iPhones

- The list is ordered. The first enabled, fully configured entry is tried
  first; after N failed attempts the loop moves to the next one and wraps
  around.
- An iPhone that is not paired (missing from the Bluetooth bonded list) is
  skipped immediately, without using up its attempts.
- If the phone is already on **any** configured hotspot — or the app's own
  request to it came up — that iPhone becomes the active target and no
  rotation happens.
- Disabled entries are simply excluded from the rotation, so you can turn one
  off without deleting it.

### On the iPhone

Personal automations cannot be exported or imported — Apple does not allow it —
so the trigger has to be created by hand. It takes about a minute:

1. Shortcuts → **Automation** → **+** → **Bluetooth**.
2. Device: pick your Android phone. Trigger: **Is Connected** (a.k.a. "when
   connected").
3. Action: search **Set Personal Hotspot** → set it to **On**.
4. Turn **Ask Before Running** off, and in the automation's Info → Privacy make
   sure **Allow Running When Locked** is on.
5. Ensure the iPhone's Personal Hotspot is enabled once in Settings and that the
   Wi-Fi password there matches what you typed into the app.

The standalone shortcut file in [`ios/iPhone-Hotspot-On.shortcut`](ios/iPhone-Hotspot-On.shortcut)
is the `Wait → Set Personal Hotspot On` action list, generated by
[`ios/build-shortcut.py`](ios/build-shortcut.py). Note two Apple limits:

- Since iOS 15 a `.shortcut` file must be **signed** before iOS imports it. Sign
  it on a Mac with [`ios/sign-shortcut.sh`](ios/sign-shortcut.sh)
  (`shortcuts sign --mode anyone ...`), then AirDrop the signed file over.
- The Bluetooth **trigger** still has to be attached to it manually, as above.

**Do this on every iPhone you added to the app.** Each iPhone needs its own
Bluetooth automation pointing at this Android phone, or it will never raise its
hotspot when the loop rotates to it.

If the imported shortcut shows the hotspot toggle as unset, set it to *On* once
and save.

## How it decides

`ReconnectEngine` is plain Kotlin with no Android imports, so every rule is
unit-tested (`./gradlew testDebugUnitTest`, 59 tests). Each tick:

| World state | Action |
| --- | --- |
| Associated with any configured hotspot SSID (or our own request to it came up) | idle, report that iPhone as connected |
| Associated with any other SSID — including one whose name Android hides | idle — never fights your home Wi-Fi |
| No Wi-Fi, Bluetooth/Wi-Fi off, or no enabled entry configured | blocked, with the reason |
| No Wi-Fi, current iPhone not paired | skip to the next configured iPhone immediately |
| No Wi-Fi, iPhone link down | `ConnectBluetooth`, backing off 15s…300s |
| iPhone link connecting | idle; after 45s treat as stuck and retry |
| iPhone link up | wait 8s for the shortcut, then `ConnectHotspot`, same backoff |
| New attempt due after N attempts on this iPhone | rotate to the next enabled iPhone, fresh retry budget |

Bluetooth and hotspot retries keep separate budgets, so hotspot attempts backing
off to five minutes never delay a fresh trigger after the link drops. Both reset
as soon as the link comes up or the hotspot is joined, so a fresh attempt always
starts at the short delay.

## External control (Tasker, adb, other automation)

The app exposes a small, documented control surface so another automation can
switch iPhones without opening the UI. Commands are broadcast to
`.receiver.ControlReceiver`; **explicit** broadcasts are recommended because
Android does not reliably deliver implicit broadcasts to manifest receivers in
the background:

```sh
adb shell am broadcast -n com.kaovilai.hotspotrelay/.receiver.ControlReceiver \
  -a com.kaovilai.hotspotrelay.action.SET_ENABLED \
  --es ssid "Work iPhone" --ez enabled false
```

| Action | Extras | Effect |
| --- | --- | --- |
| `…action.SET_ENABLED` | `id` \| `ssid` \| `label` \| `index`, `enabled` (bool, required) | Enable/disable one iPhone; its Wi-Fi suggestion is registered or removed to match |
| `…action.MOVE` | selector, `to` (0-based) or `delta` (+1/−1) | Reorder one iPhone; `delta` moves it relative to its position |
| `…action.SET_ORDER` | `order` = comma-separated SSIDs/labels/ids | Full reorder; unlisted entries are appended |
| `…action.SET_ROTATE_AFTER` | `value` (1–10) | Rotation limit; `1` = round-robin |
| `…action.START` / `…action.STOP` | — | Start/stop the loop |
| `…action.QUERY` | — | No change; returns the current state |

Rules:

- Exactly one selector extra is needed (`id` > `ssid` > `label` > `index`).
- Malformed commands are rejected with `ok=false`: a missing `enabled`, a
  non-boolean `enabled`, a non-numeric `to`/`delta`/`index`/`value`, or `MOVE`
  without either `to` or `delta`. The app never guesses a default.
- Every command answers with JSON in the ordered-broadcast result (`state`, `ok`,
  `message`) and also emits `com.kaovilai.hotspotrelay.action.STATE_CHANGED`
  with the same extras, so an automation can listen for the new state.
- `START`/`STOP` settle asynchronously, so the immediate response is only the
  request acknowledgement. The foreground service itself emits
  `STATE_CHANGED` whenever the loop actually starts or stops — including a
  start that Android refuses right after the acknowledgement — so listen for
  that event when you need the final `running` value.
- State JSON contains `running`, `rotateAfterAttempts`, and per-target
  `index/id/label/name/selector/ssid/enabled/complete`. **Passwords are never
  exposed, and commands cannot change credentials, SSIDs or Bluetooth
  selectors** — that stays in the app UI.
- `START` may be refused by Android when sent from the background
  (foreground-service launch restriction); the response then has `ok=false` and
  a message saying so.
- The receiver is exported by necessity (Tasker cannot hold a signature
  permission), so any app can toggle/reorder/start/stop. None of these actions
  can read secrets or add networks.

Tasker: *Send Intent* → Target **Broadcast Receiver**, Package
`com.kaovilai.hotspotrelay`, Class
`com.kaovilai.hotspotrelay.receiver.ControlReceiver`, Action
`com.kaovilai.hotspotrelay.action.SET_ENABLED`, Extra `ssid:Work iPhone`,
Extra `enabled:false:Boolean`. To react to changes, use an *Intent Received*
event for `com.kaovilai.hotspotrelay.action.STATE_CHANGED` and read
`%state`.

## Limitations worth knowing

- **Android cannot switch radios on.** Neither Wi-Fi nor Bluetooth can be enabled
  by an app on modern Android. If a radio is off the app reports "Needs
  attention" instead of silently retrying.
- **HID host is borrowed during an attempt.** While the app is registered as a
  HID device (pairing, or while trying to reach the iPhone), the phone's HID
  *host* service is disabled, so a Bluetooth mouse/keyboard will not work. It is
  restored as soon as the hotspot is joined or the service stops.
- **The iPhone shows a keyboard attached** while the link is up; it detaches
  after the hotspot is joined.
- **Wi-Fi joining is mediated by Android.** The app cannot force a network; it
  relies on the approved suggestion plus a short `requestNetwork` for the exact
  SSID. If the system refuses either, the retry loop cannot help.
- **Hotspot password storage.** It is kept in app-private SharedPreferences
  (sandboxed, excluded from backups). This is a personal automation app, not a
  secrets vault.
- **WPA2 assumed.** The app registers the hotspot with a WPA2 passphrase. If the
  iPhone hotspot is WPA3-only (iOS 16+ "Maximize Compatibility" turned off), the
  join will fail — leave Maximize Compatibility on.
- **Updates restart the loop.** The service is restarted after a reboot and after
  Obtainium replaces the package, as long as it was running before.
- **iOS ends the hotspot when no clients are attached.** Because Android stays
  connected while in range, this normally does not bite; if the iPhone's own
  hotspot timeout has turned it off, the next reconnect triggers the shortcut
  again.
- Tested part: unit tests, builds, signing, release publishing. Not testable
  here: the live iPhone/iOS interplay and OEM-specific Bluetooth stacks — verify
  once with your own pair.

## Build

Requires JDK 17 and the Android SDK with platform 37:

```sh
./gradlew testDebugUnitTest assembleDebug
```

Signed release (env vars keep the key out of the repo):

```sh
export HOTSPOT_RELAY_KEYSTORE=/path/to/release.jks
export HOTSPOT_RELAY_KEYSTORE_PASSWORD=...
export HOTSPOT_RELAY_KEY_ALIAS=hotspot-relay
export HOTSPOT_RELAY_KEY_PASSWORD=...
./gradlew test assembleRelease
```

## Releasing (what Obtainium sees)

1. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`.
2. Commit, then tag and push:

   ```sh
   git tag v1.0.1 && git push origin v1.0.1
   ```

CI builds the APK, signs it with the repository secrets
(`HOTSPOT_RELAY_KEYSTORE_B64`, `HOTSPOT_RELAY_KEYSTORE_PASSWORD`,
`HOTSPOT_RELAY_KEY_ALIAS`, `HOTSPOT_RELAY_KEY_PASSWORD`), verifies the
signature, and publishes `HotspotRelay-<version>.apk` on the GitHub Release.
Obtainium picks the higher `versionCode` up automatically.
