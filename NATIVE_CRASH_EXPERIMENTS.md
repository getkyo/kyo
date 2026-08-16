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
| E6 | gdb on a core dump (DWARF binary) | LOCUS of the fault | RUNNING |
| E7 | #4992 via patched nativelib (in-tree maven repo, 0.5.12-kyo, self-verifying override) | cause A | PREPPED (build.sbt override + .local-sn-repo), not yet run |
| E8 | #4992 + serialize-startup (both) | combined | PENDING (needs E7 working) |

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
