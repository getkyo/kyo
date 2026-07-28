# Analysis: Adding Windows to CI

## Context

CI currently runs two Linux poles (`linux-x64` on `ubuntu-latest`, `linux-arm64` on
`ubuntu-24.04-arm`), each fanning out JVM/JS/Native/Wasm via the reusable
`build.yml`. GitHub free public-repo Windows runners (`windows-latest`) are
spec-identical to the Linux ones: 4 vCPU, 16 GB RAM, 14 GB SSD. So the memory
tuning that CI already carries (SBT_TASK_LIMIT=1, 12G driver heap via .jvmopts)
transfers unchanged.

## Target selection: JVM, JS, Wasm on windows-x64. Not Native.

| Target | Windows viability | Why |
|--------|-------------------|-----|
| JVM  | yes | Corretto ships Windows x64; sbt/coursier work on Windows |
| JS   | yes | Node win-x64; chrome-headless-shell has `win64` builds (ChromeDownloader already maps `System.OS.Windows` to `win64`/`win32` and appends `.exe`) |
| Wasm | yes | Same Node-based pipeline as JS |
| Native | **no, separate project** | kyo-http's native transport binds libcurl + libh2o-evloop, and `openssl-native-settings` links `-lssl -lcrypto`; these are provisioned via apt on Linux only. Windows would need ported/vendored equivalents plus an MSVC/clang link environment. That is a porting campaign, not a CI wiring change. |

Native-on-Windows is surfaced here explicitly, not silently dropped: it requires
making the native HTTP transport and TLS dependencies buildable on Windows (or a
per-OS module exclusion mechanism in the build, which does not exist today). If
that is wanted, it should be its own campaign.

`windows-11-arm` is also free, but Corretto does not ship Windows arm64 JDKs
(the setup action provisions `corretto:25`), so it would need a different JVM
vendor for that pole. Deferred; noted as a follow-up option.

## What already works in our favor

- `.gitattributes` forces `eol=lf` for all text files: no CRLF breakage in bash
  scripts, C sources, or scalafmt checks.
- `.github/actions/setup/action.yml` cache paths already include the Windows
  locations (`~/AppData/Local/Coursier/Cache`, `~/AppData/Local/kyo-browser`),
  and the swap / apt / podman steps are already `contains(inputs.os, 'linux')`-gated.
- `scripts/ci-test.sh` runs under Git Bash (`shell: bash` in build.yml); the
  Native-only bits (`setsid`, `pgrep`) are behind the Native path or feature-
  detected. `ci-monitor.sh` degrades to the scheduler-only layer off-Linux.
