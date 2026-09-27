package kyo

import kyo.*
import kyo.Path.WatchOptions

/** Scratch probe, never merged: moves the ancestor of a watched directory many times and counts the refusals. */
class WindowsMoveRetryProbeTest extends kyo.test.Test[Any]:

    "moving the ancestor of a watched directory, repeated" in {
        val fileSystem = FileSystem.host
        Scope.run {
            Path.run(Path.tempDir("kyo-move-retry-probe")).map { base =>
                Kyo.foreach(1 to 300) { i =>
                    val ancestor = base / s"anc-$i"
                    val root     = ancestor / "root"
                    Scope.run {
                        fileSystem.mkDir(root).andThen {
                            fileSystem.openWatcher(root, WatchOptions(capacity = 1)).map { _ =>
                                Random.nextInt(12).map(ms => Async.sleep(ms.millis)).andThen {
                                    Abort.run[FileSystemException](fileSystem.move(ancestor, base / s"moved-$i", Path.MoveOptions())).map {
                                        case Result.Success(_) => 0
                                        case Result.Failure(e) =>
                                            println(s"[probe] round $i refused: $e")
                                            1
                                        case Result.Panic(t) =>
                                            println(s"[probe] round $i panic: $t")
                                            1
                                    }
                                }
                            }
                        }
                    }
                }.map(refused => assert(refused.sum == 0, s"${refused.sum} of 300 moves refused"))
            }
        }
    }

end WindowsMoveRetryProbeTest
