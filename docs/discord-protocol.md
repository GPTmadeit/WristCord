# What WristCord 2 learned from the Discord Android app

Everything WristCord 2 does on the wire is lifted from Discord's own Android client, not guessed,
with one exception: [voice](#voice-on-the-watch), which has its own sources and labels.
This file records what was found, where in the app it lives, and how sure we are, so the next
time Discord changes something there is a known place to re-check.

**Source artifact:** `com.discord` **351.11 - Stable** (versionCode 351011), from the Play Store
XAPK. Signature verified before analysis: v2/v3 scheme, signer `CN=Jason Citron, O=Hammer and
Chisel` (Discord's original company), SHA-256 `3c39d23c…bfa113`.

**How:** the app is React Native. Everything interesting is in `assets/index.android.bundle`,
Hermes bytecode **v98** (160,594 functions, 346,743 strings, 19,168 Metro modules). It was
parsed with [hermes-dec](https://github.com/P1sec/hermes-dec) and a small indexer that maps a
string to the functions that use it and the Metro module those functions belong to. Every module
calls `fileFinishedImporting("<original path>.tsx")`, so the original source file names survive,
and they are quoted below. The bundle was only ever parsed, never executed.

Confidence labels: **observed** = read directly from decompiled code or literal data;
**inferred** = follows from what was observed but was not read verbatim.

---

## Endpoints and versions

`window.GLOBAL_ENV` defaults, set in the bundle's environment prelude (observed):

| Key | Value |
|---|---|
| `API_ENDPOINT` | `//discord.com/api` |
| `API_VERSION` | `9` |
| `GATEWAY_ENDPOINT` | `wss://gateway.discord.gg` |
| `REMOTE_AUTH_ENDPOINT` | `wss://remote-auth-gateway.discord.gg` |
| `CDN_HOST` | `cdn.discordapp.com` |
| `MEDIA_PROXY_ENDPOINT` | `//media.discordapp.net` |

## Gateway

`modules/gateway/GatewaySocket.tsx`, `modules/gateway/native/fast_connect.tsx` (observed):

- URL: `GATEWAY_ENDPOINT + "/?encoding=json&v=9&compress=" + (zstd ? "zstd-stream" : "zlib-stream")`.
  JSON encoding even on Android. WristCord uses `zlib-stream`, which `java.util.zip` handles.
- Identify payload keys, in order: `token, capabilities, properties, presence, compress,
  client_state, qos_token`. `compress` is `false` whenever transport compression is on.
  `client_state` is `{guild_versions: {}}` on a cold start.
- Opcodes (`GatewaySocketOpcode.tsx`): the usual 0–11, plus `13 CALL_CONNECT`,
  `18–22 STREAM_*`, `29 REMOTE_COMMAND` and others.

**Capabilities** (`modules/gateway/GatewayCapabilities.tsx`, `getClientCapabilities`):
`1734655`, or `1767423` when the channel-obfuscation experiment is on (adds bit 15). Bits set:
0–10, 12, 13, 14, 17, 19, 20. Bits 17/19/20 are not in any public documentation, so WristCord
sends `30719` (the same value without those three bits) to keep the READY shape predictable.

## Client identity

`../discord_common/js/packages/analytics-utils/getSuperProperties.tsx` (observed):

- `os: "Android"`, `browser: "Discord Android"`, `device: <model>`, `system_locale`,
  `has_client_mods`, `client_version: <Version> + " - rn"`, `release_channel`,
  `device_vendor_id`, `design_id`, `browser_user_agent: ""`, `browser_version: ""`,
  `os_version`, `os_sdk_version`, `client_build_number: 35101100000000`, `client_event_source: null`,
  `client_launch_id`.
- REST user agent (from the app's dex): `Discord-Android/351011;RNA`.
- `X-Super-Properties` is the base64 of that JSON. `prepareRequest` also sets
  `X-Discord-Locale`, `X-Discord-Timezone` and `X-Debug-Options`.

## QR login (remote auth)

`modules/remote_auth/useAuthWebsocket.tsx` and `RemoteAuthCrypto.android.tsx`. The Android app
implements the side that *shows* the QR, not only the scanner. Observed:

1. Connect to `REMOTE_AUTH_ENDPOINT + "/?v=2"`.
2. `hello {heartbeat_interval, timeout_ms}` → send `{op:"heartbeat"}` on that interval.
3. Send `{op:"init", encoded_public_key}`: base64 SPKI of a fresh RSA-2048 key.
4. `nonce_proof {encrypted_nonce}` → RSA-OAEP decrypt (Java side: `RSA/ECB/OAEPPadding` with
   explicit `OAEPParameterSpec` + `MGF1ParameterSpec`) → **send the decrypted nonce itself**,
   base64 with `+/` swapped for `-_`: `{op:"nonce_proof", nonce}`.
5. `pending_remote_init {fingerprint}`: compared against the local public key fingerprint
   (mismatch throws `bad fingerprint`). The QR is `https://discord.com/ra/<fingerprint>`.
6. `pending_ticket {encrypted_user_payload}` → decrypts to a preview of the account (the phone
   has scanned).
7. `pending_login {ticket}` → `POST /users/@me/remote-auth/login {ticket}` →
   `{encrypted_token}` → RSA-OAEP decrypt → the token.
8. `cancel` → the phone declined.

## Remote voice control: what the protocol does not allow

This decided WristCord 2's design, so it is spelled out.

`modules/game_console/GameConsoleActionCreators.tsx` sends op `29 REMOTE_COMMAND`
`{target_session_id, payload}` with payloads `{type:"VOICE_STATE_UPDATE", self_mute, self_deaf}`,
`{type:"DISCONNECT"}` and `{type:"AUDIO_SETTINGS_UPDATE", context, id, …}`. That is how the phone
controls a voice call running **on an Xbox or PlayStation**.

The Android app only *sends* these. Of the six functions in the bundle that reference
`REMOTE_COMMAND`, none receives one: the only store that handles that action
(`GatewayConnectionStore.handleRemoteCommand`) forwards it out to the socket. **Observed:
nothing in Discord's Android app acts on a remote command from another session.**

So no second device can mute, deafen or hang up a call running in the phone's Discord app
through Discord's servers. Sending op 4 from another session would *move* the call to that
session instead. 2.0.0-beta.1 therefore still pressed the phone's buttons through a phone app.
Since beta.2 WristCord does what the protocol does allow and has no phone app. It shows a call
on another device with its real state from the gateway (`self_mute`/`self_deaf`, channel, who
else is there). **Move here** sends that op 4 from the watch's own session, which moves the call
to the watch.

## Voice on the watch

**Not from the app.** Discord Android runs voice in its native media engine, outside the
Hermes bundle the rest of this file comes from, and that engine was not disassembled. WristCord's
voice connection (`discord/.../voice/`, `native/dave/`) instead follows these sources:
- Discord's published [voice connection documentation](https://docs.discord.com/developers/topics/voice-connections);
- the [DAVE protocol whitepaper](https://daveprotocol.com);
- two open-source clients: [davey](https://github.com/Snazzah/davey) 0.2.0, which does
  DAVE/MLS and is linked in directly, and
  [@discordjs/voice](https://github.com/discordjs/discord.js/tree/main/packages/voice), the
  reference for message order and edge cases.

Labels in this section:
- **documented**: published by Discord or the DAVE spec.
- **reference**: what discord.js or davey do.
- **tested**: exercised against the fake voice server in `discord/src/test/.../VoiceTest.kt`.

None of it has run against Discord's real voice servers yet.

### Joining

1. Main gateway op 4 `VOICE_STATE_UPDATE {guild_id, channel_id, self_mute, self_deaf}` from the
   watch's own session (documented). A DM call has `guild_id: null`, and the channel id is then
   the voice server's id.
2. Discord replies with `VOICE_STATE_UPDATE` (carrying our `session_id`) and
   `VOICE_SERVER_UPDATE {token, guild_id, endpoint}`. An `endpoint` of `null` means another update
   follows (documented). A later `VOICE_SERVER_UPDATE` mid-call moves the call to a new server.
3. If the account was already in that call on another device, the op 4 moves the call: the other
   session is disconnected (inferred, from how joining on a second device behaves in Discord).
4. Leaving is op 4 with `channel_id: null`.

### Voice gateway v8 (documented, tested)

`wss://<endpoint>/?v=8`. JSON messages from the server carry `seq`. Binary messages are
`[seq u16 BE][op u8][payload]` from the server and `[op u8][payload]` from the client.

| Op | Name | Dir | Payload |
|---|---|---|---|
| 0 | Identify | → | `server_id, user_id, session_id, token, max_dave_protocol_version` |
| 1 | Select Protocol | → | `protocol: "udp", data: {address, port, mode}` |
| 2 | Ready | ← | `ssrc, ip, port, modes` |
| 3 | Heartbeat | → | `{t, seq_ack}` (v8 acknowledges the last `seq` received) |
| 4 | Session Description | ← | `mode, secret_key, dave_protocol_version` |
| 5 | Speaking | ↔ | `speaking: 1` (microphone), `delay: 0`, `ssrc`; from others it maps `ssrc` → `user_id` |
| 6 | Heartbeat ACK | ← | |
| 7 | Resume | → | `server_id, session_id, token, seq_ack` |
| 8 | Hello | ← | `heartbeat_interval` |
| 9 | Resumed | ← | |
| 11 | Clients Connect | ← | `user_ids` |
| 13 | Client Disconnect | ← | `user_id` |
| 21-31 | DAVE | ↔ | see below |

Closes: resume only after `4015` or a code below 4000 (reference). `4017` means the call requires
DAVE (documented): Discord has required it for voice since 2026-03-01.

### UDP and RTP (documented, tested)

- **IP discovery:** 74 bytes, `[type u16 = 1][length u16 = 70][ssrc u32][address, 64 bytes][port u16]`.
  The reply (type 2) carries the external address and port that go into op 1.
- **Keepalive:** an 8-byte little-endian counter every 5 s (reference).
- **Mode:** `aead_aes256_gcm_rtpsize` when offered, else `aead_xchacha20_poly1305_rtpsize`
  (XChaCha20 from the `chacha20poly1305` crate in `native/dave`).
- **RTP:** `0x80`, payload type `0x78`, `seq u16`, `timestamp u32` (+960 per 20 ms frame), `ssrc u32`.
- **Transport encryption:** the additional data is the unencrypted header: the 12 bytes, any CSRCs,
  and the 4-byte extension preamble when the X bit is set. The extension body is encrypted
  and is stripped after decryption (reference: discord.js `VoiceReceiver`). The nonce is a 32-bit
  big-endian counter appended after ciphertext and tag, zero-padded to 12 bytes for AES-GCM and
  24 for XChaCha20.
- **Opus:** 48 kHz, 20 ms frames. Discord sends stereo; the watch sends mono at 64 kbps through
  Android's own codec. Five silence frames `F8 FF FE` end each talk spurt (documented).

### DAVE (documented, reference, tested)

Each call is an MLS ([RFC 9420](https://www.rfc-editor.org/rfc/rfc9420)) group. Discord's voice
server is its *external sender*, adding and removing members. Every Opus frame is encrypted with
the sender's key from the group's current epoch before transport encryption wraps it. Protocol
version 1. davey implements the group and the frame encryption; WristCord routes the messages:

| Op | Name | Dir | What WristCord does |
|---|---|---|---|
| 21 | prepare_transition `{transition_id, protocol_version}` | ← | prepare; answer 23 unless `transition_id` is 0 (applies at once) |
| 22 | execute_transition `{transition_id}` | ← | switch to the prepared protocol version and epoch |
| 23 | transition_ready `{transition_id}` | → | |
| 24 | prepare_epoch `{epoch, protocol_version}` | ← | epoch 1 is a new group: reinit and send a new key package |
| 25 | mls_external_sender (binary) | ← | the voice server's credential |
| 26 | mls_key_package (binary) | → | sent after op 4 (session description) and after every reinit |
| 27 | mls_proposals (binary) | ← | members to add or revoke; answered with 28 when they yield a commit |
| 28 | mls_commit_welcome (binary) | → | commit, plus a welcome for new members |
| 29 | mls_announce_commit_transition (binary) | ← | `[transition_id u16][commit]`: apply, then 23 |
| 30 | mls_welcome (binary) | ← | `[transition_id u16][welcome]`: join, then 23 |
| 31 | mls_invalid_commit_welcome `{transition_id}` | → | when a commit or welcome cannot be applied; then reinit |

- **Silence frames** pass through unencrypted (documented). During a switch to or from protocol
  version 0, unencrypted frames from others are accepted for a short window (passthrough: 10 s
  up, 24 s down; reference).
- **Speech waits for the group.** discord.js sends frames as they are until its DAVE session is
  ready; WristCord drops speech instead, so none leaves with transport encryption only (tested).
- **Voice privacy code:** 30 digits derived from the MLS epoch authenticator and shown in groups
  of five (documented, "displayable codes"). Everyone in the call sees the same code, and it
  changes when the group does.
- **Who may be in the group.** A welcome (30) or proposal (27) naming a user the client doesn't
  recognise must be refused (documented; davey checks it). The voice server's own announcements
  (op 11, op 5) aren't enough for that list. It can send the welcome before announcing the
  people already in the call, and the watch then refused every welcome, so beta.2's first real
  call never got its keys and was silent both ways (inferred from the symptom; reproduced in
  `DaveCallTest`). The list now also includes everyone Discord's main gateway shows in the
  channel, the same users the voice channel screen lists.
- **A refused welcome cannot be retried.** openmls deletes the key package while reading the
  welcome, before the roster check runs (observed in openmls 0.8.3, `keys_for_welcome`). The
  only way back is op 31 and a fresh key package.
- **Unknown SSRC.** Audio can arrive before the op 5 that maps its SSRC to a user. Every member
  has their own sender key, so the watch tries each member's key and maps the SSRC to whichever
  opens the frame. A failed try changes nothing: davey records a nonce only after a frame
  decrypts (tested).

## Settings protobuf

`../discord_common/js/packages/protos/discord_protos/discord_users/v1/preloaded_user_settings.tsx`,
field numbers read from each message's `internalBinaryRead` switch (observed):

| Message | Field | No. | Type |
|---|---|---|---|
| PreloadedUserSettings | `status` | 11 | StatusSettings |
| | `localization` | 12 | LocalizationSettings |
| | `appearance` | 13 | AppearanceSettings |
| | `guild_folders` | 14 | GuildFolders |
| AppearanceSettings | `theme` | 1 | enum Theme |
| | `client_theme_settings` | 3 | ClientThemeSettings |
| ClientThemeSettings | `background_gradient_preset_id` | 2 | UInt32Value |
| StatusSettings | `status` | 1 | StringValue (`online`, `idle`, `dnd`, `invisible`) |
| | `custom_status` | 2 | CustomStatus (`text`=1, `emoji_id`=2 fixed64, `emoji_name`=3, `expires_at_ms`=4 fixed64) |
| GuildFolders | `folders` | 1 | repeated GuildFolder (`guild_ids`=1 packed fixed64, `id`=2 Int64Value, `name`=3 StringValue, `color`=4 UInt64Value) |
| | `guild_positions` | 2 | repeated fixed64 |

`frecency_user_settings.tsx`: `favorite_emojis`=5 (`emojis`=1 repeated string),
`emoji_frecency`=6 and `emoji_reaction_frecency`=13 (`emojis`=1 map<string, FrecencyItem>;
FrecencyItem `total_uses`=1, `recent_uses`=2, `frecency`=3, `score`=4).

**Theme enum:** `UNSET=0, DARK=1, LIGHT=2, DARKER=3, MIDNIGHT=4`. The names do not mean what they
say. `PROTO_THEME_MAP_WEB_REFRESH` maps them onto the four themes the app shows (observed):

| Proto value | Theme in Discord's UI | Internal name |
|---|---|---|
| 0 UNSET | Dark | `darker` |
| 1 DARK | **Ash** | `dark` |
| 2 LIGHT | Light | `light` |
| 3 DARKER | **Dark** | `darker` |
| 4 MIDNIGHT | **Onyx** | `midnight` |

## Colours

- `tokens/colors/generated/raw-color-definitions.tsx`: 1,707 raw colours (`NEUTRAL_1…100`,
  `BRAND_500 = #5865f2`, …).
- `tokens/colors/generated/native/generated-definitions.tsx`: 428 semantic tokens, each resolved
  per theme (`ASH`, `LIGHT`, `ONYX`, `DARK`) to a raw colour plus opacity.
- `modules/client_themes/ClientThemesConstants.tsx`: the 23 Nitro gradient presets (proto id,
  base theme, angle, colour stops).

WristCord's `DiscordTokens.kt` (including the gradient table) is generated from those three
tables by `tools/gen_theme.py`. Only what the watch uses is committed, resolved to ARGB, in
`docs/extracted/tokens-used.json` (83 tokens across the four themes, and the 23 gradients).

## When a message notifies

From `shouldNotifyBase` and `shouldNotify` (`modules/notifications/NotificationTextUtils.tsx`) and
`stores/UserGuildSettingsStore.tsx`. `NotificationPolicy` in `:discord` follows this order:

1. No notification for: message flag `SUPPRESS_NOTIFICATIONS` (bit 12, `@silent`), your own message,
   a blocked or ignored author, status DND, a message request.
2. `allowNoMessages`: muted server, category or channel, or a resolved level of "nothing". **A mute
   wins over a mention.**
3. `allowAllMessages`: not muted and level "all" notifies for everything.
4. Otherwise it notifies only if `isRawMessageMentioned`, honouring `suppress_everyone` and
   `suppress_roles`.

`resolvedMessageNotifications` takes the first set value of channel override, category override,
then the server. The values are 0 all, 1 mentions, 2 nothing, 3 unset. An unset server falls back
to its `default_message_notifications`.

Discord's real push is decided on its servers and delivered by Firebase to Discord's own signed
app, so a third-party client cannot receive it. WristCord also honours `mobile_push`, and simplifies
two things: system messages never notify, and a thread notifies only for a mention instead of
tracking `computeThreadNotificationSetting`. It keeps `afk: true` in presence so that this
connection does not divert the phone's own notifications.

## Sending an attachment

Observed in the bundle (`getAttachmentPayload`, and the cloud upload in Metro module 8023). The
JSON field names of the first request are also seen in other callers of the same endpoint family;
this flow has been exercised against a mock server, not yet against Discord itself.

1. `POST /channels/{id}/attachments` with `{"files":[{"id":"0","filename":...,"file_size":...,"is_clip":false}]}`
   answers `{"attachments":[{"upload_url":...,"upload_filename":...}]}`.
2. `PUT {upload_url}` with the raw bytes and `Content-Type: application/octet-stream`. This is a
   signed storage URL, so the account token is not sent.
3. `POST /channels/{id}/messages` with the usual body plus
   `"attachments":[{"id":"0","filename":...,"uploaded_filename":...}]`. `content` may be empty.

`ATTACH_FILES` is permission bit 15. WristCord hides the button where the account lacks it.

## Reproducing this against a newer Discord build

The scripts are in [`tools/re/`](../tools/re). They only parse the bundle, they never run it.

```bash
python3 -m venv venv && venv/bin/pip install hermes-dec==0.1.8
unzip com.discord.apk assets/index.android.bundle -d apk
export HQ_BUNDLE=$PWD/apk/assets/index.android.bundle HQ_INDEX=$PWD/hq.idx HQ_OUT=$PWD/out
venv/bin/python3 -I tools/re/hq.py index                 # string/function/module index, ~2 min
venv/bin/python3 -I tools/re/hq.py files 'gateway/'      # find modules by original path
venv/bin/python3 -I tools/re/hq.py refs '^REMOTE_COMMAND$' # who uses a string
venv/bin/python3 -I tools/re/hq.py dec <function id>     # decompile one function
```

`hermes-dec` 0.1.8 asserts on anonymous functions when decompiling (`defs.py`, `assert
self.function_name`); `hq.py dis` works without that, or replace the assert with a fallback name.
Then: `semantic.py` (semantic colour tokens), `gradients.py` (Nitro gradients),
`protofields.py out/dec/<module>.js` (protobuf field tables), and finally
`python3 tools/gen_theme.py $HQ_OUT` to regenerate the Kotlin theme tables.
