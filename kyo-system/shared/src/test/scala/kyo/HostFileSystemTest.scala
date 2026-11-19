package kyo

import kyo.internal.Platform

class HostFileSystemTest extends kyo.test.Test[Any]:

    /** A host that refuses every text write, as a root does when the directories created in it cannot be entered. */
    final private class WriteRefusing(base: FileSystem.Write[Sync]) extends FileSystem.Write[Sync]:
        export base.{write as _, *}
        override def write(path: Path, value: String, options: Path.WriteOptions)(using
            Frame
        ): Unit < (Sync & Abort[FileWriteException]) =
            Abort.fail(FileAccessDeniedException(path))
    end WriteRefusing

    "a case sensitivity probe that cannot write removes its probe directory and answers the platform's policy" in {
        for
            root <- Scope.acquireRelease(FileSystem.host.tempDir("kyo-host-case-probe"))(handle =>
                // Unsafe: the scoped test owns this temporary directory.
                Sync.Unsafe.defer(handle.remove())
            ).map(_.path)
            host    <- FileSystem.host(root)
            probed  <- HostFileSystem.probeCaseSensitivity(new WriteRefusing(host))
            entries <- host.list(root)
        yield
            val inferred = if Platform.isWindows then Glob.CaseSensitivity.Insensitive else Glob.CaseSensitivity.Sensitive
            assert(entries == Chunk.empty[Path], s"the probe left $entries in the root")
            assert(probed == inferred)
    }

end HostFileSystemTest
