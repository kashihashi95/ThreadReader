# ADR-005: Opt-in LLM Text Clean-up on LiteRT-LM

> Note: this repository contains only the phone-only `mobile` build; the on-device LLM clean-up (LiteRT-LM) and the server-side clean-up described here is not included.

## Status
Accepted as an experimental prototype. Off by default.

## Date
2026-10-30

## Context
Rule-based sanitizing cannot expand acronyms by context, say numbers and money naturally, or punctuate run-on comments. A small on-device model can, but it must never change what the author said or delay playback.

## Decision

### Runtime and model
- `com.google.ai.edge.litertlm:litertlm-android` **0.19.0** (Apache-2.0, Google Maven).
- `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm` at revision `1caa527c1e88b55f046fcd8d2b4265606680663d`: 2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`, Apache-2.0, not gated. Its weights are a mix of 2, 4 and 8 bit.
- LiteRT-LM 0.19 needs Kotlin 2.4 and coroutines 1.11, so the project moved up from Kotlin 2.0.21 and coroutines 1.9.

### Model delivery
The model is not in the APK, an asset pack or git. It is downloaded on the PC into the gitignored `models/llm/` and pushed to the app's external files dir:

    curl -L -o models/llm/gemma-4-E2B-it.litertlm https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/1caa527c1e88b55f046fcd8d2b4265606680663d/gemma-4-E2B-it.litertlm
    adb shell mkdir -p /sdcard/Android/data/com.redder/files/llm
    adb push models/llm/gemma-4-E2B-it.litertlm /sdcard/Android/data/com.redder/files/llm/

`LlmModel` treats the model as installed only when the file has the exact pinned size. Until then the Settings row reads "Model not installed" and its switch is disabled. Uninstalling the app deletes the model, but updates keep it.

### Pipeline
Sanitizing and formatting run first, then `TextCleanupPipeline` works in the background ahead of synthesis:
- `MessyTextHeuristic` picks chunks with acronyms, digits or symbols, list or quote formatting, or 30+ words without punctuation. Other chunks never reach the model.
- `CleanupValidator` is a pure guardrail. It rejects a reply, and the original text is read, if more than 25% of the tokens changed (word-level edit distance over the original token count, ignoring case and surrounding punctuation), if the length ratio is outside 0.8-1.4, or if it added a preamble or note. Rejections are logged under `ThreadReaderTiming`.
- Playback is never delayed: `spokenTextFor` returns at once with the cleaned text if it is ready and the original if not. Each chunk has a 30 s timeout, and three failures in a row stop the worker.
- Results are cached by prompt and chunk text for the thread. The worker stays at most 8 chunks ahead of synthesis, takes no new chunk while paused, frees the model after 60 s of waiting, and frees it when it finishes or the thread stops (see ADR-006).
- Captions keep the original text.

### Setting
`pref_smart_cleanup` in `redder_prefs`, default false, read when each thread starts.

`pref_cleanup_prompt` holds the user's edit of the prompt, from the Settings field under the switch; absent or blank means `CleanupPrompt.SYSTEM`. The pipeline reads it for each chunk. When it changes, results from the old prompt are dropped and the worker goes back to the synthesis position; audio already synthesized stays. LiteRT-LM takes the system prompt per conversation and each chunk has its own, so no reload is needed. `CleanupValidator` applies to every prompt.

### Custom prompts (2026-10-31)
A prompt other than the default sends every chunk to the model, makes synthesis wait up to 20 s per chunk for the reply, and starts with the strict check off. The default prompt keeps the heuristic and never delays synthesis. Instructions go in the user turn, because in the system role a prompt with examples sometimes got an example back. `TextSanitizer` now handles Reddit acronyms, age tags, money and similar fixed forms before the model. The "Optimised" preset (`pref_cleanup_optimised`) is used instead of the saved prompt without changing it.

### Reddit acronyms and post context (2026-11-05)
"OOP" was heard read as "object-oriented programming". The rule step had no entry for it, the prompt says "spell out abbreviations", and the model had no idea the text was from Reddit; on the server it gave "out of pocket" for the same BORU header.
- **Rules first, in all three builds.** `RedditAcronyms` (Kotlin) and `expand_acronyms` (`server/chunking.py`) say about forty acronyms in full before any model, held together by the golden file. OP and OOP are "original poster". The table is data: whole words; a token with "/", "_", "@", a URL or a dotted name is skipped, so u/ and r/ names and code keep their letters; a few entries carry a condition (SO only after "my", "her" and similar and not shouted; INFO only as the opening word; NC not after "in" or "from"; DD, DS and DF only after "my" or "our"; OOP, DS, VLC and GC not when the comment is about code, games, media players or building). Left out as too ambiguous: "ex", lower-case "so" and "op", SO without a determiner, TA, DD as "designated driver", "Info" as a noun.
- **The rules have no subreddit.** `RedditPost` carries none, and adding it means both parsers and the golden file; the conditions read the same comment instead. The server's model is told the sub.
- **`CleanupValidator` refuses a reply that drops one of these full forms**, strict or not (`LOST_EXPANSION`), so the model cannot turn "original poster" back into something else.
- **Context for the server's model.** Around whatever prompt is selected (the user's text is never edited), the server adds a "Context (do not output)" section with the sub and the post title, then a one-line glossary of Reddit meanings ending "Prefer Reddit meanings; leave unknown acronyms as written". Everything before "Text:" is the same for every chunk of a post. The post's opening words were tried too, since a thread can run to hundreds of chunks and the whole of it never fits: with 300 and with 80 words the model took "Commenter 1:" and "Editor's note:" lines for notes on the story and dropped them, and with 300 the prompt grew from about 420 to 670 tokens and each chunk took about a third longer. A running summary would cost a second model call per chunk. So sub and title only. `num_ctx` went from 2048 to 4096, because a saved prompt may be 8 KB. `/v1/clean` takes an optional `context` of `{sub, title}`; the phone does not send one yet, so shared threads get the glossary only. Measured with `server/proof/cleanup_eval.py`.
- **Audio already made.** At start the server chunks again every post nobody has started. Changed chunks get new keys and are rendered again as they come up; started posts keep their chunks and audio, so positions hold.

## Consequences
Trade-offs known at the time of writing:
- The 25% rule rejects most chunks dense in acronyms, including many good expansions, because each expansion adds words. It also catches real mistakes. It stays strict for now.
- The model takes well over a gigabyte of graphics memory next to the TTS engine, far above the 250 MB in CONSTRAINTS.md. That is why the feature is opt-in and the model is freed when idle.
- `liblitertlm_jni.so` adds to the base APK; `checkApkSize` is the limit to watch.
