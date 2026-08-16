# Native SIGSEGV: experiment register

Single source of truth for the investigation. One variable per experiment, isolated.
Symptom: `kyo-coreNative/test` (and others) intermittently `Unhandled signal 11,
si_addr=(nil)` under concurrency. Reliable repro: loop `kyo-coreNative/test` on
linux-arm64 podman, crashes within a few loops. Goal: locate the true cause(s), fix at
root, green the native CI.

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

This retires candidates A and B for the crash we actually hit: GC-swap could not help
(E1/E2), serialize-startup could not help (E5), module-init vendoring is irrelevant to
this frame. Candidate A/B may still be real upstream bugs, but they are not this crash.

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
