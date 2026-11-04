# WristCord

**A Discord client for the Pixel Watch 4/5.** Sign in with Discord's own QR login, then read
your servers, DMs, unreads and voice channels straight from Discord, in Discord's own themes.
**Join voice calls on the watch itself** with a Bluetooth headset, end-to-end encrypted the way
Discord's own apps are. No phone app needed.

> **2.0 is a beta.** QR sign-in, reading and replying, and the voice channel list are confirmed
> on a real account. Calls on the watch carry audio both ways since beta.4: verified in a real
> Discord call on a Pixel Watch 4, end-to-end encrypted, with the watch's own logs. beta.2 and
> beta.3 joined silent calls; [docs/wear-audio.md](docs/wear-audio.md) explains why. While a
> call is on the watch, its voice channel screen shows live diagnostics, so a problem says where
> the audio stops. Please report anything odd.

> **Unofficial client.** WristCord is not made by or affiliated with Discord. Discord's Terms
> of Service do not allow third-party clients, and an account using one can be actioned. WristCord
> behaves like Discord's own Android app on the wire, does nothing automated, and only ever does
> what you tap, but the risk is yours to weigh. v1.3 (phone bridge only, no Discord login) is still
> on the [releases page](../../releases/tag/v1.3.0) if you would rather not take it.

| Module        | What it is |
|---------------|------------|
| `:wear`       | The watch app (minSdk 33): the Discord client, voice calls, remote, tile, complication |
| `:discord`    | Plain-JVM library: gateway, REST, QR login, settings proto, live state, voice connection. Unit-tested on the build machine |
| `native/dave` | Rust JNI library: Discord's DAVE end-to-end encryption through [davey](https://github.com/Snazzah/davey) |

## Download

