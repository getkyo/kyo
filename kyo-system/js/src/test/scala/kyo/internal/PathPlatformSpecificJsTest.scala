package kyo.internal

import kyo.*
import scala.scalajs.js as sjs

class PathPlatformSpecificJsTest extends kyo.test.Test[Any]:

    /** Runs `f` with `process` deleted from the global object, so it is an UNDECLARED identifier — the state a
      * browser is in, and the one where a bare read throws while `typeof process` still answers "undefined".
      * Restored in a `finally` because the test runner talks over `process.stdout`.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        // `globalThis`, not `sjs.Dynamic.global`, which Scala.js allows only left of a `.`-selection.
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        sjs.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    "raw channel synchronization reports the Sync operation" in {
        Sync.Unsafe.defer(new NodeRawChannel(-1, Path("sync-classification.bin")).sync(metadata = true)).map {
            case Result.Failure(error: FileIOException) => assert(error.operation == FileSystemOperation.Sync)
            case other                                  => fail(s"expected FileIOException, got $other")
        }
    }

    "with no process global" - {
        "an environment variable reads as unset instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(Path.envOrEmpty("HOME")) == "")
        }

        "the working directory fails naming the operation instead of throwing ReferenceError" in {
            val error = intercept[UnsupportedOperationException](withoutProcessGlobal(Path.cwdPath))
            assert(error.getMessage.contains("Path.cwd"))
        }
    }

end PathPlatformSpecificJsTest
