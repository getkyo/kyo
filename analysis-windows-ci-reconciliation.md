# Windows CI reconciliation onto evolved origin/main

## Situation

`native-ci-oom` (my branch) is 37 commits ahead; `origin/main` gained 23 commits
while the work was parked. The canonical logical change is the squashed 62-file
commit in the `windows-ci` PR worktree, not the 37 noisy branch commits (which
include a merge commit and calibrate-then-revert pairs).

Approach: fresh integration branch off `origin/main`, re-apply the change-set
selectively, module by module, validating compilation as I go. Rebasing 37
commits through the aeron rewrite is more error-prone than re-applying the clean
squashed diff.

## Categorization of the 62-file change-set

### DROP (obsolete: superseded by main's #1761 cross-platform kyo-aeron rewrite)
- `kyo-aeron/jvm/src/main/scala/kyo/internal/AeronClients.scala` (my new file; dead against main's new `AeronClient` API)
- `kyo-aeron/jvm/src/main/scala/kyo/Topic.scala` changes (file DELETED on main)
- The aeron-error-survival parts of the compiler: `System.exit`-avoidance, the reaper thread, and the op/close timeouts that existed only to survive the conductor hot-spin. Main solves this class upstream: `recordedFatalError` at the offer/poll boundary maps a fatal client error to `TopicTransportFailedException` instead of exiting, and there is an `errorHandlerRegression` test.

### KEEP, re-author onto main's rewritten files (kyo-compiler)
- `SpawnBackend.close`: confirm the worker is actually dead before returning (Windows completes `waitFor` slightly before `isAlive` flips). Re-apply onto main's `close` (which is now `exchange.close.andThen(aeron.unsafe.close()).andThen(process.destroyForcibly)`).
- `SpawnBackendTest`: scope-bind backends with an idempotent close finalizer so a failed assertion tears down the worker + client instead of leaking a non-daemon thread that hangs the fork. Re-apply onto main's test. (Re-verify the leak still exists with the new FFI transport; the hygiene holds regardless.)
- `CompilerWorker.scala`: textual conflict; inspect and re-apply the Windows-relevant change only.

### KEEP, merges clean (the actual classpath-truncation root cause)
- kyo-config: `Rollout`, `Flag`, `StaticFlag`, `DynamicFlag` + tests + README (rollout `:` marker). Decision confirmed with user: keep the marker (avoid ambiguity). Verified clean auto-merge.
- kyo-http `FlagAdmin` + README marker example.

### KEEP, verify semantically (auto-merged but main also touched)
- `kyo-ffi/plugin/.../KyoFfiPlugin.scala` (npm.cmd on Windows)
- kyo-http unix-socket test gating: `HttpClientUnixTest`, `HttpServerUnixTest`, `HttpWebSocketTest`, README
- `.github/workflows/build.yml`

### KEEP, clean (no main overlap)
- kyo-scheduler: whole-array blocking-monitor scan, per-platform jitter band, NIO status-file move
- kyo-ffi: universal-CRT resolution, LP64-long/errno gating, CCompilerTest host paths
- kyo-browser: WMI process scan, native download separators, System32 bsdtar, download-event wait
- kyo-data: Frame CRLF derivation
- kyo-core: JS drive-designator volume root

### REBUILD onto main's evolved versions (CI)
- `.github/workflows/ci.yml`: os-poles (linux-x64/linux-arm64/windows-x64 with per-os targets) onto main's evolved ci.yml (~191 lines changed: disk flight recorder, checkout-sha pin, native LLVM cleanup, JS linker memory cap). NOTE: git auto-merged ci.yml textually but that merge is untrustworthy given the pole restructure; rebuild by hand.
- `.github/actions/setup/action.yml`: Windows provisioning step (textual conflict); re-apply onto main's version.

## Out of scope (separate investigations)
- The `Async.timeout` / LLMStreamTest hang: pre-existing on main, independent of Windows CI. Delegated to a separate analysis (analysis-llmstream-hang.md).

## Execution log (done)

Approach corrected mid-flight: instead of a fresh branch + file-by-file re-apply,
did `git merge origin/main` into `native-ci-oom` (preserves provenance, git
auto-resolves the clean files, surfaces only real conflicts). Merge commit
`4de28a540`.

Conflicts resolved:
- `kyo-aeron/jvm/.../Topic.scala`: accepted main's delete (aeron rewrite).
- `kyo-aeron/jvm/.../AeronClients.scala`: `git rm` (my helper, dead against main's `AeronClient` API).
- `kyo-compiler/.../CompilerWorker.scala`: took main's version (my only delta was the obsolete `AeronClients.connect` swap; main moved to `AeronClient.connect(Path(dir))`).
- `kyo-compiler/.../SpawnBackend.scala`: took main's version, re-applied ONLY `awaitWorkerExit` onto its `close` (the Windows worker-death confirmation the merged `SpawnBackendTest` still asserts at line 186). Dropped the obsolete op/close timeouts (main's recorded-fatal-error handling makes the transport fail typed instead of hanging) and the `AeronClients.connect` call.
- `.github/actions/setup/action.yml`: kept BOTH my Windows sbt-shim step and main's new BoringSSL + libaeron staging steps (non-overlapping additions).

Semantic checks on auto-merged overlaps: ci.yml keeps my os-poles AND main's `checkout@v7.0.1` bump; build.yml keeps my `timeout-minutes: 240` AND the bump; KyoFfiPlugin keeps my `npm.cmd`; `CompilerPool.launchDriver` (my CI-timeout tuning) still fits main's embedded MediaDriver.

Local validation (all green): kyo-compilerJVM compile + test (SpawnBackendTest 6/6 incl. the kill/leak test), kyo-configJVM 297, kyo-schedulerJVM 210, and Test/compile of kyo-data / kyo-ffi / kyo-http / kyo-browser (JVM) + kyo-core (JS).

## NEW open question surfaced by main's aeron rewrite (needs a decision)

Main's #1761 made kyo-aeron cross-platform via a native C shim that needs a
staged `libaeron` (built by `kyo-aeron/scripts/build-aeron.sh`). The setup
action's `Prepare libaeron` step only handles linux/macos os-arch and SKIPS
`windows-x64`. So on the new Windows pole, kyo-aeron's C shim has no native lib
and its JVM/JS/Wasm legs (and kyo-compiler downstream) will fail to build the
same way they did locally before I staged it. This is NOT covered by the
original Windows CI work (which predated the aeron rewrite). Options:
1. Add a `windows-x64` os-arch case to `build-aeron.sh` (real port: aeron's
   CMake on Windows/MSVC or mingw).
2. Drop kyo-aeron (and its dependents: kyo-compiler) from the windows-x64 pole
   targets, same way Native is dropped.
Locally I confirmed the darwin-aarch64 build works, so linux/macos are fine; the
gap is Windows-specific. Recommend surfacing to the user before a Windows dispatch.

## CORRECTION: the first probe was buggy; the MinGW "easy path" was a false positive

The v1 probe (run 30382646286) reported "MINGW-AERON-BUILD-OK", and I built + committed
an implementation on it. That result was FALSE. The probe piped cmake to `tail`
(`if cmake ... | tail; then echo OK`), so the pipeline exit code was tail's (0), masking
cmake's actual failure. The full validation run (30383136030) exposed it: aeron's cmake
fails at aeron-driver/CMakeLists.txt:124 with "neither POLL nor EPOLL nor WSAPoll found",
because aeron 1.50.2 sets WSAPOLL_PROTOTYPE_EXISTS (and the winsock libs) ONLY under
`if (MSVC AND Windows)` (line 62). So aeron auto-supports Windows via MSVC, not MinGW.
The one reliable v1 result was GCC-PTHREAD-OK (no pipe): MinGW winpthreads works.

Probe v2 (run 30383734337, corrected: captures real exit codes, never masks via pipe)
tests the salvage question: force `-DWSAPOLL_PROTOTYPE_EXISTS=True -D_WIN32_WINNT=0x0600`
so aeron builds under MinGW despite its MSVC-only auto-detection, then LINK the shim into
a dll (the step v1 skipped). If that works, MinGW is viable with a two-flag build-aeron.sh
addition. If not, the real path is MSVC (port the shim's pthread to Win32 + switch the FFI
compiler to cl), or revert to the exclusion.

The `[kyo-aeron] build the native shim on Windows via MinGW` commit currently on
native-ci-oom is therefore built on the false premise and Windows-broken; it is fixed
forward (if v2 confirms forced-MinGW) or reverted (if not). The shipped PR still carries
the safe exclusion, so nothing broken has reached it.

### (Withdrawn) claim: kyo-aeron supported on Windows via MinGW
Withdrawn per the correction above; the v1 basis was a false positive. So support is pure wiring, committed in
"[kyo-aeron] build the native shim on Windows via MinGW":
- build-aeron.sh: MinGW Makefiles generator for a windows-x86_64 os-arch.
- setup action: stages libaeron on the Windows runner (tools preinstalled).
- build.sbt: aeron's Windows link interface (wsock32/ws2_32/Iphlpapi + winpthreads),
  verified exhaustively against aeron's own CMake target_link_libraries (libm/uuid/
  bsd/atomic are all Linux-only, so nothing else is needed).
