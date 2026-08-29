# Soundbored

An Android soundboard. Add a sound from a **YouTube link** or an **audio file on the device**,
pick an interval on the waveform, optionally fade it in or out, and it becomes a button on your
board. The audio is cut once and stored locally — pressing a button never touches the network.

Sounds are grouped into **boards** you switch between from the dropdown in the app bar. A board
holds any number of sounds and scrolls.

A saved button can be reopened later (long-press → **Edit sound**). Since only the cut audio is
kept, editing refetches the original video and restores your in/out points and fades so you can
move them; saving replaces the audio in place, keeping the button's identity and slot.

## Build

The project uses AGP 9.3.2 / Gradle 9.7.1 and needs a JDK 17+ (Android Studio's bundled JBR works):

```
JAVA_HOME=~/.local/share/android-studio/jbr ./gradlew :app:assembleDebug
```

`local.properties` points at the SDK. `compileSdk`/`targetSdk` are 37; `minSdk` is 26.

The debug build has its own application id (`.debug` suffix) and is named "Soundbored Debug", so it
installs beside a released build instead of replacing it.

Release builds are signed only if a `keystore.properties` sits next to `settings.gradle.kts`:

```properties
storeFile=release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Neither that file nor the keystore is in git. Without them `assembleRelease` still works, it just
produces an unsigned APK.

Instrumented test (needs a connected device or emulator **with network**):

```
JAVA_HOME=~/.local/share/android-studio/jbr ./gradlew :app:connectedDebugAndroidTest
```

- `ClipPipelineTest` runs the whole path against a real video: resolve, download, waveform, cut,
  then checks the cut file's duration, that it plays, and that a rendered fade-out really does
  decay in the saved audio. Needs network.
- `LocalAudioTest` synthesises a WAV and cuts it, covering imported formats that cannot be
  remuxed and so must be re-encoded.
- `PreviewPlayerTest` checks that the preview starts exactly where the selection does, including
  a content check that the decoded samples really come from the requested offset.
- `ClipRepositoryTest` covers storage: edit-in-place (no duplicate button, no orphaned file),
  boards owning their sounds, deleting a board taking its audio with it, and migration from the
  old index format.

Only `ClipPipelineTest` needs network.

## How a sound gets made

1. **Resolve** — for YouTube, `yt/YoutubeSource` asks NewPipeExtractor for the video's audio streams
   and picks the highest-bitrate progressive **M4A** (AAC) track, preferring the original language
   track over dubs. AAC in an MP4 container is what lets step 4 be lossless when there are no fades.
2. **Fetch** — `audio/AudioFetcher` pulls the track in 2 MB ranged requests. A single long-lived
   request to googlevideo gets throttled and then dropped, so chunking (with resume from the last
   written byte) is what makes this reliable. For a local file, `audio/LocalAudioImporter` copies
   the picked document into the same cache slot and reads its duration and tags instead. From here
   on the two sources are indistinguishable.
3. **Waveform** — `audio/Waveform` decodes the file to PCM and reduces it to ~25 ms peak buckets.
   It streams partial results, so the editor opens immediately and the drawing fills in behind it.
4. **Cut** — `audio/AudioTrimmer` seeks to the previous sync sample, walks forward to the exact
   requested start, and copies the encoded frames into a new MP4 with `MediaMuxer`. No re-encoding,
   so it is fast and lossless. (Opus-only videos are written to an Ogg container instead, Android 10+.)
   **With a fade — or a source MediaMuxer cannot hold, like MP3, WAV or FLAC** — that shortcut is
   off: the region is decoded to PCM, any ramp is applied to the samples, and the result is
   re-encoded to AAC. The fade is therefore baked into the file —
   playback stays a plain `MediaPlayer.start()`. The editor preview decodes the selected range
   with the same `PcmDecoder` and pushes it to an `AudioTrack`, applying fades on the way out, so
   what you hear before saving is what gets saved. It deliberately does not seek a `MediaPlayer`:
   that seek is only approximate, and on a Pixel 7 Pro a seek to 5 s into a YouTube M4A is ignored
   outright — playback starts from the top of the file.
5. **Store** — the clip lands in `filesDir/clips/` on the board that is currently open, with a
   small JSON index next to it holding the boards, the clips and which board was last open. The
   fetched source file is deleted. `audio/SoundPlayer` keeps a prepared `MediaPlayer` per clip so
   a tap fires instantly; tapping a playing clip restarts it, different clips overlap.

Clips are capped at 60 seconds. Fades may not overlap each other or outgrow the clip; the
waveform scales its bars by the fade envelope, so the drawing is what you will hear.

## Layout

```
audio/    AudioFetcher, LocalAudioImporter, PcmDecoder, AudioTrimmer, Waveform,
          PreviewPlayer, SoundPlayer
data/     Board, Clip, SourceKind + ClipRepository (files on disk, JSON index, StateFlow)
yt/       NewPipeExtractor wiring (HttpURLConnection downloader, stream picking)
ui/       Compose screens, the waveform selector, view models
```

The index is versioned: a pre-boards index (a bare JSON array of clips) is migrated on read, with
those clips joining the first board.

## Things worth knowing

- **NewPipeExtractor is the moving part.** When YouTube changes its player, extraction breaks until
  the library ships a fix; bump `com.github.TeamNewPipe:NewPipeExtractor` in `app/build.gradle.kts`.
- `protobuf-javalite` is declared explicitly because NewPipeExtractor needs it at runtime but does
  not declare it in its published POM — without it, resolving a link throws `NoClassDefFoundError`.
- There are no Compose UI tests: Espresso 3.7 crashes on an API 37 image (it reflects on
  `InputManager.getInstance`, removed in Android 17). Re-add them once Espresso catches up.
- Imported files are read through a persisted URI grant, so reopening a local sound for editing
  works later. If the user removes the file or revokes access, editing reports it instead of
  silently failing.
- Downloading copyrighted audio is on you and your jurisdiction; the app just moves bytes.

## License

Soundbored — cut YouTube clips and local audio into soundboard buttons.
Copyright (C) 2026 Arvid Eriksson.

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for the full text.

GPL-3.0 rather than something permissive because the app links
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), which is GPL-3.0; the
combined work carries those terms.
