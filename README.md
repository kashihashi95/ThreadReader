# ThreadReader

An Android app that reads Reddit threads aloud (post and comments) with an on-device neural voice, so you can listen to Reddit like a podcast.

## The problem it solves

Long Reddit threads are good listening but bad reading on a phone screen, and the phone's built-in text-to-speech reads them badly: usernames, acronyms, links and run-on comments come out as noise, and it stops when the screen locks. ThreadReader cleans the text up for speech, generates natural-sounding audio entirely on the phone with the Supertonic model, and plays it as a proper media session with lock-screen controls, captions and a playlist of your subreddits.

<!-- SCREENSHOT: the Now Playing screen with captions on a phone, plus the lock-screen media notification; ideally a short screen recording with audio of a thread being read -->

## Features

- Reads a thread's post and comments in order, announcing who is replying to whom.
- On-device neural TTS with [Supertonic 3](https://huggingface.co/supertone-oss-archive/supertonic-3) on ONNX Runtime: no cloud TTS and no account. Narrator picker and a Best/Fast quality setting.
- Text clean-up for speech: about forty common Reddit acronyms said in full, links and markup removed, silence trimming and a peak limiter on the audio.
- Media3 `MediaSession` playback: lock-screen and headset controls, background playback, resume where you left off.
- Playlist of your subreddits: posts ordered round-robin by rank, with done/started states and live captions on a card-deck "Now Playing" screen.
- Generates at most 2.5 minutes of audio ahead and stops when paused, to keep the phone cool.

## Architecture note

Everything runs on the device by default: fetching, text clean-up, speech synthesis and playback. The design also supports an optional self-hosted backend that runs a larger voice model and LLM rewriting off the phone, which is faster and saves battery (see [ADR-007](docs/adr/ADR-007-personal-server-build.md) and [ADR-008](docs/adr/ADR-008-server-playlist.md)). That backend isn't included in this repository.

## Tech stack

- Kotlin, Jetpack Compose (Material 3), Kotlin coroutines and serialization
- AndroidX Media3 (ExoPlayer, MediaSession)
- ONNX Runtime for Android running the Supertonic 3 voice model
- OkHttp; JUnit 5 + Robolectric for tests
- Gradle (Kotlin DSL), with the voice model delivered as a Play Asset Delivery pack

## Build and run

Requirements: JDK 21 and the Android SDK (compileSdk 36, minSdk 26).

1. Download the Supertonic 3 voice model (about 400 MB, from its official Hugging Face repository) and Google's bundletool (from its official GitHub release), both pinned and checksum-verified:

   ```bash
   scripts/get_models.sh
   ```

   On Windows: `powershell -ExecutionPolicy Bypass -File scripts\get_models.ps1`

2. Build, test and install:

   ```bash
   ./gradlew assembleMobileDebug            # APK in app/build/outputs/apk/mobile/debug/
   ./gradlew testMobileDebugUnitTest        # unit tests
   ```

3. Install on a connected phone. The voice model ships as a Play asset pack, which a plain APK install doesn't include, so install the bundle with bundletool:

   ```bash
   ./gradlew bundleMobileDebug
   java -jar scripts/bundletool.jar build-apks --bundle=app/build/outputs/bundle/mobileDebug/app-mobile-debug.aab --output=threadreader.apks --local-testing --connected-device
   java -jar scripts/bundletool.jar install-apks --apks=threadreader.apks
   ```

The model files and bundletool are not committed; `get_models` puts them in `supertonic_assets/src/main/assets/supertonic/` and `scripts/bundletool.jar`, both gitignored.

## Configuration

- **Debug builds need no configuration.** Create `local.properties` with your `sdk.dir` if Android Studio hasn't.
- **Release signing:** copy [`secrets.properties.example`](secrets.properties.example) to `secrets.properties` (gitignored) and fill in your own keystore details.
- **Reddit access:** no API key is needed; by default the app reads public threads through community Reddit mirrors, with failover between them.

## How I built it

I built ThreadReader with Claude Code, working from written specs and architecture decision records ([docs/adr](docs/adr)) and holding the agent to a written quality bar ([CONSTRAINTS.md](CONSTRAINTS.md)). I chose the voice engine by building and comparing Piper, Kokoro and Supertonic (see ADR-001, ADR-004 and ADR-009), then cut the app down to a phone-only build that keeps the phone cool.

## Third-party models

The Supertonic 3 model files are © Supertone Inc. and are distributed under the **BigScience Open RAIL-M License**, a copy of which is in [`supertonic_assets/LICENSE-Supertonic-OpenRAIL-M.txt`](supertonic_assets/LICENSE-Supertonic-OpenRAIL-M.txt). They are not stored in this repository; `scripts/get_models.*` downloads them from <https://huggingface.co/supertone-oss-archive/supertonic-3> (pinned revision `aafc6e3`). Use of the model, including in any app built from this repository, is subject to the use-based restrictions in Attachment A of that license.

Other dependencies (ONNX Runtime: MIT; AndroidX, OkHttp: Apache-2.0) are under their own licenses.

## License

The app's source code is MIT licensed. See [LICENSE](LICENSE). The MIT license does not cover the Supertonic model files (see above).
