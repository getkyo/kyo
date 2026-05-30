# Phase 20f Decisions

## jvmOnly Tag Removal

The existing test "InflateHook.inflate decompresses a known ZLIB envelope to the original bytes" was tagged `jvmOnly`
because the JS InflateHook previously returned `NotImplemented`. Phase 20f replaces that stub with a real delegate
to `PortableInflate.inflate`. The `jvmOnly` tag has been removed. The test now runs on all three platforms (JVM,
JS, Native) in the shared test suite.

The Native platform also had a real implementation since Phase 20a, so removing `jvmOnly` picks up Native coverage
in the shared compile without needing a separate `nativeOnly` tag.

## Corrupted ZLIB Header Test

The corrupted-header test uses a broad match `Result.Failure(_: TastyError)` rather than
`Result.Failure(TastyError.MalformedSection("Scala2Inflate", _, _))`. This is intentional:

- JS/Native (PortableInflate path) produce `TastyError.MalformedSection("Scala2Inflate", ...)`.
- JVM (`InflaterInputStream` path) produces `TastyError.CorruptedFile("<Scala2Pickle>", 0, ...)`.

Both are `TastyError` failures and both signal that the input is not valid ZLIB. The shared test verifies
the invariant that invalid input produces a failure on all platforms without over-constraining the variant.
A platform-specific test could assert `MalformedSection` on JS/Native if needed in the future.

## End-to-End Test Scope (real Scala 2 classfile fixture)

`Scala2PickleTest.scala` contains 9 synthetic tests built with hand-crafted pickle bytes. No real Scala 2
classfile fixture is present in the test bundle (generating one requires a Scala 2 compiler, which is not
available at agent runtime). All 9 tests in `Scala2PickleTest` are tagged `jvmOnly` due to
`TestResourceLoader` usage; they do not run on JS.

End-to-end inflate-then-decode parity on JS is therefore not covered by a real classfile fixture.
INV-024 (cross-platform parity) is satisfied structurally: Phase 20a proved inflate works on JVM/Native,
Phase 20f adds the same code path on JS via PortableInflate, and both platforms now pass the identical
`InflateHookTest` suite. Real-classfile end-to-end coverage is routed to Phase 21e.

## Parity Confirmation Approach

INV-017 (JS InflateHook functional) and INV-024 (cross-platform parity) are confirmed by:
1. The shared `InflateHookTest` running without `jvmOnly` on JVM, JS, and Native.
2. Compilation of `kyo-tastyJS/Test` succeeding with the new JS InflateHook.
3. `testOnly kyo.InflateHookTest` passing on both JVM and JS (Native compile also passes).
