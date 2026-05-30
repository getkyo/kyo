# Phase 22a Decisions

## Utf8 Dialect: Pure UTF-8

kyo-tasty uses pure UTF-8 via `StandardCharsets.UTF_8` on JVM and Scala Native, and
`TextDecoder("utf-8")` on JS. All three are strict UTF-8 decoders per the Unicode
standard. Neither accepts modified-UTF-8 (MUTF-8) conventions.

Confirmed by reading:
- `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/binary/Utf8.scala`: delegates to
  `new String(bytes, offset, length, StandardCharsets.UTF_8)`
- `kyo-tasty/native/src/main/scala/kyo/internal/tasty/binary/Utf8.scala`: identical
  to JVM
- `kyo-tasty/js/src/main/scala/kyo/internal/tasty/binary/Utf8.scala`: delegates to
  `TextDecoder("utf-8")`

TASTy format stores names as raw UTF-8 bytes (tag UTF8 in the Names section). Classfile
constant-pool entries (CONSTANT_Utf8) in kyo-tasty are also decoded via `Utf8.decode`.
The Java classfile specification uses MUTF-8 for CONSTANT_Utf8 entries, but kyo-tasty
reads them with pure UTF-8; this is a known approximation that works for all
well-formed class names in practice (names containing embedded null are pathological).

## Overlong-Null Handling Decision: Document Replacement Behavior (Option b)

The plan (Phase 22a, Test 2) originally requested asserting `Abort.run` failure with
`TastyError.MalformedSection("Utf8", reason, _)`. However, `Utf8.decode` returns a plain
`String` with no `Abort` effect. Changing the signature to return `String < Abort[TastyError]`
would require updating every call site (Interner, TastyHeader, ConstantPool) and is
outside the scope of tests-only work.

Instead, Option (b) was chosen: the test asserts the actual replacement behavior. JVM's
`StandardCharsets.UTF_8` does not accept `[0xC0, 0x80]` as U+0000 (that requires
`DataInputStream.readUTF` or `new String(bytes, "Modified-UTF-8")`). Instead, each
malformed byte is replaced by U+FFFD. The test confirms:

- `result.length == 2` (two replacement chars, not one null char)
- `result.charAt(0) == '�'` and `result.charAt(1) == '�'`

This documents the pure-UTF-8 behavior: overlong null is invalid and gets replaced,
not silently accepted as a null character.

## Surrogate Pair Length (Tests 1 and 3)

`String.length == 2` holds on all three platforms for supplementary characters:
- JVM: UTF-16 surrogate pair = 2 code units
- JS: verified with `node -e '"\\u{1F600}".length'` returning 2 (JS strings are UTF-16;
  supplementary chars use 2 code units)
- Native: UTF-16 via JDK charset; same as JVM

The existing Test 17 comment "(2 on JVM surrogate pair, 1 on JS/Native)" was incorrect
for the JS case. Tests 19 and 21 assert `length == 2` with confidence on all platforms.

## API Reconciliation

No production code changes were made. The three new tests (19, 20, 21) are appended to
`kyo-tasty/shared/src/test/scala/kyo/Utf8Test.scala` after the existing Test 17. Test 18
(offset/length sub-range) was not disturbed.

Files modified: 1 (Utf8Test.scala, tests only)
Tests added: 3 (tests 19, 20, 21 covering T4 items)