- TestKyo: the host exclusion is removed; kyo-aeron and kyo-compiler build and test
  on Windows like every other platform.
Full validation run: 30383136030 (in flight).

## (Original, now reversed) Decision (user): exclude kyo-aeron + dependents on Windows for now

Determined aeron IS portable (its C lib is Windows/MSVC CI-tested), but kyo's
own shim `kyo_aeron.c` is POSIX (pthread/unistd, no `_WIN32` guards) and needs a
real port (Win32 threading + MSVC toolchain, or a MinGW-builds-aeron probe). User
chose to exclude for now, WITH its dependents, and leave the port as follow-up.

Implemented in `project/TestKyo.scala`: `windowsUnsupportedModules = {kyo-aeron,
kyo-compiler}`, skipped when `os.name` is Windows across all testKyo phases
(compile-main/compile-test/test), which every CI leg routes through (ci-test.sh
-> testKyo). Dependency graph is clean: kyo-aeron <- kyo-compiler (JVM-only) <-
nothing. Commit on native-ci-oom.

Validation: the exclusion logic is validated standalone (aeron all-platforms +
kyo-compiler drop when onWindows, nothing else does; nothing drops off-Windows).
NOTE: an attempt to validate end-to-end via `-Dos.name=Windows` on macOS is
INVALID (it makes sbt shell out to `cmd` and error during project load), so
end-to-end confirmation requires a real Windows run.

