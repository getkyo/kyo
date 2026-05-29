# Phase 03a Decisions

Phase: Bound binary input primitives
Date: 2026-05-29
Status: COMPLETE

## D1: MalformedVarintException placement

Placed `MalformedVarintException` as a package-level class in `kyo.internal.tasty.binary` (in `Varint.scala`), before the `Varint` object and its scaladoc. This keeps the exception co-located with the code that throws it without nesting it inside the companion object, making it importable as `kyo.internal.tasty.binary.MalformedVarintException`.

## D2: SectionIndex.readSync uses (using AllowUnsafe) not import danger

Per the plan's AFTER block, `readSync` now takes `(using AllowUnsafe)` and the `import AllowUnsafe.embrace.danger` moved to the `read` method (the §839 case 3 boundary). This makes the unsafe requirement explicit in the private method signature.

## D3: NameUnpickler catch preserves AIOOBE message

The `read` method's catch for `ArrayIndexOutOfBoundsException` now uses `ex.getMessage` as the reason (falling back to the generic message if getMessage returns null). This makes the test assertion `reason.contains("ref=99")` work, matching the plan's Then clause for test 6.

## D4: SectionIndex catch preserves AIOOBE message

Same pattern as D3: the `read` method preserves the descriptive AIOOBE message from `readSync` as the MalformedSection reason, enabling tests to assert on specific content like "nameRef=99 out of range".

## D5: NameUnpickler bounds checks use checkRef helper

All 11 tagged-name cases (QUALIFIED, EXPANDED, EXPANDPREFIX, UNIQUE, DEFAULTGETTER, SUPERACCESSOR, INLINEACCESSOR, OBJECTCLASS, BODYRETAINER, SIGNED, TARGETSIGNED) use a private `checkRef` helper rather than inline guards. The helper throws `ArrayIndexOutOfBoundsException` with a message including the role name, ref value, and table size.

Enumerated buf-access sites covered:
- QUALIFIED: prefix, selector (2 checks)
- EXPANDED: prefix, selector (2 checks)
- EXPANDPREFIX: prefix, selector (2 checks)
- UNIQUE: separator, optional underlying (2 checks)
- DEFAULTGETTER: underlying (1 check)
- SUPERACCESSOR: underlying (1 check)
- INLINEACCESSOR: underlying (1 check)
- OBJECTCLASS: underlying (1 check)
- BODYRETAINER: underlying (1 check)
- SIGNED: original, resultSig, ps when ps>0 (3 check sites)
- TARGETSIGNED: original, target, resultSig, ps when ps>0 (4 check sites)

Total distinct checkRef call sites: 21.

## D6: Varint overflow guard placement

The overflow check fires at the TOP of the while-condition block, BEFORE reading the next byte. This means: for a 5-byte varint (readNat), the 6th iteration detects `bytes >= 5` and throws before consuming the 6th byte. For readLongNat, the 11th iteration detects `bytes >= 10`. This is consistent with the "cap at N bytes" semantics described in the plan.

## D7: Test file placement

Varint overflow tests (3 scenarios) added to existing `VarintTest` class inside `ByteViewTest.scala` rather than creating a separate `VarintTest.scala`. The steering rule says "New test files appear only for new source files"; `Varint.scala` is a modified source file and already has a `VarintTest` class in `ByteViewTest.scala`.

ByteView bounds tests (2 scenarios) added to existing `ByteViewTest` class.
NameUnpickler bounds test (1 scenario) added to existing `NameUnpicklerTest` class.
SectionIndex bounds tests (2 scenarios) in new `SectionIndexTest.scala` (new source file per plan test list).

## D8: Negative sectionLen encoding

Test 8 uses a 5-byte Nat `0x7f,0x7f,0x7f,0x7f,0xff` which decodes to -1 as Int via the normal readNat loop (accumulation overflows Int sign). The MalformedVarintException overflow guard does NOT fire because the 5th byte is terminating (0x80 SET), so only 5 bytes are consumed (bytes counter = 5 at exit, threshold is `>= 5` which fires at iteration 6 before reading). The negative check `if sectionLen < 0` then fires.

## Fix 03a-V1: VarintTest extracted to its own file

Phase 03a placed `class VarintTest` inside `ByteViewTest.scala`. The plan declared `VarintTest.scala` as its own file. Fixed by:
- Creating `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala` (210 lines) containing the full `VarintTest` class with all 11 tests.
- Rewriting `ByteViewTest.scala` to contain only the `ByteViewTest` class (no VarintTest, Varint, or MalformedVarintException imports).
- Decision D7 in this file is superseded: the separate file is now the canonical location.

## Fix 03a-V2: Tautological assertion replaced with position check

The assertion `assert(result >= 0L || result < 0L)` on the "readLongNat accepts exactly 10-byte encoding without throwing" test was a tautology (any Long satisfies it). Replaced with `assert(view.position == 10)`, which verifies that all 10 bytes of the encoding were consumed, confirming the decoder completed the full 10-byte path without throwing and advanced the cursor correctly.
