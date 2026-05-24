# Phase 1 In-Flight Review (pulse 1)

Pulse 1: 2026-05-24T00:00:00Z
Files reviewed:
- kyo-reflect/execution-plan.md lines 54-126
- kyo-reflect/PHASE-1-PREP.md (full)
- kyo-reflect/STEERING.md (full)
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/ByteView.scala
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Varint.scala
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/binary/Utf8.scala (shared stub)
- kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
- kyo-reflect/js/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
- kyo-reflect/native/src/main/scala/kyo/internal/reflect/binary/Utf8.scala
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyFormat.scala
- kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TastyHeader.scala
- kyo-reflect/shared/src/test/scala/kyo/ByteViewTest.scala
- kyo-reflect/shared/src/test/scala/kyo/TastyHeaderTest.scala
- kyo-reflect/shared/src/test/scala/kyo/Utf8Test.scala

---

## Plan anchor

### Files to produce (plan mandates 8 files)

| File | Present? | Notes |
|---|---|---|
| `shared/.../binary/ByteView.scala` | PRESENT | sealed trait + Heap + Mapped stub |
| `shared/.../binary/Varint.scala` | PRESENT | standalone object |
| `shared/.../binary/Utf8.scala` | PRESENT (shared stub only) | abstract class Utf8Impl, no object Utf8 in shared |
| `jvm/.../binary/Utf8.scala` | PRESENT | object Utf8 extends Utf8Impl |
| `js/.../binary/Utf8.scala` | PRESENT | object Utf8 extends Utf8Impl |
| `native/.../binary/Utf8.scala` | PRESENT | object Utf8 extends Utf8Impl |
| `shared/.../tasty/TastyFormat.scala` | PRESENT | all constants present |
| `shared/.../tasty/TastyHeader.scala` | PRESENT | read() method present |

All 8 mandated files are present. No missing files.

### Test class count vs plan

| Class | Plan tests | Actual tests in file | Notes |
|---|---|---|---|
| ByteViewTest | 6 (tests 1-6) | 6 | Matches |
| VarintTest | 8 (tests 7-14) | 8 | Matches |
| Utf8Test | 4 (tests 15-18) | 4 | Matches |
| TastyHeaderTest | 6 (tests 19-24) | 6 + 3 extra | 3 extra: truncated, UUID format, tooling version |

