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
  conservative). Also crashes, with the **identical** primary fault
  (`Unhandled signal 11, si_addr=(nil)`), just later per unit work.

**GC ruled out.** immix, commix, and boehm all produce the same SIGSEGV null-deref.
A safe GC does not fix this and is not the answer. The fault is a null reference
dereferenced under the concurrent scheduler, independent of the collector: it lives in
scala-native's multithreaded runtime (memory model / atomics, per the WorkerQueue note
below) or in kyo's unsafe native code. Next step: a DWARF-symbolized backtrace
(`withSourceLevelDebuggingConfig(_.enableAll)`) to locate the faulting frame.

## The "not GC" candidate (if boehm also crashes)

`kyo-scheduler/.../WorkerQueue.scala` is a spin-lock-protected 4-ary min-heap: it
extends `AtomicBoolean`, locks via `compareAndSet`, unlocks via `set(false)`, guards
plain-array writes to `arr`/`keys`, and reads a `@volatile count` behind
`VarHandle.acquireFence()`. This is correct under the JVM memory model (the unlock's
volatile write releases, the lock's CAS acquires, establishing happens-before for the
plain array writes). If scala-native's multithreaded runtime does not honor
`AtomicBoolean` release/acquire or `VarHandle.acquireFence`, a stealing thread
(`stealingBy`) can observe a torn heap and dereference a null `Task`: a null-deref no GC
change can fix. Boehm is the discriminator: boehm-fixes-it means a live reference was
GC-cleared/moved (immix bug, GC-specific); boehm-still-crashes points here (scala-native
MT memory model) or to kyo's unsafe native paths.

## Two upstream scala-native bugs (maintainer-confirmed)

The crash is not one bug. The branch owner's repro (scala-native#4992) surfaced two,
per maintainer WojciechMazur's review on that PR:

1. **Module-init publish-ordering race (#4992).** `module_load.c`
   `__scalanative_startAndWaitForModuleInitialization` writes the InitializationContext
   fields after the publishing CAS; a concurrent first-touch reader can read `instance`
   before it is written, dereferencing null. GC-independent (explains immix/commix/boehm
   crashing identically). Fix is a 3-line reorder, open upstream, not yet released.
2. **GC rapid-thread-startup bug.** Maintainer: "the test scenario discovered a bug in
   the GC (issue with rapid startup of 256 threads and issues with heap growth)." Locus
   in `gc/immix/MutatorThread.c` `MutatorThread_init`: a new thread switches to `Managed`
   and initializes its allocators from the shared `blockAllocator` (lines 63-68) BEFORE
   `MutatorThreads_add` (69) puts it in the list the stop-the-world Synchronizer iterates.
   In that window many rapidly-starting threads touch heap/allocator state while a
   growth-triggered collection runs without stopping them. Fix promised by maintainer,
   not yet published.

kyo hits the startup trigger: workers start lazily via `exec.execute(this)` on a
`newCachedThreadPool`, so work flooding in spins threads up near-simultaneously.

## Vendoring: resource-override does NOT work

Dropping a patched `module_load.c` into `kyo-core/native/src/main/resources/scala-native/`
fails: scala-native unpacks each classpath entry's `scala-native/` resources into a
separate `classes-N/` dir, so the override compiles in isolation (`fatal error:
'gc/shared/ScalaNativeGC.h' file not found`) and, if it did compile, would duplicate the
symbol. Vendoring any scala-native C fix requires a locally-built patched `nativelib`.

## Attribution plan (keep each fix isolated)

Never test both fixes at once first; a green would hide which mattered.
- serialize thread startup (kyo-side Scheduler change): isolates the GC startup bug's
  trigger. Crash gone -> GC bug bites kyo; crash persists -> module-init race is
  independently crashing.
- #4992 only (patched nativelib): isolates the module-init race.
- both: the sound end-state. When the maintainer's GC fix lands (or one is derived from
  the MutatorThread_init window), it goes into the same patched nativelib as a separate
  patch so attribution stays clean.

## Reproduction plan

Loop `kyo-coreNative/test` in one sbt session (links once, reruns the binary) under
glibc hardening (`MALLOC_CHECK_=3` aborts on the first heap inconsistency; turns silent
corruption into a reliable abort) on linux-arm64 podman first (faithful, no emulation),
escalating to x86 emulation / higher worker counts if arm64 will not reproduce.
