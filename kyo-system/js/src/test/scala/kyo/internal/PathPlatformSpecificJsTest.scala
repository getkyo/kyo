package kyo.internal

import kyo.*
import kyo.test.HostFilter
import scala.scalajs.js as sjs

class PathPlatformSpecificJsTest extends kyo.test.Test[Any]:

    // Exercises the node:fs backend, which a browser has not.
    override protected def hostFilters = Chunk(HostFilter.NotBrowser)

    import AllowUnsafe.embrace.danger

    // The leaves swap process-global state around synchronous calls.
    override def config = super.config.sequential

    /** Runs `f` with `process` deleted from the global object, so it is an undeclared identifier: the state a
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

    /** Runs `f` with `process.getBuiltinModule` removed, the state of a Node that predates it. */
    private def withoutGetBuiltinModule[A](f: => A): A =
        val process = sjs.Dynamic.global.process
        val saved   = process.getBuiltinModule
        discard(sjs.special.delete(process, "getBuiltinModule"))
        try f
        finally process.updateDynamic("getBuiltinModule")(saved)
        end try
    end withoutGetBuiltinModule

    /** The named refusal and its message, or a description of whatever came back instead.
      *
      * A host with no file system is reported on the channel `Path` declares, not as a panic: the promise in the
      * signature is kept on every host, and a page is one where being told is the only useful outcome.
      */
    private def refusalOf[E, A](result: Result[E, A]): (String, String) =
        result match
            case Result.Failure(e: FileSystemUnsupportedOnHostException) => (e.operation.toString, e.host)
            case other                                                   => ("no typed refusal", other.toString)

    /** The operation and host the failure names. Its rendered message carries a frame under a development build, so the
      * fields are what a test can pin.
      */
    private def refusal(host: Platform.Host): (String, String) =
        (FileSystemOperation.Read.toString, host.toString)

    "without process.getBuiltinModule" - {
        "a file read fails naming the host" in {
            val result = withoutGetBuiltinModule(Path("kyo-path-platform-no-builtin.txt").unsafe.read())
            assert(refusalOf(result) == refusal(Platform.host))
        }

        "a Path is built and read without any module" in {
            assume(!Platform.isWindows, "Windows path syntax comes from the host's node:path")
            val (shown, parts, absolute) = withoutGetBuiltinModule {
                val path = Path("/", "a", "..", "b", ".", "c")
                (path.unsafe.show, path.parts, path.isAbsolute)
            }
            assert(shown == "/b/c")
            assert(parts == Chunk("", "b", "c"))
            assert(absolute)
        }
    }

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

        "a file read fails naming the host instead of throwing ReferenceError" in {
            val (result, host) = withoutProcessGlobal((Path("kyo-path-platform-no-process.txt").unsafe.read(), Platform.host))
            assert(refusalOf(result) == refusal(host))
        }

        "the working directory fails naming the operation instead of throwing ReferenceError" in {
            val error = intercept[UnsupportedOperationException](withoutProcessGlobal(Path.cwdPath))
            assert(error.getMessage.contains("Path.cwd"))
        }
    }

end PathPlatformSpecificJsTest
