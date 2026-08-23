# WristCord

A Discord voice remote for a Pixel Watch 4/5 that drives the **Discord app already running on
your paired Android phone**. Two modules, two installable debug APKs.

| Module    | minSdk | What it is                                                   |
|-----------|--------|--------------------------------------------------------------|
| `:mobile` | 31     | Phone app: onboarding, discovery probe, and the two strategies |
| `:wear`   | 33     | Watch app + a four-button Tile                                |

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
| `WristCord-phone-<version>-debug.apk` | the Android phone paired to the watch |
| `WristCord-watch-<version>-debug.apk` | the Pixel Watch 4/5 |

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

Both APKs:

```bash
./gradlew assembleDebug
```

Just one:

```bash
./gradlew :mobile:assembleDebug
```

```bash
./gradlew :wear:assembleDebug
```

Outputs:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`

Confirm the two APKs really do share an identity (this is the single most common reason the
Data Layer silently does nothing):

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs mobile/build/outputs/apk/debug/mobile-debug.apk
```

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs wear/build/outputs/apk/debug/wear-debug.apk
```

The `SHA-256 digest` lines must be identical, and `aapt2 dump badging` must show
`package: name='com.wristcord'` for both.

## Install the phone APK

USB, with the watch disconnected or `-s` naming the phone:

```bash
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
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
adb -s 192.168.1.50:5555 install -r wear/build/outputs/apk/debug/wear-debug.apk
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

## Acceptance checklist

- [ ] Mute works with the phone screen off (lock the phone, tap Mute on the watch, check Discord).
- [ ] Disconnect works with the phone screen off.
- [ ] Deafen works with the screen on; with the screen off it reports
      *"Phone screen is off — 'Deafen' needs the screen"* rather than doing nothing.
- [ ] Output lists the phone's devices and switching one works with the screen on.
- [ ] Pulling the phone out of Bluetooth range makes the watch show **Phone not connected**
      (`TARGET_NODE_NOT_CONNECTED`) instead of a dead button.
- [ ] Every failure you can provoke shows both a phone toast and watch-side text.

## Layout

```
settings.gradle.kts        two modules; shared/ is a source dir, not a third module
gradle/libs.versions.toml  version catalog
keystore/wristcord.jks     the shared debug signing key (checked in on purpose)
shared/kotlin/             Wire.kt - paths, VoiceState, ActionResult; compiled into both APKs
mobile/  discord/          selectors, notification listener, both strategies, controller
         audio/            AudioOutputs - the picker list
         link/             StatePublisher + WearableListenerService
         diagnostics/      Probe - the Step 0 dumps
         ui/               onboarding, diagnostics, selector overrides
wear/    ui/               Compose remote + output list
         link/             PhoneLink, StateStore, Commands, WearableListenerService
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
