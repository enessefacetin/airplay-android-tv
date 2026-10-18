# Roadmap

Status as of 2026-10-18 (end of the first development session).

## Done

- Screen mirroring from iPhone and Mac (H.264 and 4K H.265), low-latency decoder, rotation
- AirPlay music (ALAC) with cover art, track info, progress, playing/paused state
- AirPlay video from apps such as YouTube (HLS via ExoPlayer)
- Lip sync between audio and video
- Wake the TV from standby when AirPlay starts (HDMI-CEC); verified after a reboot
- Turning the TV off ends the session
- Optional PIN pairing with remembered devices
- Settings: AirPlay name, PIN, 4K, low-latency mirroring
- TV remote: play/pause, previous/next, Back (pause and close / disconnect)
- 10-foot UI for all screens; OLED burn-in protection on the music screen
- Keep-alive (boot receiver, START_STICKY, 15-minute job), mDNS retries
- Manual GitHub release workflow with a signed APK

## Open checks

- [ ] Overnight standby test: leave the TV in standby overnight, then AirPlay to it
  in the morning. If it doesn't wake, check `adb logcat` for the service and
  `KeepAliveJob`, and Google TV's energy mode / Low Power Standby.

## Next (in suggested order)

1. **Unit tests.** None exist yet. Start with pure logic that has broken before:
   - `AvSync` (latency rises, decays, music never lowers, low-latency mode)
   - `VideoNal` (H.264 and H.265 NAL parsing, keyframe detection)
   - `Dmap` (track metadata), `MdnsAdvertiser.parseTxt`
   - PIN state handling in `AirPlaySession`
2. **TV volume.** Make the sender's volume slider control the TV's volume (system
   volume or HDMI-CEC) instead of the AudioTrack gain, for better sound at low volume.
3. **Portrait mirroring.** Fill the black bars beside a portrait phone screen with a
   blurred copy of the picture, like the music screen.
4. **CI on every push.** The release workflow is manual. Add a build (and the unit
   tests) on push and pull requests; cache `third_party/openssl/`.
5. **Smaller items**
   - Brief "Playing audio" placeholder before track info arrives: show a skeleton
     instead.
   - Show the paired-device names in Settings, and forget them one by one.
   - The status line says "from a device" while a PIN is shown; say "Pairing…".

## Known limits

- No FairPlay DRM video (Apple TV+, Netflix casting).
- No AirPlay 2 multi-room audio (sync with HomePods).
- Only `armeabi-v7a` is built; other devices need more ABIs.
- Reinstalling the app ends a running AirPlay session (the receiver restarts).
