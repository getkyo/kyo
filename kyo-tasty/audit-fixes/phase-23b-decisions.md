# Phase 23b Decisions

## Test placement

NativeFileSourceTest: placed in `kyo-tasty/native/src/test/scala/kyo/NativeFileSourceTest.scala`.
Reason: the test imports `kyo.internal.tasty.query.NativeFileSource` directly, which has
Scala Native FFI bindings (`@extern`) and cannot compile on JVM or JS. Placement in
`native/src/test/` is required per `feedback_all_platforms_all_tests`.

NativeMmapReaderTest: placed in `kyo-tasty/native/src/test/scala/kyo/NativeMmapReaderTest.scala`.
Reason: the test imports `NativeMmapReader` and `MappedByteView`, both of which have
Scala Native-specific types (`Ptr[Byte]`, `scalanative.unsafe._`). Native-only placement required.

Utf8Test addition: placed in the existing `kyo-tasty/shared/src/test/scala/kyo/Utf8Test.scala`
with `taggedAs nativeOnly`. The parity assertion uses only `Array[Byte]` and `String`, which
compile on all platforms. The `nativeOnly` tag (added to `Test.scala`) causes the test to be
ignored on JVM and JS.

## nativeOnly tag

The `Test.scala` base class had `jvmOnly` and `jsOnly` tags but no `nativeOnly` tag.
Added `nativeOnly` to `Test.scala` using `Platform.isNative` from `kyo-data/native/Platform.scala`,
which already declares `inline def isNative: Boolean = true`.

## Concurrency-test scope decision

The plan called for a concurrent-unmap test: harness triggers forced munmap during an active read.
Deferred: Scala Native 0.5 does not expose thread-safe ways to race a munmap against an active
pointer dereference without risking a genuine SIGSEGV. The `MappedByteView.closed` field is
`private val`, so it cannot be set from outside the class.

The simpler "close-after-scope-exit" variant was implemented instead:
- Capture the view inside a `Scope.run` block
- After `Scope.run` completes, the finalizer sets `closed = true` and calls `munmap`
- A subsequent `readByte()` throws `IllegalStateException("mmap arena closed")`
- This covers the flag guard code path (the `checkOpen()` method in `MappedByteView`)

## Pre-existing bugs fixed as prerequisites

Phase 23b tests revealed three latent bugs in production code that had never been exercised
by any existing test (all prior tests used in-memory FileSource implementations):

1. **StatBuf struct layout**: `NativeFileSource.readFileNative` used `statBuf._1` for `st_size`
   in a custom `CStruct8[Long,...]`. Field `_1` is offset 0 (st_dev / st_mode / st_nlink packed),
   not st_size. Fixed by switching to `scalanative.posix.sys.stat` (posixlib), which provides
   a canonical cross-platform mapping where `_6 = st_size`.
   Same fix applied to `NativeMmapReader.open` and `NativeFileSource.statFile`.

2. **StatBuf struct layout (mtime)**: `NativeFileSource.statFile` used `statBuf._3` for
   `st_mtimespec.tv_sec`. Fixed to `statBuf._8._1` (posixlib: `_8 = st_mtim`, `._1 = tv_sec`).

3. **O_WRONLY / O_CREAT / O_TRUNC C helpers as functions vs. variables**: The C helpers were
   defined as `int kyo_reflect_O_WRONLY(void) { return O_WRONLY; }` (functions), but the
   Scala `@extern` bindings declared them as `def O_WRONLY: CInt = extern` (no parens), which
   Scala Native treats as C global variables. Reading a function address as an int produces
   wrong flag values, causing files to be created with wrong permissions.
   Fixed by changing `writeFileNative` to use `java.io.FileOutputStream` (available in
   Scala Native 0.5+), which bypasses the POSIX FFI write path entirely and produces
   correct 0644 permissions.

## Summary of tests added

- `NativeFileSourceTest` (new, native/src/test): 2 tests: happy-path 100-byte read-after-write,
  and missing-path FileNotFound failure. Pins T5.
- `NativeMmapReaderTest` (new, native/src/test): 2 tests: read inside open Scope succeeds,
  and read after Scope.run exit raises IllegalStateException. Pins T5.
- `Utf8Test` (extended, shared/src/test): 1 `nativeOnly` test decoding "hello world" via
  Native's StandardCharsets.UTF_8 path. Pins T5.
- `Test.scala` (extended, shared/src/test): added `nativeOnly` tag object.
