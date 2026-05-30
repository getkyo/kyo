# Phase 21f decisions

Decision 1: DigestComputerTest Test 2 uses computeParanoid instead of compute for content-level differentiation.
Rationale: DigestComputer.compute hashes (path, mtime, size) stats. Two files with the same path, mtime=0, and the
same byte length produce identical stats and therefore identical digests. The plan scenario "[1,2,3] vs [1,2,4]"
has equal lengths, so compute() cannot distinguish them. computeParanoid hashes file content directly and correctly
distinguishes the two inputs. The test comment explains this difference.
Time: 2026-05-30T06:32Z

Decision 2: SnapshotFormatTest Test 5 asserts magic decodes to "KRFL" not "kRfl".
Rationale: The plan prompt says magic decodes to "kRfl" but SnapshotFormat.magic is Array('K','R','F','L') which
decodes to "KRFL" (all uppercase). The scaladoc also says "KRFL" at the header-layout comment. The plan prompt had
a typo. The test uses the actual wire value "KRFL" sourced directly from SnapshotFormat.magic.
Time: 2026-05-30T06:30Z

Decision 3: SnapshotFormat.scala scaladoc name mismatch fix scope is two lines only.
Rationale: Line 34 had "TYPES_EXTRA" (doc) vs "TYPESEXT" (wire), and line 39 had "BODY_BYTES" (doc) vs "BODYBYTE"
(wire). Both corrected to match the actual wire names verbatim. No code logic was changed; only the scaladoc
section-ID names were updated.
Time: 2026-05-30T06:30Z

Decision 4: SnapshotRoundTripTest local-parent test uses a synthetic classpath (Tasty.Symbol.make + transitionToReady)
instead of the ChildClass+BaseClass TASTy fixture.
Rationale: ChildClass.tasty encodes its parent type using TASTy type tags 136 and 312 which TypeUnpickler returns as
Unknown. Unknown types are filtered by SnapshotWriter (only Named types are written to PARENTS section) so the
round-trip produces empty parents. No existing TASTy fixture yields a Named parent that survives the round-trip. The
synthetic approach directly calls sym._parents.set(Chunk(Tasty.Type.Named(barSym))) to place a known local Named
parent, then calls transitionToReady to build a minimal but valid Classpath. This exercises the exact
SnapshotWriter serialization path (symbolId map lookup for Named parents) and SnapshotReader restoration path.
Time: 2026-05-30T06:37Z

Decision 5: DataFormatException arm in Native InflateHook is placed BEFORE ZipException.
Rationale: In scala-native's javalib, InflaterInputStream wraps DataFormatException as IOException during read(),
but DataFormatException itself is available as a throwable. The DataFormatException arm maps to MalformedSection
(same as the ZipException arm) to match the semantic intent of "corrupt ZLIB data" on all platforms. ZipException
is kept below because it signals the same family of errors. IOException remains the broadest catch for other I/O
failures. DataFormatException availability confirmed by presence in native/target .ll generated code.
Time: 2026-05-30T06:30Z

Decision 6: SimpleMemoryFileSource in DigestComputerTest is a local inner class, not reusing SnapshotRoundTripTest.MemoryFileSource.
Rationale: DigestComputerTest is a separate test class; importing SnapshotRoundTripTest for its inner class would
create a cross-test-file dependency. A local minimal implementation satisfies the FileSource contract with
identical stat semantics (mtime=0, size=bytes.length). Rule 8c (1:1 source-to-test file) is preserved.
Time: 2026-05-30T06:30Z
