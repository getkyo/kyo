# Why some CI jobs run much longer than others

Run analysed: [30699720019](https://github.com/getkyo/kyo/actions/runs/30699720019) (push to main, `6466d5fd8f`, mode=full).
Evidence is the raw per-job logs from the REST `jobs/<id>/logs` endpoint, which keep the per-line
timestamps `ci-logs.sh` strips. All numbers below are reproducible with `scripts/ci-analyze.py`.

## Answer first

Two separate things make a job long, and only one of them is a bug.

1. **kyo-test's 30-second leak-check idle budget is spent in full once per test suite on the JVM
   rows**, 107 times in the slowest job. It costs 56.5 min of the 3h27m linux-x64/JVM job and
   36.8 min of the 2h18m linux-arm64/JVM job: 27% of each. It is not workload and not machine speed.
   It is `SbtRunner.scala:138`'s `idleBudgetNanos = 30_000_000_000L`, reached every time because
   kyo-browser and kyo-ui fork one JVM per suite and hold a JVM-lifetime shared Chrome that keeps the
   scheduler from ever going idle.
2. **linux-arm64 skips ~2,500 browser tests**, because Google publishes no `chrome-headless-shell`
   for linux-arm64. That is why arm64 looks fast. It is doing less work, not doing work faster.

Neither cause is CPU contention, memory pressure, a noisy neighbour, or a retry. Those were checked
and ruled out with the ci-monitor.sh samples embedded in the logs.

## Job wall clock

```
3h27m13s  linux-x64/JVM        <- slowest completed
3h00m28s  linux-arm64/Native
2h21m03s  linux-x64/Native
2h18m15s  linux-arm64/JVM
1h58m03s  windows-x64/JS
1h55m14s  linux-x64/JS
1h48m30s  linux-x64/Wasm
1h31m07s  linux-arm64/JS
1h22m48s  linux-arm64/Wasm
   still running at 3h39m, against a 240 min timeout:  windows-x64/JVM
```

## The two JVM rows, phase by phase

The compile phases are the same on both machines. The whole difference is the test phase.

| phase | linux-arm64/JVM | linux-x64/JVM |
|---|---|---|
| setup | 2m13s | 2m55s |
| compiling main | 10m48s | 11m01s |
| compiling test | 43m01s | 42m30s |
| **testing** | **1h21m18s** | **2h29m40s** |

So x64 is not a slower machine. On the 24,595 tests both jobs actually executed, the per-test
numbers are within noise of each other, and on the JS rows x64 is marginally faster than arm64
(ratio 0.89x). The test phase differs by 68 minutes, and that is what the rest of this explains.

## Cause 1: a 30-second hang between suites (the bug)

Between one suite printing its `Results:` line and the next printing `Running 1 suite(s)`, the log
goes completely silent for a value that is not a distribution but a constant:

```
gap-value histogram, all jobs in the run
   31.5s  x172        <- one value, 172 times
    6.0s  x6
    5.5s  x5
    6.5s  x4
```

Work produces a spread. A spike at a single value is a timeout being reached. Per job:

| job | gaps >=25s | total | share of job |
|---|---|---|---|
| linux-x64/JVM | 107 | 56.5 min | 27% |
| linux-arm64/JVM | 70 | 36.8 min | 27% |
| linux-x64/JS | 0 | 0 | 0% |
| linux-arm64/JS | 0 | 0 | 0% |

Concentrated in `kyo-ui` (1707s), `kyo-browser` (1363s), `kyo-pod` (253s) on linux-x64/JVM.

### It is not one bad run

The previous completed main run, [30681003064](https://github.com/getkyo/kyo/actions/runs/30681003064),
loses the same time in the same places, to within about 1%:

| module | run 30681003064 | run 30699720019 |
|---|---|---|
| kyo-ui | 1700s | 1707s |
| kyo-browser | 1357s | 1363s |
| kyo-pod | 220s | 253s |
| kyo-net | 34s | 34s |
| kyo-http | 33s | 33s |
| **job total** | **55.7 min (30%)** | **56.5 min (27%)** |

103 of that run's 106 gaps are also 31.5s. This is a deterministic structural cost paid on every
linux-x64/JVM row, not a flaky or load-dependent effect.

### It is the browser/CDP teardown path, and it is JVM-only

Three independent controls in this one run point at the same thing.

**Control A, same suites on a different platform.** `kyo-browser` runs the same suites on the JS row
and the JVM row, and reports nearly the same test time, but not the same wall clock:

| | suites | reported | span | efficiency |
|---|---|---|---|---|
| linux-x64/**JS** | 65 | 514s | **605s** | 85% |
| linux-x64/**JVM** | 70 | 603s | **2230s** | 27% |

Same work, same browser, 3.7x the wall clock, and the difference is exactly the 1363s of 31.5s gaps.
The hang is on the JVM path only.

**Control B, the same suites with no browser launched.** On linux-arm64 every `kyo-browser` test is
cancelled, because `chrome-headless-shell` has no linux-arm64 build. That row records **0 gaps**
across all 69 suites. Browser launched gives the hang; browser never launched gives no hang.

**Control C, which suites pay it.** Within linux-x64/JVM's `kyo-browser`, the 43 suites followed by a
31.5s gap are the ones that drive a browser (`BrowserMutationTest`, `AccessibilityTest`,
`SelectorIntegrationTest`, `BrowserHistoryTest`). The 26 suites with no gap are the pure-unit ones
that never launch one (`CdpTypesTest`, `SelectorTest`, `KeyTest`, `PercentEncodeTest`).

### The exact cause: kyo-test's leak-check idle budget, spent once per suite

`kyo-test/runner/jvm/.../SbtRunner.scala:138` runs the leak check at the sbt `done()` boundary with a
30-second ceiling:

```scala
// Both budgets are ceilings that a healthy fork never spends: awaitSchedulerIdle returns as soon
// as the scheduler has been idle for one settle window ...
// The cost is paid only by a fork that already looks wrong ...
idleBudgetNanos = 30_000_000_000L,
settleNanos     =    500_000_000L,
```

`LeakCheck.awaitSchedulerIdle` polls until the scheduler has been idle for one 500ms settle window,
or until the 30s budget runs out. 30s + 0.5s settle, plus the next fork's start, is the 31.5s measured.

Three facts turn that from a candidate into the cause:

1. **It runs once per suite, not once per module.** `build.sbt:2549` (kyo-browser) and `build.sbt:2670`
   (kyo-ui) give every test class its own `Tests.SubProcess` fork, so sbt's `done()` boundary, and
   therefore this budget, is reached once per suite. That is exactly the granularity of the gaps.
2. **It is JVM-only.** `LeakCheck` exists solely under `kyo-test/runner/jvm`; `runner/js`,
   `runner/native` and `runner/shared` have no equivalent. That is why the JS and Wasm rows record
   **zero** such gaps while running the same browser suites.
3. **The budget is only spent when the scheduler never goes idle**, which is guaranteed here by
   design: `SharedChrome` (`kyo-browser/.../internal/SharedChrome.scala`) parks a detached fiber on
   `Async.never` to hold Chrome alive for the whole JVM, and the CDP connection and the per-suite
   `HttpServer` keep their pumps running. A fork that has started Chrome can therefore never satisfy
   `awaitSchedulerIdle`, so it always pays the full ceiling. A fork that started nothing satisfies it
   at once, which is precisely the arm64 `kyo-browser` control above (0 gaps, everything cancelled).

The two arm64 modules also separate the mechanism cleanly, and the difference is one line of test
setup. `UITest.withUI` starts the server before it touches the browser:

```scala
handlers <- UI.runHandlers("/")(uiTree)
server   <- HttpServer.init(0, "localhost")(handlers*)
result   <- Browser.runShared() { ... }
```

So on arm64, `kyo-ui` suites still bind an `HttpServer` before Chrome resolution fails and the tests
cancel: the scheduler is busy, and those suites pay the 30s (61 gaps, 1924s). `kyo-browser` suites
cancel before starting anything at all, so the scheduler is idle and they pay nothing (0 gaps). Same
platform, same run, opposite outcomes, decided by whether anything was left running at `done()`.

The code comment says this budget is "a ceiling that a healthy fork never spends". The measurement
says it is spent 172 times per run. The two are reconciled by the JVM-lifetime `SharedChrome`: these
forks are never "idle" by construction, so the ceiling is the normal path rather than the exception.

Two other 30s budgets on the same teardown path were checked and ruled out:
`BrowserLauncher.removeTmpDir` (its `Schedule.fixed(200.millis).maxDuration(30.seconds)` logs a
warning on exhaustion, and no `removeTmpDir` warning appears in any job log) and `Command.spawn`'s
scope release (it calls `destroyForcibly()` with no grace).

Fix directions, in rough order of payoff:

- Make the leak check's idle wait aware of the intentionally long-lived `SharedChrome` fiber, so a
  fork holding it is not treated as busy. This removes the cost without weakening the check.
- Failing that, let a suite opt out of the scheduler-idle wait the way `UITest` already opts out of
  `leakCheckSockets` and `leakCheckFileDescriptors`; today those two flags do not cover this budget.
- Reconsider the per-suite fork for kyo-ui and kyo-browser. `build.sbt:2551` budgets it at "~3 minutes
  of additional Chrome startup"; the measured cost is roughly 50 minutes of teardown per run, because
  the 30s ceiling is multiplied by the suite count.

## Cause 2: linux-arm64 skips the browser suite (not a bug, but it hides work)

Status-aware comparison of the two JVM jobs, matching tests by name:

```
A executed 24596 tests, cancelled 2890      (linux-arm64/JVM)
B executed 27101 tests, cancelled  384      (linux-x64/JVM)

[workload] only B ran :   2506 tests   36.9 min
[workload] only A ran :      1 test     0.0 min
```

The cancellation reason is in the log verbatim:

> kyo-browser cannot auto-download chrome-headless-shell for Linux/Aarch64. Google publishes no
> chrome-headless-shell for linux-arm64.

Per module, that is the entire spread between the two jobs:

| module | arm64 span | x64 span | delta | arm64 cancelled | x64 cancelled |
|---|---|---|---|---|---|
| kyo-browser | 151s | 2230s | +2080s | 1421 | 0 |
| kyo-ui | 2062s | 3624s | +1561s | 1086 | 0 |
| kyo-tasty | 239s | 351s | +112s | 0 | 0 |
| kyo-pod | 1052s | 1161s | +110s | 0 | 0 |

Everything else is within tens of seconds. The consequence is that **linux-arm64 is not a green
signal for the browser suite**: roughly 2,500 tests never run there.

## What was ruled out, with evidence

`ci-monitor.sh` writes a sample into the same log every 20s, so this is measured, not assumed.

- **CPU steal / noisy neighbours.** `stealTicks` is 0 across every Linux job for the whole run. No
  hypervisor contention on any runner.
- **Memory pressure.** `psiMem10` p95 is 0.00 on both JVM jobs; peak 5.18 on x64, 0.57 on arm64,
  both brief. `availMB` p50 is 9.6 GB (x64) and 9.0 GB (arm64) of 16 GB. No swapping, no OOM.
- **Disk.** `diskFreeMB` never drops below 73 GB on x64, 96 GB on arm64. No `DISK-WARN`, no
  `DISK-CRIT`, no `DISK-ABORT`.
- **Retries.** `ci-test.sh` retries only on the Native path. Both JVM jobs record zero retry,
  watchdog, or crash markers, and the run itself is attempt 1 with no previous attempt. The slowness
  is not re-executed work.
- **Environment differences.** Same runner 2.336.0 everywhere; one image version per OS pole
  (linux-arm64 `20260719.67.1`, linux-x64 `20260720.247.2`, windows `20260728.188.1`), identical
  across jobs within a pole. Azure regions differ per job, which is normal, and with steal at 0 it
  had no measurable effect.

## The runners are mostly idle

Joining the ci-monitor samples onto each module block shows what the box was doing:

```
linux-x64/JVM, 4 cores
  module         span   reported   gap   load p50
  kyo-ui         3624s    1007s    72%     1.00
  kyo-browser    2230s     603s    73%     1.00
  kyo-pod        1161s     451s    61%     1.00
  kyo-tasty       351s     344s     2%     7.00
  kyo-net          96s      57s    41%    20.00
```

`kyo-tasty`, `kyo-net` and `kyo-aeron` genuinely use the machine. The three slowest modules sit at
load 1.00 on a 4-core runner: one core busy, three idle, for 116 of the 135 minutes of the test
phase. Across the whole test phase, 64% of the wall clock on linux-x64/JVM is not test execution.

## Suggested order of work

1. Stop spending `SbtRunner.scala:138`'s 30s idle budget once per suite (see the fix directions
   above). Largest single win, about 27% of every JVM row.
2. `kyo-pod` shows the same per-suite signature and is covered by the same fix, since it forks per
   runtime and keeps container-backend pumps alive.
3. Decide what linux-arm64 should do about the browser suite. Today it silently skips ~2,500 tests.
   Installing Chromium from the distro (the cancellation message already suggests it) would make the
   pole meaningful, at the cost of making it as slow as x64 until item 1 lands.
4. Separately, `compiling test` costs 42-43 min on every JVM row and is the second largest block.
   Not covered here.

## Reproducing

```sh
scripts/ci-analyze.py fetch     30699720019
scripts/ci-analyze.py timeline  30699720019 -v
scripts/ci-analyze.py gaps      30699720019 --job linux-x64/JVM --min 25 -v
scripts/ci-analyze.py modules   30699720019 --job linux-x64/JVM
scripts/ci-analyze.py compare   30699720019 linux-arm64/JVM linux-x64/JVM
scripts/ci-analyze.py resources 30699720019 --job linux-x64/JVM
```
