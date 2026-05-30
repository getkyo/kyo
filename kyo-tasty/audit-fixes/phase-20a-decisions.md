# Phase 20a Decisions

## Error-shape pattern

The Native `InflateHook.inflate` uses `Sync.defer` with an inner `try`/`catch` block, mirroring the Phase 17 Annotation.args rewrite in Tasty.scala. The typed local variable `val result: Array[Byte] < Abort[TastyError]` captures either the successful `Array[Byte]` (from `out.toByteArray()`) or a `Abort.fail(...)` expression on caught exceptions. The variable is then returned at the end of the `Sync.defer` block. This avoids `Abort.run[Throwable]`+`.map` (the older JVM style) while staying compliant with `no-either` and `no-casts` rules.

Two exception types are caught:
- `java.util.zip.ZipException` maps to `TastyError.MalformedSection("Scala2Inflate", ...)` (corrupt ZLIB stream).
- `java.io.IOException` maps to `TastyError.CorruptedFile("Scala2Inflate", 0L, ...)` (I/O error).

## Native javalib confirmation

`java.util.zip.InflaterInputStream` is available in the scala-native javalib (Q-002 from the plan). Native test compilation and execution confirmed: `kyo-tastyNative/Test/compile` passes and `testOnly kyo.InflateHookNativeTest` runs and passes (1 test, 0 failures).

## Test placement strategy

Two files:

1. `kyo-tasty/shared/src/test/scala/kyo/InflateHookTest.scala`: tagged `jvmOnly`. The `Test.scala` base class only defines `jvmOnly` and `jsOnly` tags; there is no `nativeOnly` tag. Tagging `jvmOnly` means the test is ignored on Native when run via the shared test suite, but the code compiles on all platforms.

2. `kyo-tasty/native/src/test/scala/kyo/InflateHookNativeTest.scala`: Native-specific test, no tag. Uses the same hardcoded ZLIB bytes and expected output as the shared test. Runs unconditionally on Native via `testOnly kyo.InflateHookNativeTest`.

Gap: to consolidate, a future change could add `nativeOnly` (i.e., `object nativeOnly extends Tag(runWhen(Platform.isNative))`) to `Test.scala` and replace the two separate tests with a single shared test tagged appropriately. Deferred as out of scope for this phase.

## ZLIB golden bytes

The ZLIB envelope for "hello kyo" (9 bytes, RFC 1950 framed) was generated with `java.util.zip.DeflaterOutputStream` and inlined as 17 hardcoded bytes:
```
0x78 0x9c 0xcb 0x48 0xcd 0xc9 0xc9 0x57 0xc8 0xae 0xcc 0x07 0x00 0x11 0xa2 0x03 0x88
```
