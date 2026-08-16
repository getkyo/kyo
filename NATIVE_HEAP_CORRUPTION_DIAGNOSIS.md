# Native heap-corruption crash: diagnosis

## Symptom (CI run 31913770016, linux-x64 Native, tip 6543956d39)

The x64 Native job failed. Summary across the aggregate + retries:
`Total 1628, Failed 0, Errors 11, Passed 1612, Skipped 5, Canceled 10`.
Two modules crashed:

- **kyo-coreNative**: exit 134. Sequence in the log:
  ```
  [PASS] closeAwaitEmpty > race between closeAwaitEmpty and close  (3ms)
  ScalaNative: Unhandled signal 11, si_addr=(nil)
  Fatal glibc error: malloc.c:3351 (__libc_malloc): assertion failed:
      !victim || chunk_is_mmapped (mem2chunk (victim)) || ar_ptr == arena_for_chunk (mem2chunk (victim))
  [error] Test runner interrupted by fatal signal 6
  ```
- **kyo-browserNative**: exit 139 (SIGSEGV, `si_addr=(nil)`), after the
  `isolate.clone` / `withFork` parallel-fork suites. Crashed on **both** aggregate
  retries (persistent, not a one-roll fluke).

## What this is (and is not)

It is **not** a clean post-suite teardown crash. It is **heap corruption during
concurrency-heavy tests**: a SIGSEGV on a null address, and in the kyo-core case a
glibc malloc arena assertion (`ar_ptr == arena_for_chunk`) that fires the moment the
signal handler touches malloc. The arena assertion proves the heap metadata was
**already corrupt** before the fault: a chunk is being reconciled against the wrong
arena. That is the signature of concurrent unsynchronized allocation/free, i.e. a
memory-safety race under scala-native multithreading.

## Configuration

- scala-native 0.5.12, multithreading ON (scala-native default; `native-settings-base`
  sets `SCALANATIVE_THREAD_STACK_SIZE=32MB`, and the scheduler runs multiple carrier
  threads, `threads=0/4` in the ci-mon line).
- Default GC (`immix`): no `withGC` / `SCALANATIVE_GC` override anywhere in the tree.
- kyo scheduler sizes `coreWorkers` from `availableProcessors` (4 on the runner).

## Why the current mitigation cannot converge

`ci-test.sh check_log` retries the run on a native crash-signal exit (134/135/139).
But the retry re-runs the **whole 51-module aggregate**. With N modules each carrying
a small independent per-run crash probability p, P(some module crashes) is
`1-(1-p)^N`, which stays high for N=51; each retry rerolls **all** modules and a
different one crashes (core, then browser twice here). Whole-aggregate retry is the
wrong granularity for a per-module probabilistic crash.

## Candidate root causes (ranked)

1. **scala-native 0.5.12 immix GC race under multithreading.** Most likely given the
   arena-corruption signature and that it is config-driven, not test-specific. Levers
   to test once reproduced: `SCALANATIVE_GC=commix` (concurrent-mark variant), or a
   scala-native patch bump.
2. **A memory-safety bug in kyo native code** (scheduler, off-heap, or FFI) exercised
   under concurrency. Would need per-site narrowing.
3. glibc/runner-arch interaction (x64 hit it here; arm64 passed the same run, so it is
   at minimum arch-probability-sensitive, not arch-exclusive).

## Open correctness question (blocks any masking decision)

Does the corruption ever produce **wrong test results** (silent), or does it only ever
**crash**? If it can corrupt a result without crashing, retry-masking (even per-module)
hides real failures and is illegitimate. This must be answered from a reproduction
before any mitigation-only path is chosen. This is the strategic call to bring to Fable
once the reproduction data exists.

## Reproduced locally (confirmed)

`kyo-coreNative/test` looped on linux-arm64 podman crashes on the **first iteration**
(`ScalaNative: Unhandled signal 11, si_addr=(nil)`, exit 11), right after the QueueTest
`closeAwaitEmpty > race between closeAwaitEmpty and close` concurrency test: the same
locus as CI. Local arm64 reproduces readily where arm64 CI passed the same tip, so the
per-run probability is environment-sensitive (CPU count / timing), not arch-exclusive.

**Correctness finding (answers the open question): the corruption is fail-stop, not
silent.** When the binary crashes, sbt-native relaunches it to finish the remaining
suites; the interrupted suites are reported failed *because they did not complete*, and
every test that ran before the fault carries its legitimate PASS/FAIL. No evidence of a
corrupted-to-PASS result. So a *convergent* retry would mask only crashes, not wrong
answers, and is legitimate on that axis. The fault is a consistent NULL dereference
(`si_addr=(nil)`) under the concurrent scheduler, the signature of a mutator reading a
reference the GC concurrently cleared: a GC/mutator race, not a test-logic bug.

## GC experiments (linux-arm64 podman, kyo-coreNative test looped)

- **immix** (default): crashes on the first looped run. Baseline.
- **commix**: crashes identically (`Unhandled signal 11, si_addr=(nil)`) on the first
  run, `multithreadingEnabled=detect` confirmed at link. Commix is immix's *concurrent*
  variant: same mark-region heap layout and collector code, plus concurrent marking, so
  it inherits the race rather than avoiding it. Not a fix.
- **boehm**: the genuinely different collector (decades-hardened, thread-safe
  conservative GC). Needs `libgc-dev` in the toolchain (added to build.sh's native apt
  list; the CI setup action needs the same for the real fix). Under test.

If boehm is stable, the fix is `nativeConfig ~= (_.withGC(GC.boehm))` in
`native-settings-base` (every native module, since kyo-browser and others crash the same
way) plus `libgc-dev` in the CI toolchain. If boehm also crashes, the fault is not GC
implementation choice but scala-native's multithreaded runtime or a kyo memory-safety
bug, and the next step is a direct null-deref hunt.

## Reproduction plan

Loop `kyo-coreNative/test` in one sbt session (links once, reruns the binary) under
glibc hardening (`MALLOC_CHECK_=3` aborts on the first heap inconsistency; turns silent
corruption into a reliable abort) on linux-arm64 podman first (faithful, no emulation),
escalating to x86 emulation / higher worker counts if arm64 will not reproduce.
