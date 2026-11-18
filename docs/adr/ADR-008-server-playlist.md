# ADR-008: Backend-owned playlist

> Note: the self-hosted backend and its client flavor are not included in this repository. This repository contains only the phone-only `mobile` build, which mirrors the ordering rule below (see ADR-009); the test data it checks against is kept in `server/testdata/`.

Status: design record. Items marked **OPEN** are decisions the data could not settle. Supersedes ADR-007's "the phone keeps the app logic" for subreddit listening only. Manually shared threads still work the ADR-007 way.

## Context
With a backend rendering audio (ADR-007), a subreddit playlist can be prepared ahead of listening, shared by several devices, and played from a browser page, while the phone stays cool. That needs a single source of truth for the list's order and of each post's state.

## Post states
| State | Meaning | In the playlist? |
|---|---|---|
| new | Listed in hot; no complete audio yet | yes |
| prepared | Everything it would render ahead (see Long threads) is on disk | yes |
| started | A device started it and it has not ended | yes, and it is what resumes |
| finished | Played to the end | **no** |
| skipped | Next post pressed | **no** (skip counts as played) |
| done | The explicit toggle | **no** |

Done is a separate flag, not a step on this line. A later `start`, `position`, `finish`, `skip` or history reset never clears it; only `undone` does. Two conflicting toggles resolve by the tap's own time (client `at`, then event ID). `undone` also forgets finished or skipped, so the post comes back, at its old place during a session. Previous on a finished post replays it without changing its state.

## Ordering: "most recent hot first"
1. "Most recent hot" means **the rank in each sub's newest hot page**, not the post's age. Stickied and pinned posts are dropped (`class="post stickied"`).
2. Interleave: go round the subs in the sub list's order, taking each sub's next post by rank, until all are used. Same turn-taking as `SubredditQueue`.
3. Started posts, then undone posts no longer in hot, go first; newest first.
4. **Stability:** a session is any `start`/`position`/`finish`/`skip` event from any device in the last 30 minutes. During a session, order never changes: new posts append to the tail, in rule 2's order among themselves. Done and undone only hide and show rows. The next refresh after a session ends rebuilds from rule 1.

Example: with subs A, B and C, the order is A #1, B #1, C #1, A #2, B #2, C #2, and so on, skipping a sub once its page is used up. `server/testdata/order_golden.json` holds the expected order for captured listing pages (`server/testdata/listings`) and is checked by the backend and by the `mobile` build's `PlaylistOrder`, so a change to either side's rule fails a build.

### How much hot moves
Over short gaps (about 20 to 35 minutes) a sub's hot page barely moved in a small capture: the top post was almost always unchanged and the top 10 overlapped about 9.8 of 10 (Kendall tau about 0.96). Hot is not time order, and score does not explain it either, so neither sorting by time nor by score would reproduce it. The sample is too small to say anything about an hour or a day.

**OPEN:** the 30-minute session and the hourly refresh are judgement calls. A skip report after real use tests whether the top item is the right one.

## Preparing ahead
- **The next 3 posts in the playlist, in total** (`prepare_next`, settable through the API), refilled as they are listened to. Chosen over 3 per sub: long threads can run hundreds of chunks, and with clean-up on the CPU that would keep the machine busy for hours.
- **Disk cap** for audio (`disk_cap_gb`, 10 GB by default). Over the cap, audio furthest from the head of the playlist goes first, then rendering stops until there is room.
- **Retention:** audio of a removed post is kept 3 days so undo stays instant, then deleted. A post out of the playlist is kept 7 days at most. Events are never deleted.
- **Content hash:** each chunk's audio is keyed by a hash of text, voice, speed and pause. A thread is refetched at most every 4 hours while among the next 3. Only changed chunks render. A started post is pinned to the chunk list it started with, so nothing shifts under the listener.
- **Long threads:** at most 30 minutes of audio per post is rendered ahead (`prepare_max_minutes`); **OPEN**. The rest renders the moment it is asked for.
- **GPU pause:** before each chunk and on a timer, GPU use is read. Rendering pauses when GPU use is high while our worker is not mid-chunk, or when memory in use, less our own worker's share, is over a limit, so a game or other GPU work on the machine wins. Resumes after 2 clear minutes. If GPU use cannot be read, rendering pauses.
- **Listener first:** a chunk asked for by a player is rendered ahead of everything, and rendering ahead pauses for 60 seconds after one. The voice runs one chunk at a time, so a listener waits for at most one chunk.
- **CPU:** the optional LLM step pauses when the machine's CPU is busy; voice rendering is on the GPU and does not.
- The 5-minute idle unload stays: when rendering stops, models leave as before.

