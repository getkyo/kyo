package kyo.test.snapshot

import java.io.IOException
import kyo.Maybe
import kyo.Span
import kyo.internal.Platform
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** How `SnapshotStore` reports a file-system failure: as an `IOException` on every platform, the way `java.nio.file` reports one on the JVM
  * and Native, so a snapshot that cannot be stored names the path and the cause instead of claiming the host has no file system.
  *
  * Uses ScalaTest directly, mirroring `SnapshotStoreBytesTest`: plain synchronous file I/O.
  */
class SnapshotStoreFailureTest extends AnyFunSuite with NonImplicitAssertions:

    /** A fresh directory holding one regular file, whose path is returned. A page has no file system, so a leaf that asks for one cancels
      * on the browser rows.
      */
    private def regularFile(): String =
        assume(!Platform.isBrowser, "reads and writes snapshot files through the file system, which a page has not")
        val path = s"target/snap-store-test-${java.lang.System.nanoTime()}/file.txt"
        SnapshotStore.write(path, "content")
        path
    end regularFile

    test("write fails with an IOException when the parent is a regular file") {
        val parent = regularFile()
        val _      = intercept[IOException](SnapshotStore.write(s"$parent/child.txt", "content"))
    }

    test("writeBytes fails with an IOException when the parent is a regular file") {
        val parent = regularFile()
        val _      = intercept[IOException](SnapshotStore.writeBytes(s"$parent/child.bin", Span[Byte](1.toByte)))
    }

    test("read fails with an IOException when the path is a directory") {
        val directory = regularFile().stripSuffix("/file.txt")
        val _         = intercept[IOException](SnapshotStore.read(directory))
    }

    test("readBytes fails with an IOException when the path is a directory") {
        val directory = regularFile().stripSuffix("/file.txt")
        val _         = intercept[IOException](SnapshotStore.readBytes(directory))
    }

    test("read is Absent for a path that does not exist") {
        assert(SnapshotStore.read(s"${regularFile()}.missing") == Maybe.Absent)
    }

end SnapshotStoreFailureTest