Grab `WristCord-watch-<version>.apk` from the [releases page](../../releases) (check it against
`SHA256SUMS.txt` if you like). 2.0 betas are GitHub **pre-releases**, so a watch still on v1.3
will not offer them by itself; install the first beta by hand (below, or with
[WatchPush](https://github.com/GPTmadeit/WatchPush)). Each beta updates itself after that.

It is signed with the same key as every earlier release, so it installs over v1.3 or beta.1
without losing your button layout.

**There is no phone app any more.** If you installed WristCord on your phone (v1.x or
2.0.0-beta.1), uninstall it there: nothing uses it.

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
- **Voice channels.** Who is in each one, with Discord's mute/deafen/camera/live flags and a
  speaking ring, and which device your own call is on ("on your phone", "on desktop").
- **Voice calls on the watch**, with a Bluetooth headset. See below.
- **Your Discord theme.** The watch follows your account's appearance setting: **Dark, Ash,
  Onyx or Light**, plus your **Nitro gradient** if you use one. Change it on the phone and the
  watch follows. Every colour comes from Discord's own design tokens (below). Settings → Theme
  overrides it per watch.
- **Shows up as your watch.** In Discord's Settings → Devices the session is listed under the
  watch's model name, not as a generic phone.

## Voice on the watch

**A wireless headset or nothing.** The watch joins a call only while a Bluetooth headset is
connected to it: classic Bluetooth (HFP), LE Audio, or Bluetooth hearing aids. A call never
plays out of the watch's speaker. Without a headset, a voice channel's **Join** button is
greyed out and says why. That rule is enforced in the call code, not just the button:

- If the headset drops mid-call, the watch stops sending your mic and goes silent at once. If
  the headset is not back within **10 seconds**, it leaves the call.
- If Android ever puts the call on the watch's own speaker, the watch goes silent and stops
  sending your mic until the call is back on the headset.
- If the headset can take neither call audio nor media audio, the join is cancelled.

**Where the audio goes.** WristCord first asks Android for the headset's *call* audio, which
uses the headset's speakers and its mic. On a Pixel Watch paired with a phone, Wear OS refuses
that to every app, Android's own Telecom included: the watch is itself a hands-free device for
the phone's calls, and that blocks it. When Android doesn't bring call audio up within a couple
of seconds, the call plays as **media audio** on the headset, full-band 48 kHz. You speak into
the **watch's mic**, and the voice channel screen says so. LE Audio headsets, or a watch not
connected to a phone, keep the headset's mic. [docs/wear-audio.md](docs/wear-audio.md) has the
log lines.

**Joining** works like Discord's own apps. The watch's session asks Discord's gateway to join
(op 4) and gets a voice server back. It then connects to that server itself: WebSocket
signalling, UDP media, and Opus audio through **libopus**, compiled into the app. (Android's
own Opus codec crashed on Wear OS 17.) Like Discord's default, it uses voice activity: it only
sends while you talk, measured against the room's noise floor. Discord sees the watch as one more of your
devices, so everyone in the channel sees you join, mute and deafen as usual.

**End-to-end encrypted.** Discord has required DAVE, its end-to-end encryption for voice, in
voice channels and calls since March 2026 (Stage channels aside). WristCord runs it through
[davey](https://github.com/Snazzah/davey), an open-source Rust implementation over
[OpenMLS](https://github.com/openmls/openmls), compiled into the APK. Speech is held back for
the second or so it takes to join the call's encryption group: nothing you say is sent with
transport encryption alone. While a call is on the watch, its voice channel screen shows
**End-to-end encrypted** and the call's **voice privacy code**. That code is the same 30 digits
Discord shows on every other device in the call, so you can compare them.

**Call diagnostics (beta).** Under the encryption line, the voice channel screen shows:
- packets sent, received and actually heard;
- your mic level, and where Android routes the audio;
- which network the call uses;
- a plain-language line for any step that is failing, for example "Waiting for E2EE",
  "frames failed E2EE" or "the mic is giving silence".

The same counters go to the log every five seconds (`adb logcat -s WristCord/Call`).

**The remote.** The radial remote from v1 is unchanged in shape: big centre button, two
satellites, a bottom button, double-pinch for the centre, always-on while it is open. With the
call on the watch, its buttons act on that call directly: mute, deafen, leave, and **Output**
to pick a headset when more than one is connected. The tile's buttons do the same.

**A call on your phone (or desktop)** shows on the watch with its real mute state. The watch
cannot press mute on it. That limit is in Discord's protocol itself: Discord's remote-control
command only lets the phone control a console's call. Nothing in Discord's app acts on one
from another device
([details](docs/discord-protocol.md#remote-voice-control-what-the-protocol-does-not-allow)).
What the protocol does allow is moving the call. With a headset connected, the remote offers
**Move here**, which moves the call to the watch the way switching devices in Discord does.
**Leave** on a call elsewhere asks Discord's gateway to drop you. That is best effort: Discord's
own apps never do it for another device's call.

**Network.** For the length of a call, WristCord asks Android for Wi-Fi or LTE rather than
relaying real-time audio through the phone's Bluetooth link. If neither comes up within a few
seconds, it uses whatever the watch has.

Not on the watch: video, screen share and Stage channels. Others' cameras and streams show as
flags. DM calls can be moved to the watch, but there is no button to start one yet.

## Battery

The watch holds a connection to Discord only while something needs it: the app on screen, a
tile you just swiped to, or a voice call on the watch. A minute after the last of those lets go,
it disconnects, keeping the session resumable for a quick return. It always reports itself to
Discord as **AFK**, so your phone keeps getting push notifications while the watch is connected.
A call on the watch runs as an ongoing activity (watch-face chip and recents) and keeps the CPU
awake only for as long as it lasts.

## Privacy and security

- The token is encrypted with an AES-256-GCM key held in the watch's Android Keystore. The key
  never leaves the keystore and backups are off.
- The microphone is open only during a call on the watch, which needs a connected headset and
  your permission (asked the first time you join).
- WristCord talks to `discord.com`, `gateway.discord.gg`, `remote-auth-gateway.discord.gg`,
  Discord's CDNs and the voice server Discord assigns to a call, plus `api.github.com` for
  updates. Nothing else, no analytics.

## Updating

Settings → **Updates** downloads the APK from this repo's releases, checks that its signing
certificate matches the installed app's, then hands it to the system installer. Beta builds
follow betas and stable releases; stable builds only follow stable releases.

## Build

Prerequisites, besides the Android SDK: JDK 17+, `compileSdk` 37
(`sdkmanager "platforms;android-37.0"`), NDK 27.3 (`sdkmanager "ndk;27.3.13750724"`), and a
Rust toolchain with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk):

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

```bash
./gradlew :wear:assembleRelease
```

Gradle builds `native/dave` with cargo-ndk first (`buildDaveNative`, before `preBuild`), then
outputs `wear/build/outputs/apk/release/wear-release.apk`, R8-shrunk, with the native library for
`arm64-v8a` and `armeabi-v7a`. Debug builds add `x86_64` for the emulator.

It is signed with the key in [`keystore/wristcord.jks`](keystore/wristcord.jks), which every
release since 1.0 has been signed with, so each update installs over the last. It was checked in
when its only job was matching the watch and phone APKs. Because it is public, the updater's
signature check guards against a corrupted download, not a malicious APK; the download itself
comes over HTTPS from this repo's releases. Don't reuse the key for anything you care about.

Toolchain: AGP 9.1, Gradle 9.3.1, Kotlin 2.4, Rust stable, davey 0.2.0.

### Tests

```bash
./gradlew :discord:test :wear:testDebugUnitTest
```

The `:discord` suite covers:
- zlib-stream framing, the settings and frecency protos, markdown and permission resolution;
- the QR-login crypto, and a READY shaped the way Discord sends it;
- voice: the RTP layout and both transport ciphers, IP discovery and the voice gateway's binary
  framing;
- a full voice call against a fake voice server (WebSocket plus UDP). That run goes through the
  handshake, the DAVE/MLS message flow and encrypted audio both ways, and checks that speech is
  held back until encryption is ready.

`:wear`'s `DaveCallTest` runs a **real** DAVE join with davey on both ends. It uses a host build of
`native/dave` with its `testing` feature, which plays Discord's voice server as the MLS external
sender. The watch's `VoiceConnection` joins a call someone is already in, and audio must decrypt
both ways. It also covers a welcome that arrives before the voice server has listed anyone, and
working out who is talking from their key alone. Gradle builds that host library
(`buildDaveHost`) before the unit tests; it is never packaged.

Two more tests run against Discord's **live** servers without an account:

```bash
./gradlew :discord:test --tests '*LiveTest' -Dwristcord.live=true
```

- the QR handshake runs until Discord hands back a scannable `https://discord.com/ra/…` code.
  Discord only does that after accepting the key exchange and nonce proof, so it proves the
  reverse-engineered flow end to end.
- the gateway connects, inflates HELLO, heartbeats, sends IDENTIFY, and gets Discord's `4004` for a
  token that cannot be valid.

`OpusCodecTest` checks the libopus bridge on the host: one 20 ms frame out per packet at the
right level, loss concealment, and closed codecs that stay safe.

On a device or emulator, debug builds have probes, all started with
`adb shell am start -n com.wristcord/.wear.MainActivity --ez com.wristcord.<NAME> true`:

| `<NAME>` | What it does | Log tag |
|---|---|---|
| `SELFTEST` | Loads the DAVE library and builds an MLS key package. Round-trips XChaCha20 and Opus. Reports the headset gate. | `WristCord/SelfTest` |
| `ENGINETEST` | Runs the real call engine on the connected headset: picks the audio path, plays a soft 2 s tone, measures the mic. | `WristCord/SelfTest` |
| `MICTEST` | Measures every mic source in both audio modes. | `WristCord/SelfTest` |
| `SCOTEST` | Tries Bluetooth call audio three ways (direct, legacy SCO, Telecom), plus media audio. | `WristCord/ScoProbe` |

`adb install --abi arm64-v8a` runs them against the ARM library on an x86_64 emulator.

### Looking at every screen without an account

Debug builds have a synthetic demo account:

```bash
adb shell am start -n com.wristcord/.wear.MainActivity --ez com.wristcord.DEMO true \
    --es com.wristcord.DEMO_SCREEN chat --ei com.wristcord.DEMO_THEME 1
```

`DEMO_SCREEN` is one of the following:
- `dms guild chat message`
- `remote voice outputs` (a call on the phone)
- `incall incall-remote` (a pretend call on the watch)
- `settings status theme icons buttons`

`DEMO_THEME` is the proto theme (1 Ash, 2 Light, 3 Dark, 4 Onyx), and
`--ei com.wristcord.DEMO_GRADIENT 9` adds a Nitro gradient. Release builds contain none of this.

## How it was built: reverse engineering Discord's app

Most of what WristCord does on the wire was taken from Discord's own Android app (351.11),
not from guesswork:
- the gateway URL, IDENTIFY payload and capability flags;
- the client identity and REST headers;
- the QR-login protocol and the settings protobuf field numbers;
- the voice remote-command limits;
- the colour tokens for every theme.

The app is React Native compiled to Hermes bytecode v98. It was parsed (never run) with
[hermes-dec](https://github.com/P1sec/hermes-dec) plus a string → function → module indexer.
Original source file names survive in the bundle, so each finding is cited by Discord's own
module path.

Voice is the exception. Discord's app does voice in a native media engine outside that bundle,
so the voice connection follows other sources:
- Discord's published [voice documentation](https://docs.discord.com/developers/topics/voice-connections);
- the [DAVE protocol whitepaper](https://daveprotocol.com);
- two open-source implementations, [davey](https://github.com/Snazzah/davey) and
  [@discordjs/voice](https://github.com/discordjs/discord.js/tree/main/packages/voice).

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
adb -s 192.168.1.50:5555 install -r WristCord-watch-v2.0.0-beta.4.apk
```

If `install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a WristCord signed with a different
key is present: `adb -s 192.168.1.50:5555 uninstall com.wristcord` and retry.

## First run

1. Open WristCord on the watch and scan the QR with Discord on your phone: **You → ⚙ → Scan QR
   Code**. Confirm on the phone.
2. Add the tile: long-press the watch face → **Tiles** → **+** → **WristCord**. The complication
   is in your watch face's own editor as *WristCord mute state*.
3. For calls: pair a Bluetooth headset with the watch in its Bluetooth settings. Then open a
   server's voice channel and tap **Join**; the first time, allow the microphone.

## Layout

```
discord/                   :discord - the Discord client, plain JVM
  gateway/                 Gateway (HELLO/heartbeat/IDENTIFY/RESUME/backoff), ZlibStream
  remoteauth/              RemoteAuth - QR login
  rest/                    DiscordRest - messages, send, ack, reactions, settings proto, logout
  proto/                   ProtoReader/Writer, UserSettings, Frecency
  state/                   DiscordState, Snapshot, MessageStore, Permissions
  markdown/                Discord markup -> Block/Inline tree
  voice/                   VoiceConnection (voice gateway v8, UDP, RTP, DAVE flow), VoiceProtocol
native/dave/               Rust JNI library: DAVE through davey, libopus (src/opus.rs), the
                           XChaCha20-Poly1305 transport; src/testing.rs plays the voice
                           server's MLS side for tests only
wear/  discord/            Account (connection policy), TokenVault, Voice merge, ThemePrefs, Demo
       voice/              VoiceCall, AudioEngine (call or media audio path), OpusCodec
                           (libopus), Headset (the headset rule), CallNetwork, VoiceService,
                           NativeDave, SelfTest and ScoProbe (debug probes), SlotConfig
       ui/                 Login, Home, DMs, Guild, Chat, message actions, voice channel,
                           remote, outputs, settings
       ui/theme/           DiscordTokens.kt (generated), DiscordTheme
       tile/               tile, complication
       shared/             updater, brand, icon sets, voice model
docs/                      discord-protocol.md (reverse engineering, voice), wear-audio.md
tools/                     gen_theme.py (theme tables), re/ (bundle indexer and extractors)
```

## Acceptance checklist (2.0)

- [x] QR sign-in completes on a real account.
- [ ] The home screen shows your servers in your phone's order.
- [ ] Opening an unread channel clears its unread on the phone's Discord.
- [x] A message sent from the watch appears in Discord on the phone.
- [ ] Reacting from the watch shows on the phone; the palette shows your usual emoji.
- [ ] Changing Discord's theme on the phone changes the watch (Settings → Theme → Match Discord).
- [ ] Setting Do Not Disturb on the watch changes your status on the phone.
- [x] A voice channel shows who is in it.
- [ ] With no headset connected, **Join** is greyed out and says to connect one.
- [ ] With a headset, **Join** connects: the others hear you and you hear them, on the headset.
      The channel screen shows *End-to-end encrypted* and the same privacy code as the phone.
- [ ] Mute and deafen on the watch show on the phone's view of the channel.
- [ ] Switching the headset off mid-call silences the watch at once and leaves after 10 seconds.
- [ ] With the call on the phone, **Move here** moves it to the watch.
- [ ] Settings → Sign out removes the watch from Discord's Devices list.
- [ ] Settings → Updates finds the next beta.

## Notes

- **No Discord notifications from the watch itself.** Your phone's Discord notifications already
  reach the watch through Wear OS. A second copy from WristCord would be noise, and receiving
  them would mean keeping a socket open all day.
- **Forums** are listed but open on your phone; there is no forum view on the watch yet.
- **32-bit watches.** The ARM libraries are built for `arm64-v8a` and `armeabi-v7a`. The Pixel
  Watch 4 is a 32-bit watch, so the `armeabi-v7a` one is the one tested on real hardware.

## Credits

- [davey](https://github.com/Snazzah/davey) (MIT) by Snazzah, the DAVE implementation inside
  `native/dave`, and through it [OpenMLS](https://github.com/openmls/openmls).
- [discord.js](https://github.com/discordjs/discord.js) (Apache-2.0), whose voice package was the
  reference for the voice connection's DAVE handling.
- [libopus](https://opus-codec.org) (BSD-3-Clause) by Xiph.Org and contributors, the voice
  codec, built through [opusic-sys](https://github.com/DoumanAsh/opusic-sys) (BSD-3-Clause).
- [hermes-dec](https://github.com/P1sec/hermes-dec) for reading Discord's Hermes bundle.
