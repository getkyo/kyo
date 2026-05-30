# Phase 22c Decisions

Phase: 22c - Test JAR archive edges
Date: 2026-05-30
HEAD at start: a16212f796d7747c262739a7a860435f07e556af

## Summary

Three tests added to `kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala`.
No production code changes. Tests only (jvm platform).

---

## T1: Zip64 JAR with CD past 2GB - Synthetic approach

### Decision: synthetic byte structure, not a real 3 GB file

Building a 3 GB file in a CI test is infeasible (disk, time). Instead, we hand-craft
a synthetic byte structure that exercises the 64-bit parsing code path:

1. Write a real small JAR with one entry via ZipOutputStream.
2. Locate the EOCD record in the resulting bytes.
3. Build a Zip64 EOCD record (56 bytes) with `cenOffset = 3_000_000_000L` at byte offset 48.
4. Build a Zip64 EOCD locator (20 bytes) pointing at the Zip64 EOCD record.
5. Patch the standard EOCD `cenOffset` field to `0xFFFFFFFF` (Zip64 sentinel) so the
   parser takes the Zip64 path.
6. Assemble: [prefix] [Zip64 EOCD record] [Zip64 locator] [EOCD].

The parser calls `readUInt64LE(zip64Buf, 48)` to extract `cenOffset`, then checks
`cenOffset >= fileLen`. Since the file is tiny, `3_000_000_000 >= fileLen` is true,
and MalformedSection fires with a reason containing `"3000000000"`.

### Why this proves 64-bit reading

If the parser truncated the 8-byte field to 32-bit signed, it would read
`3_000_000_000L` as Int = `-1_294_967_296`, and the error reason would contain
`"-1294967296"`, not `"3000000000"`. The assertion `reason.contains("3000000000")`
is therefore a structural proof that the 64-bit read path is taken correctly.

### Byte layout (little-endian)

```
Zip64 EOCD record (56 bytes):
  [0-3]   sig = 0x50 0x4B 0x06 0x06
  [4-11]  recordSize = 44 (8-byte LE)
  [12-15] versionMade = 0
  [14-15] versionNeeded = 0
  [16-19] diskNum = 0
  [20-23] startDisk = 0
  [24-31] entriesOnDisk = 0
  [32-39] totalEntries = 0
  [40-47] cenSize = 0
  [48-55] cenOffset = 3_000_000_000L (0x00000000B2D05E00, 8-byte LE)

Zip64 EOCD locator (20 bytes):
  [0-3]   sig = 0x50 0x4B 0x06 0x07
  [4-7]   diskWithZip64EOCD = 0
  [8-15]  offsetOfZip64EOCD = eocdPos (position of Zip64 EOCD in assembled file)
  [16-19] totalDisks = 1

Standard EOCD (22 bytes, patched):
  [0-3]   sig = 0x50 0x4B 0x05 0x06
  [4-5]   diskNumber = 0
  [6-7]   startDisk = 0
  [8-9]   entriesOnDisk = (original value)
  [10-11] totalEntries = (original value)
  [12-15] cenSize = (original value)
  [16-19] cenOffset = 0xFFFFFFFF (patched to Zip64 sentinel)
  [20-21] commentLen = 0
```

---

## T2: Multi-disk archive rejection

### Decision: minimal 22-byte EOCD file with diskNumber=2

The simplest possible test: write exactly 22 bytes forming an EOCD record with
`diskNumber = 2`. The EOCD scanner finds the signature at offset 0, reads
`stdDiskNum = 2`, and the guard `stdDiskNum != 0 || stdStartDisk != 0` fires,
producing MalformedSection with reason containing `"multi-disk"`.

No real JAR is needed. The production check is at the standard EOCD path
(before the Zip64 locator check). The test confirms the guard on the non-Zip64 path.

---

## T3: JMOD support - DEFERRED

### Decision: defer JMOD support to a separate future phase

JMOD format: 4-byte magic (`0x4A 0x4D 0x01 0x00`) + 2-byte version + ZIP content.

The embedded ZIP data starts at byte 6 of the JMOD file. All internal ZIP offsets
(CEN offset in EOCD, LFH offsets in CEN records) are relative to byte 0 of the
embedded ZIP (= byte 6 of the JMOD file). Correct support requires:

1. Detecting the JMOD magic in the first 4 bytes before EOCD scanning.
2. Threading a `prefixOffset: Long = 6L` through all `raf.seek` calls:
   - `findEocd`: no change needed (EOCD is still at the end of file).
   - `readCenLocation`: `cenOffset` read from EOCD must be adjusted by `+prefixOffset`.
   - `listEntries`: `raf.seek(cenOffset + prefixOffset)` for CEN read.
   - `parseCenRecordsFull`/`parseCenRecords`: `lfhOffset` from CEN records is relative
     to embedded ZIP start; reads via `JarMappedReader` must add `prefixOffset`.

This threading change touches both `JarCentralDirectory` (EOCD/CEN reading) and
`JarMappedReader` (LFH/data seeking), constituting a production code change beyond
the test-only scope of Phase 22c.

The deferred test `P22c-T3` is a named placeholder (`succeed`) that keeps the
test ID visible in the suite. It is tagged `jvmOnly` and produces no assertion.

---

## Conventions observed

- Zero em-dashes and en-dashes.
- No semicolons.
- No casts (asInstanceOf).
- No default parameters.
- No explicit Abort/Fail type annotations.
- Tests use `jvmOnly` tag (jvm platform only).
- Maybe/Present/Absent used (no Option).
