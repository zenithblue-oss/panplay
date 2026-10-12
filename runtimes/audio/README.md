# PanPlay game audio

Built by `scripts/build-audio.sh` (pins and sha256s in `scripts/components.env`) for Proton's `lib/wine/aarch64-unix`
`winepulse.so` / `winealsa.so`. The Wine unix side is aarch64 Android bionic (x86/x64 game code runs under
FEX/Box64 on the PE side only), so the client libraries are NDK aarch64 builds (API 28).

| Component | Contents | Licence |
|---|---|---|
| PulseAudio 17.0-2 | pulseaudio v17.0 (commit 1f020889) daemon, pactl, libpulse, module-native-protocol-unix, module-aaudio-sink, libsndfile 1.2.2, libltdl 2.6.2 | LGPL-2.1+ |
| ALSA 1.2.16.1-2 | alsa-lib 1.2.16.1 (with seq/rawmidi: winealsa.so links the MIDI API), `libasound_module_pcm_aaudio.so` | LGPL-2.1+ |

- `pulseaudio-android.patch`: bionic build fixes (no NLS, no execinfo/capability headers, no PI mutex check,
  `pa_drop_root` no-op) and the meson entry for the AAudio sink. The server reports version `17.0-dirty` because of it.
- `module-aaudio-sink.c`: termux-packages `packages/pulseaudio/module-aaudio-sink.c` (commit fdf35a1a, Tom Yan,
  LGPL-2.1+). PanPlay changes: `buffer_bursts=` argument, usage GAME, buffer size log line.
- `pcm_aaudio.c`: PanPlay ALSA ioplug plugin. AAudio low-latency shared output, playback only, S16/FLOAT, 1-2 ch.
  Underruns are padded with silence (no ALSA XRUN) and logged to logcat tag `pcm_aaudio` on stop.

Runtime (`GameAudio.kt`, called from `ContainerManager.run` for graphical sessions):

- Driver: global default in Components > Game audio, per-game override in the Launch sheet (shortcut JSON key
  `"audio"`: `pulse` / `alsa` / `off`, empty = default). `WINEDLLOVERRIDES` disables the other `wine*.drv`
  (Off disables both: the game sees no audio device). A missing component falls back to Off.
- ALSA: no server. Wine gets `ALSA_CONFIG_PATH` = generated `files/audio/asound.conf`
  (`pcm.!default` = `plug` -> `aaudio`, `buffer_bursts 4`).
- PulseAudio: the app starts `pulseaudio -n -F files/audio/pulse/default.pa` per session (socket
  `files/audio/pulse/native`, anonymous auth, no cookie, no autospawn), log `files/logs/pulseaudio.log`. It is
  stopped and the socket removed when the session ends; the daemon carries `WINEPREFIX`, so the stale-process reaper
  also catches one left by a crashed launcher.

Latency defaults: Wine's mmdevapi period is 10 ms. AAudio runs LOW_LATENCY / shared at the device rate. The AAudio
buffer is set to 4 bursts (tablet: 4 x 256 = 1024 frames, 21 ms) instead of AAudio's default 2048 frames (43 ms);
no underruns were seen at 4 bursts.

## Measurements (Lenovo TB336FU, Mali-G57, 48 kHz, no MMAP, 2026-10-12)

`tests/wasapitone.c` (WASAPI shared, event-driven) for 30 s; CPU = utime+stime of the process over 20 s, % of one core.
FPS = `dxcube d3d11` (DXVK) for 30 s with the tone playing in parallel, PerfRecorder avgFps, two runs each.

| | ALSA | PulseAudio | Off |
|---|---|---|---|
| Wine stream latency (GetStreamLatency) | 24 ms | 20 ms | - |
| Extra buffering below Wine | AAudio 1024 frames (21 ms) | pulse sink-input 26 ms + AAudio 1024 frames (21 ms) | - |
| Audio server CPU | none | pulseaudio 7.2 % | - |
| Game process CPU (tone) | 4.6 % | 4.5 % | - |
| Underruns (app side / pcm_aaudio / AudioFlinger) | 0 / 0 / no change | 0 / - / no change | - |
| dxcube avg FPS (run 1, run 2) | 73.8, 72.8 | 69.9, 71.5 | 71.6, 73.9 |

Default: ALSA (no extra process, about 26 ms less buffering, about 7 % of a core less CPU, no FPS cost seen).
PulseAudio stays available for games that only behave with winepulse.
