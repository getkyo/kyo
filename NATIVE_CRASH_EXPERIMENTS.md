# Native SIGSEGV: experiment register

Single source of truth for the investigation. One variable per experiment, isolated.
Symptom: `kyo-coreNative/test` (and others) intermittently `Unhandled signal 11,
si_addr=(nil)` under concurrency. Reliable repro: loop `kyo-coreNative/test` on
linux-arm64 podman, crashes within a few loops. Goal: locate the true cause(s), fix at
root, green the native CI.

## RESOLUTION: pure-kyo workaround = scheduler threads with inheritThreadLocals=false (2026-08-16)

Direction (user): if the workaround holds, ship it, file a scala-native PR upstream, NO vendoring.

- v3 instrumentation TALLY over 20 loops: crashes=0, tid-aliasing=20, canary=0. So the crash is
  ThreadLocal aliasing (a `Context` reaching a different thread), causally confirmed (refusing the
  aliased cursor -> 0 crashes), and NOT external clobber (candidate B).
- Aliasing pattern: an earlier thread's `Context` (lower getId) reused by several later threads
  (`owner=10 -> used 12,13,14,15`) -> children sharing an ancestor's Context = the InheritableThreadLocal
  inheritance path.
- FIX (committed dfad6b1abe): kyo-scheduler creates all its threads with `inheritThreadLocals=false`
  via the public JDK9 `Thread(group,task,name,stackSize,inheritThreadLocals)` ctor -
  `Worker.WorkerThread` and the `Threads` factory (clock/timer/top/selfcheck). Each scheduler thread
  then gets its OWN Scala Native `StackTrace` `Context` (fresh via `initialValue`) instead of an
  inherited/shared one. kyo-schedulerJVM/test PASSES (no JVM regression).
- VALIDATED: 40 clean kyo-coreNative/test loops on STOCK scala-native + the NoInherit change (30 via
  the forced-stock override, 10 on the fully clean branch after removing the vendoring). Baseline
  crashes in 1-3. JVM regression clear: kyo-schedulerJVM/test and kyo-coreJVM/test both green. The
  `interrupt > repeated` 2m timeout occurred once in 40 loops under 30-run container load and did not
  recur (one-off; tracked for the matrix, not a NoInherit regression).
- DONE: vendoring removed (build.sbt override + `.local-sn-repo`, commit cad5bd138e). Shipped config =
  pure-kyo NoInherit, stock scala-native, no vendoring. Branch fully validated locally.
- REMAINING toward 3-green: push HEAD (remote is at fd4f563a, ~40 commits behind) for a full-matrix
  run; push is user-gated. Upstream scala-native report + branch history cleanup are user's "later"
  items (empirical aliasing evidence + NoInherit-fixes-it; the inheritance SOURCE looks correct -
  keys stored as `key.reference`, `inheritValues` applies `childValue` - so the true cause is below
  the visible source and is for the maintainer to pinpoint).

## MECHANISM = ThreadLocal aliasing, NOT external clobber (instrumentation, 2026-08-16)

Built an instrumented `StackTrace.scala` via the validated NIR-factory (recompile a shadowing
StackTrace in a minimal scala-native project, swap ALL StackTrace* NIR/class/tasty into the vendored
0.5.12-kyo jar; kyo links + stays faithful, confirmed by a stock recompile+swap that still crashed).
Instrumentation: 64-byte canaries bracketing the cursor+context region of `Context.data` (checked
before every unwind step) + an owner-thread id on `Context` asserted per capture.

**Result (v2, identityHashCode owner id): crashes=4, canary=0, owner-mismatch=4 (43 markers),
crash-without-marker=0.** So:
- **candidate B REFUTED**: the canary NEVER tripped -> the cursor region is NOT overwritten by an
  external heap clobber. The cursor is corrupted from WITHIN (consistent with two threads writing one
  cursor concurrently), not by block reuse spilling into the region.
- **Owner MISMATCH on every crash run** -> a `Context` is reaching a thread other than its first user:
  ThreadLocal ALIASING (matches the branch owner's long-standing "scala-native thread-management race"
  hunch). This is a DIFFERENT mechanism than Fable's candidate B.

