package kyo.internal

import kyo.*
import scala.scalajs.js as sjs

class PathPlatformSpecificJsTest extends kyo.test.Test[Any]:

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

    private def fsMessage(host: Platform.Host): String =
        s"kyo-system needs Node's node:fs module for this operation (Node 20.16 or 22.3, Bun 1.2.6, Deno 2.1, or later); this host is $host"

    /** The panic's class name and message, or a description of what came back instead. */
    private def panicOf[E, A](result: Result[E, A]): (String, String) =
        result match
            case Result.Panic(error) => (error.getClass.getName, error.getMessage)
            case other               => ("no panic", other.toString)

    "without process.getBuiltinModule" - {
        "a file read panics naming the module and the host" in {
            val result = withoutGetBuiltinModule(Path("kyo-path-platform-no-builtin.txt").unsafe.read())
            assert(panicOf(result) == ("java.lang.UnsupportedOperationException", fsMessage(Platform.host)))
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

        "a file read panics naming the module and the host instead of throwing ReferenceError" in {
            val (result, host) = withoutProcessGlobal((Path("kyo-path-platform-no-process.txt").unsafe.read(), Platform.host))
            assert(panicOf(result) == ("java.lang.UnsupportedOperationException", fsMessage(host)))
        }

        "the working directory fails naming the operation instead of throwing ReferenceError" in {
            val error = intercept[UnsupportedOperationException](withoutProcessGlobal(Path.cwdPath))
            assert(error.getMessage.contains("Path.cwd"))
        }
    }

end PathPlatformSpecificJsTest
