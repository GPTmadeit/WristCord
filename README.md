# WristCord

**A Discord client for the Pixel Watch 4/5.** Sign in with Discord's own QR login, then read
your servers, DMs, unreads and voice calls straight from Discord, in Discord's own themes.
The voice remote from v1 is still here; its mute state now comes from Discord itself.

> **2.0 is a beta.** The protocol work is verified against Discord's live servers (details
> below), but the signed-in screens have only been exercised with a synthetic account. Expect
> rough edges, and please report them.

> **Unofficial client.** WristCord is not made by or affiliated with Discord. Discord's Terms
> of Service do not allow third-party clients, and an account using one can be actioned. WristCord
> behaves like Discord's own Android app on the wire, does nothing automated, and only ever does
> what you tap, but the risk is yours to weigh. v1.3 (phone bridge only, no Discord login) is still
> on the [releases page](../../releases/tag/v1.3.0) if you would rather not take it.

| Module     | minSdk | What it is |
|------------|--------|------------|
| `:wear`    | 33     | The watch app: the Discord client, voice remote, tile, complication, watch-face chip |
| `:mobile`  | 31     | Optional phone app: the *voice bridge* that presses mute/deafen/output on the phone's Discord call |
| `:discord` | -      | Plain-JVM library: gateway, REST, QR login, settings proto, live state. Unit-tested on the build machine |

## Download