**Caveat + confirmation in flight (v3):** v2's owner id was `identityHashCode`, which can be unstable
if immix moves the object (address-derived hash) -> a same-thread mismatch would be spurious. v3
re-checks with a STABLE `Thread.currentThread().getId` (plus the hash as a cross-check) and RETURNS
EMPTY on a stable-tid mismatch (causal test): tid-aliasing>0 AND crashes==0 => aliasing IS the cause;
tid-aliasing==0 => v2 was hash instability and the mechanism is elsewhere. (Note: the InheritableThreadLocal
inheritance copy is CORRECT - `Values(fromParent)`/`inheritValues` applies `childValue`, giving each
child a fresh Context - so if aliasing is real its source is the get()/Values lookup or thread
lifecycle, not inheritance.)

## LEADING HYPOTHESIS = candidate B (Fable-redirected, 2026-08-16) [SUPERSEDED by the section above]

Fable reviewed the full evidence and redirected the fix hunt. Both fixes I was about to try are weak
by our OWN data, and the real suspect was never tested:

- Serialize-capture (global lock around currentRawStackTrace): ~refuted by M-freq (1.6M concurrent
  captures clean, and those DID call getStackTrace so shared DWARF state was exercised). Also a global
  lock perturbs timing so hard that 20 clean loops cannot distinguish "fixed" from "false-green" on a
  1-in-1-to-3-loops probabilistic repro. Do NOT try this first.
- Publication-fence / non-inheritable ThreadLocal: refuted by M-spawn (~80k creation events clean) +
  childValue allocs the fresh ByteArray on the PARENT before Thread.start (pthread_create is a sync
  point). Rank last.
- **candidate B (scala-native GC rapid-thread-startup race)** fits EVERYTHING and was never tested:
  a new mutator can allocate from the shared blockAllocator while a concurrent collection is in flight
  and it is not yet stopped, causing wild block reuse that overwrites ANY live heap object, including a
  walking thread's cursor ByteArray. Explains: all 3 GCs crash identically (shared startup path), the
  cursor overwritten mid-walk with heap-looking garbage, GC-independence, and the need for kyo's
  scheduler (creates carriers under load + heavy fiber allocation = creation + GC pressure together).
  The minimization matrix never tested that combined cell: M-gc had pressure with threads up front;
  M-spawn had creation but no heavy pressure (n=1).

**Code confirms the window (v0.5.12 gc/immix/MutatorThread.c `MutatorThread_init`):**
`switchState(Managed)` -> `Allocator_Init` -> `MutatorThreads_add` (register in stop-the-world list)
-> `Allocator_InitCursors` (claims first block, "might trigger GC") -> `scalanative_GC_yield` (stop if
GC ongoing). The yieldpoint trap is created DISARMED mid-init. So the explicit "stop if a collection is
already running" (GC_yield) happens AFTER the first block claim (InitCursors), and a thread that
started during an in-progress stop-the-world can claim a block the collector is reclaiming.
Candidate fix (to validate, not yet applied): move `scalanative_GC_yield()` to BEFORE
`Allocator_InitCursors`, so a late-starting thread parks at the in-progress collection before it
touches the shared heap. C-side -> the proven C-resource swap, not NIR.

**Upstream recon (2026-08-16): NO fix to backport.** 0.5.12 is the latest release (2026-05-21).
main through 2026-07-21 (41 commits) has nothing for the thread-startup GC race. #4875 (per-thread
yieldpoints) is already IN 0.5.12. #4992 (module-init = candidate A) is still OPEN. The GC bug is
maintainer-known (WojciechMazur) but undescribed and unfixed; fwbrasil is waiting on them (PR comment
2026-08-16). So the fix is ours to craft (and a clean standalone repro would also unblock the maintainer).

