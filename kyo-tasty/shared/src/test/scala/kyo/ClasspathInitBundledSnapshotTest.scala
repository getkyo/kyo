package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.BundledSnapshotProbe

/** Phase 13 cross-platform leaf for end-to-end transparent bundled snapshot loading.
  *
  * Leaf 10: cross-platform -- probe returns Absent on platforms that cannot open ZIPs (JS/Native default).
  *
  * JVM-only leaves (4, 5, 6, 7) that require real jar files (java.util.zip, java.io.File) live in
  * ClasspathInitBundledSnapshotJvmTest.scala (jvm/src/test).
  *
  * Pins: INV-006 cross-platform placement; probe degrades gracefully on JS/Native.
  */
class ClasspathInitBundledSnapshotTest extends Test:

    // Leaf 10: cross-platform -- probe returns Absent for default openZip
    // Given: a plain MemoryFileSource (no openZip override, returns Absent by default).
    // When: BundledSnapshotProbe.probe(root, memorySource)
    // Then: returns Maybe.Absent (default FileSource.openZip returns Absent; probe falls through).
    // Pins: INV-006 cross-platform placement; probe degrades gracefully on JS/Native.
    "Leaf 10: probe returns Maybe.Absent when FileSource.openZip returns Absent (cross-platform)" in run {
        val source = new MemoryFileSource()
        source.add("some-root.jar", Array[Byte](0xca.toByte))
        Scope.run:
            BundledSnapshotProbe.probe("some-root.jar", source).map: result =>
                assert(result == Maybe.Absent, s"expected Absent from default FileSource.openZip; got $result")
    }

end ClasspathInitBundledSnapshotTest