Grab the APKs from the [releases page](../../releases). 2.0 betas are GitHub **pre-releases**, so
a watch still on v1.3 will not offer them by itself; install the first beta by hand (below, or
with [WatchPush](https://github.com/GPTmadeit/WatchPush)). Each beta updates itself after that.

| File | Install on | Needed? |
|------|-----------|---------|
| `WristCord-watch-<version>.apk` | the Pixel Watch | yes |
| `WristCord-phone-<version>.apk` | the Android phone paired to the watch | only for mute/deafen/output on the **phone's** call |

Both are signed with the same key as every v1 release, so they install over v1.3 without
losing your button layout.

## What it does

- **Sign in with a QR code.** The watch shows one; Discord on your phone (**You → ⚙ → Scan QR Code**)
  reads it, asks you to confirm, and the watch gets a session of its own. No password or MFA
  prompt ever touches the watch. Settings → **Sign out** ends that session server-side.
- **Servers, in your order.** Your server list exactly as the phone has it, folders included,
  with Discord's unread pip and red mention count. Muted servers and channels stay quiet.
- **Channels you can actually see.** Discord sends every channel of a server; WristCord runs
  Discord's permission resolution itself and hides the ones your roles cannot open.
- **Read and reply.** Messages render with Discord's markup: bold/italic/underline/strike,
  spoilers (tap to reveal), code, quotes, headings, subtext, lists, masked links, mentions,
  role colours, custom emoji, `<t:…>` timestamps, replies, forwards, embeds, stickers, images.
  The **Message** edge button opens the watch's own input (voice, keyboard, emoji).
- **Reactions from your Discord frecency.** The react palette is built from the emoji you
  actually use in Discord, synced from your account (custom server emoji included), and falls
  back to sensible defaults.
- **Read state everywhere.** Opening a channel marks it read on your phone and desktop too, and
  reading on the phone clears it on the watch.
- **Status.** Online / Idle / Do Not Disturb / Invisible and custom status, set the way Discord's
  apps set them (through your account settings), so every device follows.
- **Voice, for real.** Which call you are in, on which device ("on your phone", "on desktop"),
  who else is there and their mute/deafen state, all from Discord's gateway.
- **Your Discord theme.** The watch follows your account's appearance setting: **Dark, Ash,
  Onyx or Light**, plus your **Nitro gradient** if you use one. Change it on the phone and the
  watch follows. Every colour comes from Discord's own design tokens (below). Settings → Theme
  overrides it per watch.
- **Shows up as your watch.** In Discord's Settings → Devices the session is listed under the
  watch's model name, not as a generic phone.

## The voice remote, and why the phone bridge still exists

The radial remote from v1 is unchanged in shape: big centre button, two satellites, a bottom
button, double-pinch for the centre, always-on while it is open. What changed is where the
state comes from: the red mic on your wrist is Discord's own `self_mute`, not a guess read off
a notification label.

**Pressing** mute on a call that is running on your phone is different, and the reason was
found in Discord's app rather than assumed. Discord does have a "control another session's
voice" command (gateway op 29, `REMOTE_COMMAND`), and the Android app uses it to mute, deafen and
hang up calls running on an **Xbox or PlayStation**. It only ever *sends* those commands. Nothing
in the app acts on one it receives
([details](docs/discord-protocol.md#remote-voice-control-what-the-protocol-does-not-allow)). So
no second device can mute the phone's call through Discord's servers, and taking the call over
from the watch would move it off the phone.

So the four call buttons still go through the **phone bridge**: the optional phone app fires
Discord's own notification actions (mute and leave work with the phone locked) or presses its
in-call buttons through an accessibility service scoped to `com.discord` (deafen and output, with
the screen on). Setup is unchanged from v1: see [docs/step0-discovery.md](docs/step0-discovery.md).
Without the phone app, the remote still shows the real state, and **Leave** falls back to asking
Discord's gateway to drop you from the call (best effort: Discord's own apps never do this for
another device's call, so it is untested).

| Action     | State from        | Pressed through |
|------------|-------------------|-----------------|
| Mute       | Discord gateway   | phone bridge (notification action, a11y fallback) |
| Deafen     | Discord gateway   | phone bridge (a11y, or notification if present) |
| Output     | phone bridge      | phone bridge (a11y) |
| Leave      | Discord gateway   | phone bridge, else gateway |

If the call is on your desktop, the remote says so instead of pretending its buttons will work.

## Battery

The watch holds a connection to Discord only while something needs it: the app on screen, a
tile you just swiped to, or a voice call in progress (while the watch-face chip is on, which is
the default, it keeps the connection open for the length of the call so the tile and
complication stay live). A minute after the last of those
lets go it disconnects, keeping the session resumable for a quick return. It always reports
itself to Discord as **AFK**, so your phone keeps getting push notifications while the watch is
connected.

## Privacy and security

- The token is encrypted with an AES-256-GCM key held in the watch's Android Keystore. The key
  never leaves the keystore and backups are off.
- WristCord talks to `discord.com`, `gateway.discord.gg`, `remote-auth-gateway.discord.gg` and
  Discord's CDNs, plus `api.github.com` for updates. Nothing else, no analytics.
- The phone app still holds no token and never talks to Discord.

## Updating

**Watch:** Settings → Updates. **Phone:** the Updates card. Both download the matching APK from
this repo's releases, check that its signing certificate matches the installed app's, then hand
it to the system installer. Beta builds follow betas and stable releases; stable builds only
follow stable releases.

## Build

```bash
./gradlew assembleRelease
```

Outputs `wear/build/outputs/apk/release/wear-release.apk` and
`mobile/build/outputs/apk/release/mobile-release.apk`, both R8-shrunk and signed with the shared
key in [`keystore/wristcord.jks`](keystore/wristcord.jks). That key is a throwaway development key
checked in on purpose: its only job is making the two APKs' signatures match so the Data Layer
will route between them. Don't reuse it for anything you care about.

Toolchain: AGP 9.1, Gradle 9.3.1, JDK 17+, `compileSdk` 37 (`sdkmanager "platforms;android-37.0"`).

### Tests

```bash
./gradlew :discord:test :wear:testDebugUnitTest
```

The `:discord` suite covers zlib-stream framing, the settings and frecency protos, markdown,
permission resolution, the QR-login crypto, and a READY shaped the way Discord sends it. Two more
tests run against Discord's **live** servers without an account:

```bash
./gradlew :discord:test --tests '*LiveTest' -Dwristcord.live=true
```

- the QR handshake runs until Discord hands back a scannable `https://discord.com/ra/…` code.
  Discord only does that after accepting the key exchange and nonce proof, so it proves the
  reverse-engineered flow end to end.
- the gateway connects, inflates HELLO, heartbeats, sends IDENTIFY, and gets Discord's `4004` for a
  token that cannot be valid.

### Looking at every screen without an account

Debug builds have a synthetic demo account:

```bash
adb shell am start -n com.wristcord/.wear.MainActivity --ez com.wristcord.DEMO true \
    --es com.wristcord.DEMO_SCREEN chat --ei com.wristcord.DEMO_THEME 1
```

`DEMO_SCREEN` is one of `dms guild chat message remote people settings status theme buttons`.
`DEMO_THEME` is the proto theme (1 Ash, 2 Light, 3 Dark, 4 Onyx), and `--ei com.wristcord.DEMO_GRADIENT 9`
adds a Nitro gradient. Release builds contain none of this.

## How it was built: reverse engineering Discord's app

Everything WristCord does on the wire was taken from Discord's own Android app (351.11),
not from guesswork: the gateway URL, IDENTIFY payload and capability flags, the client identity
and REST headers, the QR-login protocol, the settings protobuf field numbers, the voice
remote-command limits, and the colour tokens for every theme. The app is React Native compiled
to Hermes bytecode v98. It was parsed (never run) with
[hermes-dec](https://github.com/P1sec/hermes-dec) plus a string → function → module indexer.
Original source file names survive in the bundle, so each finding is cited by Discord's own
module path.

**[docs/discord-protocol.md](docs/discord-protocol.md)** has the findings, where each came
from, and how sure we are of each. [`tools/gen_theme.py`](tools/gen_theme.py) regenerates the
theme tables from the extracted tokens.

## Install the watch APK over wireless ADB

The Pixel Watch 4/5 has no USB data path, so the watch APK goes over Wi-Fi. Put the watch and
your machine on the same network.

**On the watch:**

1. Settings → System → About → Versions → tap **Build number** seven times.
2. Settings → **Developer options** → turn on **ADB debugging**, then **Wireless debugging**.
3. Wireless debugging → **Pair new device**. The watch shows a six-digit code and an
   `IP:PORT`. That port is the *pairing* port, not the connect port.

**On your machine** (the pairing port from the "Pair new device" screen):

```bash
adb pair 192.168.1.50:37123
```

Enter the six-digit code, then connect using the `IP:PORT` on the main **Wireless debugging**
screen:

```bash
adb connect 192.168.1.50:5555
```

```bash
adb -s 192.168.1.50:5555 install -r WristCord-watch-v2.0.0-beta.1.apk
```

If `install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a WristCord signed with a different
key is present: `adb -s 192.168.1.50:5555 uninstall com.wristcord` and retry.

The phone APK installs normally (`adb install -r WristCord-phone-v2.0.0-beta.1.apk`, or open the
file on the phone).

## First run

1. Open WristCord on the watch and scan the QR with Discord on your phone: **You → ⚙ → Scan QR
   Code**. Confirm on the phone.
2. Optional, for pressing mute/deafen/output on the phone's call: open WristCord on the phone and
   grant **Notification access** and the **Accessibility service** from its onboarding screen.
3. Add the tile: long-press the watch face → **Tiles** → **+** → **WristCord**. The complication
   is in your watch face's own editor as *WristCord mute state*.

## Layout

```
discord/                   :discord - the Discord client, plain JVM
  gateway/                 Gateway (HELLO/heartbeat/IDENTIFY/RESUME/backoff), ZlibStream
  remoteauth/              RemoteAuth - QR login
  rest/                    DiscordRest - messages, send, ack, reactions, settings proto, logout
  proto/                   ProtoReader/Writer, UserSettings, Frecency
  state/                   DiscordState, Snapshot, MessageStore, Permissions
  markdown/                Discord markup -> Block/Inline tree
wear/  discord/            Account (connection policy), TokenVault, Voice merge, ThemePrefs, Demo
       ui/                 Login, Home, DMs, Guild, Chat, message actions, remote, settings
       ui/theme/           DiscordTokens.kt (generated), DiscordTheme
       link/               phone bridge: PhoneLink, Commands, StateStore, OngoingService
       tile/               tile, complication
mobile/                    the phone bridge (unchanged in role since v1)
shared/kotlin/             watch <-> phone wire protocol, updater - compiled into both APKs
docs/                      discord-protocol.md (reverse engineering), step0-discovery.md (bridge)
tools/gen_theme.py         regenerates the theme tables
```

## Acceptance checklist (2.0)

- [ ] QR sign-in completes and the home screen shows your servers in your phone's order.
- [ ] Opening an unread channel clears its unread on the phone's Discord.
- [ ] A message sent from the watch appears in Discord on the phone.
- [ ] Reacting from the watch shows on the phone; the palette shows your usual emoji.
- [ ] Changing Discord's theme on the phone changes the watch (Settings → Theme → Match Discord).
- [ ] Setting Do Not Disturb on the watch changes your status on the phone.
- [ ] Join a call on the phone: the watch shows the channel and "on your phone", and the mute
      state flips when you mute on the phone.
- [ ] With the phone bridge installed, Mute/Leave on the watch act on the phone's call.
- [ ] Settings → Sign out removes the watch from Discord's Devices list.
- [ ] Settings → Updates finds the next beta.

## Notes

- **No Discord notifications from the watch itself.** Your phone's Discord notifications already
  reach the watch through Wear OS. A second copy from WristCord would be noise, and receiving
  them would mean keeping a socket open all day.
- **Forums** are listed but open on your phone; there is no forum view on the watch yet.
- **No voice on the watch itself.** Joining a call from the watch would need Discord's full voice
  stack (RTP, Opus and its DAVE end-to-end encryption). It is the obvious next step.
