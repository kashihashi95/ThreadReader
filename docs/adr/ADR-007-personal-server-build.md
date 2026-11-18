# ADR-007: Optional self-hosted backend for TTS and LLM clean-up

> Note: the self-hosted backend and its client flavor are not included in this repository. This repository contains only the phone-only `mobile` build; this ADR records the design.

Status: design record.

## Context
Running a neural voice and an LLM clean-up step on the phone costs time, heat and battery. A machine on the user's own network with a GPU can render audio with a larger voice model and run the LLM rewriting much faster, and the phone then only plays finished audio. The design adds an optional self-hosted backend and a client build that uses it, without changing how the phone-only build works.

## Decision
**The phone keeps the app logic. The backend does only the two heavy steps.**

The phone already fetches threads through the mirrors, parses, chunks, runs the subreddit queue and played history, caches clips, looks ahead 2.5 minutes, and drives the HUD, captions and rewind. Moving those to the backend would rewrite working code and add server state, for no gain. The heavy steps already sit behind seams: `TtsEngine` and `TextCleanupLlm`. The client build gets a remote implementation of each.

Consequence: the backend takes text, not thread URLs. Mirror fetches still come from the phone. If a server-side fetch is ever needed, it can be added as one more endpoint.

## Backend
- A small HTTP server using only the language's standard library, so it adds no packages beyond the voice and LLM runtimes.
- Bound to loopback while testing; reachable from the phone only over the LAN or through a VPN such as Tailscale. No router ports are opened.
- Voice: a larger neural TTS model (Kokoro v1.0) on the GPU, behind a small engine class so another model can be swapped in. No model-specific API.
- LLM: a small instruction model (Gemma) run through a private Ollama instance, kept separate from any other Ollama the machine runs.
- Models load on first use and unload after 5 idle minutes, so an idle machine gets its GPU and RAM back. Requests run one at a time per model.
- No database for this step. No user data stored. Text is logged only as length.

## API
All requests need `Authorization: Bearer <token>`; the token is a random value in a file on the backend, entered once in the app. Compared in constant time.

| Endpoint | Body | Reply |
|---|---|---|
| `GET /v1/health` | none | engine, LLM, loaded state, version |
| `GET /v1/voices` | none | voice ids and names |
| `POST /v1/tts` | `{text, voice, speed}` | `audio/wav`, mono 16-bit |
| `POST /v1/clean` | `{text, prompt}` | `{text, tokens, ms}` |

Limits: 4 KB text per request, JSON only. Errors are JSON with a status code. Each chunk is one request: a GPU voice model makes one in well under a second, so per-chunk WAV keeps the existing clip cache, rewind and caption jump working with no streaming code.

## Android
- A separate client flavor with its own application id and label, installable beside the phone-only build.
- Client sources: `RemoteTtsEngine`, `RemoteTextCleanupLlm`, server settings (address, token, test button).
- The client leaves out the on-device voice runtimes and voice packs from its dependencies. It installs as a plain APK.
- Only one engine, so the picker shows voices only. Rule clean-up and `LexiconTextNormalizer` stay shared; the normalizer is skipped for the backend's voice, since that route has its own phonemes.
- Cleartext HTTP is allowed only for the configured private and VPN hosts, via network security config. A VPN such as Tailscale encrypts traffic; plain LAN traffic is not encrypted, which is accepted for a home network with a bearer token.
- **Backend unreachable:** a clear message on the dashboard and HUD, no on-device fallback. Retries twice with a short backoff, then stops and leaves the position. A failed clean-up alone reads the rule-cleaned text; a failed voice stops.

## Access
The phone and backend share a LAN or a VPN such as Tailscale; no router ports. The app stores one address.

## Model load and unload
Token required. `GET /v1/models/status` gives, per model (`voice`, `llm`): `state` (unloaded, loading, loaded, unloading, failed), `progress` 0 to 1, `stage`, `elapsed_s`, `expected_s`, `overdue`, `pinned`, `idle_unload_in_s`, `last_error`, `vram_mib`, `ram_mib`, `needed`. `POST /v1/models/load` and `/unload` take `{"model": "voice"|"llm"|"both"}`, reply 202 at once and can be repeated safely. The same status is in `/v1/status`.

- **Hold:** a manual Load pins the model until a manual Unload; there is no expiry, and the app and page show "Kept loaded". Models loaded by Play or rendering ahead keep the 5-minute idle rule. Pins live in memory and do not survive a restart.
- **Progress is an estimate.** Neither engine reports a percentage. It is elapsed time over a moving average of real loads, raised to a floor for the stage reached, capped at 95% until loaded. Only `loaded` is 100%. Past the estimate the line says so and the bar moves without a number.
- **Unload** waits up to 15 seconds for the current chunk, then cancels it by ending the worker. A load is cancelled at once. Requests queued behind it get a 503, and asking again loads the model again. The app and page ask first when audio is playing or a listener was heard from in the last 90 seconds.
- **Rendering ahead is held by a manual Unload** of the voice, or of the LLM while clean-up is on, until the next Load, a chunk that needs the voice, or a newer `start` event.
- **Clean-up off:** Load with `both` loads the voice only, and the LLM row reads "not needed".
- **Memory figures:** where per-process GPU memory is not reported, voice VRAM is total GPU memory less the total just before it loaded.

## Consequences
- `AppFlavor` in `main` is the seam; each flavor has one `CurrentFlavor`.
- The voice engine runs in a worker process that exits on unload, because freeing the framework's cache alone would leave the GPU context and large imports behind.
- `/v1/voices` lists the voices installed on the backend, and nothing is downloaded at run time, so a new voice file appears in the app by itself.
- The network security config takes exact hosts and domain suffixes, not ranges, so the configured backend host, loopback and the VPN's DNS suffix are listed. `ServerAddress` refuses public hosts.
- When the backend goes away while audio is queued, the queued audio (up to the 2.5-minute look-ahead) plays out under the message, then playback stops without marking the post played.
- Phone battery use and heat drop, since the phone only plays audio; the cost is a second machine that must be on and reachable.
