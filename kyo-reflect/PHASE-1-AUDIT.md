# Phase 1 Audit (commit debd96e17)

Auditor: SLOT-B. Audit performed against committed HEAD only (not dirty tree).

## Test count

- Plan says: 24 tests across 4 test classes (ByteViewTest, VarintTest, Utf8Test, TastyHeaderTest)
- Implemented: 27 across 4 classes
  - ByteViewTest: 6 (`it`-blocks)
  - VarintTest: 8 (`it`-blocks)
  - Utf8Test: 4 (`it`-blocks)
  - TastyHeaderTest: 9 (`it`-blocks)
  - Total: 6 + 8 + 4 + 9 = 27. Matches commit message.

Note: plan called for 7 + 8 + 4 + 6 = 25 cases in its enumeration (count anomaly: plan says 24 but lists 24 numbered items; ByteView=6, Varint=8, Utf8=4, TastyHeader=6 = 24). Implementation adds 3 supplemental tests: VarintTest gained 0 extra, TastyHeaderTest gained 3 extra (truncated header, UUID formatting, tooling-version-string decode). All extras are useful and not redundant.

### Per-leaf list (the 24 plan tests)

ByteViewTest:
- Leaf 1 (peekByte at offset, no advance): PRESENT_STRICT
- Leaf 2 (readByte advances by 1, correct byte): PRESENT_STRICT
- Leaf 3 (readByte at end produces ArrayIndexOutOfBoundsException): PRESENT_STRICT
- Leaf 4 (subView shares array, correct bounds): PRESENT_STRICT (uses `eq bytes` plus start/end/position checks)
- Leaf 5 (goto sets position to addr): PRESENT_STRICT
- Leaf 6 (remaining == end - position): PRESENT_STRICT

VarintTest:
- Leaf 7 (readNat decodes 0 as `Array(0)`): PRESENT_WEAKENED — plan says encoded as `Array(0)`, but in dotty big-endian base-128 the encoding of 0 is `Array(0x80)` (the stop bit). The implementation file uses the correct dotty semantics (encoding 0 as `0x80`), and the test correctly uses `Array(0x80.toByte)`. The plan's "Array(0)" example was wrong; impl is correct. Flag as NOTE.
- Leaf 8 (readNat decodes 127 as `Array(127)`): PRESENT_WEAKENED — same issue: plan says `Array(127)` but the correct dotty encoding is `Array(0xFF)` (stop bit set on 127). Test uses `0xFF`, matching dotty. NOTE.
- Leaf 9 (readNat decodes 128 as `Array(0x80, 0x01)`): PRESENT_WEAKENED — plan example was little-endian-LEB128 style. Dotty big-endian: `Array(0x01, 0x80)`. Test uses `Array(0x01, 0x80)`, matching dotty. NOTE.
- Leaf 10 (readNat decodes 16383 / two-byte max): PRESENT_STRICT
- Leaf 11 (readNat decodes Int.MaxValue, 5-byte): PRESENT_STRICT
- Leaf 12 (readInt decodes -1): PRESENT_STRICT — uses single byte `0xFF` per dotty semantics
- Leaf 13 (readInt decodes Int.MinValue): PRESENT_STRICT — uses `0x78, 0x00, 0x00, 0x00, 0x80` (verified algebraically below)
- Leaf 14 (readLongNat decodes Long.MaxValue, 9-byte): PRESENT_STRICT

Utf8Test:
- Leaf 15 (ASCII decode): PRESENT_STRICT
- Leaf 16 (3-byte UTF-8 for U+00E9): PRESENT_WEAKENED — plan says U+00E9 is a 3-byte sequence; in fact U+00E9 is encoded in 2 bytes (`0xC3 0xA9`). Test correctly uses 2 bytes. Test name even says "2-byte". NOTE on plan.
- Leaf 17 (4-byte UTF-8 for U+1F600): PRESENT_STRICT — guards platform-difference via `result.codePointAt(0) == 0x1F600` rather than `length`, correctly handling JVM (surrogate pair, length=2) vs JS/Native (length=1)
- Leaf 18 (offset/length sub-range only): PRESENT_STRICT

TastyHeaderTest:
- Leaf 19 (valid 28.8.0 succeeds): PRESENT_STRICT
- Leaf 20 (wrong magic 0xDEADBEEF → CorruptedFile): PRESENT_STRICT
- Leaf 21 (major=99 → UnsupportedVersion): PRESENT_STRICT
- Leaf 22 (major=28, minor=7, exp=0 → success): PRESENT_STRICT (backward compatible)
- Leaf 23 (major=28, minor=9, exp=0 → ???): PRESENT_DEVIATION — plan says "succeeds (minor <= supported)", but the verbatim dotty rule (`fileMinor <= compilerMinor` for the backward branch, and equality for the exact branch) makes minor=9 > supported=8 FORWARD-incompatible → FAIL. The implementation correctly asserts Failure(UnsupportedVersion). Plan was wrong about the policy direction. **Critical note: the implementation matches dotty semantics; the plan was incorrect. Test name explicitly says "forward incompatible". This is a plan bug that the implementer correctly caught.**
- Leaf 24 (experimental=1 → UnsupportedVersion): PRESENT_STRICT

