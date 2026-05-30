# Phase 14a Audit

## Summary

Phase 14a correctly adds `byteOffset: Long` to the three case classes with no default parameters. All 50+ callsites pass a value; no callsite was missed. The 0L sentinel sites are mostly well justified. One site warrants a WARN: `CommentsUnpickler.read` has `view` in scope at the catch block but uses 0L despite the decisions doc acknowledging that `view.position` is accessible. Real-offset accuracy for the spot-checked sites is correct. Tests are substantive. Cross-platform discipline is complete (JVM + Native updated; JS has no mmap reader and delegates to SnapshotReader.read, which is correct). No downstream breakage found.

## Findings

### 1. 0L sentinel justification - WARN

Enumerated 0L sites from decisions doc and code:

- D1 ConstantPool accessors (type-mismatch, bounds): post-parse; no stream cursor. Correct 0L.
- D3 Tasty.scala annotation/body catch blocks: DecodeException and AIOOBE carry no cursor. Correct 0L.
- D4 SnapshotReader wrong-magic, PlatformMmapReader IOException: raw array check before ByteView, IOException carries no cursor. Correct 0L.
- D5 JarCentralDirectory empty-file (fileLen == 0): no scan performed, no meaningful offset. Correct 0L. The list/listFull Sync-boundary catch blocks also use 0L; exception propagates from inner throw through unwrap, losing cursor context. Correct 0L.
- D7 CommentsUnpickler AIOOBE: **WARN.** The decisions doc D7 contains a self-contradictory note: it first states "view IS in scope, so view.position is used", then concludes 0L was used because the exception discards position. Inspection of the actual code confirms `view` is a named parameter in scope at the catch site (line 41-43 of CommentsUnpickler.scala). The comment in code says "exception does not carry a byte offset" but `view.position` is directly reachable. This is the same situation as PositionsUnpickler (which correctly uses `view.position`). A future phase should change this to `view.position`.
- D8 AttributeUnpickler AIOOBE (0L) vs UnknownTagException (e.pos.toLong): correct distinction.
- ClasspathOrchestrator "ASTs section not found": section detection precedes any stream access. Correct 0L.
- ModuleInfoReader "No Module attribute found": view has been fully consumed by the scan loop at that point. 0L is defensible but `view.position` would give the end-of-attributes offset. Minor, not actionable.

### 2. INV-006 coverage - PASS

Full grep across all non-test `.scala` source in `kyo-tasty/` confirms every constructor call passes a third argument. No two-argument constructor call of the three error cases exists in the current tree. 61 total construction or reference lines found; all in updated form. INV-006 is satisfied.

### 3. Real-offset accuracy - PASS

Spot-checked sites:

- `JarCentralDirectory.findEocd`: "EOCD not found" passes `fileLen`, which is the byte position scanned past. Correct -- this marks how far the scan ran before failing.
- `JarCentralDirectory.readCenLocation` multi-disk checks: passes `eocdOffset`, `locOffset`, and `zip64EocdOffset` respectively. Each points to the structure that contained the offending field. Correct semantics.
- `ClassfileUnpickler` magic check: passes `view.position` after `readU4`, so position is 4. Version check passes `view.position` after two `readU2` calls, so position is 8. Both match the bytes-consumed model documented in D6. Correct.
- `PositionsUnpickler` line overflow (Phase 08b): passes `view.position` at the AIOOBE catch site. The view is the parameter of `readSync`, accessible in the outer `read` catch. Correct.
- `ConstantPool.read` unknown-tag: `errorOffset = view.position` is captured inside the while loop immediately after reading the unknown tag byte, so position is count(2) + tag(1) = 3 for the test input. Correct.

### 4. Test substance - PASS

Test 1 (MalformedSection): asserts `off != 0L` using a real SectionIndex.read path with nameRef=99 out of range. The assertion is meaningful -- it proves the cursor was plumbed, not just that the field compiles. The byte input `encodeNat(99)` produces one byte (99 | 0x80 = 0xE3), so `view.position == 1` after reading, which is non-zero. Sound.

Test 2 (ClassfileFormatError 3L): the byte layout `[0x00, 0x02, 0xFF]` is correctly analyzed in decisions D2. After reading cp_count (2 bytes) and the unknown tag (1 byte), position is 3. The test pins to exact value 3L via a second match arm that reports the actual offset on mismatch. Substantive; not fudged.

Test 3 (SnapshotFormatError 0L): constructs the value directly without going through SnapshotReader. This proves the field is present and destructurable (INV-006 structural requirement) but does not exercise the wrong-magic code path end-to-end. Acceptable given the rationale that wrong-magic detection operates on a raw array with no cursor, making an integration test yield the same 0L anyway.

### 5. Cross-platform discipline - PASS

JVM `PlatformMmapReader.scala` updated (IOException -> 0L). Native `PlatformMmapReader.scala` updated symmetrically (same patch). JS `PlatformMmapReader.scala` delegates entirely to `SnapshotReader.read` and constructs no `SnapshotFormatError` directly; no update needed and none was made. Complete.

### 6. Code quality - PASS

`TastyError.scala` case class fields have no default parameters; all three new `byteOffset: Long` fields are required. No em-dashes, semicolons, `asInstanceOf`, or `Option/Some/None` introduced in the diff. New-in-diff code comments use "no cursor:" prefix consistently.

### 7. API compat - PASS

Grep across `kyo-tasty-bench/`, `kyo-tasty-fixtures/`, and `kyo-tasty-sbt/` finds zero constructor calls to any of the three error cases. No downstream module breakage.

## Recommendations

- Route CommentsUnpickler 0L -> `view.position` fix to a later phase (the view parameter is in scope; one-line change). Label this in steering as a residual INV-006 gap.
- Consider an integration-level test for SnapshotFormatError that exercises the wrong-magic path via SnapshotReader.readBytes rather than direct construction, so the 0L sentinel is tested in-situ rather than by assertion.
