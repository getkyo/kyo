# Draft: scala-native issue — SIGSEGV in concurrent `Throwable.fillInStackTrace`

Prepared for the branch owner to file/adapt upstream (I do not open issues). Evidence lives in
`NATIVE_CRASH_BACKTRACE.txt`, `NATIVE_CRASH_BACKTRACE_4992.txt`, `NATIVE_CRASH_EXPERIMENTS.md`.

## Title
SIGSEGV in `Throwable.fillInStackTrace` under multithreaded exception construction (0.5.12)

## Environment
- Scala Native 0.5.12, Scala 3.8.4, linux-arm64 (reproduced) and linux-x64 (CI).
- Multithreading enabled (`multithreadingEnabled=detect`), default immix GC.
- Workload: kyo's fiber scheduler (~4 carrier threads) running exception-heavy code (Abort/Retry),
  i.e. many stack-trace-bearing exceptions constructed across threads under load.

## Symptom
Intermittent SIGSEGV (probabilistic; ~1-3 test iterations locally). The scala-native signal
handler prints `Unhandled signal 11, si_addr=(nil)` (also seen `si_addr=0x10`) then `exit(11)`.

## Root frame (gdb, DWARF build)
The fault is in the DWARF unwinder invoked from `StackTrace.currentRawStackTrace` during
`Throwable.fillInStackTrace`:

```
#3 libunwind::LocalAddressSpace::get32(addr=0)            AddressSpace.hpp:178
#4 CFI_Parser::decodeFDE(fdeStart=0)                      DwarfParser.hpp:183
#5 DwarfInstructions::stepWithDwarf(fdeStart=0)           DwarfInstructions.hpp:218
   -> fdeInfo/cieInfo are GARBAGE: codeAlignFactor=2705589728, dataAlignFactor=65527,
      pcEnd in the heap region (0xfff7...). A valid CIE is codeAlignFactor=1/dataAlignFactor=-8.
#6 UnwindCursor::stepWithDwarfFDE                         UnwindCursor.hpp:1028
...
#10 StackTrace$.currentRawStackTrace                      StackTrace.scala:72
#11 Throwable.fillInStackTrace                            Throwable.scala:35
```

So the per-thread unwind cursor holds a corrupt IP; `findFDE` returns garbage; `decodeFDE(0)`
dereferences null. Only ONE thread is in the unwinder at the fault, so it is not two concurrent
walks colliding — a single thread's cursor is corrupted by concurrent activity.

## What it is NOT (each ruled out by a verified-applied test)
- Not the collector: immix, commix, boehm all crash identically.
- Not thread-startup timing: serializing carrier-thread startup does not help.
- Not a libunwind null-FDE-check gap: guarding `decodeFDE` against `fdeStart==0` (verified compiled
  in) still crashes at a different site.
- Not #4992 (module-init publish race): the `module_load.c` reorder was vendored and grep-verified
  in the built binary (`nativelib_native0.5_3-0.5.12-kyo` unpacked), and it still crashed on loop 1.
- `ThreadLocal` is correctly per-thread (`Thread.threadLocals`), so the `StackTrace$Context` cursor
  ByteArray is per-thread, not shared by inheritance.

## Hypothesis for the maintainers
A per-thread unwind cursor (in the `StackTrace$Context` GC-heap `ByteArray`) is being corrupted
mid-capture under concurrency — candidates: the GC reclaiming/relocating the `ByteArray` while the
raw cursor pointer is live in C, or a safepoint/GC interaction during the unwind. Serializing the
capture is unlikely to fix it (only one thread unwinds at the fault).

## Reproduction
Any multithreaded program that constructs stack-trace-bearing exceptions on many threads under GC
pressure. kyo's `kyo-coreNative` test suite reproduces within a few `test` iterations; a minimal
standalone repro (N threads each looping `new Exception().getStackTrace` while others allocate) is
the next step if the maintainers want one.
