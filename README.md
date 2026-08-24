# WristCord

A Discord voice remote for a Pixel Watch 4/5 that drives the **Discord app already running on
your paired Android phone**. Two modules, two installable APKs, one per device.

| Module    | minSdk | What it is                                                   |
|-----------|--------|--------------------------------------------------------------|
| `:mobile` | 31     | Phone app: onboarding, discovery probe, and the two strategies |
| `:wear`   | 33     | Watch app + a configurable Tile                               |

Both are built with `compileSdk`/`targetSdk` 36, Kotlin + Compose, **the same `applicationId`
(`com.wristcord`) and the same signing key** — Google Play services only routes Data Layer
traffic between apps whose package name *and* signing certificate match on both devices. The
shared key lives at [`keystore/wristcord.jks`](keystore/wristcord.jks) and is wired into the
`debug` and `release` signing configs of both modules.

> The keystore is checked in on purpose and is a **throwaway development key** — its only job is
> to make the two APKs' signatures match so the Data Layer will talk. It guards nothing. If you
> ever want to distribute this properly, generate your own key and replace it; never reuse this
> one for anything you care about.

## Download

Grab both APKs from the [latest release](../../releases/latest). You need **both** — one per
device. A Wear OS app is a separate installable (`android.hardware.type.watch`, minSdk 33) and
the phone half is the side that actually touches Discord, so neither device can run the other's
build.

| File | Install on |
|------|-----------|
| `WristCord-phone-<version>.apk` | the Android phone paired to the watch |
| `WristCord-watch-<version>.apk` | the Pixel Watch 4/5 |

Once both are on, neither needs a computer again: **Settings → Updates** on the watch and the
**Updates** card on the phone pull the next release straight from this repo. Each device
updates only itself.

## How it controls Discord — and how it does not

Discord publishes no API for controlling its own Android client. Automating a user account or
shipping a reimplemented client is a Terms of Service violation that gets accounts terminated.
WristCord therefore contains **no user token, no self-bot, and no Discord client code**, and
never talks to Discord's servers. It does exactly two things, both of which are things a human
holding the phone can do:

1. **Fires the PendingIntents Discord itself attached to its own ongoing voice-call
   notification.** Discord built the intent and owns the receiver; WristCord only pulls the
   trigger. Works with the phone screen off.
   → [`NotificationActionStrategy`](mobile/src/main/kotlin/com/wristcord/mobile/discord/NotificationActionStrategy.kt)
2. **Clicks Discord's own in-call buttons through an AccessibilityService** scoped to
   `com.discord` alone. Needs the phone screen.
   → [`A11yStrategy`](mobile/src/main/kotlin/com/wristcord/mobile/discord/A11yStrategy.kt)

| Action     | Strategy                            | Screen off? |
|------------|-------------------------------------|-------------|
| Mute       | notification action, a11y fallback  | yes         |
| Disconnect | notification action, a11y fallback  | yes         |
| Deafen     | a11y (notification if it exists)    | no          |
| Output     | a11y, always                        | no          |

Deafen prefers the notification when Step 0 discovery finds a real deafen action on your build
of Discord; most builds do not have one, so it falls to accessibility.

**No strategy ever fails silently.** Every command returns an `ActionResult` to the watch; every
failure raises a phone toast *and* a watch-side error state. If the phone never answers at all, a
15-second watchdog on the watch turns the wait into a visible error.

## Step 0 — discovery before you trust a single selector

Discord's notification action titles and view ids are undocumented and shift between releases
and locales, so nothing in this project guesses silently. Every pattern in
[`DiscordSelectors.kt`](mobile/src/main/kotlin/com/wristcord/mobile/discord/DiscordSelectors.kt)
is a *default* that you correct from real data — and the phone app can override each one at
runtime, so a wrong selector costs you a text field, not a rebuild.

Run this before relying on the app: **[docs/step0-discovery.md](docs/step0-discovery.md)**.

## Build