Extra tests beyond the 24:
- TastyHeader truncated header → MalformedSection (good defensive coverage matching the `catch ArrayIndexOutOfBoundsException` path)
- TastyHeader UUID hex formatting
- TastyHeader tooling-version UTF-8 decode

## CONTRIBUTING.md violations

- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala:67`: uses mutable `var cursor` and the trait exposes `def goto(addr: Int): Unit` (side-effecting Unit return). CONTRIBUTING line 402 says "no mutable `var`s, no `while` loops … Mutable state is acceptable only in performance-critical internals (atomics, bit-packing) where it's encapsulated behind a pure interface." This is acceptable per the carve-out (binary parsing hot path is precisely that case) and is acknowledged by the ByteView scaladoc. NOTE — not a violation; document the rationale in scaladoc more explicitly if desired.
- `Varint.scala`: uses `while`/`var`. Same carve-out applies (binary decoding hot path). NOTE.
- `TastyHeader.readBytes` uses `var i`, `var j`, `while` loops, and `try/catch ArrayIndexOutOfBoundsException` for control flow. CONTRIBUTING line 402: "no throw/catch for control flow". This catches AIOOBE to translate to MalformedSection. Mitigation: comment on lines 47-52 explicitly justifies this ("parsers are only called when they know bytes remain … but tests may supply truncated buffers"). The intent is reasonable but the convention forbids exception-based control flow. WARN.
- `TastyHeader.readBytes` uses early-`return` from a `Unit < Abort[...]` context: lines 67-72 and 95-100 do `return Abort.fail(...)`. Using `return` is generally discouraged in Scala (and not idiomatic in kyo), though not explicitly banned in CONTRIBUTING. NOTE.
- `kyo-reflect/shared/src/test/scala/kyo/ByteViewTest.scala:44`: `view.subView(1, 4).asInstanceOf[ByteView.Heap]`. CONTRIBUTING line 415: "Avoid `asInstanceOf` and `@unchecked` — acceptable only inside opaque type boundaries or kernel internals." This is in a test, narrowing from `ByteView` to inspect the `Heap`-specific `start`/`end`/`bytes` fields. The user feedback `feedback_no_casts` says "never use asInstanceOf, fix the types instead". The fix is to either expose `start`/`end`/`bytes` on the trait, return `Heap` from `subView`, or have the test verify behavioural invariants (`peekByte` at the new position) rather than internal field state. WARN.
- `kyo-reflect/js/src/main/scala/kyo/internal/reflect/binary/Utf8.scala:24`: `decoder.decode(arr).asInstanceOf[String]`. This is the canonical Scala.js facade pattern (Dynamic.decode returns `js.Any`); rewriting via a typed `js.Dynamic` facade would be cleaner. NOTE — acceptable for now; track as a future cleanup.
- No `@nowarn`, no `Frame.internal`, no `AllowUnsafe`, no `Sync.Unsafe.defer` usages anywhere in produced Phase 1 sources. Good.
- Scaladocs are present and informative on all main types and methods. Conforms to CONTRIBUTING § Documentation.
- All produced files end with `end ObjectName` / `end ClassName` per file template convention.

## Unsafe markers

```
asInstanceOf:
  kyo-reflect/js/src/main/scala/kyo/internal/reflect/binary/Utf8.scala:24
  kyo-reflect/shared/src/test/scala/kyo/ByteViewTest.scala:44
