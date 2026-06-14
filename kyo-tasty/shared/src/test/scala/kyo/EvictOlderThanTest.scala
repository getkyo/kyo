package kyo

/** Tasty.evictOlderThan.
  *
  * evictOlderThan deletes files older than cutoff, keeps recent files.
  * evictOlderThan actually removes files; no stale residue appears on the filesystem.
  * evictOlderThan on an empty directory is a no-op (returns Success without deleting anything).
  * evictOlderThan on a non-existent directory aborts with SnapshotIoError.
  *
  * Uses Path.tempDir and Path.setLastModified so the tests are deterministic and cross-platform.
  */
class EvictOlderThanTest extends kyo.test.Test[Any]:

    // staleMs is a fixed timestamp in the past (2001-09-08 UTC).
    // Any positive maxAge will treat files with this mtime as stale.
    private val staleMs  = 1_000_000_000_000L
    private val maxAge   = 7.days
    private val maxAgeMs = maxAge.toMillis

    "evictOlderThan deletes old .krfl files and keeps recent ones" in {
        Path.tempDir("kyo-evict-old").map { dir =>
            val old1   = dir / "aaa.krfl"
            val old2   = dir / "bbb.krfl"
            val recent = dir / "ccc.krfl"
            old1.writeBytes(Span.from(Array[Byte](1, 2, 3))).map { _ =>
                old1.setLastModified(staleMs).map { _ =>
                    old2.writeBytes(Span.from(Array[Byte](4, 5, 6))).map { _ =>
                        old2.setLastModified(staleMs).map { _ =>
                            recent.writeBytes(Span.from(Array[Byte](7, 8, 9))).map { _ =>
                                Abort.run[TastyError](Tasty.evictOlderThan(dir.toString, maxAge)).map {
                                    case Result.Success(_) =>
                                        old1.exists.map { e1 =>
                                            old2.exists.map { e2 =>
                                                recent.exists.map { er =>
                                                    assert(!e1, s"old aaa.krfl must be deleted; exists=$e1")
                                                    assert(!e2, s"old bbb.krfl must be deleted; exists=$e2")
                                                    assert(er, s"recent ccc.krfl must remain; exists=$er")
                                                    succeed
                                                }
                                            }
                                        }
                                    case Result.Failure(e) =>
                                        fail(s"evictOlderThan must succeed; got TastyError: $e")
                                    case Result.Panic(t) =>
                                        throw t
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "evictOlderThan removes old files completely (no residual paths)" in {
        Path.tempDir("kyo-evict-res").map { dir =>
            val old1 = dir / "aaaa.krfl"
            old1.writeBytes(Span.from(Array[Byte](1, 2, 3))).map { _ =>
                old1.setLastModified(staleMs).map { _ =>
                    Abort.run[TastyError](
                        Tasty.evictOlderThan(dir.toString, maxAge).map { _ =>
                            Abort.recover[FileFsException](e => Abort.fail(TastyError.SnapshotIoError(s"list: ${e.getMessage}")))(
                                Path(dir.toString).list("*.krfl")
                            )
                        }
                    ).map {
                        case Result.Success(remaining) =>
                            assert(
                                remaining.isEmpty,
                                s"old .krfl file must be deleted after eviction; remaining: $remaining"
                            )
                            succeed
                        case Result.Failure(e) =>
                            fail(s"evictOlderThan must succeed; got TastyError: $e")
                        case Result.Panic(t) =>
                            throw t
                    }
                }
            }
        }
    }

    "evictOlderThan removes stale files; no .deleting residue paths appear" in {
        Path.tempDir("kyo-evict-nodot").map { dir =>
            val old1   = dir / "old1.krfl"
            val old2   = dir / "old2.krfl"
            val recent = dir / "recent.krfl"
            old1.writeBytes(Span.from(Array[Byte](1, 2, 3))).map { _ =>
                old1.setLastModified(staleMs).map { _ =>
                    old2.writeBytes(Span.from(Array[Byte](4, 5, 6))).map { _ =>
                        old2.setLastModified(staleMs).map { _ =>
                            recent.writeBytes(Span.from(Array[Byte](7, 8, 9))).map { _ =>
                                Abort.run[TastyError](Tasty.evictOlderThan(dir.toString, maxAge)).map {
                                    case Result.Success(_) =>
                                        old1.exists.map { e1 =>
                                            old2.exists.map { e2 =>
                                                recent.exists.map { er =>
                                                    Abort.recover[FileFsException](_ => Chunk.empty[Path])(
                                                        Path(dir.toString).list
                                                    ).map { remaining =>
                                                        val names = remaining.map(_.toString)
                                                        assert(!e1, s"old1 must be absent; exists=$e1")
                                                        assert(!e2, s"old2 must be absent; exists=$e2")
                                                        assert(er, s"recent must remain; exists=$er")
                                                        assert(
                                                            names.forall(!_.endsWith(".deleting")),
                                                            s"no residue with .deleting suffix; names: $names"
                                                        )
                                                        succeed
                                                    }
                                                }
                                            }
                                        }
                                    case Result.Failure(e) =>
                                        fail(s"evictOlderThan must succeed; got TastyError: $e")
                                    case Result.Panic(t) =>
                                        throw t
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "evictOlderThan on an empty directory is a no-op (Success)" in {
        Path.tempDir("kyo-evict-empty").map { dir =>
            Abort.run[TastyError](Tasty.evictOlderThan(dir.toString, maxAge)).map {
                case Result.Success(_) =>
                    succeed
                case Result.Failure(e) =>
                    fail(s"evictOlderThan on empty dir must be a no-op; got TastyError: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "evictOlderThan on a non-existent directory aborts with SnapshotIoError" in {
        Abort.run[TastyError](
            Tasty.evictOlderThan("/no-such-dir-kyo-evict-test", maxAge)
        ).map {
            case Result.Success(_) =>
                fail("evictOlderThan on non-existent dir must abort with SnapshotIoError")
            case Result.Failure(e: TastyError.SnapshotIoError) =>
                succeed
            case Result.Failure(e) =>
                fail(s"Expected SnapshotIoError but got: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end EvictOlderThanTest