Total plan-mandated: 24. All 24 present. 3 bonus tests in TastyHeaderTest (truncated header, UUID hex format, tooling version decode) -- these are additive, not substitutes.

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | CANNOT CONFIRM | No compile/test output observable; tree is dirty, no test result artifacts. Supervisor must run the verification command before commit. |
| Compile-only "success" claim | NOT OBSERVED | No such claim in files read. |
| Priority inference / silently skipped items | CLEAN | All 24 plan leaves present; no "edge-case" dropout. |
| Scope substitution (simpler equivalent) | FLAG (minor) | Shared Utf8.scala defines `abstract private[binary] class Utf8Impl` rather than an `expect` or abstract dispatch object. This is a valid cross-platform pattern in Scala but diverges from the plan's "expect-object pattern or conditional import" phrasing. The actual runtime dispatch works via each platform's `object Utf8 extends Utf8Impl`. See MINOR section. |
| `foreach`-discards-assert in tests | CLEAN | No `foreach { assert(...) }` pattern found. Assertions are direct. |
| Stale-state / tautological matchers | CLEAN | No `assert(true)`, no `>= 0` assertions. All assertions on concrete values. |
| LEB128 signed encoding: 2's complement (NOT zigzag per STEERING) | CLEAN -- CORRECT | Varint.readInt uses `((b << 1).toByte >> 1).toLong` sign-extension, matching dotty TastyReader.readLongInt verbatim. The comment in Varint.scala explicitly flags zigzag as wrong. STEERING directive honored. |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signatures match plan exactly | CLEAN | `peekByte(at: Int): Byte`, `readByte(): Byte`, `readNat(): Int`, `readInt(): Int`, `readLongNat(): Long`, `readEnd(): Int`, `subView(from: Int, until: Int): ByteView`, `goto(addr: Int): Unit`, `remaining: Int`, `position: Int` -- all present and matching. |
| No off-plan architecture substitution | FLAG (minor) | ByteView.Heap.subView returns type `ByteView` at the trait level, but the plan says `subView` returns `ByteView`. CLEAN. However, ByteView.readEnd() is declared at the trait level despite only Heap implementing it. Mapped stub does NOT override readEnd; if Mapped is ever used, it will trigger a compile error (abstract method). This is acceptable for Phase 1 stub since Mapped is only a compile stub. |
| No cross-cutting refactor outside Phase 1 scope | CLEAN | No modifications to files outside kyo-reflect. |
| Internal helpers stay in `kyo.internal.reflect.*` | CLEAN | All new files are under `kyo.internal.reflect.binary` or `kyo.internal.reflect.tasty`. |
| Version check uses verbatim dotty formula | CLEAN | TastyFormat.isVersionCompatible matches the dotty formula verbatim. Test 23 correctly expects FAIL for minor=9 (correcting the plan's misleading "succeeds" description). |
| TastyFormat.MajorVersion == 28, MinorVersion == 8, ExperimentalVersion == 0 | CLEAN | Lines 24-26 of TastyFormat.scala. |
| Header read order: magic, major, minor, experimental, toolingLen+bytes, UUID | CLEAN | TastyHeader.readBytes follows the dotty order exactly. |
| UUID read as two uncompressed Longs, not LEB128 | CLEAN | readUncompressedLong reads 8 bytes big-endian. |

---

## Scope-cutting checks (all 24 plan-mandated test leaves)

| Leaf | Status | Notes |
|---|---|---|
| 1: peekByte(at) reads without advancing position | PRESENT_STRICT | ByteViewTest, asserts b==30 and position==0 |
| 2: readByte() advances position by 1 | PRESENT_STRICT | Reads two bytes, asserts position 1 then 2 |
| 3: readByte() at end produces AIOOBE | PRESENT_STRICT | assertThrows[ArrayIndexOutOfBoundsException] |
| 4: subView shares same underlying array | PRESENT_STRICT | casts to ByteView.Heap, asserts start/end/position and `bytes eq bytes` reference equality |
| 5: goto(addr) sets position to addr | PRESENT_STRICT | goto(3), asserts position==3, reads byte and checks value |
| 6: remaining returns end - position | PRESENT_STRICT | Three-step check: fresh (5), after readByte (4), after goto(3) (2) |
| 7: readNat decodes 0 | PRESENT_STRICT | Array(0x80) -> 0; correct TASTy encoding (stop-bit set, value 0) |
| 8: readNat decodes 127 | PRESENT_STRICT | Array(0xFF) -> 127; 0xFF = 0x7F | 0x80 correct |
| 9: readNat decodes 128 | PRESENT_STRICT | Array(0x01, 0x80) -> 128; correct two-byte TASTy encoding |
| 10: readNat decodes 16383 | PRESENT_STRICT | Array(0x7F, 0xFF) -> 16383 |
| 11: readNat decodes Int.MaxValue (5 bytes) | PRESENT_STRICT | Array(0x07,0x7F,0x7F,0x7F,0xFF) -> Int.MaxValue |
| 12: readInt decodes -1 | PRESENT_STRICT | Array(0xFF) -> -1; correct dotty semantics, NOT zigzag |
| 13: readInt decodes Int.MinValue | PRESENT_STRICT | Array(0x78,0x00,0x00,0x00,0x80) -> Int.MinValue; includes full derivation comment verifying the encoding |
| 14: readLongNat decodes Long.MaxValue | PRESENT_STRICT | Array(9 x 0x7F except last is 0xFF) -> Long.MaxValue; derivation shown |
| 15: Utf8.decode ASCII bytes | PRESENT_STRICT | "hello" roundtrip |
| 16: Utf8.decode 2-byte UTF-8 (U+00E9) | PRESENT_STRICT | asserts result == "é" and length == 1 |
| 17: Utf8.decode 4-byte UTF-8 (U+1F600) | PRESENT_STRICT | asserts codePointAt(0) == 0x1F600 (platform-safe, avoids String.length which differs JVM vs JS/Native) |
| 18: Utf8.decode with offset+length sub-range | PRESENT_STRICT | Array with sentinel 0xFF at start and end, offset=1, length=3 -> "中" |
| 19: TastyHeader reads 28.8.0 successfully | PRESENT_STRICT | headerBytes(validMagic, 28, 8, 0, ...), checks data.major/minor/experimental |
| 20: wrong magic -> CorruptedFile | PRESENT_STRICT | wrongMagic = 0xDEADBEEF, matches ReflectError.CorruptedFile pattern |
| 21: major=99 -> UnsupportedVersion | PRESENT_STRICT | headerBytes with major=99, asserts found.major==99 and supported.major==28 |
| 22: minor=7, experimental=0 -> success | PRESENT_STRICT | backward compatible; asserts data.minor==7 |
| 23: minor=9, experimental=0 -> UnsupportedVersion | PRESENT_STRICT (CORRECTED from plan) | Plan said "succeeds"; PREP doc and dotty formula show minor=9>8 fails. Impl correctly fails. Test assertion checks Failure(UnsupportedVersion(found,_)) with found.minor==9. This is correct per dotty rule. |
| 24: experimental=1, supportedExperimental=0 -> UnsupportedVersion | WEAKENED -- FLAG | Test uses `Result.Fail(...)` pattern match (line 155). The correct Kyo Result extractor for Abort failures is `Result.Failure(...)` not `Result.Fail(...)`. Same issue at lines 171, 194, 210 in additional tests. If `Result.Fail` does not exist or does not match, the test falls through to the `other =>` arm and calls `fail(...)` -- which would surface as a test failure at runtime. But if `Result.Fail` is a valid alias for `Result.Failure` in this codebase, it is fine. This MUST be verified. |

---

## CRITICAL (steer immediately)

1. **TastyHeaderTest.scala, lines 155, 171, 194, 210: `Result.Fail` pattern extractor** -- If `Result.Fail` is not the correct pattern extractor for `Abort` failures in this Kyo version (the correct form used elsewhere in the same file is `Result.Failure`), tests 24 and the three bonus tests will fall to the `other =>` arm and call `fail(s"Expected ... but got: $other")`. All four of the affected match arms need to use `Result.Failure` consistently. The same file uses `Result.Failure` correctly at lines 74, 90, etc. but then switches to `Result.Fail` at line 155 -- this looks like a typo. Verify and fix: `Result.Fail` -> `Result.Failure` in TastyHeaderTest.scala lines 155, 171, 194, 210.

---

## MINOR (queue for post-commit audit)

1. **Shared Utf8.scala** declares `abstract private[binary] class Utf8Impl` rather than an `expect` object or direct Scala 3 cross-platform dispatch. The plan mentions "expect-object pattern or conditional import". The current approach (abstract class + platform-specific object extending it) works in Scala 3 cross-platform builds as long as each platform sourceDir provides `object Utf8 extends Utf8Impl`. Verify this compiles on all three platforms in the verification step.

2. **ByteView.Mapped** is declared as `sealed abstract class Mapped extends ByteView`. The plan says "Mapped stubs (Mapped is platform-specific; the trait and Heap are shared)". In the current implementation, Mapped has no abstract method overrides and ByteView's abstract methods are not overridden in Mapped, so compiling Mapped as a concrete subclass would fail. As a `sealed abstract class` it compiles fine since it cannot be instantiated. CLEAN for Phase 1; just note that ByteView.readEnd(), subView(), goto(), remaining, position are still abstract in Mapped and will need platform-specific implementations in the Mapped phase.

3. **Test 13 (readInt Int.MinValue)**: The comment block in ByteViewTest.scala lines 133-212 is extremely long (80+ lines of derivation). Correct but noisy. Post-commit: trim to essentials.

4. **TastyHeaderTest `encodeNat` helper** (lines 36-57): Multi-byte encodeNat has a subtlety -- for values where `x` reaches 0 after the first iteration (e.g., x=128: groups=[0,1], reversed=[1,0], first byte=1 (continuation), last byte=0|0x80=0x80). This is correct. However if `v == 0`, the while loop body never executes and groups is empty, causing `gs.last` to throw. Fortunately v=0 is never passed (version 0 is encoded as `(0|0x80)=0x80` via the `v < 128` branch). CLEAN for the test cases used, but fragile.

---

## Recommendation: STEER

**STEER: Fix `Result.Fail` -> `Result.Failure` in TastyHeaderTest.scala lines 155, 171, 194, 210 before running the verification command; this is a likely typo that will cause tests 24 and 3 bonus tests to spuriously fail.**