**Plan (Fable-ordered):** (1) upstream recon DONE, nothing there. (2) M-gc x M-spawn combined repro
(v6): CLEAN 7/15 (cut) -> candidate-B cell does NOT reproduce standalone either; kyo is the repro.
(3) candidate-B REORDER FIX (move scalanative_GC_yield before Allocator_InitCursors, immix+commix),
vendored on top of #4992 and HARD-VERIFIED in the unpacked build (`UNPACKED candidate-B fix present:
1`, `ORDER OK: yield(76) before InitCursors(80)`): **REFUTED - crashed on loop 1, si_addr=0x10**
(commit 6b267e19e3, validate-cbfix). So the simple reorder does not close the race: either candidate B
is not the mechanism, or its window is elsewhere (stack registration, the disarmed-yieldpoint-trap
gap, or a different clobber source). The reorder is KEPT in the vendored jar (it is a real correctness
improvement even if not the crash fix) pending the instrumentation verdict.

**NEXT = instrumentation (Fable diagnostic #2, now unavoidable).** bt5 already shows heap-looking
garbage IN the cursor (external clobber), so instrument StackTrace.scala: canary words bracketing the
cursor+context region of Context.data (checked before init_local and after the step loop) + an
owner-thread-id on Context asserted per capture. One instrumented nativelib separates: canary tripped
= external heap clobber (candidate-B family, needs a better/other fix); owner mismatch = ThreadLocal
aliasing; neither = libunwind-internal. Build path: StackTrace.scala is compiled NIR, so either the
sbt-project NIR-factory (compile a shadowing StackTrace in a minimal scala-native project, extract the
NIR, swap ALL StackTrace* entries into the vendored jar) or a full scala-native source build. Validate
the swap coherence with a STOCK recompile+swap first (kyo links + STILL crashes) before instrumenting.

## CURRENT STATE (earlier this session, superseded on mechanism by the section above)

The crash is a scala-native 0.5.12 runtime concurrency bug, GC-independent, that only reproduces
under kyo's full fiber scheduler. Nailed down by:

**Standalone minimization series (no kyo, plain scala-native project, linux-arm64 podman).** Each
isolates one variable against the kyo repro; ALL are clean, which rules candidates out:

| id | shape (N threads, `new Exception`+alloc) | isolates | result |
|----|------------------------------------------|----------|--------|
| M-freq | shallow capture loop, 8 threads x 200k | raw frequency | CLEAN (15 runs, ~1.6M captures) |
| M-deep | throw from ~120 deep frames | stack depth | CLEAN (15 runs) |
| M-gc | heavy fragmenting alloc (varied sizes + rotating retention -> frequent immix evacuation) | GC pressure / moving | CLEAN (20 runs) |
| M-spawn | steady capturers + a spawner creating threads WHILE they capture | thread-creation-during-capture | CLEAN (run 1, ~80k creation events; kyo crashes in ~dozens) |

So the trigger is NOT frequency, NOT depth, NOT GC (consistent with E2 boehm), NOT plain
thread-creation-during-capture. It needs kyo's specific scheduler machinery (work-stealing carriers
+ deep varied kyo stacks + fiber allocation + the Abort/enrich exception timing). kyo IS the repro.

**Source reading (scala-native 0.5.12 `StackTrace.scala`).** The unwind cursor is NOT a separate
object: `Context` holds one `data: ByteArray` and `unwindCursor`/`unwindContext`/`ip` are raw
pointers into fixed offsets of it (`data.atUnsafe(UnwindCursorOffset)`). `ThreadLocalContext extends
InheritableThreadLocal[Context]`; `initialValue`/`childValue` each `ByteArray.alloc` a FRESH buffer,
so it is genuinely per-thread. In `currentRawStackTrace` the `isFillingStackTrace` reentrancy guard
is set AFTER `get_context`/`init_local` (before the step loop). Corruption is neither GC-move (E2)
nor cross-thread buffer sharing; the remaining consistent candidate is an unsafe-publication race in
thread creation (child reads a half-constructed `Context`/`data` pointer), same CLASS as #4992 and
matching the branch owner's thread-management hunch.

**Decisive diagnostic IN FLIGHT:** loop `kyo-coreNative/test` with the scheduler forced to a SINGLE
carrier (patched `Flags.scala` core/min/maxWorkers=1). Baseline crashes in 1-3 loops. Clean over 20
single-carrier loops => concurrency-in-capture is NECESSARY (enables a targeted fix without a
scala-native source build); still crashing => not concurrent-capture, rethink.

## Candidate causes (both upstream scala-native, maintainer-confirmed on SN#4992)

- **A: module-init publish race (SN#4992).** `module_load.c` writes the
  InitializationContext fields after the publishing CAS; a concurrent first-touch reader
  can read `instance` before it is written -> null deref. Fix: 3-line reorder. Open, not
  released.
- **B: GC rapid-thread-startup bug.** `MutatorThread_init` registers a thread in the
  stop-the-world list only after it starts allocating from the shared blockAllocator;
  rapid startup + heap-growth collection races. Fix promised by maintainer, not published.

## Experiment log

| id | change (the one variable) | isolates | result |
|----|---------------------------|----------|--------|
| E0 | baseline: stock, default immix GC | reference | CRASH signal 11, si_addr=nil, ~loop 1 |
| E1 | GC = commix | GC implementation | CRASH, identical primary fault |
| E2 | GC = boehm (needs libgc-dev) | different GC family | CRASH, identical -> **GC ruled out** |
| E3 | + DWARF (sourceLevelDebugging) | (to read a backtrace) | CRASH; SN handler prints no stack |
| E4 | patched module_load.c as project resource | vendoring path A | BUILD FAIL (separate classes-N dir, missing gc headers, dup symbol) -> resource-override unviable |
| E5 | serialize worker-thread starts (kyo Scheduler) | cause B trigger | CRASH after several clean loops -> **not sufficient alone** |
| E6a | gdb on a core dump (DWARF binary) | LOCUS of the fault | NO CORE: scala-native `stackOverflowHandler` (stackOverflowGuards.c:254) `exit(sig)`s on an unhandled fault, so no core; also high crash variance |
| E6b | gdb-wrapper: run real binary under gdb, `break exit if $x0==11`, loop test | LOCUS of the fault | **CAPTURED loop 1** (see below + NATIVE_CRASH_BACKTRACE.txt) |
| E7 | #4992 via patched nativelib | cause A (module-init) | SUPERSEDED: E6b shows the crash is the libunwind unwinder, not module-init; scaffolding reverted |
| E8 | #4992 + serialize-startup (both) | combined | SUPERSEDED (same) |
| FIX-1 | Trace.enrich skips the native runtime-stack suffix (guard `new Exception` unwind with `Platform.isNative`) | one unwind source | committed 9927fff853; **INSUFFICIENT** (still crashed loop 1). Kept: it removes a real hot unwind source and is correct, but the unwinder crash has other `fillInStackTrace` triggers |
| E6c | gdb-wrapper on the fixed HEAD | the REMAINING unwind trigger | RUNNING |

The crash is any concurrent `fillInStackTrace` hitting scala-native's libunwind (thread-local
cursors, but a shared `LocalAddressSpace`/DWARF FDE lookup). enrich's suffix was one trigger;
exception CONSTRUCTION is the dominant one (unavoidable for user/test exceptions), so a
trigger-side fix cannot be complete.

## ROOT FIX (at the crash site)

The fault is a missing null-check in the DWARF parser: `CFI_Parser::decodeFDE` starts with
`get32(fdeStart)` and crashes when `fdeStart == 0` (a null FDE, produced for some frame's PC
under concurrent walks). The caller `stepWithDwarf` already treats a non-NULL `decodeFDE`
return as `UNW_EBADFRAME` (graceful stop), so guarding `decodeFDE` against `fdeStart == 0`
converts the SIGSEGV into a normal "cannot unwind this frame". Trigger-agnostic, safe,
upstreamable.

- FIX-1 (enrich suffix skip): **reverted** (wrong layer; superseded).
- **FIX-2 (committed d098748dd2):** vendored `nativelib` (0.5.12 + the `decodeFDE` null guard
  in `DwarfParser.hpp`), resolved from `.local-sn-repo` via a self-verifying `0.5.12-kyo`
  `dependencyOverride`. Pending an upstream scala-native release, then drop + bump.
- Validation (first attempt): crashed loop 3, `si_addr=0x10`. BUT the `dependencyOverride`
  DID NOT APPLY: `allDependencies` shows `org.scala-native:nativelib:0.5.12` (base name, the
  `_native0.5_3` suffix is applied at resolution via `%%%`). The override used the full name
  `nativelib_native0.5_3`, which does not match the base-name `%%%` dependency, so stock 0.5.12
  was linked and the guard was NEVER TESTED. The loop-3 crash was the unpatched runtime; the
  "si_addr=0x10 -> race/whack-a-mole" read was premature.
- Re-validation (reliable): patch the coursier-CACHED nativelib jar directly (bypasses the
  cross-version resolution mismatch), verify the guard is unpacked into the binary, then loop.
  RUNNING. This is the actual first test of whether the null-FDE guard fixes the crash.
- Clean CI vendoring (if the guard holds): fix the override to `%%% "nativelib"` cross-version
  form, or cache-patch in ci-test.sh, or build a proper patched nativelib.

## ROOT CAUSE (E6b backtrace) — NOT module-init, NOT GC

The faulting thread crashes in **scala-native's libunwind DWARF unwinder**, dereferencing
NULL: `libunwind::CFI_Parser::decodeFDE(fdeStart=0)` -> `LocalAddressSpace::get32(addr=0)`,
reached from `Throwable.fillInStackTrace` -> `StackTrace.currentRawStackTrace`
(scala-native runtime). The kyo trigger is `kyo.kernel.internal.Trace.Owner.enrich`
(Trace.scala:197): `val suffix = (new Exception).getStackTrace().drop(2)`. kyo enriches
every propagating exception (Abort/Retry through a Safepoint) with the native runtime
stack, and that `new Exception` runs the buggy concurrent unwinder. `findFDE` returns 0
for a frame's PC under concurrent unwinds (the other threads unwind the same frame shapes
fine), so it is a concurrency race in libunwind's shared FDE lookup, not a bad PC.

**CORRECTION (Fable static analysis of the vendored sources).** The above located the
faulting FRAME but not the MECHANISM, and the "retires candidate A (#4992)" inference was
UNSOUND. The bundled libunwind has no internal race: `DwarfFDECache` is guarded by a real
`pthread_rwlock` and `_LIBUNWIND_HAS_NO_THREADS` is defined nowhere; no coherent libunwind
path produces `stepWithDwarf(fdeStart=0)` with valid state, so the cursor was corrupted from
OUTSIDE. scala-native's `StackTrace.scala` stores the unwind cursor/context in a GC-heap
`ByteArray` whose offsets are fields of the `StackTrace$Context$` MODULE, so the module-init
publish race (#4992, maintainer-confirmed) can corrupt that module -> the cursor lands in
memory the walking thread does not own -> a concurrent thread zeroes it mid-walk ->
`decodeFDE(0)`. The two observed `si_addr` values are #4992's two textbook manifestations:
0x0 (null propagation) and 0x10 (a field read at offset 16 off a null module instance). E5
does not serialize module FIRST-TOUCH (it happens at the first concurrent exception storm),
and all three GCs crash identically: both consistent with #4992, not a GC or libunwind bug.
So candidate A is the PRIME SUSPECT, E7 was never validated, and FIX-2's `decodeFDE` guard is
a symptom-fix (drop as the fix; offer upstream as defensive hardening only). "Root-caused to
libunwind" and "NOT module-init" are downgraded to hypotheses until the verified #4992 run lands.

**Fix direction:** on Scala Native, do not capture the native-stack suffix in `enrich`
(the `new Exception` unwind). Native suffixes are cryptic mangled frames and the unwinder
is the crashing, low-value facility; kyo's own `elements` trace is preserved. Validate by
fix-and-verify against the repro. Also worth an upstream scala-native report.

## What E6 decides (why it is first)

A real backtrace tells us the faulting frame directly, instead of inferring cause from
black-box pass/fail (E1-E5 were black-box and E5 in particular passed several loops then
crashed, which is uninformative about mechanism). If E6 lands in
`__scalanative_startAndWaitForModuleInitialization` / a module first-touch accessor ->
cause A (validate with E7). If in `WorkerQueue`/scheduler or GC mark/sweep -> a different
locus, re-plan. E6 gates whether E7/E8 are the right next step.

## #4992 REFUTED too — it is a scala-native concurrent-fillInStackTrace bug (upstream)

The #4992 `module_load.c` reorder was vendored via the `0.5.12-kyo` override and HARD-VERIFIED
in the unpacked build (`UNPACKED reorder present: 1, from-0.5.12-kyo-jar: 1`). It STILL crashed
on loop 1. So #4992 is not the cause either. Every candidate is now refuted with a verified test:

| candidate | verified applied? | result |
|-----------|-------------------|--------|
| GC = commix / boehm | yes | crash |
| serialize thread startup | yes | crash |
| libunwind `decodeFDE` null-guard (0.5.12-kyo) | yes | crash (si_addr 0x10) |
| #4992 module_load.c reorder (0.5.12-kyo) | yes (grep in build) | crash loop 1 |
| enrich native-suffix skip | yes | crash |

**Source reading (scala-native StackTrace.scala):** the unwind cursor lives in a per-thread
`Context.data` `ByteArray` (`ThreadLocalContext extends InheritableThreadLocal`, `childValue`
allocs a FRESH ByteArray per child, `initialValue` per creator) — so it is thread-local, not
shared by inheritance. Yet the captured crash state (`decodeFDE(fdeStart=0)` from `stepWithDwarf`)
is a CORRUPT cursor that Fable showed is impossible from a coherent libunwind path. A thread-local
cursor going corrupt under concurrency points at scala-native's runtime: the `InheritableThreadLocal`
implementation returning/copying wrong state, the `Context$` offset module, or the GC moving/reusing
the `ByteArray` mid-walk. All are scala-native-internal and not patchable from kyo or via a jar
resource (StackTrace.scala/Throwable.scala are compiled NIR, not resources).

## Mechanism pinpoint (bt5, #4992 VERIFIED in build: unpacked `0.5.12-kyo`)

`bt full` on the crash (NATIVE_CRASH_BACKTRACE_4992.txt): the unwinder parses GARBAGE
FDE/CIE (`codeAlignFactor=2705589728`, `dataAlignFactor=65527`, `pcEnd` in the heap region,
`fdeStart=0`; a valid CIE is `codeAlignFactor=1`/`dataAlignFactor=-8`). So the per-thread
unwind cursor holds a corrupt IP -> `findFDE` returns garbage -> `decodeFDE(0)` -> crash. Only
ONE thread is in the unwinder at the fault, so it is not two concurrent walks colliding; the
single thread's own cursor was corrupted by concurrent activity (GC or runtime state), with
#4992 applied. Conclusive: scala-native runtime, not kyo, not #4992, not the collector.

## Honest conclusion + options

The crash is a scala-native 0.5.12 runtime bug in concurrent `Throwable.fillInStackTrace`. Complete
fix is upstream or a scala-native SOURCE build. Options for green CI:
1. Report upstream (#4992 thread or new) with the E6b backtrace + this refutation matrix; it is a
   reproducible concurrent-stack-trace SIGSEGV, high value. Bump when a release carries the fix.
2. scala-native SOURCE-BUILD fix hypotheses to test (heavy but complete + upstreamable): make the
   Context a plain (non-inheritable) `ThreadLocal`; or serialize `currentRawStackTrace` with a
   global lock. Vendor the resulting nativelib.
3. Cheap confirming diagnostic (not a fix): run the repro with `--cpus 1` (scheduler -> 1 carrier ->
   no concurrent capture); a clean run confirms concurrency-in-capture is the root.
The vendored #4992 jar is KEPT (it fixes a real, separate maintainer-confirmed bug) but is NOT the
crash fix. The `decodeFDE` guard was dropped.

## Attribution methodology (per user)

Establish **both-applied -> green** first (E8), then disable one fix at a time from green
to prove which is load-bearing. Do NOT start red and add singly: if the crash needs both
fixes, a single fix reads as "no effect" and misattributes. The cells:
- both (E8): expected green (target).
- both minus serialize = #4992-only (E7): isolates A.
- both minus #4992 = serialize-only (E5): CRASHED.
- neither (E0): CRASHED.

## Uncommitted experiment scaffolding (not the final fix)

- `kyo-scheduler/.../Scheduler.scala`: serialize-startup wrapper (E5/E8).
- `build.sbt` + `.local-sn-repo/`: patched-nativelib override for #4992 (E7/E8).

These are experiment infrastructure. The real fix is chosen after E6 + attribution and
will be minimal (vendored nativelib patch and/or a kyo-side change), with the scaffolding
cleaned up.
