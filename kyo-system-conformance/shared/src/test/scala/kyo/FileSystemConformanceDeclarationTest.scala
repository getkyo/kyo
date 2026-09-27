package kyo

import scala.compiletime.testing.typeCheckErrors

/** Compile-time contract for the public filesystem conformance suite declarations. */
class FileSystemConformanceDeclarationTest extends kyo.test.Test[Any]:

    "the write tier carries the write and channel suites" in {
        val tier = classOf[FileSystemWriteConformanceTest[Sync]]
        assert(classOf[FileSystemWriteTest[Sync]].isAssignableFrom(tier))
        assert(classOf[FileSystemChannelTest[Sync]].isAssignableFrom(tier))
    }

    // Locking is declared on FileSystem.Read, so a backend that only reads is still held to it.
    "the read tier carries the lock suite" in {
        assert(classOf[FileSystemLockTest[Sync]].isAssignableFrom(classOf[FileSystemReadConformanceTest[Sync]]))
    }

    "a read-only fixture cannot select write members" in {
        val errors = typeCheckErrors("""
            def check(read: kyo.FileSystem.Read[kyo.Sync])(using kyo.Frame) =
                read.writeBytes(kyo.Path("file"), kyo.Span.empty[Byte], kyo.Path.WriteOptions())
            """)
        assert(errors.exists(_.message.contains("writeBytes")))
    }

end FileSystemConformanceDeclarationTest
