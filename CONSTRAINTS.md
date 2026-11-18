# Constraints

Last reviewed: 2026-09-20 by the ThreadReader core team

## Floor (always enforced, no setup required)

- No suppression annotations without issue reference: `@Suppress`, `@SuppressWarnings`, `// no-lint`.
- No unimplemented stubs: `throw NotImplementedError()`, empty `catch {}` blocks.
- No skipped, commented-out, or disabled tests (`@Ignore`, `.skip`) without an explicit tracker issue in the commit message.
- Zero secrets or API private keys in source code.
- Zero accessibility scraping APIs (`AccessibilityService`) used for data extraction.
- This file does not get weakened to make a change pass.

## Enforced with numbers

| Dimension | Rule | Checked by | Runs at | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| **Time-to-First-Audio (TTFA)** | $\text{TTFA} < 1.5\text{ s}$ from Share Intent to audio playback | `./gradlew benchmarkTTFA` | CI / Task end | Users expect immediate response when sharing from Reddit; delay $>2\text{s}$ causes abandonment. |
| **Inference Speed (RTF)** | $\text{RTF} \le 0.40\times$ on reference mid-range SoC (Snapdragon 7-series) | `./gradlew testAudioBenchmark` | CI / Task end | Audio synthesis must run $2.5\times$ faster than speech to ensure buffer never starves. |
| **Peak Heap Memory** | Peak RSS / Heap $\le 250\text{ MB}$ during active synthesis + playback | Android Studio Memory Profiler / `./gradlew profileMemory` | Task end / Release | Keeps app safely under Android Low Memory Killer (LMK) thresholds on 4GB RAM devices. |
| **Binary Footprint** | Base APK download size $< 40\text{ MB}$ (without external voice packs) | `./gradlew checkApkSize` | CI Ship Gate | Play Store conversion rate drops significantly for initial downloads exceeding 50MB. |
| **Thermal / CPU Duty Cycle** | $0\%$ CPU utilization on producer coroutines when `Channel` buffer is full (capacity = 2) | `./gradlew testIdleDutyCycle` | CI Task Gate | Prevents background battery drain and thermal throttling when user pauses or listens steadily. |
| **Test Coverage** | Changed lines $\ge 80\%$ covered by unit/integration tests | `./gradlew jacocoTestReport` | Task end, CI | Guarantees regression safety for tree pruner, sanitizers, and audio packaging. |
| **Code Lint & Types** | 0 lint errors, 0 compiler warnings | `./gradlew lint ktlintCheck` | Every edit | Enforces clean codebase standards and prevents deprecation warnings. |

## Measured, not yet enforced

| Metric | Target | Current Value | Direction |
| :--- | :--- | :--- | :--- |
| **Cold Startup Time** | $< 400\text{ ms}$ | $320\text{ ms}$ | Must not grow |
| **Audio Scrub Latency** | $< 250\text{ ms}$ from tap to audio seek | $180\text{ ms}$ | Must not grow |
| **Audio Clipping Occurrences** | 0 clipped frames per 1,000 utterances | 0 | Must remain 0 |

## Exceptions

| ID | Rule | Path | Reason | Owner | Expires |
| :--- | :--- | :--- | :--- | :--- | :--- |
| *None* | — | — | Initial release adheres to all constraints without exceptions. | @architecture | 2026-12-31 |
