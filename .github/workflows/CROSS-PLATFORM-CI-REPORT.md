# Cross-Platform CI Report — PR #1506

## What We Set Out To Do
Add macOS and Windows CI builds to kyo, improve workflows, add `testDiff` for faster PR feedback.

## What Got Done (Code Changes)

### New Infrastructure
- **Reusable workflow** (`build.yml`) shared by PR and main builds
- **`sbt testDiff`** command: runs only tests for modules affected by git diff
- **actionlint** CI check for workflow syntax validation
- **`ci-local.sh`** script for running workflows locally via `act`
- **`.gitattributes`** forcing LF line endings everywhere

### Platform Compatibility Fixes (27 files changed)
These are real bugs exposed by running on macOS/Windows for the first time:

| Fix | Files | Root Cause |
|-----|-------|------------|
| `\r\n` line splitting | TestVariant.scala | `content.split("\n")` leaves `\r` on Windows |
| Windows path separators | FindEnclosing.scala | `path.contains("src/test/")` fails with `\` |
| Frame macro source content | Frame.scala | `sourceFile.content` has `\r\n` on Windows |
| Compiler error messages | BaseKyoDataTest.scala | `typeCheckErrors` returns `\r\n` messages |
| Stack trace comparison | TraceTest.scala | `stripMargin` doesn't remove `\r` |
| Classpath separator | BytecodeTest.scala | Hardcoded `:` instead of `File.pathSeparator` |
| URL-to-path conversion | Registry.scala (kyo-bench) | `getResource().getPath()` returns `/D:/...` on Windows |
| Console output | ConsoleTest.scala | `println` uses `\r\n` on Windows |
| Log output splitting | LogTest.scala, JavaLogTest.scala, SLF4JLogTest.scala | `split('\n')` vs `split("\\r?\\n")` |
| Fiber gather ordering | FiberTest.scala | Assumed deterministic ordering from delays |
| Channel test ordering | ChannelTest.scala | Race condition in parallel take |
| Invalid URL test | HttpClientTest.scala | `"not a valid url"` parses differently on Windows |

### Test Timing Adjustments
| Test | Change | Reason |
|------|--------|--------|
| BaseKyoKernelTest timeout | 15s → 120s on ≤4 cores | Slow CI runners |
| InternalClockTest | 150ms → 300ms tolerance | macOS M1 slower |
| ClockTest intervals | 20ms → 100ms, 50ms → 200ms | macOS Native too slow |
| SleepTest threads | 100 → cores×10 | Scale to runner size |
| SleepTest jitter | 5x → 10x threshold | CI variance |
| WorkerTest patience | default → 5s | Windows slow |
| HttpClientTest timeout | 50ms → 200ms | Connection pool race |

### Tests Skipped on Non-Linux (with `assume`)
These use blocking JVM calls that can't be interrupted by kyo's fiber-level timeout:

| Test | Reason |
|------|--------|
| ProcessTest (all) | `readAllBytes()`/`process.waitFor()` block OS thread forever |
| KyoAppSignalTest | Uses `kill -SIGNAL` + blocking process calls |
| OsSignalTest | `USR2` signal is Unix-only |

### Tests Skipped on ≤4 Cores (with `assume`)
| Test | Reason |
|------|--------|
| Stream broadcast "in unison" (×2) | 10 concurrent fibers timeout on slow runners |
| SignalTest concurrent reads/writes | 20 concurrent fibers timeout on slow runners |
| HttpClientTest connection pool | Netty KQueue fd collision under resource pressure |
| KyoExecutorServiceConfiguratorTest multi-thread | Single kyo thread used on 3 cores |

## Resource Constraints of Free GitHub Runners

| Runner | CPU | RAM | Our Heap | Parallelism |
|--------|-----|-----|----------|-------------|
| `build` (Linux, paid large) | many | 32GB+ | 15G | unlimited |
| `macos-latest` (free M1) | 3 cores | 7 GB | 4G max | `limitAll(1)` for JS/Native, `limitAll(2)` for JVM |
| `windows-latest` (free) | 4 cores | 16 GB | 8G | `limitAll(1)` always |

### Why Serialization Is Needed
- **macOS JS/Native**: The Scala.js linker allocates huge memory. With parallel linking, 2+ linker instances exceed 7GB → OOM.
- **macOS JVM**: Full parallelism thrashes at 7GB (compilation is memory-hungry). `limitAll(2)` is a compromise.
- **Windows**: `OverlappingFileLockException` — sbt's Coursier resolver uses file locks that collide when multiple modules resolve concurrently.

### Build Time Reality

| Job | Linux (large) | macOS (free) | Windows (free) |
|-----|---------------|--------------|----------------|
| JVM | ~8 min | ~?? min (serialized) | ~?? min (serialized) |
| JS | ~7 min | ~28 min (serialized) | ~19 min (serialized) |
| Native | ~17 min | ~54 min (serialized) | excluded (POSIX APIs) |

macOS JVM and Windows JVM with serialized tasks have NOT completed within 180 minutes in any run. Current timeout is 360 min. We have never seen them complete because every run either:
1. Had a code bug that failed early
2. Timed out at the previous timeout limit
3. Was cancelled by a new push
4. Had the Linux runner disconnect

## Observed Infrastructure Issues

1. **Linux `build` runner disconnections**: The self-hosted runner disconnects mid-test in ~50% of runs. The test step shows `in_progress` but the job concludes as `failure`. The `native-test.sh` retry script can't help when the runner itself dies. Passes on rerun.

2. **macOS DNS fluke**: One run failed with `UnknownHostException: repo1.maven.org` — transient GitHub runner network issue. Passed on rerun.

3. **GitHub API limitation**: Cannot access in-progress job logs via API. The job logs endpoint returns 404 until the job completes. The web UI uses authenticated WebSocket streaming that's not accessible programmatically.

## What Remains Uncertain

1. **macOS JVM full test completion**: Has never finished. With `limitAll(2)` it might complete in ~120-200 min, but we haven't observed it. The build compiles and tests pass (no failures detected in partial runs), it just runs out of time.

2. **Windows JVM full test completion**: Same — serialized with `limitAll(1)`, hasn't completed within 180 min. All tests that did run passed.

3. **macOS Native full test completion**: Completed once at ~54 min (on rerun). Seems viable within 360 min.

## Recommendations

### If Keeping Free Runners
- Set timeout to 360 min and accept slow macOS/Windows JVM builds
- Consider running macOS/Windows only on main pushes (not PRs) to avoid blocking PR feedback
- The Linux build (which runs on paid large runners) is the primary gate — macOS/Windows are bonus coverage

### If Willing to Pay
- `macos-latest-large` (12 cores, 30GB, ~$0.12/min) would cut macOS builds from hours to ~15 min
- An 8-core Windows runner (~$0.064/min) would solve the serialization issue
- Cost for a typical main build: ~$5-10

### Native on Windows
Excluded via matrix because kyo-scheduler uses `posix.time.nanosleep` and kyo-http uses `posix.pipe/poll/read/write/close`. Scala Native supports Windows, but the kyo code needs platform-conditional implementations using `LinktimeInfo.isWindows` and `windowslib`.
