# Phase 09 Audit — ConstantPool typed-accessor kind validation

HEAD: 832aa6533
Path: kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala
Tests: kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala

## Verdicts

- tagName completeness: PASS. Covers all 18 case-class subtypes of CpEntry (Utf8Lazy + Utf8Decoded both map to "Utf8"; ClassRef, NameAndType, Fieldref, Methodref, InterfaceMethodref, CpInteger, CpFloat, CpLong, CpDouble, StringConst, MethodHandle, MethodType, Dynamic, InvokeDynamic, CpModule, CpPackage). Hole singleton lands on the `_ => "Hole"` fallthrough. No ConstDynamic subtype exists in CpEntry — commit message is wrong on that name but the code does not reference it.
- Hole guard semantics: PASS. `entry()` matches `case e: CpEntry` then `if e eq CpEntry.Hole`. Hole is a `case object` so `eq` is correct. `read()` actively stores `CpEntry.Hole` at slot idx+1 for CONSTANT_Long / CONSTANT_Double (lines 304, 310), so the guard fires on real-world inputs. The pre-existing `case null` arm now mislabels true-null slots as "Long/Double hole" while the new Hole arm uses the more precise "unused second slot" wording — minor cosmetic asymmetry, not a correctness bug.
- Error messages contain both directions: PASS. Every accessor's mismatch arm reads `s"Expected X at pool[$idx], found ${tagName(other)}"`. Greps confirm 9 accessor sites updated (utf8, classRef, integer, long_, float_, double_, moduleName, packageName, nameAndType, memberRef).
- Test coverage of both directions: PASS. B5-1 (utf8 at ClassRef → "Utf8" + "ClassRef") and B5-3 (classRef at Utf8 → "ClassRef" + "Utf8") pin both directions; B5-2 pins the happy path through ClassRef.nameIdx → Utf8.

## NOTE for Phase 10 prep

- B5-4 assertion is tolerant: `msg.toLowerCase.contains("hole") || ...contains("long/double")`. Both the null-slot message and the Hole-eq message satisfy it, so removing the `eq Hole` guard would not break this test. Hole vs null discrimination is not pinned. Phase 10 (or a B5 follow-up) should add a targeted test that constructs a pool whose Hole slot is the `eq Hole` path (Long/Double pool, asserting the precise "unused second slot" wording) so the guard is anchored against regression.
- Commit message lists 18 subtypes and erroneously names "ConstDynamic" (not present in CpEntry). Doc-only; no code impact.

## Overall

Ready. B5 closed; phase 10 may proceed.
