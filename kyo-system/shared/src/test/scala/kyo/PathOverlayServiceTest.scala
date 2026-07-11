package kyo

import java.nio.charset.StandardCharsets

class PathOverlayServiceTest extends kyo.test.Test[Any]:

    // Helper: run a program through a fresh overlay over a fresh in-memory lower service.
    // The inner function receives both the overlay (as CommitHandle so callers can commit) and
    // the lower service directly (for pre-/post-commit inspection by commit-isolation tests).
    private def withOverlay[A, S](
        program: (Path.Service.Overlay[Sync], Path.Service[Sync]) => A < (Sync & Abort[FileException] & S)
    ): A < (Sync & Scope & Abort[FileException] & S) =
        Path.Service.inMemory.map { lower =>
            Path.Service.overlay(lower).map { ov =>
                program(ov, lower)
            }
        }

    // Helper: run through overlay only, lower not needed.
    private def withOv[A, S](
        program: Path.Service.Overlay[Sync] => A < (Sync & Abort[FileException] & S)
    ): A < (Sync & Scope & Abort[FileException] & S) =
        withOverlay((ov, _) => program(ov))

    "overlay disposition is ManualCommit" in {
        withOv { ov =>
            assert(ov.disposition == Path.Disposition.ManualCommit)
        }
    }

    "write to overlay upper, read returns the written value (upper wins)" in {
        withOverlay { (ov, lower) =>
            val p      = Path("file.txt")
            val lower2 = lower
            // Seed lower with original content
            Path.runWith(lower2)(p.write("original")).andThen {
                // Write to overlay (stages in upper)
                Path.runWith(ov)(p.write("overlay-value")).andThen {
                    // Read through overlay returns the upper value
                    Path.runWith(ov)(p.read).map { result =>
                        assert(result == "overlay-value")
                    }
                }
            }
        }
    }

    "remove via overlay makes path report missing through overlay (Whiteout)" in {
        withOverlay { (ov, lower) =>
            val p = Path("ghost.txt")
            Path.runWith(lower)(p.write("lower-content")).andThen {
                Path.runWith(ov)(p.remove).andThen {
                    Path.runWith(ov)(p.exists).map { found =>
                        assert(!found)
                    }
                }
            }
        }
    }

    "unread path in overlay falls through to lower" in {
        withOverlay { (ov, lower) =>
            val p = Path("lower-only.txt")
            Path.runWith(lower)(p.write("lower-content")).andThen {
                Path.runWith(ov)(p.read).map { result =>
                    assert(result == "lower-content")
                }
            }
        }
    }

    "remove lower file: exists returns false and list omits it" in {
        withOverlay { (ov, lower) =>
            val dir  = Path("dir")
            val file = dir / "a.txt"
            Path.runWith(lower) {
                dir.mkDir.andThen(file.write("content"))
            }.andThen {
                Path.runWith(ov) {
                    file.remove.andThen {
                        file.exists.map { e =>
                            assert(!e)
                        }.andThen {
                            dir.list.map { children =>
                                assert(!children.contains(file))
                            }
                        }
                    }
                }
            }
        }
    }

    "mkDir opaque over lower directory hides lower children in list" in {
        withOverlay { (ov, lower) =>
            val dir   = Path("dir")
            val child = dir / "lower-child.txt"
            Path.runWith(lower) {
                dir.mkDir.andThen(child.write("content"))
            }.andThen {
                // mkDir over an existing lower dir creates OpaqueDir, hiding lower children
                Path.runWith(ov) {
                    dir.mkDir.andThen {
                        dir.list.map { children =>
                            // lower-child.txt should be hidden by OpaqueDir
                            assert(!children.exists(_.name.contains("lower-child.txt")))
                        }
                    }
                }
            }
        }
    }

    "list produces exact sorted Chunk merging upper and lower with deduplication" in {
        withOverlay { (ov, lower) =>
            val dir = Path("dir")
            // Populate lower with b.txt and c.txt
            Path.runWith(lower) {
                dir.mkDir.andThen {
                    (dir / "b.txt").write("b").andThen {
                        (dir / "c.txt").write("c")
                    }
                }
            }.andThen {
                // Overlay: add a.txt in upper, remove c.txt (whiteout)
                Path.runWith(ov) {
                    (dir / "a.txt").write("a").andThen {
                        (dir / "c.txt").remove.andThen {
                            dir.list.map { children =>
                                val names = children.map(_.name.getOrElse(""))
                                // a.txt (upper), b.txt (lower, kept), c.txt (whiteout, dropped)
                                assert(names == Chunk("a.txt", "b.txt"))
                            }
                        }
                    }
                }
            }
        }
    }

    "list is sorted by segment name" in {
        withOverlay { (ov, lower) =>
            val dir = Path("dir")
            Path.runWith(lower) {
                dir.mkDir.andThen {
                    (dir / "z.txt").write("z").andThen {
                        (dir / "m.txt").write("m")
                    }
                }
            }.andThen {
                Path.runWith(ov) {
                    (dir / "a.txt").write("a").andThen {
                        dir.list.map { children =>
                            val names = children.map(_.name.getOrElse(""))
                            assert(names == names.sortBy(identity))
                        }
                    }
                }
            }
        }
    }

    "lower is byte-identical before commit: write on overlay does not touch lower" in {
        withOverlay { (ov, lower) =>
            val p = Path("f.txt")
            // Seed lower
            Path.runWith(lower)(p.write("original")).andThen {
                // Write through overlay (stages in upper only)
                Path.runWith(ov)(p.write("new-value")).andThen {
                    // Lower still has the original content
                    Path.runWith(lower)(p.read).map { result =>
                        assert(result == "original")
                    }
                }
            }
        }
    }

    "lower is byte-identical before commit: remove on overlay does not delete from lower" in {
        withOverlay { (ov, lower) =>
            val p = Path("ghost.txt")
            Path.runWith(lower)(p.write("present")).andThen {
                Path.runWith(ov)(p.remove).andThen {
                    // Lower still has the file
                    Path.runWith(lower)(p.exists).map { found =>
                        assert(found)
                    }
                }
            }
        }
    }

    "lower is byte-identical before commit: mkDir on overlay does not create dir in lower" in {
        withOverlay { (ov, lower) =>
            val dir = Path("new-dir")
            Path.runWith(ov)(dir.mkDir).andThen {
                // Overlay reports the dir exists
                Path.runWith(ov)(dir.exists).map(e => assert(e)).andThen {
                    // Lower does not have it yet
                    Path.runWith(lower)(dir.exists).map { e =>
                        assert(!e)
                    }
                }
            }
        }
    }

    "first read of lower file records a Path.Stamp with correct type, size, and mtime" in {
        // Verify via conflict detection: observe lower file (stamps it), then remove from lower
        // so that the commit detects a File→Absent mismatch. The conflict's `ancestor` field
        // IS the recorded stamp; checking it proves stamp.entryType, size, and mtime are correct.
        // Using removal (not re-write) avoids timing flakiness from same-millisecond mtime updates.
        withOverlay { (ov, lower) =>
            val p = Path("stamped.txt")
            Path.runWith(lower)(p.write("hello")).andThen {
                Path.runWith(lower)(p.stat).map { lowerStat =>
                    Path.runWith(ov)(p.read).andThen {
                        // Remove from lower: forces File→Absent type mismatch in validate
                        Path.runWith(lower)(p.removeExisting).andThen {
                            Abort.run[CommitConflict](ov.commit).map {
                                case Result.Failure(cc) =>
                                    assert(cc.conflicts.size == 1)
                                    val conflict = cc.conflicts.head
                                    assert(conflict.path == p)
                                    conflict.ancestor match
                                        case Absent => assert(false, "expected Present ancestor stamp")
                                        case Present(stamp) =>
                                            assert(stamp.entryType == Path.Stamp.Kind.File)
                                            assert(stamp.size == Present(lowerStat.sizeBytes.bytes))
                                            assert(stamp.lastModifiedMs == Present(lowerStat.lastModifiedMs))
                                    end match
                                case other =>
                                    assert(false, s"expected CommitConflict, got $other")
                            }
                        }
                    }
                }
            }
        }
    }

    // type-transition cases: file to directory and back

    "mkDir over a lower file creates OpaqueDir (upper dir shadows lower file)" in {
        withOverlay { (ov, lower) =>
            val p = Path("target")
            Path.runWith(lower)(p.write("file-content")).andThen {
                Path.runWith(ov) {
                    p.mkDir.andThen {
                        p.isDirectory.map { isDir =>
                            assert(isDir)
                        }.andThen {
                            p.isRegularFile.map { isFile =>
                                assert(!isFile)
                            }
                        }
                    }
                }
            }
        }
    }

    "write over a lower directory stages File entry (upper file shadows lower dir)" in {
        withOverlay { (ov, lower) =>
            val dir = Path("mydir")
            Path.runWith(lower) {
                dir.mkDir.andThen {
                    (dir / "child.txt").write("child")
                }
            }.andThen {
                Path.runWith(ov) {
                    dir.write("now-a-file").andThen {
                        dir.isRegularFile.map { isFile =>
                            assert(isFile)
                        }.andThen {
                            dir.isDirectory.map { isDir =>
                                assert(!isDir)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Additional correctness cases ---

    "append to a lower file copies it up and appends" in {
        withOverlay { (ov, lower) =>
            val p = Path("appendme.txt")
            Path.runWith(lower)(p.write("hello")).andThen {
                Path.runWith(ov) {
                    p.append(" world").andThen(p.read)
                }.map { result =>
                    assert(result == "hello world")
                }
            }
        }
    }

    "append to an upper file concatenates in-place (no lower consult)" in {
        withOverlay { (ov, _) =>
            val p = Path("newfile.txt")
            Path.runWith(ov) {
                p.write("foo").andThen(p.append("bar")).andThen(p.read)
            }.map { result =>
                assert(result == "foobar")
            }
        }
    }

    "rollback discards all staged writes" in {
        withOverlay { (ov, lower) =>
            val p = Path("will-vanish.txt")
            Path.runWith(ov)(p.write("staged")).andThen {
                ov.rollback.andThen {
                    Path.runWith(lower)(p.exists).map { found =>
                        assert(!found)
                    }
                }
            }
        }
    }

    "commitOverwrite replays staged journal onto lower" in {
        withOverlay { (ov, lower) =>
            val p = Path("committed.txt")
            Path.runWith(ov)(p.write("payload")).andThen {
                ov.commitOverwrite.andThen {
                    Path.runWith(lower)(p.read).map { result =>
                        assert(result == "payload")
                    }
                }
            }
        }
    }

    "commit with no conflicts replays journal onto lower" in {
        withOverlay { (ov, lower) =>
            val p = Path("conflict-free.txt")
            Path.runWith(ov)(p.write("value")).andThen {
                Abort.run[CommitConflict](ov.commit).map {
                    case Result.Success(_) =>
                        Path.runWith(lower)(p.read).map { result =>
                            assert(result == "value")
                        }
                    case Result.Failure(e) =>
                        assert(false, s"unexpected CommitConflict: $e")
                    case Result.Panic(t) =>
                        assert(false, s"unexpected panic: $t")
                }
            }
        }
    }

    "commit detects conflict when lower diverges after observation" in {
        withOverlay { (ov, lower) =>
            val p = Path("diverge.txt")
            // Seed lower, observe through overlay (stamps it), then mutate lower directly
            Path.runWith(lower)(p.write("v1")).andThen {
                Path.runWith(ov)(p.read).andThen { // records stamp
                    // Mutate lower directly to simulate external divergence (different length so
                    // the conflict is detected via sizeBytes even if mtime granularity is coarse).
                    Path.runWith(lower)(p.write("v2-modified-content")).andThen {
                        Abort.run[CommitConflict](ov.commit).map {
                            case Result.Failure(cc) =>
                                assert(cc.conflicts.size == 1)
                                assert(cc.conflicts.head.path == p)
                                assert(cc.conflicts.head.ancestor.isDefined)
                            case other =>
                                assert(false, s"expected CommitConflict, got $other")
                        }
                    }
                }
            }
        }
    }

    "commitWith KeepTheirs: lower keeps its current content after conflict resolution" in {
        withOverlay { (ov, lower) =>
            val p = Path("cw-keep-theirs.txt")
            // Seed lower, observe through overlay (stamps it), stage an overlay version,
            // then diverge lower to create a conflict.
            Path.runWith(lower)(p.write("original-content")).andThen {
                Path.runWith(ov)(p.read).andThen {                                      // records stamp for "original-content"
                    Path.runWith(ov)(p.write("overlay-version")).andThen {              // staged in upper
                        Path.runWith(lower)(p.write("lower-modified-longer")).andThen { // conflict
                            ov.commitWith(_ => Resolution.KeepTheirs).andThen {
                                // KeepTheirs: overlay staged write is dropped; lower keeps its current content.
                                Path.runWith(lower)(p.read).map { content =>
                                    assert(content == "lower-modified-longer")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith Write(entry): lower receives the substituted entry bytes after conflict resolution" in {
        withOverlay { (ov, lower) =>
            val p           = Path("cw-write-entry.txt")
            val mergedBytes = Span.from("resolved-by-merge".getBytes(StandardCharsets.UTF_8))
            val mergedEntry = Path.Entry.File(mergedBytes, Path.PathStat(0L, mergedBytes.size.toLong))
            Path.runWith(lower)(p.write("original-content")).andThen {
                Path.runWith(ov)(p.read).andThen { // records stamp
                    Path.runWith(ov)(p.write("overlay-version")).andThen {
                        Path.runWith(lower)(p.write("lower-modified-longer")).andThen { // conflict
                            ov.commitWith(_ => Resolution.Write(mergedEntry)).andThen {
                                // Write(entry): lower receives the merged entry, not the staged overlay bytes.
                                Path.runWith(lower)(p.read).map { content =>
                                    assert(content == "resolved-by-merge")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "commitWith Remove: path is absent from lower after conflict resolved to Remove" in {
        withOverlay { (ov, lower) =>
            val p = Path("cw-remove.txt")
            Path.runWith(lower)(p.write("original-content")).andThen {
                Path.runWith(ov)(p.read).andThen { // records stamp
                    Path.runWith(ov)(p.write("overlay-version")).andThen {
                        Path.runWith(lower)(p.write("lower-modified-longer")).andThen { // conflict
                            ov.commitWith(_ => Resolution.Remove).andThen {
                                // Remove: the path is deleted from lower entirely.
                                Path.runWith(lower)(p.exists).map { exists =>
                                    assert(!exists)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "list with overlapping upper+lower names deduplicates correctly" in {
        withOverlay { (ov, lower) =>
            val dir = Path("dedup")
            // Both lower and upper have "shared.txt"; upper value should win
            Path.runWith(lower) {
                dir.mkDir.andThen((dir / "shared.txt").write("lower"))
            }.andThen {
                Path.runWith(ov) {
                    (dir / "shared.txt").write("upper").andThen {
                        dir.list.map { children =>
                            val names = children.map(_.name.getOrElse(""))
                            // shared.txt appears exactly once
                            assert(names.count(_ == "shared.txt") == 1)
                            assert(names.size == 1)
                        }
                    }
                }
            }
        }
    }

end PathOverlayServiceTest
