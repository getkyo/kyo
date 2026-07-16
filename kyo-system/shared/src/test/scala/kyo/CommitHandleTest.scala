package kyo

import java.nio.charset.StandardCharsets

/** Tests for the top-level [[CommitHandle]] surface returned by [[PathService.overlay]]: the
  * entry-valued read-set that [[CommitHandle.commit]] validates against the live lower service,
  * and [[CommitHandle.commitOverwrite]]'s conflict-free last-writer-wins replay.
  */
class CommitHandleTest extends kyo.test.Test[Any]:

    "PathService.overlay returns a CommitHandle whose commit validates the entry-valued read-set" in {
        PathService.inMemory.map { lower =>
            PathService.overlay(lower).map { handle =>
                val p = Path("a")
                // The out-of-band write below uses a DIFFERENT LENGTH from the seed ("longer-content"
                // vs "1") so the divergence is size-driven, not mtime-driven: two same-length writes
                // landing in the same millisecond would make the mtime+size comparison see no change
                // (the OverlayServiceTest "records a Path.Entry" leaf avoids the same trap via removal
                // instead of re-write).
                Path.runWith(lower)(p.write("1")).andThen {
                    Path.runWith(lower)(p.stat).map { lowerStat =>
                        // Observe through the overlay: records the Path.Entry.File in the read-set.
                        Path.runWith(handle)(p.read).andThen {
                            Path.runWith(handle)(p.write("2"))
                        }.andThen {
                            // Mutate the lower directly (out of band, not through the overlay).
                            Path.runWith(lower)(p.write("longer-content")).andThen {
                                Abort.run[CommitConflict](handle.commit).map {
                                    case Result.Failure(cc) =>
                                        assert(cc.conflicts.size == 1)
                                        val conflict = cc.conflicts.head
                                        assert(conflict.path == p)
                                        conflict.ancestor match
                                            case Present(Path.Entry.File(bytes, stat)) =>
                                                assert(new String(bytes.toArrayUnsafe, StandardCharsets.UTF_8) == "1")
                                                assert(stat.sizeBytes == lowerStat.sizeBytes)
                                                assert(stat.lastModifiedMs == lowerStat.lastModifiedMs)
                                            case other =>
                                                assert(false, s"expected Present(Path.Entry.File(...)), got $other")
                                        end match
                                        conflict.theirs match
                                            case Present(Path.Entry.File(bytes, _)) =>
                                                assert(new String(bytes.toArrayUnsafe, StandardCharsets.UTF_8) == "longer-content")
                                            case other =>
                                                assert(false, s"expected theirs to reflect the out-of-band write, got $other")
                                        end match
                                        Path.runWith(lower)(p.read).map(v => assert(v == "longer-content"))
                                    case other =>
                                        assert(false, s"expected CommitConflict, got $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitOverwrite has no CommitConflict in its row and applies last-writer-wins" in {
        PathService.inMemory.map { lower =>
            PathService.overlay(lower).map { handle =>
                val p = Path("a")
                Path.runWith(lower)(p.write("1")).andThen {
                    Path.runWith(handle)(p.read).andThen {
                        Path.runWith(handle)(p.write("2"))
                    }.andThen {
                        Path.runWith(lower)(p.write("9")).andThen {
                            val overwrite: Unit < (Sync & Abort[FileException]) = handle.commitOverwrite
                            overwrite.andThen {
                                Path.runWith(lower)(p.read).map(v => assert(v == "2"))
                            }
                        }
                    }
                }
            }
        }
    }

end CommitHandleTest
