package kyo

import scala.io.Source
import scala.util.Using

/** Ensures classpath snapshot resources remain synchronized with cross-platform expected values. */
class FileSystemSnapshotResourceTest extends kyo.test.Test[Any]:

    private def resource(name: String): String =
        Using.resource(Source.fromResource(s"kyo/path/$name.snap"))(_.mkString.trim)

    "snapshot resources match their cross-platform representations" in {
        assert(resource("glob-matrix") == FileSystemSnapshotValues.globMatrix)
        assert(resource("normalized-tree") == FileSystemSnapshotValues.normalizedTree)
        assert(resource("watch-trace") == FileSystemSnapshotValues.watchTrace)
        assert(resource("conflict-report") == FileSystemSnapshotValues.conflictReport)
        assert(resource("recovery-records") == FileSystemSnapshotValues.recoveryRecords)
        assert(resource("normalized-errors") == FileSystemSnapshotValues.normalizedErrors)
    }

end FileSystemSnapshotResourceTest
