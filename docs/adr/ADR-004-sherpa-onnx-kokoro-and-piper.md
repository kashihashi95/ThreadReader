# ADR-004: sherpa-onnx 2.0.1 for Opt-in Kokoro and Piper Voices

> Note: this repository contains only the phone-only `mobile` build; the sherpa-onnx Kokoro and Piper voice support described here is not included.

## Status
Accepted. Partially supersedes [ADR-001](ADR-001-piper-onnx-over-kokoro.md): the hand-rolled `PiperOnnxTtsEngine` is removed, and Piper and Kokoro now run through sherpa-onnx. Supertonic stays the default engine.

## Date
2026-10-28

## Context
ADR-001 picked Piper and expected Kokoro to come later as an opt-in voice pack. Since then Supertonic (`SupertonicOnnxTtsEngine`) became the default engine, and `PiperOnnxTtsEngine` was never wired into playback. It had no real phonemizer (it fed character codes to the model), so it could not produce real Piper speech. We want many more voices without writing our own G2P, and without adding to the base APK's model payload.

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) runs Kokoro and Piper/VITS models and ships an Android AAR with a Kotlin API. We pin release **v2.0.1** (2026-10-26).

## Decision

### Library
- `app/libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar` (SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`), tracked through git LFS (`*.aar` in `.gitattributes`). It is referenced with `implementation(files(...))` because `FAIL_ON_PROJECT_REPOS` rules out `flatDir`.
- We use the static-link build because Supertonic already depends on `onnxruntime-android:1.21.1`. The regular sherpa AAR ships its own `libonnxruntime.so` for every ABI, which would collide. The static AAR links ONNX Runtime 1.28.2 into `libsherpa-onnx-jni.so` on arm64-v8a, armeabi-v7a and x86_64, so those ABIs carry no duplicate `.so`. On **x86 (32-bit, emulator-only)** the AAR still ships a separate `libonnxruntime.so` (1.28.2). `packaging.jniLibs.pickFirsts += "lib/x86/libonnxruntime.so"` resolves the clash. File dependencies resolve first, so sherpa's 1.28.2 copy is the one that gets packaged, and the ORT 1.21.1 Java binding uses it through the backward-compatible ORT C API. This is **not verified on an x86 device**.
- There are no duplicate classes: the AAR's `classes.jar` only contains `com.k2fsa.sherpa.onnx.*`. R8 keeps `com.k2fsa.sherpa.onnx.**` because the JNI layer reads config fields by name.
- API used (checked against the AAR's `classes.jar`, not older docs): `OfflineTts(assetManager: AssetManager?, config)`. With a **null** AssetManager, sherpa loads models from absolute file paths (`newFromFile`). We also use `OfflineTtsConfig`, `OfflineTtsModelConfig(kokoro|vits, numThreads, provider)`, `OfflineTtsKokoroModelConfig`, `OfflineTtsVitsModelConfig`, and `generate(text, sid, speed): GeneratedAudio(samples, sampleRate)`.
- **sherpa-onnx 2.x removed espeak-ng and piper-phonemize** (upstream PR #4055, which made the library GPL-free). `dataDir` is now ignored, and text is phonemized through a **lexicon**. We therefore ship the upstream `lexicon-en-us.txt` (6.3 MB, SHA-256 `8b67ae549a465eee78f92ecb74644ad90c3c58ce65f19c68baf85878a10ef73a`) and set it as `lexicon` for both Kokoro and VITS. The `espeak-ng-data` folders in the model tarballs are no longer shipped. All 18 copies we downloaded were byte-identical (tree hash `50782b03…`), but sherpa 2.x does not read them.

### Models and voices
| Voice id(s) | Model (sherpa `tts-models` tarball) | Speakers | ONNX size | Dataset / model license |
| --- | --- | --- | --- | --- |
| `kokoro:<the 28 English speakers, af_alloy (0) to bm_lewis (27)>` | `kokoro-multi-lang-v1_0` (full precision) | 28 of 54 | 325.6 MB + 28.2 MB voices.bin | Apache-2.0 |
| `piper:en_US-libritts_r-medium#<0..903>` | `vits-piper-en_US-libritts_r-medium-int8` | 904 | 22.5 MB | CC-BY-4.0 (LibriTTS-R, OpenSLR 141) |
| `piper:en_US-arctic-medium#<0..17 or name>` | `vits-piper-en_US-arctic-medium-int8` | 18 | 22.1 MB | CMU ARCTIC: "free for use for any purpose (commercial or otherwise)" |
| `piper:en_US-bryce-medium` | `…-bryce-medium-int8` | 1 | 18.6 MB | Public domain |
| `piper:en_US-joe-medium` | `…-joe-medium-int8` | 1 | 18.6 MB | CC0 (NabuCasa voice-datasets) |
| `piper:en_US-john-medium` | `…-john-medium-int8` | 1 | 18.6 MB | Public domain (LibriVox) |
| `piper:en_US-kristin-medium` | `…-kristin-medium-int8` | 1 | 19.3 MB | Public domain (LibriVox) |
| `piper:en_US-norman-medium` | `…-norman-medium-int8` | 1 | 19.3 MB | Public domain (LibriVox) |
| `piper:en_US-sam-medium` | `…-sam-medium-int8` | 1 | 18.3 MB | Apache-2.0 |
| `piper:en_GB-alba-medium` | `…-alba-medium-int8` | 1 | 18.7 MB | CC-BY-4.0 (Edinburgh DataShare 10283/3270) |
| `piper:en_GB-cori-medium` | `…-cori-medium-int8` | 1 | 19.3 MB | Public domain (LibriVox) |
| `piper:en_GB-northern_english_male-medium` | `…-northern_english_male-medium-int8` | 1 | 18.7 MB | CC-BY-SA-4.0 (OpenSLR 83) |

That is 959 selectable voices: 28 Kokoro and 931 Piper. CC-BY and CC-BY-SA voices need attribution in the app's licenses screen (follow-up). The `MODEL_CARD` of each voice ships in the pack.

**Excluded by the licensing gate** (we read each tarball's `MODEL_CARD`):

| Voice | Reason |
| --- | --- |
| `en_US-ryan-medium` | CC BY-NC-SA 4.0 (non-commercial) |
| `en_US-hfc_female-medium`, `en_US-hfc_male-medium` | CC BY-NC-SA 4.0 (non-commercial) |
| `en_GB-alan-medium` | Upstream mimic3 `apope_low` LICENSE says "Copyright 2022 Mycroft AI, All Rights Reserved" |
| `en_US-amy-medium` | Card says "See URL" (mimic3-voices). No amy voice or license exists there, so it cannot be verified |
| `en_US-kusal-medium` | Card says "See URL" (mimic2). No dataset license was found |
| lessac, jenny_dioco | Never added: Blizzard/lessac research-only terms, jenny_dioco non-commercial |

The tarball SHA-256 values are pinned in `scripts/fetch_sherpa_models.sh` and match GitHub's release digests. Kokoro is `60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4`.

**Open licensing question:** upstream's header says `lexicon-en-us.txt` was built from "CMUDict + en_list + compounds". `en_list` is espeak-ng's English dictionary source (GPL-3.0). Before release, confirm the lexicon's license with upstream, or rebuild it from CMUDict only. The lexicon is US English, so the en_GB voices are spoken with US pronunciations.

### Delivery
- There are two **install-time** asset packs, delivered exactly like `supertonic_assets`. `kokoro_assets` is 364 MB raw (146 MB before the full-precision switch) and `piper_assets` is 210 MB raw (146 MB compressed). ThreadReader is a personal-use app installed with bundletool, and a plain `bundletool install-apks` does not deliver on-demand packs (that needs Play or `--local-testing` plus Play Core), so install-time delivery is the simplest option that works. bundletool and `adb install-multiple` handle the larger install (about 470 MB of split APKs) without trouble.
- Model binaries are **not in git**. `scripts/fetch_sherpa_models.sh` (with a `.ps1` wrapper) downloads the pinned tarballs, verifies SHA-256 and lays out `<pack>/src/main/assets/sherpa/…`, and those directories are gitignored. Bundle builds fail fast through `verifySherpaPacks` when the packs were not fetched. `assemble*`, unit tests and lint do not need the models.
- Install-time packs land in the APK assets, but sherpa-onnx loads models from file paths. On first launch the dashboard therefore unpacks `assets/sherpa/` into `filesDir/sherpa` in the background, next to the Supertonic unpack and behind the same "Preparing voices…" card (`SherpaAssetManager.unpackAll()`). Each file is copied to a temp name and renamed, so an interrupted copy is redone. If playback starts before the dashboard has unpacked a model, `createEngine()` unpacks that one model on demand. This costs ~550 MB of extra storage (APK assets plus the unpacked copy), which is acceptable for personal use. `externalFilesDir/sherpa` stays a search root for developer builds where the layout was pushed with adb.
- Play Core `asset-delivery-ktx` was dropped together with on-demand delivery, and `scripts/push_to_device.ps1` no longer passes `--local-testing`.

### Engines and routing
- `SherpaOnnxTtsEngine` handles one model, Kokoro or Piper. It loads the native model lazily on first synthesis and caches it. Inference runs on `Dispatchers.Default` (the orchestrator's producer also runs off-main) with `numThreads = 4` (2 originally), under a lock so `release()` never frees a model mid-inference. A failed load (for example a missing JNI library) is remembered rather than retried for every chunk. Output goes through the same `SilenceTrimmer` → peak limiter → 16-bit LE PCM path as Supertonic, and `sampleRate` comes from the loaded model (Kokoro 24 kHz, Piper 22.05 kHz). The native layer sits behind `SherpaNativeTts`, so the engine is unit-tested without JNI.
- `RoutingTtsEngine` wraps the existing engine in `RedderAudioService.ttsEngineProvider`. `kokoro:`/`piper:` ids go to a per-model sherpa engine that is created lazily and cached. Every other id goes to the previous Supertonic or platform engine unchanged, so **default behaviour is unchanged**. If a pack is missing, an id is unknown, or sherpa synthesis fails, the voice falls back to the default engine. `warmUp()` only warms the default engine, so Kokoro's ~350 MB is never loaded unasked. WAV output always carries the routed engine's sample rate.

### Voice engine picker
- The dashboard Settings card has a **Voice engine** choice (Supertonic / Piper / Kokoro (slow)), stored as `pref_tts_engine` in `redder_prefs` (default `supertonic`), and a **Narrator voice** choice per engine, stored as `pref_voice_op_<engine>`. "Voice quality" (diffusion steps) is shown only for Supertonic. A missing or still-unpacking pack is shown on the card; a thread started while a pack is missing falls back to Supertonic.
- Curated voices (`EngineVoices`): Supertonic keeps the seven voices it already used (M1 narrator, then F1 M2 F2 M3 F3 M4), so its default allocation is unchanged. Piper uses eight LibriTTS-R speakers of the one `en_US-libritts_r-medium` model (#529 F, #805 M, #207 F, #736 M, #23 F, #552 M, #115 F, #460 M), picked by measured median pitch (216-237 Hz female, 91-124 Hz male) so they are clearly distinct and only one Piper model is loaded. Kokoro uses nine American voices of v1.0 with af_heart as narrator: af_heart (3), am_michael (16), af_bella (2), am_fenrir (14), af_nicole (6), am_puck (18), af_sarah (9), am_adam (11), af_sky (10). The OP gets the narrator; commenters rotate through the engine's other curated voices via `SpeakerVoiceAllocator`.
- `RedderAudioServiceDelegate` reads the pick when each thread starts. When it differs from the cached engine's, the old engine is released under the processing mutex before the new one is built, and the warm-up loads the picked engine's model (`RoutingTtsEngine.warmUpVoiceId`); Supertonic's sessions stay lazy behind Piper or Kokoro.
- Buffering per engine (`VoiceEngine`): Supertonic starts on the first chunk with a 25 s chunk timeout, as before. Piper buffers 8 s of audio before starting (60 s timeout); Kokoro buffers 60 s (180 s timeout). The notification shows "Generating… n/m" while a sherpa engine is still synthesizing, and the HUD shows its synthesis progress. If the queue still runs dry, the player steps onto the next item once it arrives, since ExoPlayer stays in STATE_ENDED otherwise.
- sherpa inference now uses 4 threads (was 2); only one engine runs at a time.

## Consequences

### Positive
- 942 extra voices with real phonemization, and no G2P or NDK work on our side.
- No model bytes in the base APK; the models live in install-time asset packs.

### Negative / Trade-offs
- **Base APK size**: `libsherpa-onnx-jni.so` adds 23.1 MB (arm64-v8a) uncompressed. With `useLegacyPackaging = false` the release APK's arm64-v8a base download was 44.19 MB > 40 MB. We switched to `useLegacyPackaging = true` (libraries stored deflated, extracted once at install), and `checkApkSize` now reports **18.57 MB** for arm64-v8a.
- **Kokoro fp32 breaks the RTF ≤ 0.40 constraint**, so it is opt-in and never the default. Measured on a Snapdragon 888 (SM-G990U2, a flagship rather than the mid-range 7-series reference) with 2 threads, on one 5.8 s sentence: RTF **2.9–3.1**, about 9–10 s for load plus warm-up. On a desktop x64 CPU (JVM build of the same 2.0.1 release) the RTF was 0.26. Mid-range SoCs will be worse, so Kokoro cannot stream gaplessly on phones as configured.
- **Piper int8 also missed 0.40 on the device**: libritts_r measured RTF 0.48–0.76 on the Snapdragon 888 (0.11 on desktop). This needs tuning, such as more threads, fp32 instead of int8 on ARM, or the low-quality variants, before Piper voices are offered for streaming.
- x86 (32-bit) runs Supertonic on sherpa's ORT 1.28.2 native library. This is untested.

- **Unknown words are skipped.** Without espeak-ng, sherpa-onnx silently skips any word missing from the lexicon ("OOV word skipped"). Kokoro also splits words only at whitespace and `,.!?;:`, so `"social`, `(and` and `that’s` count as unknown, and digits are never in the lexicon. On a real thread 8.1% of the words were lost. `LexiconTextNormalizer` now rewrites the text before synthesis so every word is known (see its KDoc); respelled names and typos can still sound rough.

### Kokoro v1.0 (2026-10-30)
Replaces `kokoro-en-v0_19`. Speaker ids were read from the `speaker_names` metadata of `model.int8.onnx`, since the docs disagree (53 or 54 speakers) and do not cover the int8 build. `voices.bin` holds 54; ids 28-53 are other languages and are not offered.

**Lexicon.** `lexicon-us-en.txt` in the tarball uses `word p h o n`, not `word || p h o n`, and the model's own phone alphabet, so `lexicon-en-us.txt` cannot be used as is. It also has base forms only: `students`, `wanted` and `that's` are missing, because Kokoro's own front end derives them at runtime and sherpa-onnx skips them. `scripts/build_kokoro_lexicon.py` therefore builds `lexicon-us-en-full.txt` at fetch time (297,397 words, 10.3 MB, SHA-256 pinned): upstream's 178,401 entries, 7,578 inflections derived from their stems by the usual suffix rules, and 111,418 other words converted from `lexicon-en-us.txt`. It also sets `the` and `a` to their reduced forms, since upstream lists the stressed "thee" and "ay" and sherpa-onnx has no context to choose. **None of this was judged by ear.** The converted words inherit the open license question below.

The Chinese lexicon, `dict` and the `.fst` files are not shipped. British voices are not curated, because the lexicon is US English. The unpacked v0_19 folder is deleted by `SherpaAssetManager.removeStaleModels()`.

### Kokoro's constant tones (2026-10-30)
Kokoro output carries steady narrow tones at sr/5 and 2sr/5 (4800 and 9600 Hz at 24 kHz), heard as a high whine. They come from the model: v1.0 int8 run directly in ONNX Runtime on a PC, with no sherpa-onnx or app code, shows them at +18 and +23 dB over neighbouring bins, the app's audio at +20 and +21 dB, and v0.19 fp32 has them too. Piper does not. `KokoroToneFilter` notches them (biquads, Q 30) on float samples right after synthesis, before `SilenceTrimmer`, for Kokoro only. The 2400 and 7200 Hz multiples measure under +5 dB and are left alone.

### Kokoro full precision (2026-10-30)
Replaces `kokoro-int8-multi-lang-v1_0` with `kokoro-multi-lang-v1_0`, because the int8 build was both noisier and slower. Same 47 s passage through sherpa-onnx, 4 threads, file render on the Fold 8 (SM-F976U, screen off), three runs per voice, af_heart and af_bella:

| | int8 | fp32 |
| --- | --- | --- |
| RTF mean (range) | 1.38 (1.27-1.51) | 0.74 (0.56-0.86) |
| Peak RSS | 672 MB | 862 MB |
| Tone over neighbours before the notch, 4.8 / 9.6 kHz (PC) | +18 / +22 dB | +2.5 / +3 dB |
| Harmonic-to-noise in voiced frames, 1-3 / 3-6 kHz, af_heart | -1.4 / -5.1 dB | +1.0 / -3.1 dB |
| Spectral flatness 1-3 kHz, af_heart | -5.6 dB | -8.4 dB |

The noise floor in pauses did not change (-70 dBFS on af_heart). The tones are therefore a mostly int8 artefact in v1.0. `KokoroToneFilter` stays: the notches change the spectrum elsewhere by under 0.2 dB, and it is still the guard against the remaining small lines. Nothing here was judged by ear.

Costs: 218 MB more in the pack, the same again in the unpacked copy (~436 MB of storage in total), and 190 MB more peak RSS, still under Supertonic's 989 MB. The official fp16 build (`onnx-community/Kokoro-82M-v1.0-ONNX`, no k2-fsa build exists) was rejected: it output NaN on af_heart on the PC and on both voices on the Fold, which the app would play as dropouts. The Kokoro RTF figures under "Negative / Trade-offs" above predate this change.

### Follow-ups
1. ~~Base APK size~~: resolved with `useLegacyPackaging = true` (see above).
2. ~~Voice picker UI~~: the dashboard Settings card now has a voice engine and narrator picker.
3. Performance tuning and an RTF benchmark for Piper and Kokoro on a 7-series reference device.
4. Settle the license of `lexicon-en-us.txt`, and add CC-BY/CC-BY-SA attributions to the in-app licenses.
5. Verify Supertonic and sherpa on an x86 emulator.
