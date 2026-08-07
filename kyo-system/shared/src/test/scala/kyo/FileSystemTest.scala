package kyo

import scala.compiletime.testing.typeCheckErrors

/** Tests for the top-level [[FileSystem]] surface: installation through [[Path.runWith]] and the
  * negative-capability rejection of a write program at a read-only runner.
  */
class FileSystemTest extends kyo.test.Test[Any]:

    private val selectedPath = Path("selected.txt")

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

    "ZipReadOnlyFileSystem exposes only Read" in {
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

    private def services: (FileSystem.Write[Sync], FileSystem.Write[Sync]) < (Sync & Abort[FileSystemException]) =
        for
            outer <- FileSystem.inMemory
            inner <- FileSystem.inMemory
            _     <- Path.runWith(outer)(selectedPath.write("outer"))
            _     <- Path.runWith(inner)(selectedPath.write("inner"))
        yield (outer, inner)

    "top-level FileSystem installs through runWith" in {
        FileSystem.inMemory.map { service =>
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
