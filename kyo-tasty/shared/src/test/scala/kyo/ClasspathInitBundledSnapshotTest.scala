package kyo

import kyo.internal.tasty.query.BundledSnapshotProbe

/** Verifies that BundledSnapshotProbe.probe returns Maybe.Absent for roots where no jar is present.
  *
  * On all platforms, ZipHandle.open returns Maybe.Absent for non-existent paths (JVM) or for any path (JS, Native).
  * The probe must propagate that Absent without raising Abort.
  */
class ClasspathInitBundledSnapshotTest extends kyo.test.Test[Any]:

    "probe returns Maybe.Absent when ZipHandle.open returns Absent (cross-platform)" in {
        Scope.run {
            // A path that does not exist on disk: ZipHandle.open returns Maybe.Absent on JVM
            // (path does not exist) and on JS/Native (jar reading not supported).
            BundledSnapshotProbe.probe("__nonexistent_probe_test__.jar").map { result =>
                assert(result == Maybe.Absent, s"expected Absent from probe on non-existent jar; got $result")
            }
        }
    }

end ClasspathInitBundledSnapshotTest
