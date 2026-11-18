# ADR-006: Nothing Stays Loaded or Running When Idle

## Status
Accepted. Updates the engine caching in [ADR-004](ADR-004-sherpa-onnx-kokoro-and-piper.md) and the worker in [ADR-005](ADR-005-llm-text-cleanup.md).

## Date
2026-10-30

## Context
The phone felt warm in use. The old pipeline synthesized the whole thread ahead of the listener, kept doing so while paused, and held the models until the service was destroyed. A MediaController binding or a sticky restart created the service, and `onCreate` loaded a model with nothing to play. The HUD pauses through the media session, so the service never learned about those pauses.

## Decision

### Look-ahead
`SynthesisThrottle` holds synthesis while the player is paused or while `MAX_LOOK_AHEAD_MS` (2.5 minutes) of audio is queued after the current card, for every engine. It suspends on a state flow, so it costs no CPU. Before playback starts it is open, because the start buffer (`StartBufferPolicy`) has its own bound. The clean-up worker is bounded through the synthesis position, and takes no new chunk while paused.

### Models
`ReloadableTtsEngine` lets the running pipeline keep its engine while the real one is freed; the next synthesis builds it again. It is freed when:
- every chunk is synthesized, even if audio is still queued;
- 60 s after a pause;
- 60 s after a warm-up, if no thread has started synthesizing;
- a thread fails or has nothing to read;
- on stop.

The LiteRT-LM model is freed when the worker finishes, after 60 s of waiting, and on stop. It stays loaded between windows while playback moves, because reloading it for each window costs more work than leaving it idle in memory.

### Service
- `onCreate` loads nothing. It only arms a 60 s timer that stops a service with no thread.
- The service is `START_NOT_STICKY`. A restarted process has no thread to continue. A null intent with no work stops at once.
- A long pause drops the foreground and keeps the notification. Resuming restores it.
- When a thread has been played to the end, the foreground goes at once and the service stops 60 s later, unless the listener rewinds or taps a caption first. The HUD closes when its session goes away.
- A stop through the media session (player `STATE_IDLE`) stops the service.
- The service follows `onPlayWhenReadyChanged`, so a pause from the HUD counts like any other.

### Stopping inference
A chunk can take 40 s on a slower phone, so inference checks for cancellation: Supertonic between diffusion steps and sherpa-onnx between sentences (`generateWithCallback`). The sherpa JNI layer looks up `invoke([F)Ljava/lang/Integer;` on the callback's own class and aborts the process if it is missing, so `SherpaProgressCallback` must stay a named class and is kept under R8.

## Consequences
- The first chunk after a long pause, or of a later thread, pays the model load again (about 4 s on the S21 FE).
- A rewind past what is queued reloads the model.
- The app holds no wakelock of its own. While it plays, `AudioMix` is held by the audio server on its behalf.
- Listening itself still costs what it did while the look-ahead fills: synthesis runs at about 200% CPU on the S21 FE.
