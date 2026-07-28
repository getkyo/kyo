package kyo

/** Tests for explicit staged-write lifecycle and visibility. */
class PathStagedWritesTest extends kyo.test.Test[Any]:

    "stageWrites isolates writes until commit" in {
        FileSystem.inMemory.map { lower =>
            val path = Path("staged-visibility.txt")
            Scope.run {
                Abort.run[CommitConflict] {
                    Path.runWith(lower) {
                        Path.stageWrites(path.write("staged").andThen(path.read)).map { (inside, changes) =>
                            assert(inside == "staged")
                            Path.runWith(lower)(path.exists).map { outsideBefore =>
                                assert(!outsideBefore)
                                changes.commit
                            }
                        }
                    }
                }.map {
                    case Result.Success(()) => Path.runWith(lower)(path.read).map(value => assert(value == "staged"))
                    case other              => fail(s"unexpected commit result: $other")
                }
            }
        }
    }

    "commitWritesOnSuccess commits and discardWrites discards" in {
        FileSystem.inMemory.map { lower =>
            val committed = Path("committed.txt")
            val discarded = Path("discarded.txt")
            Abort.run[CommitConflict] {
                Path.runWith(lower) {
                    Path.commitWritesOnSuccess(committed.write("yes")).andThen {
                        Path.discardWrites(discarded.write("no"))
                    }
                }
            }.map {
                case Result.Success(()) =>
                    Path.runWith(lower)(committed.read).map(value => assert(value == "yes")).andThen {
                        Path.runWith(lower)(discarded.exists).map(exists => assert(!exists))
                    }
                case other => fail(s"unexpected result: $other")
            }
        }
    }

    "scope closure discards only open staged changes" in {
        FileSystem.inMemory.map { lower =>
            val open      = Path("open.txt")
            val committed = Path("scope-committed.txt")
            Scope.run {
                Abort.run[CommitConflict] {
                    Path.runWith(lower) {
                        Path.stageWrites(open.write("discarded")).andThen {
                            Path.stageWrites(committed.write("kept")).map(_._2.commit)
                        }
                    }
                }.map {
                    case Result.Success(()) => ()
                    case other              => fail(s"unexpected outer result: $other")
                }
            }.andThen {
                Path.runWith(lower)(open.exists).map(exists => assert(!exists)).andThen {
                    Path.runWith(lower)(committed.read).map(value => assert(value == "kept"))
                }
            }
        }
    }

    "staged changes are one-shot" in {
        FileSystem.inMemory.map { lower =>
            Scope.run {
                Abort.run[CommitConflict] {
                    Path.runWith(lower) {
                        Path.stageWrites(Path("one-shot.txt").write("value")).map { (_, changes) =>
                            changes.commit.andThen {
                                Abort.run[CommitConflict](changes.commit).map {
                                    case Result.Failure(FileSystem.StagedChanges.AlreadyTerminated(action)) =>
                                        assert(action == "commit")
                                    case other => fail(s"expected AlreadyTerminated(commit), got $other")
                                }
                            }
                        }
                    }
                }.map {
                    case Result.Success(()) => ()
                    case other              => fail(s"unexpected outer result: $other")
                }
            }
        }
    }

    "commit detects divergence and commitWith applies a resolution" in {
        FileSystem.inMemory.map { lower =>
            val path = Path("conflict.txt")
            Path.runWith(lower)(path.write("base")).andThen {
                Scope.run {
                    Path.runWith(lower) {
                        Path.stageWrites(path.read.andThen(path.write("ours"))).map { (_, changes) =>
                            Path.runWith(lower)(path.write("theirs")).andThen {
                                Abort.run[CommitConflict](changes.commit).map {
                                    case Result.Failure(conflict) =>
                                        assert(conflict.conflicts.map(_.path) == Chunk(path))
                                        changes.commitWith(_ => FileSystem.Resolution.KeepOurs)
                                    case other => fail(s"expected conflict, got $other")
                                }
                            }
                        }
                    }
                }.andThen(Path.runWith(lower)(path.read).map(value => assert(value == "ours")))
            }
        }
    }

end PathStagedWritesTest