kyo-net checked and NOT excluded: its C shim carries `#if !defined(_WIN32)`
guards (kyo_posix.c:19, kyo_net_resolve.c:29), i.e. it was written Windows-aware,
unlike the aeron shim. Plausibly builds on Windows; the run confirms.

## MSVC path (user chose to support it properly, not exclude)

The design is gated/additive so no other OS is touched:
- Shim `kyo_aeron.c`: pthread wrapped in a `#if defined(_WIN32)` mutex+once abstraction (Win32
  CRITICAL_SECTION/InitOnce on Windows; pthread `#else` branch unchanged). Validated on macOS:
  AeronClientTest 6/6 with the C recompiled.
- `FfiLibrary.compilerByOs` (new, default empty -> global `cc`): lets kyo-aeron use `cl` on Windows
  while kyo-ffi/kyo-net stay on gcc. Mirrors the existing `linkLibsByOs` pattern; the plugin already
  has full MSVC family support (`/LD`, `.lib`, `/MT`).
- `build-aeron.sh`: cmake Visual Studio generator + `--config Release` for windows-x86_64.
Committed as "[kyo-aeron][kyo-ffi] MSVC groundwork". Probe v3 (run 30385633266) tests the real path
with correct exit codes: build-aeron.sh MSVC build + `cl` shim compile/link into a dll.

