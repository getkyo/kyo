package kyo.internal

import kyo.*

class PathPlatformSpecificJsTest extends kyo.test.Test[Any]:

    "raw channel synchronization reports the Sync operation" in {
        Sync.Unsafe.defer(new NodeRawChannel(-1, Path("sync-classification.bin")).sync(metadata = true)).map {
            case Result.Failure(error: FileIOException) => assert(error.operation == FileSystemOperation.Sync)
            case other                                  => fail(s"expected FileIOException, got $other")
        }
    }

    "parent-aware temporary creation removes its file when closing fails" in {
        Path.run(Path.tempDir("conf-temp-close-failure")).map { root =>
            // Unsafe: injects a synchronous close failure after closing the descriptor and restores Node's function before yielding.
            Sync.Unsafe.defer {
                val fs      = NodeFs.asInstanceOf[scala.scalajs.js.Dynamic]
                val saved   = fs.closeSync
                val primary = new scala.scalajs.js.JavaScriptException(scala.scalajs.js.Dynamic.literal(code = "EIO"))
                val failClose: scala.scalajs.js.Function1[Int, Unit] = fd =>
                    saved.asInstanceOf[scala.scalajs.js.Function1[Int, Unit]](fd)
                    throw primary
                fs.updateDynamic("closeSync")(failClose)
                try
                    val result = Sync.Unsafe.evalOrThrow(Abort.run[FileStructureException](Path.tempUnscoped(root, "owned", ".tmp")))
                    result match
                        case Result.Failure(FileIOException(_, FileSystemOperation.Create, cause)) =>
                            assert(NodeError.codeOf(cause.asInstanceOf[scala.scalajs.js.JavaScriptException]) == "EIO")
                        case other => fail(s"Expected close failure, got $other")
                    end match
                    assert(NodeFs.readdirSync(root.unsafe.show).length == 0)
                finally fs.updateDynamic("closeSync")(saved)
                end try
            }
        }
    }

end PathPlatformSpecificJsTest
