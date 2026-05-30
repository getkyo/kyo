# Phase 23a Decisions

## Test placement

JsFileSourceTest: placed in `kyo-tasty/js/src/test/scala/kyo/JsFileSourceTest.scala`.
Reason: the test imports `scala.scalajs.js` and `scala.scalajs.js.typedarray.Int8Array` to write
a temp file via Node.js `fs.writeFileSync`. These types are not available in shared/. Per the
`feedback_all_platforms_all_tests` rule, JS-specific types force placement in `js/src/test/`.

Utf8Test and InflateHookTest additions: placed in existing
`kyo-tasty/shared/src/test/scala/kyo/Utf8Test.scala` and `InflateHookTest.scala` with `taggedAs
jsOnly`. The parity assertions use only `Array[Byte]` and `String`, which compile on all
platforms. The `jsOnly` tag causes the tests to be ignored on JVM and Native.

## JsFileSource API discovered

`JsFileSource.read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError])` is the
only read method. There is no `read(path, offset, length)` overload. The plan description
"JsFileSource.read(path, 0, 50)" refers to intent (read a portion), but the implementation
reads the full file. The test was adapted to write a 100-byte file and verify all 100 bytes are
returned.

`JsFileSource.write` exists for writing bytes via a `js.typedarray.Int8Array` copy.

The `isNode` guard means all `read` / `write` calls require a Node.js environment. The test
relies on Node.js being present when JS tests run (confirmed by the test suite passing).

## Summary of tests added

- `JsFileSourceTest` (new, js/src/test): two tests covering happy-path 100-byte read and
  missing-path FileNotFound failure.
- `Utf8Test` (extended, shared/src/test): one `jsOnly` test decoding "hello world" via
  TextDecoder.
- `InflateHookTest` (extended, shared/src/test): one `jsOnly` test verifying JS InflateHook
  output is byte-equal to direct PortableInflate.inflate. Pins INV-024.
