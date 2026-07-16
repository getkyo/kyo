package kyo

import scala.compiletime.testing.typeCheckErrors

/** Tests for the top-level [[FileSystem]] surface: installation through [[Path.runWith]], the
  * negative-capability rejection of a write program at a read-only runner, and the
  * [[FileSystem.CommitStrategy]] contract each built-in factory declares.
  */
class FileSystemTest extends kyo.test.Test[Any]:

    "top-level FileSystem installs through runWith" in {
        FileSystem.inMemory.map { service =>
            val p = Path("a")
            val program: Unit < (Sync & Abort[FileException]) =
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
            val _: Unit < (kyo.Sync & kyo.Abort[kyo.FileException]) = kyo.Path.runReadOnly(prog)
            """)
        assert(errors.nonEmpty, "runReadOnly ascription should fail to compile for a PathWrite program")
    }

    "FileSystem.CommitStrategy has exactly three cases" in {
        assert(FileSystem.CommitStrategy.values.size == 3)
        assert(FileSystem.CommitStrategy.values.toSet == Set(
            FileSystem.CommitStrategy.Auto,
            FileSystem.CommitStrategy.OnSuccess,
            FileSystem.CommitStrategy.Manual
        ))
    }

    "host and inMemory declare Auto; overlay declares Manual" in {
        val host = FileSystem.host
        assert(host.commitStrategy == FileSystem.CommitStrategy.Auto)
        FileSystem.inMemory.map { inMemory =>
            assert(inMemory.commitStrategy == FileSystem.CommitStrategy.Auto)
            FileSystem.overlay(inMemory).map { overlay =>
                assert(overlay.commitStrategy == FileSystem.CommitStrategy.Manual)
            }
        }
    }

end FileSystemTest
