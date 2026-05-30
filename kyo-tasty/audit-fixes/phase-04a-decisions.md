# Phase 04a Decisions: Widen JAR offsets to 64-bit

Phase: 04a
INV produced: INV-012
Findings addressed: C1, B2, B3

---

## Decision 1: Guard pattern for ByteBuffer.position Int constraint

ByteBuffer.position(int) only accepts Int. For all sites where a Long offset is passed
to buf.position(), the fix is a pre-check that throws IOException if the offset exceeds
Int.MaxValue, then casts with .toInt after the check. This is consistent with the plan's
documented pattern and with the ByteBuffer API constraint described in prep section 3.

Sites affected: parseAllEntries (cenOffset), findEocdBuf (scanStartOffset),
readCenLocationBuf (locOffset, zip64EocdOffset).

## Decision 2: Two extra sites in JarMappedReader (lines 66, 67)

The plan cited 3 sites in JarMappedReader (lines 65, 72, 85 per stale line refs). The actual
code had 5 sites (51, 61, 65, 66, 67). Sites 66 and 67 (compSize.toInt, uncompSize.toInt) are
B2 sub-cases: compSize or uncompSize > Int.MaxValue would silently truncate and lead to
misallocated arrays. Both are now guarded with explicit > Int.MaxValue checks that throw
IOException with a descriptive message before .toInt is called. Total fixed: 14 sites (9+5).

## Decision 3: EOCD scan Int safety

EOCD_MAX_SCAN = 65557 is always < Int.MaxValue. The fix for the scan-length computation
at lines 189 and 524 is to widen the min() operand (fileLen) to Long first, compute the
min in Long space, then cast to Int. The result is always safe because EOCD_MAX_SCAN < Int.MaxValue
so the min is bounded by EOCD_MAX_SCAN. No guard needed; just the correct widening order.

## Decision 4: dataOffset Long propagation in readEntry

dataOffset is widened to Long arithmetic (lfhOffset + 30L + nameLen + extraLen). ByteBuffer.position
and ByteBuffer.slice both require Int. After verifying dataOffset <= buf.limit() (itself Int-bounded
by MappedByteBuffer's 2GB limit), dataOffset.toInt is safe. A local val dataOffsetInt is introduced
for the two call sites to make the conversion explicit and auditable.

## Decision 5: Test 3 implementation via reflection

The plan's Test 3 (64-bit LFH offset round-trip) cannot be exercised with a real > 2GB JAR in
a unit test environment. The test is implemented by opening a real small JAR, then injecting
an oversized lfhOffset via reflection into the entry map, and verifying that readEntry throws
IOException with "exceeds 2GB". This tests the guard path directly and pins the B2 invariant
without requiring multi-gigabyte test fixtures.

## Decision 6: Test 2 Zip64 locator injection strategy

Test 2 builds a real JAR, injects a synthetic Zip64 EOCD record and Zip64 locator before the
standard EOCD, and verifies that list() still returns entries correctly. The Zip64 EOCD cenOffset
points to the same CEN as the standard EOCD, so existing entries are still findable. This confirms
the Zip64 detection path runs without Int truncation (locOffset.toInt guard in readCenLocationBuf).