Both APKs, the way the releases are built (R8-shrunk, not debuggable — see
[why](#why-the-released-apks-are-release-builds)):

```bash
./gradlew assembleRelease
```

Debug builds, for iterating:

```bash
./gradlew assembleDebug
```

Outputs:

- `mobile/build/outputs/apk/release/mobile-release.apk`
- `wear/build/outputs/apk/release/wear-release.apk`

Confirm the two APKs really do share an identity (this is the single most common reason the
Data Layer silently does nothing):

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs mobile/build/outputs/apk/release/mobile-release.apk
```

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs wear/build/outputs/apk/release/wear-release.apk
```

The `SHA-256 digest` lines must be identical, and `aapt2 dump badging` must show
`package: name='com.wristcord'` for both.

## Install the phone APK

USB, with the watch disconnected or `-s` naming the phone:

```bash
adb install -r mobile/build/outputs/apk/release/mobile-release.apk
```

## Install the watch APK over wireless ADB

The Pixel Watch 4/5 charges over side pins and exposes **no USB data path**, so the watch APK
has to go over Wi-Fi. Put the watch and your machine on the same network first.

**On the watch:**

1. Settings → System → About → Versions → tap **Build number** seven times.
2. Settings → **Developer options** → turn on **ADB debugging**, then **Wireless debugging**.
3. Wireless debugging → **Pair new device**. The watch shows a six-digit code and an
   `IP:PORT` — that port is the *pairing* port and is different from the connect port.

**On your machine** (the pairing port from the "Pair new device" screen):

```bash
adb pair 192.168.1.50:37123
```

Enter the six-digit code when prompted. Then connect using the `IP:PORT` shown on the main
**Wireless debugging** screen — a different port from the pairing one:

```bash
adb connect 192.168.1.50:5555
```

```bash
adb devices -l
```

Install, naming the watch explicitly so the phone does not get the watch build:

```bash
adb -s 192.168.1.50:5555 install -r wear/build/outputs/apk/release/wear-release.apk
```

If `install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an older WristCord signed with a
different key is present — `adb -s 192.168.1.50:5555 uninstall com.wristcord` and retry.

Then add the tile: long-press the watch face → **Tiles** → **+** → **WristCord**.

## First run

1. Open **WristCord** on the phone. Grant both permissions from the onboarding screen — each
   row has a live granted/running indicator, so you can watch it turn green:
   - **Notification access** → `ACTION_NOTIFICATION_LISTENER_SETTINGS`
   - **Accessibility service** → `ACTION_ACCESSIBILITY_SETTINGS`
2. Join a Discord voice channel on the phone.
3. Run the Step 0 dumps and fix any selector that does not match reality.
4. On the watch, open WristCord or its tile. Mute/Deafen should now show live state.
5. Optional: watch Settings → **Buttons** to move actions between positions.

## The watch app

Laid out like the YouTube Music Wear OS player, because that shape is built for the same job -
one control you can hit without looking, everything else within a thumb's reach of it.

```
              12:45              <- system time
             Gaming
      (deafen) ( MUTE ) (output) <- centre disc is ~1.7x the satellites
              Mute
              Live
          (leave)  (gear)
```

- **Centre is oversized on purpose.** It is the button you press mid-sentence without
  looking at your wrist.
- **Colour carries meaning, not decoration.** Mute is Google Blue, Deafen Yellow, Output
  Green, Leave Red - the same four on the tile and in the phone app. A control is a dark disc
  with a coloured glyph when it is off, and fills solid with its own colour when it is on. You
  can read mute state from the corner of your eye.
- **Always-on.** The remote stays up in ambient: black background, outline-only controls, no
  fills, and content nudged a couple of pixels per tick on panels that ask for burn-in
  protection. Tapping is disabled in ambient so a sleeve cannot hang up your call.
- **Settings** (the fixed grey gear, bottom right) is never one of the assignable slots.

### Rearranging the buttons

Settings shows a live miniature of the remote with every position outlined, the way the
watch-face complication editor does it. Tap a position, pick what goes there:

| Position | Default |
|----------|---------|
| Centre (large) | Mute |
| Left | Deafen |
| Right | Output |
| Bottom | Leave |

Any position can hold Mute, Deafen, Output, Leave, Refresh, or Empty. Put Deafen in the centre
if that is the one you reach for; leave a slot Empty if you would rather not risk hitting it.
**The tile follows the same assignments** - it gives the centre action a full-width primary
button and puts the other three underneath, so both surfaces stay in agreement.

## Updating

Both apps check [this repo's releases](../../releases) and install their own APK.

- **Watch:** Settings → Updates → Check for updates
- **Phone:** the Updates card on the main screen

The first time, Android asks you to let WristCord install apps; both screens link straight to
that setting.

Before anything is handed to the package installer, the downloaded APK's signing certificate is
compared against the certificate of the app doing the updating, and a mismatch aborts with an
explanation. Android would reject a mismatched update anyway - doing the check ourselves means
WristCord never stages an APK that did not come from the same key it was built with, and you
get a sentence rather than an opaque system error.

## Why the released APKs are release builds

The v1.0.0 assets were debug builds, and debug builds are genuinely slow on watch hardware: a
debuggable app opts out of ART's full optimisation, and Compose keeps its tooling hooks live.
Releases are now built with `isMinifyEnabled` and R8:

| | v1.0.0 (debug) | v1.1.0 (release) |
|---|---|---|
| phone APK | 29.4 MB | 2.8 MB |
| watch APK | 39.9 MB | 3.6 MB |

R8 runs with `-dontobfuscate` (see [`wear/proguard-rules.pro`](wear/proguard-rules.pro)): it
still tree-shakes, which is where the size comes from, but nothing is renamed - so a crash on
the wrist still produces a readable stack trace and no manifest-resolved component can be
broken by obfuscation. The wear build also drops the x86 native slices, which no watch needs
and which the watch would otherwise have to download over Wi-Fi on every update.

Also fixed: `com.wristcord.shared.*` is declared stable to the Compose compiler
([`compose-stability.conf`](compose-stability.conf)). `VoiceState` holds a `List`, so without
that declaration every state push from the phone recomposed the entire remote.

Debug builds still work the same way if you want them:

```bash
./gradlew assembleDebug
```

## Acceptance checklist

- [ ] Mute works with the phone screen off (lock the phone, tap Mute on the watch, check Discord).
- [ ] Disconnect works with the phone screen off.
- [ ] Deafen works with the screen on; with the screen off it reports
      *"Phone screen is off — 'Deafen' needs the screen"* rather than doing nothing.
- [ ] Output lists the phone's devices and switching one works with the screen on.
- [ ] Pulling the phone out of Bluetooth range makes the watch show **Phone not connected**
      (`TARGET_NODE_NOT_CONNECTED`) instead of a dead button.
- [ ] Every failure you can provoke shows both a phone toast and watch-side text.
- [ ] Lower your wrist mid-call: the remote stays up, dimmed to outlines, and still reads the
      right mute state.
- [ ] Move an action to a different position in Settings; the tile picks up the same change.
- [ ] Settings → Updates finds this repo and reports the installed version.

## Layout

```
settings.gradle.kts        two modules; shared/ is a source dir, not a third module
gradle/libs.versions.toml  version catalog
keystore/wristcord.jks     the shared debug signing key (checked in on purpose)
shared/kotlin/             Wire.kt (paths, VoiceState, ActionResult), Brand.kt (palette,
                           RemoteAction, Slot), Updater.kt + UpdateController.kt (self-update);
                           compiled into both APKs
mobile/  discord/          selectors, notification listener, both strategies, controller
         audio/            AudioOutputs - the picker list
         link/             StatePublisher + WearableListenerService
         diagnostics/      Probe - the Step 0 dumps
         ui/               onboarding, diagnostics, selector overrides
wear/    ui/               radial remote, settings, output list, updates, theme, ambient
         link/             PhoneLink, StateStore, SlotConfig, Commands, listener service
         tile/             Material3TileService + tap plumbing
```

`shared/kotlin` is added to both modules' `main` source set via `sourceSets`, so the wire
protocol has exactly one definition while the project still has exactly two Gradle modules and
produces exactly two APKs.

## Notes on things that look like they should work but do not

- **`AudioManager.setCommunicationDevice()` cannot move Discord's audio.** Since Android 12 it
  is scoped to the *calling* app's uid. Called from WristCord it would re-route WristCord's own
  (nonexistent) voice stream and leave Discord exactly where it was — a silent no-op. That is
  why the Output picker drives Discord's own control instead, and why `AudioOutputs` only ever
  *lists* devices. See the comment in
  [`AudioOutputs.kt`](mobile/src/main/kotlin/com/wristcord/mobile/audio/AudioOutputs.kt).
- **There is no mute/deafen state to read.** Discord exposes it only as the label on the button
  that would undo it: an action titled "Unmute" means you are muted right now. When neither
  label is present the state is genuinely unknown, so `Toggle` is tri-state and the watch says
  "State unknown" rather than inventing "off".
- **The accessibility service is scoped to `com.discord`** in
  [`discord_accessibility_service.xml`](mobile/src/main/res/xml/discord_accessibility_service.xml).
  It receives no events from any other app on the phone.
