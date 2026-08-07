package kyo

import scala.compiletime.testing.typeCheckErrors

/** Compile-time contract for the public filesystem conformance suite declarations. */
class FileSystemConformanceDeclarationTest extends kyo.test.Test[Any]:

    "all declared conformance suites are public shared types" in {
        val suites = Chunk(
            classOf[FileSystemReadTest],
            classOf[FileSystemWriteTest],
            classOf[FileSystemChannelTest],
            classOf[FileSystemDurabilityTest],
            classOf[FileSystemLockTest],
            classOf[FileSystemWatchTestSuite],
            classOf[FileSystemStagedChangesTestSuite]
        )
        assert(suites.size == 7)
    }

    "a read-only fixture cannot select write members" in {
        val errors = typeCheckErrors("""
            def check(read: kyo.FileSystem.Read[kyo.Sync])(using kyo.Frame) =
                read.writeBytes(kyo.Path("file"), kyo.Span.empty[Byte], kyo.Path.WriteOptions())
            """)
        assert(errors.exists(_.message.contains("writeBytes")))
    }

    "overlay factories preserve generic write effects and expose watching for Sync" in {
        val errors = typeCheckErrors("""
            given Frame = Frame.internal
            val synchronous: (FileSystem.Write[Sync] & FileSystem.Watch) < (Sync & Scope) =
                FileSystem.overlay(FileSystem.host)
            def generic[S, S2](lower: FileSystem.Write[S])(using Isolate[S, Sync, S2]):
                FileSystem.Write[S & Sync] < (Sync & Scope) = FileSystem.overlay(lower)
        """)
        assert(errors.isEmpty, errors.mkString("\n"))
    }

end FileSystemConformanceDeclarationTest
