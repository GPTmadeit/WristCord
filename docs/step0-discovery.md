# Step 0 — discovery

Nothing in WristCord's control paths should be built on a guess about what Discord calls its
buttons. This is the pass that replaces guesses with observed facts.

You need: the phone APK installed, both permissions granted, and Discord able to join a voice
channel.

---

## 1. Dump Discord's notifications

1. Join a voice channel in Discord on the phone. Leave the call running.
2. Open **WristCord → Step 0 – discovery → Notifications**.

The output lands in three places at once: on screen, on the clipboard via **Copy**, and in
logcat. To watch it from a laptop instead:

```bash
adb logcat -s WristCordProbe
```

**Save to file** writes it to the app's external files dir; pull it with:

```bash
adb shell ls /sdcard/Android/data/com.wristcord/files
```

For every `com.discord` notification you get its channel, category, ongoing flag, title/text,
and then — the part that matters — each action:

```
[0] key=0|com.discord|41|null|10312
     package        = com.discord
     channel        = Voice
     category       = call
     ongoing        = true
     title          = General
     text           = Connected to General
     contentIntent  = activity
     actions        = 2
       actions[0].title = "Mute"          ->  broadcast
       actions[1].title = "Disconnect"    ->  broadcast
```

`PendingIntent.isBroadcast()` / `isService()` / `isActivity()` are public API since API 31, so
the dump can tell you the kind for each one. **This is the line that decides whether an action
survives a dark screen.** A `broadcast` or `service` action fires fine with the phone locked; an
`activity` action is subject to background-activity-launch limits and may not.

The dump ends with what WristCord's *current* selectors picked, so you can see immediately
whether they matched:

```
--- what WristCord picked as the voice notification ---
key=0|com.discord|41|null|10312 ongoing=true score=4
titles="Mute", "Disconnect"
```

`score=0` or "None of the above scored as a call notification" means the selectors are wrong for
your build — go to step 3.

## 2. Dump Discord's voice panel

1. With the call still running, open Discord's in-call voice panel on the phone.
2. Switch to WristCord (the panel does not need to stay in front — the dump reports whether
   Discord was foreground and says so if it was not) and tap **Voice panel**.

You get `viewIdResourceName`, `contentDescription`, `text`, and the clickable/enabled/checked
flags for every node, clickable ones first:

```
clickable nodes:
  android.widget.Button  id=com.discord:id/voice_deafen  desc="Deafen"  text=-  clickable=true enabled=true checked=false selected=false
  android.widget.Button  id=com.discord:id/voice_output  desc="Audio Output"  text=-  clickable=true ...
```

Labelled non-clickable nodes are listed after, because in a Compose-rendered panel the label
often sits on a child and the click target is its parent — `A11yStrategy` climbs up to six
ancestors looking for something clickable, so matching a label is usually enough.

The third button, **Audio**, dumps `AudioManager.getAvailableCommunicationDevices()` so you can
see the device ids and labels the watch's Output list will show.

## 3. Correct the selectors

Every pattern is a case-insensitive Kotlin `Regex` matched with `containsMatchIn` against action
titles (notification) or against view id + contentDescription + text (accessibility).

**Fastest path — no rebuild:** WristCord → **Selectors** → **Show**. Each field is saved the
moment it compiles; leaving one blank restores its built-in default. Invalid regexes are flagged
and not saved.

**Permanent path:** edit the `default` strings in
[`DiscordSelectors.kt`](../mobile/src/main/kotlin/com/wristcord/mobile/discord/DiscordSelectors.kt)
and rebuild. The file is written to be the only one you need to touch.

| Slot | Matched against | Default | Means |
|------|-----------------|---------|-------|
| `NOTIF_MUTE` | action titles | `^(un)?mute` | the mute toggle, in either direction |
| `NOTIF_MUTED_NOW` | action titles | `^unmute` | **presence ⇒ currently muted** |
| `NOTIF_DEAFEN` | action titles | `^(un)?deafen` | deafen toggle, if your build has one |
| `NOTIF_DEAFENED_NOW` | action titles | `^undeafen` | **presence ⇒ currently deafened** |
| `NOTIF_DISCONNECT` | action titles | `^(disconnect\|hang ?up\|leave\|end call)` | leave the call |
| `A11Y_DEAFEN` | id / desc / text | `(un)?deafen` | deafen control in the panel |
| `A11Y_MUTE` | id / desc / text | `(un)?mute` | fallback only |
| `A11Y_DISCONNECT` | id / desc / text | `(disconnect\|leave ?(voice\|call)?\|hang ?up\|end call)` | fallback only |
| `A11Y_OUTPUT` | id / desc / text | `(audio ?output\|output device\|speaker\|switch audio\|audio device\|sound output)` | opens Discord's output chooser |

The two `*_NOW` rows are the whole of WristCord's state inference. Discord never publishes
"muted: true" — it publishes a button labelled *Unmute*, which only exists because you are
muted. If neither label is present, the state is honestly unknown and the watch says so.

If your Discord is not in English, replace the defaults with your locale's titles verbatim
(`Regex.escape` is unnecessary for plain words, but escape any regex metacharacters).

## 4. Verify

After editing selectors, the phone's **What the watch sees** card updates within a second:

- **In a voice call** → yes
- **Muted** → yes/no rather than *unknown (no matching notification action)*

Then confirm the real thing, in this order:

1. Screen on: tap **Mute** on the watch, watch Discord's mic icon flip.
2. Lock the phone, tap **Mute** again. Unlock and confirm it flipped. This is the acceptance
   criterion the notification path exists for.
3. Screen on: tap **Deafen** — Discord should come forward, the click should land, and the
   phone should return to where it was via `GLOBAL_ACTION_BACK`.
4. Screen off: tap **Deafen** — expect the explicit *"Phone screen is off"* message on both the
   watch and a phone toast. That failure is by design; a silent one would not be.

The **Recent activity** list at the bottom of the phone screen shows which strategy handled each
command and why anything failed, without needing logcat.
