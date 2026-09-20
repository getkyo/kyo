package kyo.internal

import kyo.*
// Aliased: the test base's own platform selector is named `js`, which shadows the package.
import scala.scalajs.js as sjs

class PathPlatformSpecificJsTest extends kyo.test.Test[Any]:

    "raw channel synchronization reports the Sync operation" in {
        Sync.Unsafe.defer(new NodeRawChannel(-1, Path("sync-classification.bin")).sync(metadata = true)).map {
            case Result.Failure(error: FileIOException) => assert(error.operation == FileSystemOperation.Sync)
            case other                                  => fail(s"expected FileIOException, got $other")
        }
    }

    /** Runs `body` with `fs.writeSync` replaced by a write that takes no bytes, and reports how many
      * times it was called.
      *
      * The stub throws once past a small call bound rather than returning zero forever: a regression
      * of the write loops' zero-progress guard then surfaces as a failed call-count assertion
      * instead of hanging the suite.
      *
      * This seam is why the two leaves below are JavaScript-only while the source they cover is
      * shared with Wasm: replacing a member reaches the `node:fs` exports object under CommonJS,
      * which is what this platform links, and an ES module namespace object, which Wasm links, is
      * sealed.
      */
    private def withStalledWriteSync[A](body: () => A): (A, Int) =
        val fs                                                                    = NodeFs.asInstanceOf[sjs.Dynamic]
        val original                                                              = fs.selectDynamic("writeSync")
        var calls                                                                 = 0
        val stub: sjs.Function5[sjs.Any, sjs.Any, sjs.Any, sjs.Any, sjs.Any, Int] =
            (_, _, _, _, _) =>
                calls += 1
                if calls > 4 then throw AssertionError("write loop retried a write that reported no progress")
                else 0
        fs.updateDynamic("writeSync")(stub)
        try (body(), calls)
        finally fs.updateDynamic("writeSync")(original)
    end withStalledWriteSync

    "a write handle fails a write that reports no progress" in {
        Sync.Unsafe.defer {
            withStalledWriteSync(() => new NodeWriteHandle(-1, Path("stalled-write.bin")).writeBytes(Chunk[Byte](1, 2, 3)))
        }.map { (result, calls) =>
            assert(calls == 1)
            result match
                case Result.Failure(error: FileWriteStalledException) => assert(error.remaining == 3.bytes)
                case other                                            => fail(s"expected FileWriteStalledException, got $other")
        }
    }

    "a raw channel fails a positioned write that reports no progress" in {
        Sync.Unsafe.defer {
            withStalledWriteSync(() => new NodeRawChannel(-1, Path("stalled-write-at.bin")).writeAt(0L, Array[Byte](1, 2, 3)))
        }.map { (result, calls) =>
            assert(calls == 1)
            result match
                case Result.Failure(error: FileWriteStalledException) => assert(error.remaining == 3.bytes)
                case other                                            => fail(s"expected FileWriteStalledException, got $other")
        }
    }

end PathPlatformSpecificJsTest
