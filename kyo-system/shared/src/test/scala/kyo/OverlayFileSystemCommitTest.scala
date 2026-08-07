package kyo

import java.nio.charset.StandardCharsets

/** Tests for the durable overlay commit machinery in [[OverlayFileSystem]].
  *
  * Covers conflict detection and abort, Move/Copy resolved-entry replay, all four commitWith
  * resolution types, and the WriteOpLog decode-failure
  * split (torn/CRC-failed = silent discard; bad magic or wrong version = loud fail).
  *
  * All arms use in-memory lower for determinism. Lower is mutated out-of-band to create conflict
  * conditions. No `Thread.sleep` anywhere.
  */
class OverlayFileSystemCommitTest extends kyo.test.Test[Any]:

    private type Staged = FileSystem.StagedChanges[Sync & Abort[FileSystemException]] & FileSystem.Write[Sync]

    private def withOverlay[A, S](
        program: (Staged, FileSystem.Write[Sync]) => A < (Sync & Abort[FileSystemException] & S)
    ): A < (Sync & Scope & Abort[FileSystemException] & S) =
        FileSystem.inMemory.map { lower =>
            FileSystem.overlay(lower).map { ov =>
                program(ov, lower)
            }
        }

    "commit aborts CommitConflict when lower diverges after observation" in {
        withOverlay { (ov, lower) =>
            val p = Path("p19.txt")
            // Seed lower, read through overlay (stamps it), stage an overlay write,
            // then diverge lower with different-size content to trigger a conflict.
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(ov)(p.write("overlay-value")).andThen {
                        Path.runWith(lower)(p.write("lower-diverged-longer-content")).andThen {
                            Abort.run[CommitConflict](ov.commit).map {
                                case Result.Failure(cc) =>
                                    assert(cc.conflicts.size == 1)
                                    assert(cc.conflicts.head.path == p)
                                    // Lower must be unchanged by the failed commit.
                                    Path.runWith(lower)(p.read).map { lowerVal =>
                                        assert(lowerVal == "lower-diverged-longer-content")
                                    }
                                case other =>
                                    assert(false, s"expected CommitConflict, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "commit aborts CommitConflict when lower file is deleted after observation" in {
        withOverlay { (ov, lower) =>
            val p = Path("p19-deleted.txt")
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(ov)(p.write("overlay-value")).andThen {
                        Path.runWith(lower)(p.removeExisting).andThen {
                            Abort.run[CommitConflict](ov.commit).map {
                                case Result.Failure(cc) =>
                                    assert(cc.conflicts.size == 1)
                                    // Lower must remain absent (commit did not apply).
                                    Path.runWith(lower)(p.exists).map { e =>
                                        assert(!e)
                                    }
                                case other =>
                                    assert(false, s"expected CommitConflict, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith replays Move using resolved entry when source is deleted before commit" in {
        withOverlay { (ov, lower) =>
            val src  = Path("src-p20.txt")
            val dest = Path("dest-p20.txt")
            // Seed source in lower, stage a move (captures resolved entry at stage time).
            Path.runWith(lower)(src.write("source-content")).andThen {
                Path.runWith(ov)(src.move(dest)).andThen {
                    // Delete source from lower before commit (simulates concurrent deletion).
                    Path.runWith(lower)(src.removeExisting).andThen {
                        // KeepOurs replays via resolved entry, no re-read of source.
                        ov.commitWith(_ => FileSystem.Resolution.KeepOurs).andThen {
                            Path.runWith(lower)(dest.read).map { content =>
                                assert(content == "source-content")
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith replays Copy using resolved entry when source is deleted before commit" in {
        withOverlay { (ov, lower) =>
            val src  = Path("src-p20c.txt")
            val dest = Path("dest-p20c.txt")
            Path.runWith(lower)(src.write("copied-content")).andThen {
                Path.runWith(ov)(src.copy(dest)).andThen {
                    Path.runWith(lower)(src.removeExisting).andThen {
                        ov.commitWith(_ => FileSystem.Resolution.KeepOurs).andThen {
                            Path.runWith(lower)(dest.read).map { content =>
                                assert(content == "copied-content")
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith KeepOurs succeeds despite lower divergence" in {
        withOverlay { (ov, lower) =>
            val p = Path("p21.txt")
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                        Path.runWith(ov)(p.write("overlay-wins")).andThen {
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs).andThen {
                                Path.runWith(lower)(p.read).map { content =>
                                    assert(content == "overlay-wins")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith KeepOurs with empty journal leaves lower unchanged" in {
        withOverlay { (ov, lower) =>
            val p = Path("p21-empty.txt")
            // Create conflict condition but stage nothing (empty journal).
            Path.runWith(lower)(p.write("lower-value")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                        // Empty overlay journal applies nothing.
                        ov.commitWith(_ => FileSystem.Resolution.KeepOurs).andThen {
                            Path.runWith(lower)(p.read).map { content =>
                                assert(content == "lower-diverged-longer")
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith applies all four resolution types in one staged set" in {
        withOverlay { (ov, lower) =>
            val pOurs   = Path("p40-ours.txt")
            val pTheirs = Path("p40-theirs.txt")
            val pWrite  = Path("p40-write.txt")
            val pRemove = Path("p40-remove.txt")

            val mergedBytes = Span.from("merged-value".getBytes(StandardCharsets.UTF_8))
            val mergedEntry = Path.Entry.File(mergedBytes, Path.PathStat(0L, mergedBytes.size.toLong))

            // Seed all four paths in lower.
            Path.runWith(lower) {
                pOurs.write("original")
                    .andThen(pTheirs.write("original"))
                    .andThen(pWrite.write("original"))
                    .andThen(pRemove.write("original"))
            }.andThen {
                // Read all through overlay to stamp them in the read-set.
                Path.runWith(ov) {
                    pOurs.read.andThen(pTheirs.read).andThen(pWrite.read).andThen(pRemove.read)
                }.andThen {
                    // Stage overlay writes for all four paths.
                    Path.runWith(ov) {
                        pOurs.write("ours-version")
                            .andThen(pTheirs.write("ours-version"))
                            .andThen(pWrite.write("ours-version"))
                            .andThen(pRemove.write("ours-version"))
                    }.andThen {
                        // Diverge lower to create size-based conflicts on all four paths.
                        Path.runWith(lower) {
                            pOurs.write("lower-diverged-longer")
                                .andThen(pTheirs.write("lower-diverged-longer"))
                                .andThen(pWrite.write("lower-diverged-longer"))
                                .andThen(pRemove.write("lower-diverged-longer"))
                        }.andThen {
                            // Resolve each conflicting path differently.
                            ov.commitWith { conflict =>
                                if conflict.path == pOurs then Resolution.KeepOurs
                                else if conflict.path == pTheirs then Resolution.KeepTheirs
                                else if conflict.path == pWrite then Resolution.Write(mergedEntry)
                                else Resolution.Remove
                            }.andThen {
                                // KeepOurs: lower receives the overlay-staged value.
                                Path.runWith(lower)(pOurs.read).map { c =>
                                    assert(c == "ours-version")
                                }.andThen {
                                    // KeepTheirs: lower retains its current (diverged) content.
                                    Path.runWith(lower)(pTheirs.read).map { c =>
                                        assert(c == "lower-diverged-longer")
                                    }.andThen {
                                        // Write(entry): lower receives the caller-supplied merged entry.
                                        Path.runWith(lower)(pWrite.read).map { c =>
                                            assert(c == "merged-value")
                                        }.andThen {
                                            // Remove: the path is absent from lower.
                                            Path.runWith(lower)(pRemove.exists).map { e =>
                                                assert(!e)
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

    "commitWith KeepOurs preserves staged write on single conflict" in {
        withOverlay { (ov, lower) =>
            val p = Path("p40-keep-ours.txt")
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(ov)(p.write("overlay")).andThen {
                        Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                            ov.commitWith(_ => Resolution.KeepOurs).andThen {
                                Path.runWith(lower)(p.read).map { c =>
                                    assert(c == "overlay")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith KeepTheirs strips staged write; lower keeps its current content" in {
        withOverlay { (ov, lower) =>
            val p = Path("p40-keep-theirs.txt")
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(ov)(p.write("overlay")).andThen {
                        Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                            ov.commitWith(_ => Resolution.KeepTheirs).andThen {
                                Path.runWith(lower)(p.read).map { c =>
                                    assert(c == "lower-diverged-longer")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith Write substitutes the supplied entry for the conflicting path" in {
        withOverlay { (ov, lower) =>
            val p          = Path("p40-write-entry.txt")
            val writeBytes = Span.from("written-by-resolution".getBytes(StandardCharsets.UTF_8))
            val writeEntry = Path.Entry.File(writeBytes, Path.PathStat(0L, writeBytes.size.toLong))
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(ov)(p.write("overlay")).andThen {
                        Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                            ov.commitWith(_ => Resolution.Write(writeEntry)).andThen {
                                Path.runWith(lower)(p.read).map { c =>
                                    assert(c == "written-by-resolution")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith Remove deletes the conflicting path from lower" in {
        withOverlay { (ov, lower) =>
            val p = Path("p40-remove.txt")
            Path.runWith(lower)(p.write("original")).andThen {
                Path.runWith(ov)(p.read).andThen {
                    Path.runWith(ov)(p.write("overlay")).andThen {
                        Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                            ov.commitWith(_ => Resolution.Remove).andThen {
                                Path.runWith(lower)(p.exists).map { e =>
                                    assert(!e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // WriteOpLog decode-failure split: bad magic or wrong version = loud fail through FileSystemException;
    // torn/truncated log with valid magic = crash artifact, silent Success(Absent).

    "WriteOpLog.decode raises FileIOException on bad magic bytes" in {
        // Bytes that start with unrecognized magic: not our file.
        val badMagic = Span.from(
            Array[Byte]('X'.toByte, 'X'.toByte, 'X'.toByte, 'X'.toByte, 0x01.toByte, 0x00.toByte)
        )
        val logPath = Path("intent.kyo")
        WriteOpLog.decode(logPath, badMagic) match
            case Result.Failure(_: FileIOException) => assert(true)
            case other                              => assert(false, s"expected Failure(FileIOException), got $other")
    }

    "WriteOpLog.decode raises FileIOException on unsupported version" in {
        // Valid KYIL magic but version byte 0x99.
        val wrongVersion = Span.from(
            Array[Byte]('K'.toByte, 'Y'.toByte, 'I'.toByte, 'L'.toByte, 0x99.toByte, 0x00.toByte)
        )
        val logPath = Path("intent.kyo")
        WriteOpLog.decode(logPath, wrongVersion) match
            case Result.Failure(_: FileIOException) => assert(true)
            case other                              => assert(false, s"expected Failure(FileIOException), got $other")
    }

    "WriteOpLog.decode returns Success(Absent) for torn log with valid magic but no terminator" in {
        // Valid KYIL header + version but truncated before the terminator: crash artifact.
        val torn = Span.from(
            Array[Byte]('K'.toByte, 'Y'.toByte, 'I'.toByte, 'L'.toByte, 0x02.toByte)
        )
        val logPath = Path("intent.kyo")
        WriteOpLog.decode(logPath, torn) match
            case Result.Success(Absent) => assert(true)
            case other                  => assert(false, s"expected Success(Absent), got $other")
    }

end OverlayFileSystemCommitTest
