# Frequency Shift

A native Android music player that re-tunes any track on your device to
chakra / Solfeggio frequencies in real time. Bring your own music.

## Features

- **11 frequency presets** on an infinite cyclic wheel — 174, 285, 396, 417,
  432, 440, 528, 639, 741, 852, 963 Hz
- **Two tuning modes**
  - **Vinyl** (linked) — pitch + tempo move together, like spinning a record
    faster or slower. No DSP artifacts.
  - **Studio** (independent) — pitch shifts but tempo stays at 1.0×, via
    Sonic time-stretching.
- **Critically-damped spring crossfade** when changing tunings — duration
  scales with shift size (1.2 s for small, up to 4 s for large)
- **Export to file** — bake the current tuning into a new `.m4a` saved to
  `Music/FreqShift/`. Filenames encode the tuning (`Imagine_vinyl432hz.m4a`)
- **Media-style notification** with play / pause / track-skip + custom
  **previous tuning / next tuning** buttons in the compact view
- **Lock-screen + Bluetooth controls** via Media3 MediaSession
- **Sleep timer** — 15 / 30 / 60 / 90 min, with a 30 s volume fade-out tail
- **Shuffle / repeat** with chakra-tinted glow when on
- **Persistent settings** — preset, tuning mode, shuffle, repeat all restored
  on launch via DataStore
- **Library search** — filters title and artist as you type
- **Chakra-themed UI** — every accent (album glow, seek bar, play button,
  notification icon) animates to the active chakra colour
- **Auto-dedupe + clean metadata** — same song indexed via two paths shows
  once; `<unknown>` artists become em-dashes
- **Exported files filtered** from the library so you don't accidentally
  re-pitch already-tuned files

## The frequencies

| Hz | Name / Chakra | Cents from 440 |
|---:|----------------------------|---:|
| 174 | Foundation · pain relief | −6 |
| 285 | Tissue renewal | +48 |
| 396 | Root · *Muladhara* | +18 |
| 417 | Sacral · *Svadhishthana* | +7 |
| 432 | Verdi tuning · Earth resonance | −32 |
| 440 | Standard concert pitch | 0 |
| 528 | Solar plexus · *Manipura* (the "miracle tone") | +16 |
| 639 | Heart · *Anahata* | +46 |
| 741 | Throat · *Vishuddha* | +2 |
| 852 | Third eye · *Ajna* | +44 |
| 963 | Crown · *Sahasrara* | −44 |

> Health and "DNA repair" claims associated with these frequencies come from
> alternative sound-therapy traditions, not mainstream medicine. The app makes
> no medical claims — it's a tuning tool.

## How tuning works

Each preset shifts the music's pitch so that the 12-TET note nearest the
target Hz lands exactly on the target. All shifts are within ±50 cents (half
a semitone) — gentle enough to keep the music recognisable.

```
semitones        = 12 × log₂(targetHz / 440)
nearestNoteFreq  = 440 × 2^(round(semitones) / 12)
pitchRatio       = targetHz / nearestNoteFreq
```

In **Vinyl** mode, both `speed` and `pitch` are set to `pitchRatio`. In
**Studio** mode, `speed = 1.0`, only `pitch` shifts.

## Build

Requires JDK 17, Android SDK 34. Open in Android Studio (Iguana or newer)
and Run, or:

```bash
./gradlew :app:installDebug
```

## Stack

Kotlin 2.0 · Jetpack Compose · Material 3 · Media3 1.4.1 (ExoPlayer +
MediaSession + Transformer) · Coil · DataStore. Min SDK 24, target SDK 34.

## License

MIT.
