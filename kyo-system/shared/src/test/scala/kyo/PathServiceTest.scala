package kyo

import scala.compiletime.testing.typeCheckErrors

/** Tests for the top-level [[PathService]] surface: installation through [[Path.runWith]], the
  * negative-capability rejection of a write program at a read-only runner, and the
  * [[PathService.Disposition]] contract each built-in factory declares.
  */
class PathServiceTest extends kyo.test.Test[Any]:

    "top-level PathService installs through runWith" in {
        PathService.inMemory.map { service =>
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

    "PathService.Disposition has exactly three cases" in {
        assert(PathService.Disposition.values.size == 3)
        assert(PathService.Disposition.values.toSet == Set(
            PathService.Disposition.AutoCommit,
            PathService.Disposition.CommitOnSuccess,
            PathService.Disposition.ManualCommit
        ))
    }

    "host and inMemory declare AutoCommit; overlay declares ManualCommit" in {
        val host = PathService.host
        assert(host.disposition == PathService.Disposition.AutoCommit)
        PathService.inMemory.map { inMemory =>
            assert(inMemory.disposition == PathService.Disposition.AutoCommit)
            PathService.overlay(inMemory).map { overlay =>
                assert(overlay.disposition == PathService.Disposition.ManualCommit)
            }
        }
    }

end PathServiceTest