- `build.sbt` has `SBT_UPDATE_LIMIT` ("serialize only dependency resolution, for
  Windows file lock avoidance") ready to be set by the Windows jobs.
- The kyo-ffi plugin honors `CC` (`ffiCCompiler := sys.env.getOrElse("CC", "cc")`)
  and already maps Windows to `.dll` with no `lib` prefix, on both the plugin
  compile path and the Scala.js koffi runtime path.
- kyo-pod: `ContainerRuntimeBase.available` honors `KYO_POD_RUNTIME`; pinning it
  to a value that is not an available runtime (e.g. `none`) registers zero
  container leaves. This matters because Windows runners may carry a Docker
  daemon in Windows-containers mode: `docker version` would exit 0, `hasDocker`
  would be true, and the suites would then fail pulling Linux images. Pinning
  avoids the false positive deterministically.

## Changes

### 1. `ci.yml` prep: os poles instead of linux arches

Reshape the derived matrix from `{os, image}` linux pairs into os poles that
also carry their supported targets:

```json
[
  {"os": "linux-x64",   "image": "ubuntu-latest",    "targets": "JVM JS Native Wasm"},
  {"os": "linux-arm64", "image": "ubuntu-24.04-arm", "targets": "JVM JS Native Wasm"},
  {"os": "windows-x64", "image": "windows-latest",   "targets": "JVM JS Wasm"}
]
```

The build fan-out passes `targets` as the intersection of the pole's supported
targets with the requested targets (push/PR request all; workflow_dispatch
requests `inputs.targets`). Asking for Native on windows-x64 yields an empty
matrix for that call, which build.yml's prep already turns into zero jobs.

The `arches` workflow_dispatch input becomes an os list (string input, default
`linux-x64 linux-arm64 windows-x64`) validated in prep; a `choice` with three
poles would need 7 subset options. The `custom` escape-hatch job stays
Linux-only.

### 2. `setup` action: Windows-gated provisioning step

One new step, gated on `contains(inputs.os, 'windows')`, writing to
`$GITHUB_ENV` (derived from os alone, per the action's contract):

- `KYO_POD_RUNTIME=none`: skip container suites (see above). Written via
  GITHUB_ENV in a gated step, NOT as a workflow-level conditional `env:` entry,
  because an empty-string env var would poison the Linux jobs (`Present("")`
  makes `available` empty everywhere).
- `SBT_UPDATE_LIMIT=1`: dependency-resolution file-lock avoidance.
- `CC=gcc` (MinGW, present on the runner image): kyo-ffi-it compiles
  `libkyo_it_bundled` for the JVM and JS test paths. If gcc is absent from the
  current image the fallback is `zig cc` (the plugin has a compiler-zig
  scted-test proving that path).

`coursier/setup-action` supports Windows; if the cs-installed `sbt` launcher is
not directly invocable from Git Bash (bash does not resolve `.bat` shims), the
same step drops a one-line `sbt` shell wrapper on PATH. This is the main
expected first-run friction point.

### 3. `build.yml`

No structural change: it already takes `os`, `runner-image`, `targets`. The
`NATIVE_*` env entries are target-gated and inert on Windows. Verify the
timeout: Windows runners are I/O-slower, and the JVM full run peaks ~95-100 min
on Linux; 180 min likely holds, confirmed by observation in the validation runs.

## Known risks to burn down during validation (not pre-fixable)

1. **sbt launcher under Git Bash** (above): may need the wrapper.
2. **kyo-http `HttpClientUnixTest`**: runs a real unix-domain-socket server.
   Windows Server 2022+ supports AF_UNIX and Java NIO exposes it, so it may
   just pass; if the bind fails, the correct fix is capability-probed skip
   logic in the test helper (`UnixSocketTestHelperImpl`), not an exclusion.
3. **Suites with OS-flavored assumptions** (kyo-aeron shared memory dirs,
   kyo-lsp/kyo-mcp process spawning, path-shaped assertions): unknown until a
   run; each failure gets diagnosed individually per "Fix the Code, Not the
   Test".
4. **Wall-clock**: Windows filesystem overhead on sbt/coursier is real;
   observe and adjust caching/timeout if needed.

## Validation plan

1. `actionlint` on the edited workflows (there is a CI job for it; also run locally).
2. `scripts/ci-test.sh --self-test` still green (no behavior change expected).
3. Push the branch (user-approved) and use `workflow_dispatch` with
   `arches=windows-x64`, iterating per target (`targets=JVM`, then `JS`,
   `Wasm`) until green, then a full dispatch across all three poles to prove
   the Linux paths are untouched.
4. Test-suite failures surfaced by the Windows runs are fixed at root cause in
   the affected modules as part of this change, not skipped.

## Job-count impact

Per PR: 8 build jobs today (2 poles x 4 targets) becomes 11 (plus the windows
pole's 3). Free-tier concurrency is 20; no queueing expected. Windows minutes
are free and unlimited for public repos.

## Validation findings (dispatch iterations)

### Run 1 (JVM, windows-x64): Frame derivation crashed on CRLF sources

The whole main tree compiled; kyo-direct's generated test variants failed the
Frame macro with StringIndexOutOfBoundsException. Root cause: dotty keeps
source content raw (position offsets count `\r`), but `frameImpl` memoized a
CRLF-to-LF normalized copy, shifting every callee-extraction offset by the
count of preceding CRs (silently wrong mid-file, crashing near end of file).
Trigger on CI: `TestVariant.scala` wrote variants with `println` (platform
separator, CRLF on Windows). Reach beyond CI: any user compiling CRLF sources
(git `autocrlf=true` is the Windows default).

Fixed in `3971306c3`: the memo keeps raw content (offsets consistent, `\r`
skipped as whitespace in the callee walker), variants are written with
explicit LF, and `FrameCrlfTest` (checked out CRLF everywhere via
`.gitattributes` `eol=crlf`, excluded from scalafmt) pins the behavior. The
crash was reproduced locally on macOS with a CRLF file before fixing, and the
guard was verified to fail against the old code.

### Run 2 (JVM, windows-x64): compile green, 4 kyo-scheduler test failures

Three failures shared one root cause: `StatusFile.write` renamed the temp file
with `File.renameTo`, which cannot replace an existing destination on Windows,
so every status write silently no-opped. Fixed with an NIO atomic move
(`REPLACE_EXISTING` fallback where atomic replace is unsupported).

The fourth (BlockingMonitor: 1 of 5 blocked tasks interrupted) exposed two
real bugs that Windows' coarse thread CPU-time counter (~15.6ms
GetThreadTimes ticks) amplified:

1. Sample state was keyed by batch index while block counts were keyed by
   worker position; when the collected worker set changes between cycles, one
   thread's time is compared against another's, fabricating idle/active
   verdicts (latent on all platforms). Fixed with per-slot state and a thread
   identity guard (`updateSlot`, unit-tested).
2. Scheduling pressure divided by `intervalNs` (2ms), so on Windows the
   counter granularity itself read as permanent ~8x CPU starvation and pinned
   the block threshold at ~15 instead of 2. Pressure now divides by the
   intended cadence `max(intervalNs, minIntervalNs)`, and
   `ThreadUserTime.probeResolution` probes past its old 2ms cap (up to 40ms)
   so coarse counters are discovered instead of defaulted away.

### Runs 4-6 and isolation probes: regulator freeze, downloads, test races

**Concurrency regulator frozen in its dead band (kyo-scheduler).** With the
scan-bound fix in place, blocked workers were flagged correctly, but the pool
never grew: a Windows timer diagnostic (dispatched via the new custom-runner
lane) showed probe jitter at 0.62-0.71ms, inside the dead band between the
absolute grow (0.5ms) and shrink (0.8ms) thresholds, with loadAvg climbing
past 40 and zero updates. Windows sleep(1) wake-ups quantize between 1ms and
2ms, a ~0.65ms noise floor; the absolute thresholds encode precise-timer
platforms (~0.2ms noise). First fix was a one-shot startup calibration that
re-anchored the band to a measured floor; on review (the floor is
state-dependent: the effective Windows timer resolution shifts with whatever
process holds it, so a startup measurement can mis-anchor for the process
lifetime, and the machinery cost a probe thread plus a config knob) it was
replaced with platform-sized threshold defaults in Flags.scala: Windows grows
below 1.5ms and shrinks above 2.5ms, clear of the quantization floor; POSIX
keeps the existing 0.5ms/0.8ms values; both stay overridable via the flag
properties. Net diff is five lines in Flags.scala; Regulator, Concurrency,
Config, and ConcurrencyTest are back to upstream shape.

**Chrome downloads never finalize with forward-slash paths (kyo-browser).**
All download tests timed out on every Windows target: bytes streamed,
Page.downloadProgress never reached completed, no file landed. A/B/C/D
isolation probes on the runner proved the mechanism: forward-slash
downloadPath (kyo Path rendering) fails under both the Page and Browser CDP
domains; backslash paths land correctly, including 8.3 short-name components.
Fixed at the wire boundary: the configured downloadPath converts to native
separators on Windows (pure helper, pinned by tests). This also covers
user-supplied forward-slash paths, natural in cross-platform code.

**Two test-correctness fixes surfaced by Windows timing.** The download guid
test completed its done promise on WillBegin (always first) and raced
subscription teardown against the completed Progress event, won on Linux by
microseconds and lost reliably on Windows; it now waits for the completed
event it asserts on. The killOrphans sentinel test spawned a POSIX sh/sleep
process on every platform; killOrphans is a documented silent no-op where
pgrep is missing, and the test now asserts that contract on Windows.

**Watch item: LLMStreamTest.** "stream[Answer] streams complete objects one
by one" timed out (2m) exactly once in run 6 and passed 5/5 in a dedicated
repeat probe on Windows. Not reproducible; if it recurs in full runs it needs
a dedicated investigation (possible latent streaming race exposed by changed
pool dynamics).

### Run v2 Windows JVM timeout: three kyo-compiler bugs, one root in kyo-config

Run v2 (the first with the aeron reaper) let the compiler suites actually run
on Windows and its JVM leg hit the 240-minute cap. The log showed three
failure shapes; a WinProbeTest dispatch on the custom lane (probe/win-compiler
branch) plus a jstack of the surviving fork discriminated all of them:

1. Empty diagnostics through the worker path (CompilerWorkerTest, spawn
   parity, shared-driver). Root cause in kyo-config: the rollout DSL activated
   implicitly on any flag value containing ';' or '@'. The worker classpath, a
   Windows path list joined with ';' and passed via -D, parsed as a rollout
   expression whose first choice (a terminal) always matches, collapsing the
   classpath to its first jar; the worker's pc then returned empty diagnostics
   for every buffer. POSIX joins with ':' and never triggered it. Fix: rollout
   interpretation now requires the explicit rollout: marker on the value,
   uniformly across StaticFlag, DynamicFlag init/update/reload, and the
   FlagAdmin PUT body; plain values resolve verbatim. Regression tests cover
   ';' and '@' values on both flag types.
2. Worker alive after close. Probe measured waitFor completing with the exit
   code while isAlive still read true (dead by t=50ms): Windows completes the
   exit wait slightly before liveness flips. SpawnBackend.close now waits for
   the exit and polls liveness (bounded) before returning.
3. The 4h hang itself: a cascade from bug 1. The failing tests aborted at
   their asserts before their happy-path backend.close calls, leaking worker
   JVMs (jps showed three live CompilerWorker processes) and aeron clients
   whose non-daemon conductor threads kept the forked test JVM alive; sbt sat
   in Fork.blockForExitCode until the job timeout. The spawn tests now
   scope-bind backends with an idempotent close finalizer, so any failure
   tears down in seconds instead of hanging CI for hours.

### Routed finding: kyo-cats fiber leak on origin/main (not this campaign)

The ship-gate run's linux-arm64 JVM job failed kyo-test's leak check after
all CatsTest leaves passed: a still-spinning ensure-loop fiber
(CatsTest.scala:109) trips "fiber leak: scheduler still busy ... running at
kyo.kernel.internal.KyoContinue.<init>". Reproduced on pristine origin/main
(bc5547771) 4 times in 6 runs of `sbt kyo-catsJVM/test` on an ARM mac;
this campaign's diff does not touch the kernel, kyo-cats, or the scheduler's
production interrupt path. The prime suspect is #1757 ("recover deferred
re-entry in handleCatching"), which changed exactly the ensure re-entry
mechanism the leaking test exercises. Needs a fix on main; it flakes main's
own CI independently of the windows work.

### Follow-up worth noting

killOrphans has no Windows implementation (the sweep is pgrep-based and
documented best-effort). Orphaned Chrome processes are never cleaned up on
Windows; a PowerShell Win32_Process match would close the gap. Feature work
beyond this CI campaign.

Two kyo-ffi platform-model questions surfaced by Windows Node and gated in
tests rather than resolved (both are feature-level decisions):

- C `long` on LLP64: the labs bindings assume a 64-bit C long; Windows'
  LLP64 model makes that mapping wrong under Node (the JVM tier passes
  through a different resolution). The ffi codegen's C-type model may want
  an explicit LLP64 story.
- errno domains: errno capture only works when both sides read the same
  CRT's errno. The MinGW UCRT test library agrees with the JVM's Panama
  capture but not with koffi's prebuilt binary. The ffi errno contract on
  Windows Node may need koffi-side alignment or documentation.

### Routed finding: five test files violate the name-prefix convention (pre-existing)

A ship-audit orphan scan over the PR diff flagged five test files whose names
share no prefix with any source file, all proven pre-existing on origin/main
(git cat-file -e passes for each at origin/main):

- kyo-config: EvalCounterTest.scala, PercentagePatternTest.scala,
  UpdateHistoryTest.scala (all cover DynamicFlag aspects; the convention would
  name them DynamicFlagEvalCounterTest and so on)
- kyo-ffi it: ItErrnoTest.scala, PosixTest.scala

This campaign edits their contents (the rollout marker, the Windows cancels)
but does not rename them: renaming files unrelated to Windows CI inside this
PR is a drive-by refactor that adds review noise and rename-vs-edit churn to
an already large diff. Routed here so it is not dropped; the fix is a
standalone rename pass in kyo-config and kyo-ffi.

### Local infra note

Recompiling kyo-data main reliably wedges kyo-schema's incremental compile in
this checkout (`NoClassDefFoundError: kyo.internal.FocusMacro$`, a same-module
macro invalidation ordering issue; `kyo-schemaJVM/clean` resolves it). It
reproduces identically with and without these changes and does not affect
clean CI builds.
