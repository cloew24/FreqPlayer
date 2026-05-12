# Frequency Shift

A native Android music player that re-tunes any track on your device to
chakra / Solfeggio frequencies in real time. Bring your own music.

## Features

### Tuning
- **11 presets** on an infinite cyclic wheel: 174, 285, 396, 417, 432, 440,
  528, 639, 741, 852, 963 Hz. 440 is the "off" position (no pitch shift).
- **Two tuning modes**
  - **Vinyl** (linked) — pitch + tempo move together, like spinning a record
    faster or slower. Mathematically clean resampling, no DSP artifacts.
  - **Studio** (independent) — pitch shifts but tempo stays at 1.0×, via
    Sonic time-stretching.
- **Single-jump transitions with a deep AudioTrack buffer** absorb the Sonic
  reconfiguration so changing tunings is click-free.
- **Export to file** — bake the current tuning into a new `.m4a` saved to
  `Music/FreqShift/`. Filenames encode the tuning
  (`Imagine_vinyl_chakra_528hz.m4a`).

### Library
- **MediaStore scan, sorted by recently-added** with auto-dedupe (same song
  indexed via multiple paths collapses to one entry, preferring the copy
  with album art and the newest timestamp).
- **Folder mode** — pick any folder via Storage Access Framework and play it
  as a transient queue. Library list switches to the folder; an exit button
  brings the full library back without stopping playback.
- **Search** filters title and artist as you type.
- **Embedded artwork fallback** — when MediaStore has no thumbnail, a custom
  Coil fetcher reads the file's ID3v2 APIC (or FLAC / MP4 equivalent) so
  sideloaded music shows its cover.

### Playback control
- **Media-style notification** with play / pause / track-skip plus custom
  **previous tuning / next tuning** buttons in the compact view.
- **Lock-screen + Bluetooth controls** via Media3 `MediaSession`. Tapping
  notification artwork opens the app.
- **Sleep timer** — 15 / 30 / 60 / 90 min, with a 30 s volume fade-out tail.
  Cancelling mid-fade restores volume.
- **Shuffle / repeat** with chakra-tinted glow when on.

### UI
- **Chakra-themed accents** — every accent (album glow, seek bar, play
  button, notification icon) animates to the active chakra colour.
- **Wheel haptics** — a tick each time the wheel crosses a preset boundary
  during drag, a stronger thunk when it snaps.
- **Material You dynamic colour** on Android 12+ — neutral base follows the
  wallpaper; chakra accents stay intact.
- **Tablet / landscape layout** — wide windows get a side-by-side wheel +
  library split with the now-playing panel docked inline. Phone-portrait
  keeps the stacked layout with a pinned bottom now-playing bar.
- **Bilingual** — English + German (`values-de`).

### Persistence
- DataStore retains preset, tuning mode, shuffle, and repeat across launches.

## The frequencies

| Hz | Name / Chakra | Cents from 440 |
|---:|----------------------------|---:|
| 174 | Foundation · pain relief | −6 |
| 285 | Tissue renewal | +48 |
| 396 | Root · *Muladhara* | −2 |
| 417 | Sacral · *Svadhishthana* | +7 |
| 432 | Verdi tuning · Earth resonance | −32 |
| 440 | Standard concert pitch (off) | 0 |
| 528 | Solar plexus · *Manipura* (the "miracle tone") | +16 |
| 639 | Heart · *Anahata* | +46 |
| 741 | Throat · *Vishuddha* | +2 |
| 852 | Third eye · *Ajna* | +44 |
| 963 | Crown · *Sahasrara* | −44 |

> Health and "DNA repair" claims associated with these frequencies come from
> alternative sound-therapy traditions, not mainstream medicine. The app
> makes no medical claims — it's a tuning tool.

## How tuning works

Each preset shifts the music's pitch so that the nearest 12-TET note lands
exactly on the target Hz. All shifts are within ±50 cents (half a semitone),
gentle enough to keep the music recognisable.

```
semitones        = 12 × log₂(targetHz / 440)
nearestNoteFreq  = 440 × 2^(round(semitones) / 12)
pitchRatio       = targetHz / nearestNoteFreq
```

In **Vinyl** mode, both `speed` and `pitch` are set to `pitchRatio` (single
resample). In **Studio** mode, `speed = 1.0` and only `pitch` shifts, with
Sonic time-stretching keeping the tempo stable.

### Click-free transitions

`ExoPlayer.setPlaybackParameters` is asynchronous: Sonic flushes its current
audio frame and starts a new one at the new ratio, producing a discontinuity
at the boundary. With Media3's default ~100 ms output buffer that boundary
is close enough to the speaker to be audible as a click.

We configure `DefaultAudioSink` with `setMinPcmBufferDurationUs(750_000)`,
giving AudioTrack a 750 ms output ring. By the time the AudioTrack reaches
the Sonic-flush boundary, the new samples are already buffered behind it
and the transition plays as a smooth pitch slide. Latency cost (≈¾ s from
press to audible change) is fine for a music tuning app.

We also explicitly disable `setEnableAudioTrackPlaybackParams` so pitch
always routes through Sonic (which the buffer is in front of), not through
AudioTrack's hardware pitch path (which would bypass the buffer).

## Architecture

- **`PlaybackService`** owns the `ExoPlayer` and `MediaSession`. Publishes
  the live player on `PlayerHolder.flow`. Notification's tuning buttons
  write the new preset to `DataStore`; never touch `PlaybackParameters`.
- **`PlayerViewModel`** is the single source of truth for UI state. Observes
  `PlayerHolder.flow` and `SettingsStore.flow` and applies `PlaybackParameters`
  exactly once per change. Cancels the position-poll loop on player detach.
- **`MediaLibrary`** scans MediaStore and walks SAF trees. Folder scan
  builds skeleton tracks from filenames (no per-file decode) for sub-second
  return; real metadata arrives from ExoPlayer once playback starts.
- **`SettingsStore`** is the DataStore wrapper. Four keys: preset, tuning
  mode, shuffle, repeat mode.

## Build

Requires JDK 17, Android SDK 34. Open in Android Studio (Iguana or newer)
and Run, or:

```bash
./gradlew :app:installDebug
```

## Stack

Kotlin 2.0 · Jetpack Compose · Material 3 · Media3 1.4.1 (ExoPlayer +
MediaSession + Transformer) · Coil · DataStore · DocumentFile. Min SDK 24,
target SDK 34.

## License

MIT.
