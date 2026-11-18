# ADR-009: Mobile build, everything on the phone

Status: built. The first device run and the heat measurement are still open; see Measurements.

## Context
The goal is a build with the playlist features of the self-hosted backend build (ADR-008) and no server, generating as little heat as it can, so it has no LLM clean-up. Kokoro was considered first; the design chooses Supertonic, which works well fully on the phone, so nothing of Kokoro is in this build.

## Decision
A third flavor, `mobile`: `com.redder.mobile`, "ThreadReader", installs beside the other two.

- **Voice:** Supertonic only, with no fallback voice and no engine picker. The narrator picker and the quality setting stay (Best, 8 steps, by default; Fast, 4). Rule clean-up, silence trimming and the peak limiter are as in the store build. No sherpa-onnx, Kokoro, Piper, `KokoroToneFilter` or lexicon normalizer on this route.
- **No LLM and no server:** no LiteRT-LM, model, clean-up setting or prompt screen; no server client, token or settings. `MobileFlavorTest` checks the classpath; `verifyMobileBundle` fails a bundle with another voice pack or their native libraries.
- **Shared code:** `src/supertonic` is compiled into store and mobile, `src/playlistUi` (card deck, dashboard card, captions) into personal and mobile, so a fix reaches both. `PlaylistSource` is the seam under the screens. Nothing moved changes what store or personal do.
- **Packs:** AGP has one asset pack list for all flavors, so `app/build.gradle.kts` picks the list from the tasks asked for, and refuses a store and a mobile bundle in one run. Store still gets all three.

## The playlist on the phone
`LocalPlaylist` implements `PlaylistBackend` with `load` returning null, which tells the service to fetch, format and speak the post itself.

- **Order:** ADR-008's rule (stickies out, round robin by rank, started posts first, frozen in a session, new posts at the end). `PlaylistOrder` mirrors `round_robin` and `next_order`. `server/testdata/order_golden.json` is written by the server's own code from captured pages and checked on both sides, so a change to either rule fails a build. It uses the 25 pages in `server/testdata/listings`.
- **Until every sub has been read once** the order is still being put together, and may change under a running session. After that the session rule holds.
- **Crossposts differ from the server:** the second post with the same title words is left out, here only. The mirrors' listing pages do not mark crossposts, so the title is all there is; titles under 24 characters are never matched. The captures hold none, so it changes nothing there. A crosspost with an edited title gets through.
- **States:** the same fold as `server/store.py`, from an append-only JSON-lines log with unique IDs. Done wins, and only `undone` clears it; `forget` leaves it. A long log loses old position reports only.
- **Resume** is to the start of the chunk listening reached. A thread that gained comments since can shift by a few chunks; the server pins a started post's chunks, the phone does not.
- **Mirrors:** the existing client and failover, 5 seconds between listing requests, hourly per sub with jitter. Three replies in a row of 429, 503 or a bot challenge end the pass and wait 2, then 4, then 8 hours. No network retries after 5 minutes, with no long wait. `Retry-After` is not honoured here, because the client does not pass it on. A listing page already on disk stays in use; a post's text is not kept, so a post cannot be read offline.
- **Server's permalink check is kept**, so both build the same list. It drops posts whose URL has a non-ASCII letter (`1xj5g3l`, "fiancé", in the captures). That looks like a limit worth lifting on both sides later.
- **First run:** a fresh start. Nothing is imported from the personal app or the server. A default set of subs is added if the list is empty.

## Heat: what differs from the server build
- Nothing is rendered ahead of listening. Audio is generated only while listening, up to the 2.5-minute look-ahead, and stops when paused (ADR-006).
- The model goes after 60 s paused, on stop, and when the queue ends. Between posts of a running queue it stays loaded: that costs memory, not heat, and saves a reload.
- The next post's text is fetched near the end of the current one. Its audio waits until it is its turn.
- **Tap to audio is measured, not instant**, because the first chunk has to be generated on the phone, including the model load on a cold start. Supertonic starts on its first chunk (`StartBufferPolicy`), so this is the shortest wait of the on-device engines.
- Listings refresh only with the dashboard or deck on screen, or at a position report while audio plays. A pass already running finishes, which can take about a minute. No WorkManager, alarm or job; `onProcessStart` only reads two small files.
- `ThermalGate`: before each chunk, at `THERMAL_STATUS_SEVERE` or above, generation waits and logs, checking every 15 s. Queued audio keeps playing; if it runs out, playback waits for the phone. Each chunk logs its speed, thermal status and battery temperature under `ThreadReaderHeat`.

## Sizes
Measured from the debug bundle's device set for the Fold 8: 444 MB `.apks`; 391 MB install size from bundletool; on the phone, 444 MB of APKs (401 MB of it the pack). First launch unpacks 383 MB of model files, so about 830 MB in total, not measured on the phone. The base and native splits come to 43 MB of that.

## Measurements
**Not measured.** The Fold was locked and dozing when this was built, so nothing was played. First audio, per-chunk speed, the 20-minute temperature run, model release and the Now playing screen on the device are all open. To read them after use: `adb logcat -s ThreadReaderHeat ThreadReaderTiming`.

## Not done
Tap a row to play it, in the list view. Lengths show as "not known yet", since the phone learns a post's length only by reading it. Dedupe on the server. Release signing of this flavor.
