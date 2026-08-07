package kyo

/** Shared watch contract for mutable filesystem backends. */
abstract class FileSystemWatchTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException])

    private def withControlledClock(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path, Clock.TimeControl) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        Clock.withTimeControl(clock => withFileSystem((fileSystem, root) => use(fileSystem, root, clock)))

    private def take(clock: Clock.TimeControl, watcher: Path.Watcher, count: Int)(using
        Frame
    ): Chunk[PathChange] < (Async & Abort[FileWatchException]) =
        Fiber.initUnscoped(Scope.run(watcher.events.take(count).run)).map { fiber =>
            clock.advance(10.millis).andThen(clock.advance(10.millis)).andThen(fiber.get)
        }

    private def takeQueued(clock: Clock.TimeControl, watcher: Path.Watcher, count: Int)(using
        Frame
    ): Chunk[PathChange] < (Async & Abort[FileWatchException]) =
        clock.advance(Duration.Zero, 10.millis).andThen {
            clock.advance(10.millis, 10.millis).andThen(Scope.run(watcher.events.take(count).run))
        }

    private def glob(value: String): Glob =
        Glob.parse(value) match
            case Result.Success(glob) => glob
            case Result.Failure(error) =>
                throw new AssertionError(s"invalid test glob at ${error.offset}: ${error.reason}")
            case Result.Panic(error) => throw error

    "watch suite emits Created after registration" in {
        withControlledClock { (fileSystem, root, clock) =>
            val file = root / "created.txt"
            fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                fileSystem.write(file, "created", Path.WriteOptions()).andThen {
                    take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Created(file))))
                }
            }
        }
    }

    "watch suite emits Modified" in {
        withControlledClock { (fileSystem, root, clock) =>
            val file = root / "modified.txt"
            fileSystem.write(file, "before", Path.WriteOptions()).andThen {
                fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                    fileSystem.write(file, "after-value", Path.WriteOptions()).andThen {
                        take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Modified(file))))
                    }
                }
            }
        }
    }

    "watch suite emits Removed" in {
        withControlledClock { (fileSystem, root, clock) =>
            val file = root / "removed.txt"
            fileSystem.write(file, "before", Path.WriteOptions()).andThen {
                fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                    fileSystem.removeExisting(file).andThen {
                        take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Removed(file))))
                    }
                }
            }
        }
    }

    "watch suite emits Moved within the selected view" in {
        withControlledClock { (fileSystem, root, clock) =>
            val from = root / "from.txt"
            val to   = root / "to.txt"
            fileSystem.write(from, "before", Path.WriteOptions()).andThen {
                fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                    fileSystem.move(from, to, Path.MoveOptions()).andThen {
                        take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Moved(from, to))))
                    }
                }
            }
        }
    }

    "watch suite honors immediate and recursive depth" in {
        withControlledClock { (fileSystem, root, clock) =>
            val nested    = root / "nested"
            val deep      = nested / "deep.txt"
            val direct    = root / "direct.txt"
            val recursive = nested / "recursive.txt"
            fileSystem.mkDir(nested).andThen {
                fileSystem.openWatcher(root, WatchOptions()).map { immediate =>
                    fileSystem.openWatcher(root, WatchOptions(depth = WatchDepth.Recursive)).map { recursiveWatcher =>
                        fileSystem.write(deep, "deep", Path.WriteOptions()).andThen {
                            fileSystem.write(direct, "direct", Path.WriteOptions()).andThen {
                                take(clock, immediate, 1).map { immediateEvents =>
                                    take(clock, recursiveWatcher, 2).map { recursiveEvents =>
                                        assert(immediateEvents == Chunk(PathChange.Created(direct)))
                                        assert(recursiveEvents.contains(PathChange.Created(deep)))
                                        assert(recursiveEvents.contains(PathChange.Created(direct)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "watch suite applies a root-relative glob and move normalization" in {
        withControlledClock { (fileSystem, root, clock) =>
            val outside = root / "value.bin"
            val inside  = root / "value.txt"
            val left    = root / "left.bin"
            fileSystem.write(outside, "value", Path.WriteOptions()).andThen {
                fileSystem.openWatcher(root, WatchOptions(glob = glob("*.txt"))).map { watcher =>
                    fileSystem.move(outside, inside, Path.MoveOptions()).andThen {
                        take(clock, watcher, 1).map { entered =>
                            fileSystem.move(inside, left, Path.MoveOptions()).andThen {
                                take(clock, watcher, 1).map { exited =>
                                    assert(entered == Chunk(PathChange.Created(inside)))
                                    assert(exited == Chunk(PathChange.Removed(inside)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "watch suite enforces the exact logical capacity" in {
        withControlledClock { (fileSystem, root, clock) =>
            fileSystem.openWatcher(root, WatchOptions(capacity = 3)).map { watcher =>
                Kyo.foreachDiscard(0 until 4)(index =>
                    fileSystem.write(root / s"capacity-$index.txt", index.toString, Path.WriteOptions())
                ).andThen {
                    takeQueued(clock, watcher, 1).map { events =>
                        val resumed = root / "capacity-resumed.txt"
                        fileSystem.write(resumed, "resumed", Path.WriteOptions()).andThen {
                            take(clock, watcher, 1).map { resumedEvents =>
                                assert(events == Chunk(PathChange.Overflow(root)))
                                assert(resumedEvents == Chunk(PathChange.Created(resumed)))
                            }
                        }
                    }
                }
            }
        }
    }

    "watch suite emits Invalidated when the watched root is removed" in {
        withControlledClock { (fileSystem, root, clock) =>
            fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                fileSystem.removeAll(root).andThen {
                    take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Invalidated(root))))
                }
            }
        }
    }

    "watch suite uses the backend case policy for FileSystemDefault" in {
        withControlledClock { (fileSystem, root, clock) =>
            val upper = root / "UPPER.TXT"
            val lower = root / "lower.txt"
            fileSystem.defaultCaseSensitivity.map { caseSensitivity =>
                fileSystem.openWatcher(root, WatchOptions(glob = glob("*.txt"))).map { watcher =>
                    fileSystem.write(upper, "upper", Path.WriteOptions()).andThen {
                        val finish: Unit < (Sync & Abort[FileWriteException]) = caseSensitivity match
                            case Glob.CaseSensitivity.Sensitive   => fileSystem.write(lower, "lower", Path.WriteOptions())
                            case Glob.CaseSensitivity.Insensitive => ()
                        finish.andThen {
                            val expected = caseSensitivity match
                                case Glob.CaseSensitivity.Sensitive   => lower
                                case Glob.CaseSensitivity.Insensitive => upper
                            take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Created(expected))))
                        }
                    }
                }
            }
        }
    }

    "watch suite releases resources with the acquisition scope" in {
        withControlledClock { (fileSystem, root, _) =>
            Scope.run(fileSystem.openWatcher(root, WatchOptions())).map { watcher =>
                Scope.run(watcher.events.run).map(events => assert(events.isEmpty))
            }
        }
    }
end FileSystemWatchTestSuite

class HostPathWatchTest extends FileSystemWatchTestSuite:
    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        val fileSystem = FileSystem.host
        Scope.acquireRelease(fileSystem.tempDir("kyo-path-watch"))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            use(fileSystem, handle.path)
        }
    end withFileSystem
end HostPathWatchTest

class OverlayPathWatchTest extends FileSystemWatchTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { lower =>
            val root = Path("overlay-watch-root")
            lower.mkDir(root).andThen {
                FileSystem.overlay(lower).map(overlay => use(overlay, root))
            }
        }

    "overlay watching observes lower-backend changes in its effective view" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { lower =>
                val root = Path("overlay-lower-watch-root")
                val file = root / "lower-created.txt"
                lower.mkDir(root).andThen {
                    FileSystem.overlay(lower).map { overlay =>
                        overlay.openWatcher(root, WatchOptions()).map { watcher =>
                            lower.write(file, "lower", Path.WriteOptions()).andThen {
                                clock.advance(Duration.Zero, 10.millis).andThen(clock.advance(10.millis, 10.millis)).andThen {
                                    Scope.run(watcher.events.take(1).run).map { events =>
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
end OverlayPathWatchTest

/** The in-memory backend has its own watcher, event-sourced from its state journal and entirely
  * separate from the polling watcher the host and overlay use. It advertises the Watch tier, so it
  * owes the same contract; before this it had only its own ad-hoc cases and the two implementations
  * were never held to the same assertions.
  */
class InMemoryPathWatchTest extends FileSystemWatchTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("in-memory-watch-root")
            fileSystem.mkDir(root).andThen(use(fileSystem, root))
        }
end InMemoryPathWatchTest

/** An overlay over the host, which is the only configuration where the overlay's `stableIdentity`
  * delegation is reachable.
  *
  * The existing overlay registration uses an in-memory lower, and that backend inherits the absent
  * default for `stableIdentity`, so the delegation branch never returns an identity and the move
  * correlation it feeds is never exercised. A host lower reports a real one.
  */
class OverlayOverHostPathWatchTest extends FileSystemWatchTestSuite:
    private given Frame = Frame.internal

    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystemConformanceFixtures.host("kyo-overlay-host-watch").map { (lower, root) =>
            FileSystem.overlay(lower).map(overlay => use(overlay, root))
        }

    "overlay watching correlates a rename performed in the lower" in {
        // This is the case that reaches the overlay's stableIdentity delegation. A rename made
        // *through* the overlay is answered from the journal, which carries the move, so the
        // delegation is never consulted; only a rename made in the lower falls through to it.
        // Correlating a rename into a single Moved requires a stable identity from both snapshots,
        // so a delegation that returned nothing would degrade this into Removed plus Created.
        Clock.withTimeControl { clock =>
            FileSystemConformanceFixtures.host("kyo-overlay-host-rename").map { (lower, root) =>
                val before = root / "before.txt"
                val after  = root / "after.txt"
                lower.write(before, "payload", Path.WriteOptions()).andThen {
                    FileSystem.overlay(lower).map { overlay =>
                        overlay.openWatcher(root, WatchOptions()).map { watcher =>
                            lower.move(before, after, Path.MoveOptions()).andThen {
                                clock.advance(Duration.Zero, 10.millis).andThen(clock.advance(10.millis, 10.millis)).andThen {
                                    Scope.run(watcher.events.take(1).run).map { events =>
                                        assert(
                                            events == Chunk(PathChange.Moved(before, after)),
                                            s"expected one Moved correlated by file identity, got $events"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
end OverlayOverHostPathWatchTest
