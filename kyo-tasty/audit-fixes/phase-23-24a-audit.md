# Phase 23a + 23b + 24a Combined Audit

## Summary

WARN on two findings (jvmOnly depth coverage gap on JS, TypeArena T7 substance compromise). All
other findings are OK or NOTE. No blockers. Code quality is clean across all three phases.

## Findings

### 1. Phase 23a jvmOnly substance - WARN

The three tests tagged jvmOnly (B8/INV-019 Applied at MaxDepth+1, B8 boundary at MaxDepth-1, T4 Rec
at MaxDepth-1) genuinely require building 1023-1025 levels of Applied/Rec nesting. These structures
cause `hashOf` in TypeKey to call `computeHash` recursively through the same depth, which overflows
the JS stack before the `internRec` depth guard ever fires. The jvmOnly tagging is mechanically
correct.

The coverage gap: no JS test exercises the `depth >= MaxDepth` check in `internRec`. The depth guard
is shared code in TypeArena.scala and runs on every platform, but JS has zero tests that reach it.
The cyclic Rec test (Test 9) and the T7 fiber test (Test 10) run on JS but neither builds deep
enough nesting to trigger the guard. A purpose-built shallow test that interns a type at depth
MaxDepth-1 and separately mocks or intercepts the depth counter does not exist. JS coverage of
INV-019 is absent.

### 2. Phase 23b StatBuf fix completeness - OK

The fix is comprehensive. All three sites that read file size or mtime now use the canonical
`scalanative.posix.sys.stat` struct: `readFileNative` (NativeFileSource line 86, `_6`),
`NativeMmapReader.open` (line 30, `_6`), and `statFile` (NativeFileSource lines 200-201, `_8._1`
for mtime and `_6` for size). The legacy `PosixFileBindings.StatBuf` (`CStruct8[Long,...]`) is
retained only in the `listDirNative` / `listDirNativeMulti` methods, which access only `_2` for
S_IFREG/S_IFDIR mode bits, not size or mtime; that usage is unaffected by the struct layout
discrepancy. No other site reads st_size or st_mtime via the old struct.

### 3. Phase 23b POSIX write replacement - OK

The decisions log explains the root cause precisely: the C helpers were `int kyo_reflect_O_WRONLY(void)`
(functions), but the Scala `@extern` bindings declared them without parentheses, which Scala Native
interprets as C global variables. Reading a function address as an integer produces wrong flag values.
Replacing the POSIX FFI write path with `java.io.FileOutputStream` sidesteps the issue completely.
`FileOutputStream` is available in the Scala Native 0.5+ javalib and is exercised by
`NativeFileSourceTest` via a write-then-read round trip that verifies byte-level correctness. The
new path correctly creates, truncates, and writes the file with standard 0644 permissions.

### 4. Phase 23b mmap concurrency scope - NOTE

The scoped-down test covers the `checkOpen()` flag guard code path (the `AtomicBoolean.set(true)`
in the Scope finalizer triggers `IllegalStateException("mmap arena closed")` on the next read).
This is sufficient to pin T5 and verify INV-024's post-munmap guard. The unscoped concurrent-unmap
variant (munmap racing against an active pointer dereference) remains an open gap because
`MappedByteView.closed` is private and Scala Native 0.5 does not provide a safe way to race munmap
against a live read without risking SIGSEGV. The decisions log records this explicitly as a deferred
variant, not a forgotten requirement. The gap is real but acknowledged.

### 5. Phase 24a SingleAssign race determinism - OK

The test is structurally sound. `SingleAssign.set` uses `AtomicReference.compareAndSet` with a
sentinel Unset value: exactly one fiber's CAS can win, all others throw `IllegalStateException`. The
test asserts `successes.size == 1` and `failures.size == 15`, which is guaranteed by the CAS
contract regardless of scheduler ordering. There is no timeout or interrupt path that could produce
0 successes: `Sync.Unsafe.defer(slot.set(fiberIndex))` either succeeds (CAS wins) or throws (CAS
loses); both outcomes are caught by `Abort.catching[IllegalStateException]`. Two successes are
structurally impossible with a correct CAS. The winner assertion is a range check
(`>= 0 && < 16`), not a fixed index, so scheduler non-determinism does not affect correctness.
The test is not fragile.

### 6. Phase 24a TypeArena per-fiber adaptation - WARN

The plan claim was "T7 concurrency: 8 fibers each call arena.internRec(t)" with a shared arena.
The agent correctly identified that `TypeArena` is not thread-safe and switched to per-fiber arenas.
This is a valid production contract (one arena per fiber) but it does not exercise concurrent
access to a shared arena. The test verifies that (a) per-fiber intern returns the same reference as
the input when the arena is empty and the input is the value argument to `getOrElseUpdate`, and
(b) sequential merge into a canonical arena deduplicates to one entry. Neither assertion exercises
any concurrent code path in TypeArena itself. The T7 label in the test comment is misleading: the
actual concurrency is at the fiber scheduler level, not inside TypeArena. A genuine T7 test for
TypeArena's thread-unsafety contract would either document "concurrent access is undefined, per-fiber
is the contract" or race two fibers on the same arena and assert corruption. The current test does
neither; it silently validates only the serial path. The substance compromise is real and the test
description overpromises.

### 7. Code quality - OK

No em-dashes found in any new source file across the three phases. No illegal `return` statements,
no top-level `Either` types, no unguarded `asInstanceOf` without `// Unsafe:` comment (the
SingleAssign production file has two justified casts with comments; the TypeArena production file
uses `Some`/`None` in match arms against `map.get`, which returns `Option` and cannot be replaced
with `Maybe` without changing the mutable HashMap type). One `--` in a comment in NativeMmapReader
line 33 is a C comment convention, not a prose em-dash substitute. The `Option(System.getenv(...))` 
pattern in both native test helpers is acceptable (wrapping a nullable Java API). The `var capturedView: MappedByteView = null` in NativeMmapReaderTest is an intentional capture pattern
required by the test design (view must escape the Scope.run block) and is confined to one test
method. Overall code quality is acceptable.

## Recommendations

- Phase 23a: add a JS-compatible depth-guard test that builds a shallow structure (depth < 20) and
  verifies that `internRec` at exactly MaxDepth-1 does not throw, and that at MaxDepth it does. The
  TypeKey hash for small nesting depths does not overflow the JS stack, making this feasible without
  jvmOnly tagging. This closes the JS INV-019 coverage gap.
- Phase 24a: rename the TypeArena T7 test to remove the T7 label, or replace it with a test that
  documents the actual contract being tested (per-fiber allocation with sequential Phase C merge).
  If T7 is meant to cover concurrent TypeArena access, either add a documented caveat that the
  contract is per-fiber (not shared) concurrency, or add a separate test that explicitly races two
  fibers on a shared arena and asserts the expected undefined behavior or detection.
- NativeMmapReader: log the deferred concurrent-unmap gap as a known open item in the plan
  (already captured in decisions.md; consider a stub test with a pending tag to prevent it from
  being forgotten).