RECIPE CONFIRMED by probe (run 30387413028, DLL-BUILT-OK), after four probe iterations that also
found and fixed my own probe bugs (a pipe that masked cmake's exit code; a broken vcvars invocation;
and `cl /LD` defaulting to `/MT` when I thought it was `/MD`):
- aeron builds with cmake's Visual Studio generator (aeron forces `/MD`, gates Windows on `_MSC_VER`).
- The shim compiles with `cl` and the DYNAMIC CRT `/MD` (matching aeron; `/MT` gives
  `__imp_*` unresolved-symbol errors against aeron's `/MD` objects).
- Link needs aeron's winsock stack (`ws2_32 wsock32 Iphlpapi`) PLUS `shell32` (aeron's file utils use
  `SHFileOperation`), which aeron declares for its own shared build but not for a static-lib consumer.

Wired and committed ("[kyo-aeron][ci] build kyo-aeron on Windows with MSVC"):
- build.sbt kyo-aeron on Windows: `compilerByOs=cl`, `cFlags=Seq("/MD")`, `staticLink=false` (the .lib
  embeds via the link anyway; `/MT` would clash with `/MD`), `linkLibsByOs` = winsock + shell32.
- build-aeron.sh: VS generator + `--config Release` for windows-x86_64.
- setup action: stage libaeron on Windows, and a Windows MSVC-env step exporting vcvars
  PATH/INCLUDE/LIB/LIBPATH to GITHUB_ENV so the plugin's `cl` resolves; gcc still builds the other C.
- TestKyo exclusion removed.
Integration issues found and fixed on the Windows CI legs (each Windows-only, `oses=windows-x64`):
1. `LNK1181: cannot open input file 'aeron_driver_static.lib'`: the FFI plugin's MSVC path emitted
   `/LIBPATH:` on the compiler command line, where cl ignores it. Fixed to route linker options
   through `/link` (kyo-ffi CCompiler.scala), with a unit-test regression guard.
2. Windows JS/Wasm: bare `npm` fails (CreateProcess needs `.exe`); the koffi bootstraps for kyo-net
   and kyo-aeron now select `npm.cmd` on Windows (build.sbt).
3. JVM `EXCEPTION_ACCESS_VIOLATION` in `jint_disjoint_arraycopy` after many aeron tests passed: the
   ROOT CAUSE was an LLP64 `long` truncation. The shim declared `kyo_aeron_publication_offer` /
   `kyo_aeron_subscription_poll` / the async polls as returning C `long` and cast aeron's `int64_t`
   to `long`. On LP64 that is lossless; on Windows LLP64 `long` is 32-bit, so an aeron position/length
   was truncated while the Panama binding read 64 bits, and a corrupted length drove an OOB copy that
   crashed the JVM. Fixed: the shim uses `int64_t` throughout (64-bit on every platform, matching the
   binding). Diagnosed from the crash frame + a source read, not another probe. macOS re-verified: the
   full kyo-aeron JVM suite passes (TopicTest 23, round-trip, backpressure) with the bindings regenerated.
Validation run v3: 30395485364 (in flight, int64_t + all three fixes).

## Remaining
- Push native-ci-oom (24+ commits ahead, unpushed).
- Fresh Windows CI dispatch: validates the exclusion end-to-end AND empirically
  settles kyo-net (and anything else) on Windows.
- Re-prepare the windows-ci PR worktree from the reconciled state (its squash is pre-merge).
- The `Async.timeout`/LLMStream hang: parked per user (revisit if CI flakes).
