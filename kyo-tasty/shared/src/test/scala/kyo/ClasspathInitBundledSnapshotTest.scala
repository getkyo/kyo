package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.BundledSnapshotProbe

/** Verifies that BundledSnapshotProbe.probe returns Maybe.Absent on a FileSource whose openZip implementation returns Absent (the default
  * for FileSources without a real zip backend).
  */
class ClasspathInitBundledSnapshotTest extends kyo.test.Test[Any]:

    "probe returns Maybe.Absent when FileSource.openZip returns Absent (cross-platform)" in {
        val source = new MemoryFileSource()
        source.add("some-root.jar", Array[Byte](0xca.toByte))
        Scope.run:
            BundledSnapshotProbe.probe("some-root.jar", source).map: result =>
                assert(result == Maybe.Absent, s"expected Absent from default FileSource.openZip; got $result")
    }

end ClasspathInitBundledSnapshotTest
