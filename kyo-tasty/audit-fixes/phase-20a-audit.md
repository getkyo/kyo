# Phase 20a Audit

## Summary

PASS with one WARN and three NOTEs. The implementation is functionally correct and the
tests pass. The WARN is a latent dead-code issue: the `ZipException` catch arm in the
Native hook will never fire on the scala-native javalib because corrupt ZLIB data surfaces
as `IOException` (wrapping `DataFormatException`), not as `ZipException`. The error is
still caught by the `IOException` arm and produces a `CorruptedFile` error rather than
`MalformedSection`, which is an incorrect mapping for a corrupt-stream signal.

---

## Findings

### 1. Native javalib availability - OK

`java.util.zip.InflaterInputStream`, `Inflater`, and `ZipException` are all present in
`javalib_native0.5_3-0.5.8.jar` (confirmed by jar listing). The decisions doc records
that `kyo-tastyNative/Test/compile` passes and `testOnly kyo.InflateHookNativeTest` runs
1/1. No Native tests were disabled to avoid missing classes.

### 2. Error shape - WARN

The scala-native javalib `InflaterInputStream.read()` (ported from Apache Harmony) does
NOT throw `ZipException` on corrupt ZLIB data. The `Inflater.inflateImpl` method throws
`DataFormatException` on zlib errors. `InflaterInputStream.read()` catches
`DataFormatException` and rethrows it as `IOException` (via `initCause`). The only path
that throws `ZipException` is `Inflater.createStream` (for `inflateInit2` failure) and
`Inflater.reset` -- neither is called inside the `InflaterInputStream` read loop.

Consequence: on corrupt ZLIB input, the Native hook catches the `IOException` arm and
emits `TastyError.CorruptedFile("Scala2Inflate", 0L, ...)` instead of
`TastyError.MalformedSection`. The `ZipException` arm is dead code on Native. The
JVM javalib does throw `ZipException` on corrupt ZLIB data, so the divergence is
platform-specific. Route: Phase 21f -- either add a `DataFormatException` arm that maps
to `MalformedSection`, or document that Native uses `CorruptedFile` for both I/O and
format errors.

### 3. Resource leak - NOTE

`inflater.close()` is called inside the `try` block after the read loop completes.
`ByteArrayInputStream` holds no native resources, and `ByteArrayOutputStream` is
heap-only. `InflaterInputStream.close()` calls `inf.end()` which releases the zlib
stream struct via `stdlib.free`. If an exception fires during the read loop, `inf.end()`
is not called. Because the `compressed` input is already a heap `Array[Byte]`, the
`ByteArrayInputStream` wrapper has no OS-level resource. The zlib stream struct is a
small `calloc` allocation; on Native the GC finalizer (`Inflater.finalize`) calls
`end()` on collection. This is a minor leak under exception, not a hard resource
exhaustion. A `try { ... } finally { inflater.close() }` pattern would be cleaner.
NOTE, not BLOCKER.

### 4. byteOffset 0L sentinel - NOTE

Both `MalformedSection` and `CorruptedFile` use `0L` as the byte offset. The ZLIB
stream has no meaningful byte offset relative to the `.tasty` file (the compressed bytes
are an opaque attribute payload). `ex.getMessage` is forwarded as the reason string
for both arms, which preserves the zlib error text. Parsing an offset from the message
is not meaningful here. The 0L sentinel is appropriate.

### 5. 17-byte fixture validity - OK

Python `zlib.decompress` confirms the 17-byte array decompresses to `b'hello kyo'`
(9 bytes). Layout is: bytes 0-1 = `0x78 0x9c` (CMF/FLG, RFC 1950 zlib header,
default compression level 6); bytes 2-12 = raw deflate bitstream (11 bytes); bytes
13-16 = `0x11 0xa2 0x03 0x88` (Adler-32 checksum). The doc comment in `InflateHookTest`
correctly describes: "2-byte ZLIB header + deflate bitstream + 4-byte Adler-32." The
byte count (17) and structure are accurate.

### 6. Test-platform separation - NOTE

`Test.scala` defines only `jvmOnly` and `jsOnly` tags; there is no `nativeOnly` tag.
Given this constraint, splitting into a `jvmOnly`-tagged shared test and an untagged
native/-specific test is the correct approach. The payload is duplicated across two
files, but the decisions doc acknowledges this and defers consolidation to a future
phase when `nativeOnly` can be added to `Test.scala`. No incorrect behavior results
from the split.

### 7. Code quality - OK

No em-dashes, no semicolons as statement separators, no `asInstanceOf`, no
`Either`/`Right`/`Left`, no default parameters in new code. The `Sync.defer` +
`try`/`catch` pattern with a typed `val result: Array[Byte] < Abort[TastyError]`
mirrors the Phase 17 pattern and is idiomatic for the codebase.

---

## Recommendations

- WARN (ZipException dead on Native, route Phase 21f): The `ZipException` catch arm
  never fires on scala-native because corrupt ZLIB data surfaces as `IOException`
  (wrapping `DataFormatException`). Add a `case ex: DataFormatException =>` arm mapping
  to `MalformedSection`, or at minimum document that Native maps both format and I/O
  errors to `CorruptedFile`.
- NOTE (resource leak under exception): move `inflater.close()` to a `finally` block
  to ensure the zlib stream struct is freed even on IOException during the read loop.
- NOTE (byteOffset 0L): appropriate for an opaque attribute payload; no action needed.
- NOTE (test duplication): deferred correctly; add `nativeOnly` tag to `Test.scala` in
  a future phase and collapse to a single shared test.