## Text: one implementation per job
The backend owns chunking for playlist posts, and the phone receives chunks with the audio. Ports of `RedlibHtmlParser`, `SubredditListingParser`, `DialogueTreePruner`, `TextSanitizer`, `DialogueFormatter`, `SpeakerVoiceAllocator`, `SilenceTrimmer` and `CleanupValidator` live on the backend, because the Kotlin ones still serve shared threads. They are held together by a golden file written by the Kotlin code from captured pages and checked by a test on each side, so a change on one side fails a build. The phone's narrator, read-usernames and clean-up settings are sent to the backend, so they keep working.

## Mirrors
5 seconds between requests across the whole process, a fixed list of mirrors in a fixed order, one honest user agent. Each sub is refreshed hourly with up to 5 minutes' jitter. Three 429/503s in a row end the pass; waits double from 2 hours up to 8, and `Retry-After` is honoured when longer. A bot challenge counts as a failure, is never solved or worked around, and no user agent or address is changed. A sub not refreshed for 2 hours shows **stale, with its age**, and keeps its last list.

## Sync
Each client tap or listening report is an event with a client-made unique ID, applied once however often it is sent. The log is append-only; current state is derived from it in the same transaction and can be rebuilt from it. Phones queue events in a file and send them on reconnect, then read state from the backend. Local toggles show at once.

## Migration
On first run with a working backend, the app uploads the sub list and played history. Played posts become events with ID `migrate-played-<post id>`, so a retry or a second phone cannot double them. Sub lists union. Nothing local is deleted, and the "migrated" mark is set only after the backend accepts. The database is backed up before each schema change.

## HTML page
Same server and port. The token is entered once and exchanged for an HttpOnly, SameSite=Strict cookie holding an HMAC of the token, never the token. No external resources; a strict CSP with no inline script; all Reddit text goes through `textContent`.

Over plain HTTP on a LAN address, browsers allow Media Session, localStorage, IndexedDB and `<audio>`, but not service workers, `caches` (so no offline page), Wake Lock, Clipboard, `navigator.storage` or `crypto.randomUUID` (event IDs use `getRandomValues`). The cookie cannot be `Secure`. LAN traffic is unencrypted, as accepted in ADR-007; a VPN encrypts its own.

## Card deck
The Now playing screen is a card deck: on the backend's page and as `NowPlayingActivity` on the phone.
- **Caption timing:** each chunk's sentences get shares of its audio by character count, after taking off 40 ms of lead-in and 50 ms plus the chunk's pause at the end. The line is always worked out from the player's position. `server/testdata/caption_timing.json` holds the JavaScript and Kotlin (`CaptionTiming.kt`) implementations to the same results.
- **Accuracy:** against silences in real chunks, the median boundary error was about a quarter of a second and most lines change within half a second of the speech.
- With clean-up on, the spoken text can differ from the text shown, so timing is rougher for those chunks.
- A line tap in a clip that has been dropped or not made yet starts at that clip's beginning, because it has to be rendered again first.

## Consequences
- API: `GET /v1/playlist`, `/v1/posts/<id>`, `/v1/audio/<id>/<key>.wav` (Range), `/v1/subs`, `/v1/settings`, `/v1/status`; `POST /v1/events`, `/v1/subs`, `/v1/migrate`, `/v1/settings`; `/login` and `/logout` for the page.
- A GPU voice model renders many times faster than real time, so the next 3 posts are usually ready long before they are reached.
- A post's length shows as unknown until its text has been fetched, which happens only for the next 3 or when it is played.
- While the phone sleeps, Android holds the app's sync loop, so changes reach it when it wakes or opens.
- The playlist can be up to an hour behind the mirror's own page, by design of the hourly refresh.

## Other OPEN items
- Skip removes for good unless undone. If a stray Next loses posts you wanted, it could become a soft state.
- History reset ("forget played") leaves done posts done, since done wins over everything.
- Prepared audio uses the narrator last sent by a phone. If two phones differ, the last one to change it wins.
