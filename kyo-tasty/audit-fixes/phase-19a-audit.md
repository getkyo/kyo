# Phase 19a Audit

## Summary

PASS. All seven dimensions clear. One NOTE on test coverage depth (no minor=2 byte fixture) and one NOTE on a pre-existing scaladoc name mismatch that Phase 19a left untouched. No blockers or warnings.

## Findings

### 1. Minor=2 forward-compat - OK

Both `readBytes` (heap path) and `readMappedView` (mmap path) check only `fileMajor != SnapshotFormat.majorVersion` and hard-reject on mismatch. The `fileMinor` byte is read but never compared. Both `deserialize` and `deserializeMapped` build a `sectionMap` keyed by section name and only look up names they explicitly know. A minor=2 file lacks a TPARAMS_ entry; the reader finds no entry and proceeds normally. A minor=3 file read by an old minor=2 reader has an extra TPARAMS_ entry in the index that is simply never requested. INV-003 holds in both directions.

### 2. Section ordering correctness - OK

`TPARAMS_` is inserted at index 6, between `MEMBERS` (5) and `FILES` (7). The decisions doc confirms this is conventional: neither reader nor writer uses array-index-based section lookup. Both build a `HashMap[String, (Int, Int)]` at read time; `SnapshotWriter.serialize` constructs an explicit `Seq[(String, Array[Byte])]`. No consumer iterates by index. The semantic grouping (per-symbol structural data before file metadata) is coherent.

### 3. sectionTPARAMS constant value - OK

`"TPARAMS_"` is 8 characters, matching the zero-padded 8-byte section-name slot in the binary format. All other section names are also either exactly 8 bytes (`TYPESEXT`, `BODYBYTE`) or shorter with zero-padding applied by `writeSectionName`. The trailing underscore pads `TPARAMS` (7 chars) to 8. The naming pattern is consistent with the format specification (`sectionIndexEntrySize = 24`: 8-byte name + 8-byte offset + 8-byte length).

### 4. Test substance - NOTE (minor)

Tests 1-4 pin INV-023 (minorVersion == 3) and INV-003 (add-only, existing names retained, constant-array agreement). They are adequate for a format-constants phase. No test exercises actual round-trip loading of a minor=2 byte fixture to prove forward-compat dynamically. The decisions doc acknowledges this is deferred to Phase 19b's SnapshotReaderTest, which is appropriate because Phase 19a does not touch the reader logic. The gap is intentional and bounded.

### 5. Scaladoc update - NOTE (pre-existing)

Phase 19a correctly adds `` `TPARAMS_`: Type parameter records per symbol (added in minor=3). `` to the section-ID list. Two pre-existing mismatches survive untouched: the scaladoc says `` `TYPES_EXTRA` `` (the array uses `"TYPESEXT"`) and `` `BODY_BYTES` `` (the array uses `"BODYBYTE"`). These predate this phase. Phase 19a did not introduce them and is not required to fix them, but they represent documentation drift that should be resolved in a sweep pass.

### 6. Other constant references - OK

`SnapshotWriter.serialize` builds its sections list explicitly via named constants and does not include `sectionTPARAMS` (correct: the section is empty in Phase 19a; Phase 19b will add it). The writer's `sectionCount` is derived from `sections.length` dynamically, so no hardcoded count to update. No `maxMinorVersion` constant exists. No enum of sections. `SnapshotFormat.minorVersion` is the single authoritative version value; the writer reads it via `SnapshotFormat.minorVersion.toByte` at line 184 of SnapshotWriter.scala. No stale hardcoded value found.

### 7. Code quality - OK

No em-dashes, semicolons, `asInstanceOf`, or `Option`/`Some`/`None` usages in the new code. No default-parameter changes. New constant `sectionTPARAMS` aligns with existing constant formatting. Test file follows the established `Test` base class pattern.

## Recommendations

- In a future sweep pass, fix the two pre-existing scaladoc name mismatches: `TYPES_EXTRA` -> `TYPESEXT` and `BODY_BYTES` -> `BODYBYTE` to match the actual wire names.
- Phase 19b should add a SnapshotReaderTest case that constructs a synthetic minor=2 byte array (or relies on the existing round-trip fixture) and asserts it loads without error under the minor=3 reader, fully pinning INV-003's dynamic claim.
