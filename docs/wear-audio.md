# Call audio on Wear OS: what the watch actually allows

beta.2 and beta.3 joined Discord calls that stayed silent both ways. This file records why.
Every finding below comes from a Pixel Watch 4 (kenari_lte, Android 17, build CP3A.260905.002),
paired with a Pixel phone and Pixel Buds Pro 2, reading the system log and `dumpsys` over
wireless ADB. They're Android platform behaviour, not Discord's.

Labels: **observed** = read from the watch's own logs or dumps; **inferred** = follows from that.

The debug-build probes that reproduce all of it:
`--ez com.wristcord.SCOTEST true` (call audio, three ways), `MICTEST` (every mic source in both
audio modes) and `ENGINETEST` (the real call engine with a test tone). They log under
`WristCord/ScoProbe` and `WristCord/SelfTest`.

## 1. No app gets Bluetooth call audio while the watch is connected to its phone

A Pixel Watch is itself a hands-free device for the phone's calls: a Bluetooth **HFP client**
("HFPC") link to the phone, up all the time. While that link is up, the watch's HFP *audio
gateway* refuses to open call audio (SCO) to any classic Bluetooth headset (observed):

```
HeadsetService: startScoUsingVirtualVoiceCall: rejected SCO since HFPC is connected!
AS.BtHelper: requestScoState: connect to XX:XX:XX:XX:8A:E3 failed
AS.AudioDeviceBroker: setCommunicationRouteForClient: failure to start BT SCO for uid: 10012
```

- `AudioManager.setCommunicationDevice(headset)` still returns **true**. About 50 ms later the
  audio service removes the route again, and call audio falls back to the watch speaker.
  WristCord's route guard then silenced the call, as it should.
- The pre-Android 12 `startBluetoothSco()` hits the same refusal.
- **Telecom does too.** A self-managed call through Android's transactional Telecom API
  (`TelecomManager.addCall`) was offered the Pixel Buds as an endpoint, but its `connectAudio`
  got the same answer: `isScoAcceptable: rejected SCO since HFPC is connected!`, then
  `ERROR_AUDIO_ROUTE_BLOCKED`. Telecom fell back to the speaker.

So no app on the watch can use a classic headset's **mic** while it's paired with a phone (inferred).

## 2. LE Audio would get around it, but it's off for these buds

LE Audio calls don't use HFP. The watch has the LE Audio profile services (LE_AUDIO, CSIP,
VCP, LE_CALL_CONTROL), but its stored connection policy for the Pixel Buds Pro 2 is
`le_audio: FORBIDDEN`, so they connect over classic Bluetooth only (observed). Apps can't change
that policy.

## 3. What does work: media audio to the headset, the watch's own mic

Plain media playback (`USAGE_MEDIA`, normal audio mode) goes to the buds over **A2DP**, and
capture comes from the watch's built-in mic (observed: track routed to `TYPE_BLUETOOTH_A2DP`,
mic `TYPE_BUILTIN_MIC`). That's how WristCord calls run on such a watch since beta.4:

- The engine first asks for call audio and waits up to 2.5 s for Android to report it. If the
  route doesn't hold, it switches to media audio. Watches where call audio works, LE Audio
  headsets and watches not paired to a phone still get the headset's mic.
- Media audio is full-band 48 kHz stereo, not hands-free's 16 kHz. It adds A2DP's latency of
  roughly 150-250 ms.
- The headset rule holds: no join without a headset, and nothing plays from the watch speaker.
  The route guard still silences the call if Android moves it there.

## 4. The call-voice mic source returns pure zeros without a call link

| Mode | `VOICE_COMMUNICATION` | `VOICE_RECOGNITION` | `MIC` | `UNPROCESSED` |
|---|---|---|---|---|
| normal | 30 of 50 frames exactly zero | RMS 86, no zeros | RMS 9 | RMS 11 |
| in communication | **all 50 frames zero** | RMS 35 | RMS 13 | RMS 20 |

