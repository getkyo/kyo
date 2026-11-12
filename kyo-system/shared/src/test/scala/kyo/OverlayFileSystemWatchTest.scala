package kyo

import kyo.Path.Change as PathChange
import kyo.Path.WatchOptions

/** Overlay watching beyond the shared watch contract: changes made in the lower, beneath the overlay, reach its watchers. */
class OverlayFileSystemWatchTest extends kyo.test.Test[Any]:

    /** Advances the polling watcher one tick at a time until `count` events arrive, failing rather than hanging after `maxTicks`. */
    private def take(clock: Clock.TimeControl, watcher: Path.Watcher, count: Int)(using
        Frame
    ): Chunk[PathChange] < (Async & Abort[FileWatchException]) =
        val maxTicks = 1000
        Fiber.initUnscoped(Scope.run(watcher.events.take(count).run)).map { fiber =>
            Loop.indexed { i =>
                fiber.done.map {
                    case true                   => Loop.done(())
                    case false if i >= maxTicks =>
                        fiber.interrupt.andThen(Abort.panic(new AssertionError(s"no events after $maxTicks ticks of ${10.millis}")))
                    case false => clock.advance(10.millis).andThen(Loop.continue)
                }
            }.andThen(fiber.get)
        }
    end take

    "overlay watching observes lower-backend changes in its effective view" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { lower =>
                val root = Path("overlay-lower-watch-root")
                val file = root / "lower-created.txt"
                lower.mkDir(root).andThen {
                    FileSystem.overlay(lower).map { overlay =>
                        overlay.openWatcher(root, WatchOptions()).map { watcher =>
                            lower.write(file, "lower", Path.WriteOptions()).andThen {
                                take(clock, watcher, 1).map(events => assert(events == Chunk(PathChange.Created(file))))
                            }
                        }
                    }
                }
            }
        }
    }

    "overlay watching correlates a rename performed in the lower" in {
        // This is the case that reaches the overlay's stableIdentity delegation. A rename made
        // *through* the overlay is answered from its recorded provenance, which carries the move,
        // so the delegation is never consulted; only a rename made in the lower falls through.
        // Correlating a rename into a single Moved requires a stable identity from both snapshots,
        // so a delegation that returned nothing would degrade this into Removed plus Created.
        Clock.withTimeControl { clock =>
            Scope.acquireRelease(FileSystem.host.tempDir("kyo-overlay-host-rename"))(handle => Sync.Unsafe.defer(handle.remove())).map {
                handle =>
                    val lower  = FileSystem.host
                    val root   = handle.path
                    val before = root / "before.txt"
                    val after  = root / "after.txt"
                    lower.write(before, "payload", Path.WriteOptions()).andThen {
                        FileSystem.overlay(lower).map { overlay =>
                            overlay.openWatcher(root, WatchOptions()).map { watcher =>
                                lower.move(before, after, Path.MoveOptions()).andThen {
                                    take(clock, watcher, 1).map { events =>
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

end OverlayFileSystemWatchTest
