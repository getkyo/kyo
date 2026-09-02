package kyo

import scala.compiletime.testing.typeCheckErrors

/** Compile-time contract for the public filesystem conformance suite declarations. */
class FileSystemConformanceDeclarationTest extends kyo.test.Test[Any]:

    "all declared conformance suites are public shared types" in {
        val suites = Chunk(
            classOf[FileSystemReadTest],
            classOf[FileSystemWriteTest],
            classOf[FileSystemChannelTest],
            classOf[FileSystemLockTest]
        )
        assert(suites.size == 4)
    }

    "a read-only fixture cannot select write members" in {
        val errors = typeCheckErrors("""
            def check(read: kyo.FileSystem.Read[kyo.Sync])(using kyo.Frame) =
                read.writeBytes(kyo.Path("file"), kyo.Span.empty[Byte], kyo.Path.WriteOptions())
            """)
        assert(errors.exists(_.message.contains("writeBytes")))
    }

end FileSystemConformanceDeclarationTest
