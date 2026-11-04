# What WristCord 2 learned from the Discord Android app

Everything WristCord 2 does on the wire is lifted from Discord's own Android client, not guessed.
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
session instead. That is why WristCord 2 reads voice state from the gateway (the real
`self_mute`/`self_deaf`, channel, and who else is in it) but still presses the buttons through the
optional phone bridge.

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