Frame.internal:    none
AllowUnsafe:       none
Sync.Unsafe.defer: none
```

Justification:
- Utf8.scala JS impl `asInstanceOf[String]`: needed for Scala.js `js.Dynamic` facade. Could be replaced by a typed `@js.native` `JSGlobal("TextDecoder")` facade. Future cleanup, not blocking.
- ByteViewTest `asInstanceOf[ByteView.Heap]`: violates project policy. Should be removed by exposing the inspected fields on the trait OR by changing `subView` return type to `Heap` (since `Mapped` cannot be sub-viewed in Phase 1 anyway).

## Cross-platform consistency

- Files in `shared/`: `ByteView.scala`, `Varint.scala`, `Utf8.scala` (abstract `Utf8Impl`), `TastyFormat.scala`, `TastyHeader.scala`. Correct.
- Platform-specific: `jvm/.../Utf8.scala`, `js/.../Utf8.scala`, `native/.../Utf8.scala` each defining `object Utf8 extends Utf8Impl`. Correct split.
- The shared file declares only an `abstract private[binary] class Utf8Impl`; each platform exposes the public `object Utf8`. This works because there is no shared `Utf8` symbol — each platform's `object Utf8` is independent and is the only one visible to that platform's compilation. Consumers (TastyHeader) reference unqualified `Utf8` and the cross-platform build picks the right one. Correct pattern.
- All shared source compiles with no platform-specific imports. Confirmed.

## Naming convention compliance

- Files match plan's "Files to produce":
  - `kyo/internal/reflect/binary/ByteView.scala` ✓
  - `kyo/internal/reflect/binary/Varint.scala` ✓
  - `kyo/internal/reflect/binary/Utf8.scala` (shared + 3 platform) ✓
  - `kyo/internal/reflect/tasty/TastyFormat.scala` ✓
  - `kyo/internal/reflect/tasty/TastyHeader.scala` ✓
- Internal packages: all under `kyo.internal.reflect.{binary,tasty}.*`. Correct.
- Public API additions: zero. As required by plan.
- Test files: `kyo.ByteViewTest`, `kyo.VarintTest` (added to ByteViewTest.scala — plan said separate classes; both classes do live in `ByteViewTest.scala`. Plan implied two files; impl combined into one .scala file with two classes. Functionally identical, scalatest discovers both). NOTE.

## Steering deviation

`git diff --name-only debd96e17~1 debd96e17` lists 16 files. Excluding tracked-progress markdown (PHASE-0.5-AUDIT.md, PHASE-1-INFLIGHT-REVIEW-1.md, PHASE-2-PREP.md, PROGRESS.md, STEERING.md), the code/test set is exactly:

```
kyo-reflect/js/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
kyo-reflect/native/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Varint.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyFormat.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyHeader.scala
kyo-reflect/shared/src/test/scala/kyo/ByteViewTest.scala
kyo-reflect/shared/src/test/scala/kyo/TastyHeaderTest.scala
kyo-reflect/shared/src/test/scala/kyo/Utf8Test.scala
```

This matches the plan's "Files to produce" exactly (no out-of-scope additions, no missing files). PROGRESS.md and STEERING.md updates are administrative. PHASE-2-PREP.md is forward-prep, mildly out of scope for Phase 1 but does not affect Phase 1 deliverables.

NOTE: `PHASE-1-INFLIGHT-REVIEW-1.md` and `PHASE-0.5-AUDIT.md` are workflow artifacts captured in the same commit. Not deliverables. No impact.

## Anti-flakiness

- All tests use byte arrays constructed inline. No file I/O, no `Clock.now`, no network, no random, no thread sleeps. ✓
- `Utf8Test` test 17 uses `result.codePointAt(0) == 0x1F600` instead of `result.length` to handle JVM (surrogate pair, length=2) vs JS/Native (single code point, length=1). Correct cross-platform handling. ✓
- All TastyHeader tests use a private `headerBytes(...)` builder that encodes everything inline; no fixture files. ✓
- No timing dependencies. ✓

## LEB128 verification

- `Varint.readInt` uses dotty 2's-complement sign-extension semantics (NOT zigzag): VERIFIED. Implementation at `Varint.scala:65-72` reads first byte `b`, computes `((b << 1).toByte >> 1).toLong` (sign-extends bit 6 of the original byte into the Long), then loops while continuation bit `(b & 0x80) == 0` shifting accumulator left by 7 and OR-ing low 7 bits. Verbatim from dotty's `TastyReader.readLongInt`.
- Test for `-1` uses single byte `0xFF`: VERIFIED at `ByteViewTest.scala:125-128`. Walkthrough: `b=0xFF`; `(0xFF << 1) = 0x1FE`; `0x1FE.toByte = 0xFE.toByte = -2`; `-2 >> 1 = -1` (arithmetic shift); x = -1L; `(0xFF & 0x80) = 0x80 != 0` so loop body skipped; return `(-1L).toInt = -1`. Correct.
- Test for `Int.MinValue` uses 5-byte sequence `0x78, 0x00, 0x00, 0x00, 0x80`: VERIFIED at `ByteViewTest.scala:194-195`. Walkthrough:
  - b=0x78: `(0x78<<1)=0xF0`; `0xF0.toByte = -16`; `-16 >> 1 = -8`; x = -8L. (0x78 & 0x80) = 0, enter loop.
  - b=0x00: x = (-8L << 7) | 0 = -1024L. Continue.
  - b=0x00: x = (-1024L << 7) | 0 = -131072L. Continue.
  - b=0x00: x = (-131072L << 7) | 0 = -16777216L. Continue.
  - b=0x80: x = (-16777216L << 7) | (0x80 & 0x7f) = -2147483648L | 0 = -2147483648L. (0x80 & 0x80) != 0, exit.
  - return `(-2147483648L).toInt = -2147483648 = Int.MinValue`. Correct.
- Code comment cites dotty source: VERIFIED. `Varint.scala:10` cites `dotty TastyReader.scala readLongNat / readLongInt` and `Varint.scala:17` ("dotty/tools/tasty/TastyReader.scala, readLongNat (lines ~68-77), readLongInt (lines ~81-89)"). `TastyFormat.scala:5` cites the source. `TastyHeader.scala` headers cite `TastyHeaderUnpickler.readFullHeader` and `TastyFormat.isVersionCompatible`. All correctly attributed.

## Findings categorization

### BLOCKER (must fix before Phase 3 launches)

None.

### WARN (should fix in cleanup, not gating)

1. **W1 — TastyHeader uses try/catch for control flow.** `TastyHeader.read` at `TastyHeader.scala:46-50` catches `ArrayIndexOutOfBoundsException` to convert to `ReflectError.MalformedSection`. CONTRIBUTING § Scala Conventions forbids "throw/catch for control flow". Mitigation: replace with explicit `view.remaining` bounds checks before each read, or define a `view.tryReadByte: Maybe[Byte]` primitive. Defer to Phase 2 prep window since this is the only TASTy entry point we ship today and it has a defensive justification, but flag it for refactor before Phase 3.
2. **W2 — `ByteViewTest` uses `asInstanceOf[ByteView.Heap]`.** Violates `feedback_no_casts`. Easy fix: expose `start`, `end`, `bytes` on the `ByteView` trait (already true via abstract `position`/`remaining`; just add the rest), OR narrow `subView` return type to `ByteView.Heap`. Should be cleaned up before Phase 2 closes; do not let casts propagate.
3. **W3 — early `return` from a `< Abort[...]` computation.** `TastyHeader.readBytes` (lines 67-72, 95-100) uses `return Abort.fail(...)`. Works because `readBytes`' return type is `Data < Abort[ReflectError]`, but a continuation-style `if !compatible then Abort.fail(...) else ...` would be more idiomatic in kyo and consistent with how Abort is composed elsewhere.

### NOTE (cosmetic / future improvement)

1. **N1 — JS Utf8 `asInstanceOf[String]`.** Scala.js facade. Could use typed `@js.native @JSGlobal("TextDecoder") class TextDecoder` instead. Low value.
2. **N2 — Plan's Varint examples (`Array(0)`, `Array(127)`, `Array(0x80, 0x01)` for 128) were little-endian LEB128, not dotty big-endian.** Implementation correctly used dotty encoding. The Phase 0.5/Phase 1 prep agents already caught this. Future plans should be reviewed for this LEB128-direction confusion.
3. **N3 — Plan leaf 23 (minor=9 succeeds) was incorrect.** Dotty's actual rule makes minor=9 forward-incompatible with supportedMinor=8. Implementation correctly inverts to assert UnsupportedVersion. Plan needs an erratum.
4. **N4 — Plan leaf 16 said "3-byte UTF-8 (U+00E9)".** U+00E9 is actually 2-byte UTF-8. Implementation correctly uses 2 bytes. Plan needs an erratum.
5. **N5 — VarintTest is collocated in `ByteViewTest.scala`.** Plan implied separate `.scala` files. Functionally fine; ScalaTest discovers both classes. Consider splitting if VarintTest grows.
6. **N6 — Mapped is a `sealed abstract class` stub with no abstract members.** Will be fleshed out in Phase 7. Fine.
7. **N7 — TastyHeader returns a `String` UUID built via `f"…%016x…%016x"`.** For TASTy compatibility this is fine; if downstream consumers want `java.util.UUID`, that conversion can happen in Phase 2+.
8. **N8 — `TastyFormat.MagicBytes: Array[Int]`.** Array is mutable; callers could mutate this shared array. Prefer `IArray[Int]` or `Span[Int]` (per `feedback_prefer_span`). NOTE only because no current caller mutates it.

## Recommendation: PROCEED

Phase 1 is structurally complete: all 24 plan leaves are present (3 weakened from incorrect plan examples but correctly implemented against dotty source, 1 deviation that correctly fixed a plan bug), plus 3 supplemental tests. Files match the plan exactly. Cross-platform split is correct. LEB128 decoding (the binary heart of Phase 1) is verified algebraically and verbatim from dotty. No BLOCKERs. 3 WARN items are cleanup, not gating; they should be addressed before Phase 3 (when more parsers compose on top of ByteView and the try/catch and asInstanceOf habits would propagate).

PROCEED to Phase 2.