These are one second of a quiet room per source (observed). beta.2 and beta.3 recorded with
`VOICE_COMMUNICATION` in communication mode, so the watch sent digital silence even before
reaching Discord. Media mode now uses `VOICE_RECOGNITION`, the loudest clean source. Any source
that gives a full second of exact zeros is swapped for the next one.

## 5. MediaCodec's Opus crashed the app

Android 17 runs the software codecs inside the app's own process ("ApexCodecs"). With more than
one Opus codec busy, an encoder and a decoder fed from one thread while the mic's encoder
ran on another, the decoder handed out an input buffer whose memory was already unmapped.
The first `ByteBuffer.put()` into it ended in `SIGSEGV` (observed, backtrace:
`memcpy` ← `JNI::GetByteArrayRegion`). With only the engine's own codecs it didn't crash. It did
log "MediaCodec discarded an unknown buffer" constantly, and handed decoded frames back in
bursts that the mixer then half-dropped: the "staggered" test beep.

Since beta.4 the watch uses **libopus** (Xiph.Org's reference implementation, via opusic-sys),
compiled into `native/dave`: one packet in, one 20 ms frame out, synchronously, with Opus's
own loss concealment for gaps.

## 6. Choppy incoming audio: the network, and the playout that couldn't cope

After beta.4, incoming speech came through choppy, most noticeably while you talked too.
What the watch showed (observed):
- Its Wi-Fi was on **2.4 GHz** (channel 6, 2437 MHz), the band its Bluetooth link to the buds
  shares a radio with.
- Pings to it on the LAN took **83-143 ms**. That's Wi-Fi power save: the access point holds a
  sleeping watch's packets and delivers them in bunches.

The playout couldn't absorb that. It started after 40 ms, held at most 120 ms, and dropped the
oldest audio whenever more arrived at once. It also discarded out-of-order packets, 1.8% in
the first real call, and decoded on the network thread at normal priority. There the
audio-priority mic thread (encode, encrypt, send) could hold it up while you talked.

Since beta.5:
- **A real jitter buffer** (`discord/.../voice/JitterBuffer.kt`, unit-tested):
  - reorders by sequence number;
  - adapts its delay from 60 ms upward each time the network runs it dry mid-sentence, and
    back down after 10 s of calm;
  - absorbs bursts up to 500 ms whole;
  - rebuilds a single lost packet from the next one's Opus in-band FEC, and conceals the rest;
  - treats running dry after a speaker's silence frames as the end of a sentence, not a stall.
- **Decoding at playout**, on the audio-priority playback thread. The network thread now
  only decrypts and queues, at audio priority itself.
- **A deeper output buffer for media audio** (160 ms; A2DP adds ~200 ms anyway), pre-filled
  so playback is paced from the first tick.
- **A low-latency Wi-Fi lock** for the length of a call, against power-save bunching.

On the watch, under a stress test, the engine played clean: Wi-Fi-style bunches of 5 packets
every 100 ms, shuffled, 2% lost, with a second thread doing your side's encode and encrypt at
50 frames a second. It had no output underruns; the lost packet was rebuilt from FEC.
Decoding cost ~1 ms per frame and encoding ~3.5 ms.

Outgoing audio was fixed too. RTP timestamps now follow capture time across pauses, and
the first packet of each sentence carries the marker bit (RFC 3551), so the far end's playout
schedules it correctly. Before, every sentence's first words looked late. The frame just
before voice activity fires is sent too, and the wrist mic gets up to +12 dB of gain in media
mode.

Not changeable from an app: Bluetooth and 2.4 GHz Wi-Fi share the watch's radio. If audio
still breaks up only while you talk, a 5 GHz network (or LTE) for the watch would help.

## 7. Verified in a real call

Here is beta.4's first real Discord call on the watch after 85 s (observed, from its
`WristCord/Call` stats):
- welcomed into the call's DAVE group on the first try;
- 824 packets sent;
- 3,165 received, 2,997 decrypted and 2,925 decoded;
- no transport, E2EE or sender failures;
- the mic reached level 1,268 while talking;
- audio routed to "Bluetooth media audio".
