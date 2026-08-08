package kyo

import java.nio.charset.Charset
import scala.compiletime.testing.typeCheckErrors

/** Tests for the top-level [[FileSystem]] surface: installation through [[Path.runWith]] and the
  * negative-capability rejection of a write program at a read-only runner.
  */
class FileSystemTest extends kyo.test.Test[Any]:

    private val selectedPath = Path("selected.txt")

    /** Rebases `write` and `read`, the only two ops this suite exercises, under `root`. Two
      * instances backed by the real host filesystem then behave as independent stores addressable
      * through the same literal `Path` value, which is what these `FileSystem.let` selection tests
      * need: the store a read reaches must depend only on which service is ambient.
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
        export delegate.lock
        export delegate.mkDir
        export delegate.mkFile
        export delegate.move
        export delegate.openRead
        export delegate.openReadChannel
        export delegate.openReadChannelUnscoped
        export delegate.openReadLines
        export delegate.openReadWriteChannel
        export delegate.openReadWriteChannelUnscoped
        export delegate.openWalk
        export delegate.openWrite
        export delegate.openWriteChannel
        export delegate.readBytes
        export delegate.readLines
        export delegate.realPath
        export delegate.remove
        export delegate.removeAll
        export delegate.removeExisting
        export delegate.setLastModified
        export delegate.size
        export delegate.stat
        export delegate.temp
        export delegate.tempDir
        export delegate.truncate
        export delegate.tryLock
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

    "runReadOnlyWith accepts Read" in {
        typeCheck(
            "def check(fs: kyo.FileSystem.Read[kyo.Sync]) = kyo.Path.runReadOnlyWith(fs)(kyo.Path(\"a\").read)"
        )
    }

    private def services: (FileSystem.Write[Sync], FileSystem.Write[Sync]) < (Sync & Scope & Abort[FileSystemException]) =
        for
            outer <- isolatedFileSystem("kyo-fs-let-outer")
            inner <- isolatedFileSystem("kyo-fs-let-inner")
            _     <- Path.runWith(outer)(selectedPath.write("outer"))
            _     <- Path.runWith(inner)(selectedPath.write("inner"))
        yield (outer, inner)

    "top-level FileSystem installs through runWith" in {
        isolatedFileSystem("kyo-fs-top-level").map { service =>
            val p = Path("a")
            val program: Unit < (Sync & Abort[FileSystemException]) =
                Path.runWith(service)(p.write("x"))
            program.andThen {
                Path.runWith(service)(p.read).map(v => assert(v == "x"))
            }
        }
    }

    "runReadOnly rejects PathWrite at the call site (compile-negative)" in {
        val errors = typeCheckErrors("""
            given kyo.Frame = kyo.Frame.internal
            val prog: Unit < kyo.PathWrite = kyo.Path("a").write("x")
            val _: Unit < (kyo.Sync & kyo.Abort[kyo.FileSystemException]) = kyo.Path.runReadOnly(prog)
            """)
        assert(errors.nonEmpty, "runReadOnly ascription should fail to compile for a PathWrite program")
        assert(errors.exists(_.message.contains("PathWrite")))
    }

    "runWith retains a custom backend effect in its residual" in {
        val errors = typeCheckErrors("""
            given kyo.Frame = kyo.Frame.internal
            sealed trait BackendEffect
            def check(backend: kyo.FileSystem.Write[BackendEffect]) =
                val write = kyo.Path.runWith(backend)(kyo.Path("a").write("x"))
                val _: Unit < kyo.Abort[kyo.FileSystemException] = write
            """)
        assert(errors.nonEmpty)
        assert(errors.exists(_.message.contains("BackendEffect")))
    }

    "FileSystem.let nests and restores the selected service" in {
        services.map { (outer, inner) =>
            FileSystem.let(outer) {
                for
                    before <- Path.runReadOnly(selectedPath.read)
                    during <- FileSystem.let(inner)(Path.runReadOnly(selectedPath.read))
                    after  <- Path.runReadOnly(selectedPath.read)
                yield assert((before, during, after) == ("outer", "inner", "outer"))
            }
        }
    }

    "FileSystem.let restores the selected service after failure" in {
        services.map { (outer, inner) =>
            FileSystem.let(outer) {
                Abort.run[String] {
                    FileSystem.let(inner)(Path.runReadOnly(selectedPath.read).andThen(Abort.fail("stop")))
                }.map { failed =>
                    Path.runReadOnly(selectedPath.read).map(after => assert((failed, after) == (Result.fail("stop"), "outer")))
                }
            }
        }
    }

    "child fibers inherit the selected service" in {
        services.map { (_, inner) =>
            FileSystem.let(inner) {
                Path.runReadOnly(Async.zip(selectedPath.read, selectedPath.read)).map(values => assert(values == ("inner", "inner")))
            }
        }
    }

    "sibling fibers keep independently selected services" in {
        services.map { (outer, inner) =>
            Async.zip(
                FileSystem.let(outer)(Path.runReadOnly(selectedPath.read)),
                FileSystem.let(inner)(Path.runReadOnly(selectedPath.read))
            ).map(values => assert(values == ("outer", "inner")))
        }
    }

end FileSystemTest
