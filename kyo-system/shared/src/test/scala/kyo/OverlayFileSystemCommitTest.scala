package kyo

import java.nio.charset.StandardCharsets

/** Tests for the durable overlay commit machinery in [[OverlayFileSystem]].
  *
  * Covers conflict detection and abort, Move/Copy resolved-entry replay, all four commitWith
  * resolution types, opaque-directory replay, and the WriteOpLog decode-failure split
  * (torn/CRC-failed = silent discard; bad magic or wrong version = loud fail).
  *
  * Every commit arm runs against both lowers through [[withEachLower]]. The lower is mutated
  * out-of-band to create conflict conditions. No `Thread.sleep` anywhere.
  */
class OverlayFileSystemCommitTest extends kyo.test.Test[Any]:

    private type Staged = FileSystem.StagedChanges[Sync & Abort[FileSystemException]] & FileSystem.Write[Sync]

    /** Registers `body` once per lower: the in-memory service, and a host service confined to a
      * scoped temp directory.
      *
      * The two lowers disagree about atomic move, directory metadata, and what `mkDir` does to a
      * directory that already exists, so a commit assertion that holds for one and not the other is
      * a real difference rather than a platform detail. Running only the in-memory lower is what let
      * a replay defect sit unnoticed: its symptom is a lower entry that should have been cleared,
      * and that lower used to clear it for unrelated reasons.
      *
      * `base` is the directory every path in a case must be built under. It is empty for the
      * in-memory lower and the temp directory for the host one, because the confined host service
      * checks that a path lies under its root rather than resolving a relative path against it.
      */
    private def withEachLower(name: String)(
        body: kyo.test.AssertScope ?=> (Staged, FileSystem.Write[Sync], Path) => Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        (name + " (in-memory lower)") in {
            FileSystem.inMemory.map { lower =>
                FileSystem.overlay(lower).map(ov => body(ov, lower, Path()))
            }
        }
        (name + " (host lower)") in {
            Scope.acquireRelease(FileSystem.host.tempDir("kyo-overlay-commit")) { handle =>
                // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
                Sync.Unsafe.defer(handle.remove())
            }.map { handle =>
                // The host hands back a temp directory by a name that may traverse a link: on macOS
                // /var is a link to /private/var. The overlay keys and reports canonical paths, so a
                // case that builds a path from an unresolved base compares a reported path against a
                // different spelling of the same file and fails on the link rather than on the
                // behavior under test.
                FileSystem.host.realPath(handle.path).map { root =>
                    FileSystem.host(root).map { lower =>
                        FileSystem.overlay(lower).map(ov => body(ov, lower, root))
                    }
                }
            }
        }
    end withEachLower

    withEachLower("commit aborts CommitConflict when lower diverges after observation") { (ov, lower, base) =>
        val p = base / "p19.txt"
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

    withEachLower("commit aborts CommitConflict when lower file is deleted after observation") { (ov, lower, base) =>
        val p = base / "p19-deleted.txt"
        Path.runWith(lower)(p.write("original")).andThen {
            Path.runWith(ov)(p.read).andThen {
                Path.runWith(ov)(p.write("overlay-value")).andThen {
                    Path.runWith(lower)(p.removeExisting).andThen {
                        Abort.run[CommitConflict](ov.commit).map {
                            case Result.Failure(cc) =>
                                // Membership rather than an exact count, and only until the read-set
                                // records what each observation actually claimed. The staged write's
                                // parent directory is recorded too, and a directory observation is
                                // currently compared by modification time, so removing the file
                                // reports the parent as a second conflict on any lower where the
                                // parent is a real directory. Nothing here depends on that: what the
                                // case is about is the deleted path being reported and the commit not
                                // applying.
                                assert(
                                    cc.conflicts.exists(_.path == p),
                                    s"the deleted path was not reported: ${cc.conflicts.map(_.path)}"
                                )
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

    withEachLower("commitWith replays Move using resolved entry when source is deleted before commit") { (ov, lower, base) =>
        val src  = base / "src-p20.txt"
        val dest = base / "dest-p20.txt"
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

    withEachLower("commitWith replays Copy using resolved entry when source is deleted before commit") { (ov, lower, base) =>
        val src  = base / "src-p20c.txt"
        val dest = base / "dest-p20c.txt"
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

    withEachLower("commitWith KeepOurs succeeds despite lower divergence") { (ov, lower, base) =>
        val p = base / "p21.txt"
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

    withEachLower("commitWith KeepOurs with nothing staged leaves lower unchanged") { (ov, lower, base) =>
        val p = base / "p21-empty.txt"
        // Create the conflict condition but stage nothing, so the commit has no plan to apply.
        Path.runWith(lower)(p.write("lower-value")).andThen {
            Path.runWith(ov)(p.read).andThen {
                Path.runWith(lower)(p.write("lower-diverged-longer")).andThen {
                    ov.commitWith(_ => FileSystem.Resolution.KeepOurs).andThen {
                        Path.runWith(lower)(p.read).map { content =>
                            assert(content == "lower-diverged-longer")
                        }
                    }
                }
            }
        }
    }

    withEachLower("commitWith applies all four resolution types in one staged set") { (ov, lower, base) =>
        val pOurs   = base / "p40-ours.txt"
        val pTheirs = base / "p40-theirs.txt"
        val pWrite  = base / "p40-write.txt"
        val pRemove = base / "p40-remove.txt"

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

    withEachLower("commitWith KeepOurs preserves staged write on single conflict") { (ov, lower, base) =>
        val p = base / "p40-keep-ours.txt"
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

    withEachLower("commitWith KeepTheirs drops the staged write; lower keeps its current content") { (ov, lower, base) =>
        val p = base / "p40-keep-theirs.txt"
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

    withEachLower("commitWith Write substitutes the supplied entry for the conflicting path") { (ov, lower, base) =>
        val p          = base / "p40-write-entry.txt"
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

    withEachLower("commitWith Remove deletes the conflicting path from lower") { (ov, lower, base) =>
        val p = base / "p40-remove-only.txt"
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

    // An opaque directory is the overlay's whole answer for that path: reads through the overlay
    // show only staged children. A commit that leaves the lower's earlier children in place hands
    // back a directory that lists differently before and after the commit.

    withEachLower("mkDir over a populated directory hides its children after commit") { (ov, lower, base) =>
        val d = base / "opaque-dir"
        Path.runWith(lower)((d / "old.txt").write("stale", Path.WriteOptions(createFolders = true))).andThen {
            Path.runWith(ov) {
                d.mkDir.andThen((d / "new.txt").write("fresh")).andThen(d.list)
            }.map { stagedList =>
                ov.commit.andThen {
                    Path.runWith(lower)(d.list).map { liveList =>
                        val staged = stagedList.map(_.name.getOrElse("")).sorted
                        val live   = liveList.map(_.name.getOrElse("")).sorted
                        assert(staged == Chunk("new.txt"), s"the overlay listed $staged")
                        assert(live == staged, s"the lower listed $live but the overlay listed $staged")
                    }
                }
            }
        }
    }

    withEachLower("copying a directory onto a populated target hides the target's old children") { (ov, lower, base) =>
        val src = base / "copy-src"
        val dst = base / "copy-dst"
        Path.runWith(lower) {
            (src / "a.txt").write("a", Path.WriteOptions(createFolders = true))
                .andThen((dst / "stale.txt").write("stale", Path.WriteOptions(createFolders = true)))
        }.andThen {
            Path.runWith(ov) {
                src.copy(dst, Path.CopyOptions(replace = Path.Replace.Existing)).andThen(dst.list)
            }.map { stagedList =>
                ov.commit.andThen {
                    Path.runWith(lower)(dst.list).map { liveList =>
                        val staged = stagedList.map(_.name.getOrElse("")).sorted
                        val live   = liveList.map(_.name.getOrElse("")).sorted
                        assert(staged == Chunk("a.txt"), s"the overlay listed $staged")
                        assert(live == staged, s"the lower listed $live but the overlay listed $staged")
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
