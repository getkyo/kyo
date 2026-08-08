package kyo

import java.nio.file.Files
import java.nio.file.Paths

class PathWatchJvmTest extends kyo.test.Test[Any]:

    "host recursive watching follows symbolic links only when requested" in {
        val fileSystem = FileSystem.host
        Clock.withTimeControl { clock =>
            Scope.acquireRelease(fileSystem.tempDir("kyo-watch-root"))(handle => Sync.Unsafe.defer(handle.remove())).map { rootHandle =>
                Scope.acquireRelease(fileSystem.tempDir("kyo-watch-outside"))(handle => Sync.Unsafe.defer(handle.remove())).map {
                    outsideHandle =>
                        val root       = rootHandle.path
                        val outside    = outsideHandle.path
                        val link       = root / "linked"
                        val linkedFile = link / "created.txt"
                        val directFile = root / "direct.txt"
                        Sync.defer(Files.createSymbolicLink(Paths.get(link.toString), Paths.get(outside.toString))).andThen {
                            fileSystem.openWatcher(
                                root,
                                WatchOptions(depth = WatchDepth.Recursive, followLinks = false)
                            ).map { noFollow =>
                                fileSystem.openWatcher(
                                    root,
                                    WatchOptions(depth = WatchDepth.Recursive, followLinks = true)
                                ).map { follow =>
                                    fileSystem.write(outside / "created.txt", "linked", Path.WriteOptions()).andThen {
                                        clock.advance(Duration.Zero, 10.millis).andThen(clock.advance(10.millis, 10.millis)).andThen {
                                            fileSystem.write(directFile, "direct", Path.WriteOptions()).andThen {
                                                clock.advance(10.millis, 10.millis).andThen {
                                                    Scope.run(follow.events.take(1).run).map { followed =>
                                                        Scope.run(noFollow.events.take(1).run).map { notFollowed =>
                                                            assert(followed == Chunk(PathChange.Created(linkedFile)))
                                                            assert(notFollowed == Chunk(PathChange.Created(directFile)))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

    "host recursive watching terminates traversal at a symbolic-link cycle" in {
        val fileSystem = FileSystem.host
        Scope.acquireRelease(fileSystem.tempDir("kyo-watch-cycle"))(handle => Sync.Unsafe.defer(handle.remove())).map { rootHandle =>
            val root   = rootHandle.path
            val nested = root / "nested"
            val back   = nested / "back"
            val file   = root / "created.txt"
            fileSystem.mkDir(nested).andThen {
                Sync.defer(Files.createSymbolicLink(Paths.get(back.toString), Paths.get(root.toString))).andThen {
                    Clock.withTimeControl { clock =>
                        fileSystem.openWatcher(root, WatchOptions(depth = WatchDepth.Recursive, followLinks = true)).map { watcher =>
                            fileSystem.write(file, "created", Path.WriteOptions()).andThen {
                                Fiber.initUnscoped(Scope.run(watcher.events.take(1).run)).map { fiber =>
                                    clock.advance(10.millis).andThen(fiber.get).map { events =>
                                        assert(events == Chunk(PathChange.Created(file)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end PathWatchJvmTest
