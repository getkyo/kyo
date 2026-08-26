package kyo

import java.nio.charset.Charset

/** Tests for the top-level [[FileSystem]] surface: the read and write tiers as types, and a
  * user-defined backend answering through the same service methods the host backend implements.
  */
class FileSystemTest extends kyo.test.Test[Any]:

    /** Rebases `write` and `read`, the only two ops this suite exercises, under `root`. Two instances
      * backed by the real host filesystem then behave as independent stores addressable through the
      * same literal `Path` value, which is what a backend test needs: the store a read reaches must
      * depend only on which service it is asked of.
      */
    final private class IsolatedFileSystem(root: Path) extends FileSystem.Write[Sync]:
        private val delegate = FileSystem.host
        // Delegated by name, not a wildcard: a wildcard emits one forwarder per member in an order
        // the compiler does not fix, so this class's TASTy differs between clean builds, which
        // reaches the doctest classpath fingerprint and costs the module its cached results.
        // `read` and `write` are absent because this class overrides them below. Omitting any other
        // member fails to compile, since the class must implement all of FileSystem.Write.
        export delegate.append
        export delegate.appendBytes
        export delegate.appendLines
        export delegate.copy
        export delegate.defaultCaseSensitivity
        export delegate.exists
        export delegate.isDirectory
        export delegate.isRegularFile
        export delegate.isSymbolicLink
        export delegate.list
        export delegate.mkDir
        export delegate.mkFile
        export delegate.move
        export delegate.openRead
        export delegate.openReadLines
        export delegate.openWalk
        export delegate.openWrite
        export delegate.readBytes
        export delegate.readLines
        export delegate.realPath
        export delegate.remove
        export delegate.removeAll
        export delegate.removeExisting
        export delegate.setLastModified
        export delegate.size
        export delegate.stat
        export delegate.tempDir
        export delegate.truncate
        export delegate.writeBytes
        export delegate.writeChunk
        export delegate.writeLines
        export delegate.writeString
        def write(path: Path, value: String, options: Path.WriteOptions)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            delegate.write(root / path, value, options)
        def read(path: Path)(using Frame): String < (Sync & Abort[FileReadException]) =
            delegate.read(root / path)
        def read(path: Path, charset: Charset)(using Frame): String < (Sync & Abort[FileReadException]) =
            delegate.read(root / path, charset)
    end IsolatedFileSystem

    private def isolatedFileSystem(prefix: String)(using Frame): FileSystem.Write[Sync] < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            IsolatedFileSystem(handle.path)
        }

    "Read exposes no write member" in {
        typeCheckFailure(
            "def check(read: kyo.FileSystem.Read[kyo.Sync]) = read.writeBytes(kyo.Path(\"a\"), kyo.Span.empty[Byte], kyo.Path.WriteOptions())"
        )(
            "value writeBytes is not a member"
        )
    }

    "Write satisfies Read" in {
        typeCheck("def check(write: kyo.FileSystem.Write[kyo.Sync]): kyo.FileSystem.Read[kyo.Sync] = write")
    }

    "a Read-only backend does not satisfy Write" in {
        typeCheckFailure("def check(read: kyo.FileSystem.Read[kyo.Sync]): kyo.FileSystem.Write[kyo.Sync] = read")(
            "Found"
        )
    }

    "public rows use precise categories" in {
        typeCheck(
            "def check(fs: kyo.FileSystem.Read[kyo.Sync]): kyo.Span[Byte] < (kyo.Sync & kyo.Abort[kyo.FileReadException]) = fs.readBytes(kyo.Path(\"a\"))"
        )
        typeCheck(
            "def check(fs: kyo.FileSystem.Write[kyo.Sync]): Unit < (kyo.Sync & kyo.Abort[kyo.FileWriteException]) = fs.writeBytes(kyo.Path(\"a\"), kyo.Span.empty[Byte], kyo.Path.WriteOptions())"
        )
    }

    "a user-defined backend serves reads and writes through the service methods" in {
        isolatedFileSystem("kyo-fs-top-level").map { service =>
            val p = Path("a")
            service.write(p, "x", Path.WriteOptions()).andThen {
                service.read(p).map(v => assert(v == "x"))
            }
        }
    }

    "two backends addressed by the same path reach independent stores" in {
        for
            outer <- isolatedFileSystem("kyo-fs-outer")
            inner <- isolatedFileSystem("kyo-fs-inner")
            selected = Path("selected.txt")
            _         <- outer.write(selected, "outer", Path.WriteOptions())
            _         <- inner.write(selected, "inner", Path.WriteOptions())
            fromOuter <- outer.read(selected)
            fromInner <- inner.read(selected)
        yield assert((fromOuter, fromInner) == ("outer", "inner"))
    }

end FileSystemTest
