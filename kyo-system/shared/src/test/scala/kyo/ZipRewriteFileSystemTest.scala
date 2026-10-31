package kyo

/** Tests for [[FileSystem.zip]]'s implementor: stage-then-commit materialization, discard,
  * staged-write shadowing over a baseline entry, tombstoning a baseline-only entry, the
  * commit atomic-move guarantee, and cross-tool interop with a real `java.util.zip.ZipFile`.
  */
class ZipRewriteFileSystemTest extends kyo.test.Test[Any]:

    private def bytesOf(s: String): Span[Byte] = Span.from(s.getBytes("UTF-8"))

    "directory synchronization validates baseline-only entries" in {
        Scope.run {
            hostTempDir("kyo-zip-sync-baseline").map { root =>
                val archive = root / "archive.zip"
                val dir     = Path("nested")
                val file    = dir / "file.txt"
                FileSystem.zip(archive).map { fs =>
                    fs.write(file, "content", Path.WriteOptions()).andThen(fs.commit)
                }.andThen {
                    FileSystem.zip(archive).map { fs =>
                        for
                            directory  <- Abort.run(fs.syncDirectory(dir))
                            regular    <- Abort.run(fs.syncDirectory(file))
                            descendant <- Abort.run(fs.syncDirectory(file / "child"))
                            missing    <- Abort.run(fs.syncDirectory(dir / "missing"))
                        yield
                            assert(directory == Result.succeed(()))
                            assert(regular == Result.fail(FileNotADirectoryException(file)))
                            assert(descendant == Result.fail(FileNotADirectoryException(file / "child")))
                            assert(missing == Result.fail(FileNotFoundException(dir / "missing")))
                    }
                }
            }
        }
    }

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    "stage-then-commit materializes a fresh archive when the target path does not yet exist" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-fresh").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("content"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                }.andThen {
                    FileSystem.host.exists(archive).map { existsNow =>
                        assert(existsNow)
                    }.andThen {
                        FileSystem.zipReadOnly(archive).map { zr =>
                            zr.readBytes(Path("a.txt")).map(result => assert(result.is(bytesOf("content"))))
                        }
                    }
                }
            }
        }
    }

    "commit rewrites the whole archive" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-overwrite").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map(zw =>
                    zw.writeBytes(Path("old.txt"), bytesOf("old"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw2 =>
                        zw2.removeExisting(Path("old.txt"))
                            .andThen(zw2.writeBytes(Path("new.txt"), bytesOf("new"), Path.WriteOptions(createFolders = true)))
                            .andThen(zw2.commit)
                    }.andThen {
                        FileSystem.zipReadOnly(archive).map { zr =>
                            zr.exists(Path("new.txt")).map { newExists =>
                                zr.exists(Path("old.txt")).map { oldExists =>
                                    assert(newExists)
                                    assert(!oldExists)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "discard removes every staged write; the archive's bytes on disk are unchanged" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-rollback").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map(zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("a"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                ).andThen {
                    FileSystem.host.readBytes(archive).map { before =>
                        FileSystem.zip(archive).map { zw2 =>
                            zw2.writeBytes(
                                Path("new.txt"),
                                bytesOf("staged"),
                                Path.WriteOptions(createFolders = true)
                            ).andThen(zw2.discard)
                        }.andThen {
                            FileSystem.host.readBytes(archive).map(after => assert(after.is(before)))
                        }
                    }
                }
            }
        }
    }

    "a staged write shadows the baseline value for the same path on the same handle, before commit" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-shadow").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map(zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("old"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw2 =>
                        zw2.writeBytes(Path("a.txt"), bytesOf("new"), Path.WriteOptions(createFolders = true)).andThen {
                            zw2.readBytes(Path("a.txt")).map(result => assert(result.is(bytesOf("new"))))
                        }
                    }
                }
            }
        }
    }

    "removeExisting on a baseline-only entry (never staged) tombstones it out of the materialized archive" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-tombstone").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map(zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("a"), Path.WriteOptions(createFolders = true)).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw2 =>
                        zw2.removeExisting(Path("a.txt")).andThen(zw2.commit)
                    }.andThen {
                        FileSystem.zipReadOnly(archive).map { zr =>
                            zr.exists(Path("a.txt")).map(e => assert(!e))
                        }
                    }
                }
            }
        }
    }

    "move with Replace.Never rejects a baseline target without changing the effective view" in {
        Scope.run {
            hostTempDir("kyo-zip-move-never").map { dir =>
                val archive = dir / "archive.zip"
                val source  = Path("source.txt")
                val target  = Path("target.txt")
                FileSystem.zip(archive).map(zw =>
                    zw.write(
                        source,
                        "source",
                        Path.WriteOptions()
                    ).andThen(zw.write(target, "target", Path.WriteOptions())).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw =>
                        Abort.run[FileSystemException](zw.move(source, target, Path.MoveOptions(replace = Path.Replace.Never))).map {
                            result =>
                                zw.read(source).map(sourceValue => zw.read(target).map(targetValue => (result, sourceValue, targetValue)))
                        }.map { (result, sourceValue, targetValue) =>
                            assert(result.failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
                            assert(sourceValue == "source")
                            assert(targetValue == "target")
                        }
                    }
                }
            }
        }
    }

    "move with Replace.Existing replaces a baseline target" in {
        Scope.run {
            hostTempDir("kyo-zip-move-existing").map { dir =>
                val archive = dir / "archive.zip"
                val source  = Path("source.txt")
                val target  = Path("target.txt")
                FileSystem.zip(archive).map(zw =>
                    zw.write(
                        source,
                        "source",
                        Path.WriteOptions()
                    ).andThen(zw.write(target, "target", Path.WriteOptions())).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw =>
                        zw.move(source, target, Path.MoveOptions(replace = Path.Replace.Existing)).andThen {
                            zw.exists(source).map(sourceExists => zw.read(target).map((sourceExists, _)))
                        }.map(values => assert(values == (false, "source")))
                    }
                }
            }
        }
    }

    "copy with Replace.Never rejects a baseline target without changing the effective view" in {
        Scope.run {
            hostTempDir("kyo-zip-copy-never").map { dir =>
                val archive = dir / "archive.zip"
                val source  = Path("source.txt")
                val target  = Path("target.txt")
                FileSystem.zip(archive).map(zw =>
                    zw.write(
                        source,
                        "source",
                        Path.WriteOptions()
                    ).andThen(zw.write(target, "target", Path.WriteOptions())).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw =>
                        Abort.run[FileSystemException](zw.copy(source, target, Path.CopyOptions(replace = Path.Replace.Never))).map {
                            result =>
                                zw.read(source).map(sourceValue => zw.read(target).map(targetValue => (result, sourceValue, targetValue)))
                        }.map { (result, sourceValue, targetValue) =>
                            assert(result.failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
                            assert(sourceValue == "source")
                            assert(targetValue == "target")
                        }
                    }
                }
            }
        }
    }

    "copy with Replace.Existing replaces a baseline target" in {
        Scope.run {
            hostTempDir("kyo-zip-copy-existing").map { dir =>
                val archive = dir / "archive.zip"
                val source  = Path("source.txt")
                val target  = Path("target.txt")
                FileSystem.zip(archive).map(zw =>
                    zw.write(
                        source,
                        "source",
                        Path.WriteOptions()
                    ).andThen(zw.write(target, "target", Path.WriteOptions())).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw =>
                        zw.copy(source, target, Path.CopyOptions(replace = Path.Replace.Existing)).andThen {
                            zw.read(source).map(sourceValue => zw.read(target).map((sourceValue, _)))
                        }.map(values => assert(values == ("source", "source")))
                    }
                }
            }
        }
    }

    "move with createFolders false uses a baseline-only destination parent chain" in {
        Scope.run {
            hostTempDir("kyo-zip-move-baseline-parent").map { dir =>
                val archive      = dir / "archive.zip"
                val source       = Path("source.txt")
                val parent       = Path("destination")
                val nestedParent = parent / "nested"
                val target       = nestedParent / "moved.txt"
                val sourceMtime  = 946684800000L
                val parentMtime  = 978307200000L
                val nestedMtime  = 1009843200000L
                val moveOptions  = Path.MoveOptions(
                    replace = Path.Replace.Never,
                    atomicity = Path.Atomicity.Required,
                    createFolders = false
                )
                FileSystem.zip(archive).map { zw =>
                    zw.mkDir(parent)
                        .andThen(zw.mkDir(nestedParent))
                        .andThen(zw.write(source, "baseline-content", Path.WriteOptions()))
                        .andThen(zw.setLastModified(source, sourceMtime))
                        .andThen(zw.setLastModified(parent, parentMtime))
                        .andThen(zw.setLastModified(nestedParent, nestedMtime))
                        .andThen(zw.commit)
                }.andThen {
                    FileSystem.zip(archive).map { zw =>
                        zw.move(source, target, moveOptions).andThen {
                            zw.exists(source).map { sourceExists =>
                                zw.read(target).map { content =>
                                    zw.stat(target).map((sourceExists, content, _))
                                }
                            }
                        }.map { (sourceExists, content, targetStat) =>
                            assert(!sourceExists)
                            assert(content == "baseline-content")
                            assert(targetStat.lastModifiedMs == sourceMtime)
                        }.andThen(zw.commit)
                    }
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { reopened =>
                        reopened.exists(source).map { sourceExists =>
                            reopened.read(target).map { content =>
                                reopened.stat(target).map { targetStat =>
                                    reopened.stat(parent).map { parentStat =>
                                        reopened.stat(nestedParent).map((sourceExists, content, targetStat, parentStat, _))
                                    }
                                }
                            }
                        }
                    }.map { (sourceExists, content, targetStat, parentStat, nestedStat) =>
                        assert(!sourceExists)
                        assert(content == "baseline-content")
                        assert(targetStat.lastModifiedMs == sourceMtime)
                        assert(parentStat.lastModifiedMs == parentMtime)
                        assert(nestedStat.lastModifiedMs == nestedMtime)
                    }
                }
            }
        }
    }

    "copy with createFolders false uses a baseline-only destination parent chain" in {
        Scope.run {
            hostTempDir("kyo-zip-copy-baseline-parent").map { dir =>
                val archive      = dir / "archive.zip"
                val source       = Path("source.txt")
                val parent       = Path("destination")
                val nestedParent = parent / "nested"
                val target       = nestedParent / "copied.txt"
                val sourceMtime  = 946684800000L
                val parentMtime  = 978307200000L
                val nestedMtime  = 1009843200000L
                val copyOptions  = Path.CopyOptions(
                    followLinks = false,
                    replace = Path.Replace.Never,
                    copyAttributes = true,
                    createFolders = false
                )
                FileSystem.zip(archive).map { zw =>
                    zw.mkDir(parent)
                        .andThen(zw.mkDir(nestedParent))
                        .andThen(zw.write(source, "baseline-content", Path.WriteOptions()))
                        .andThen(zw.setLastModified(source, sourceMtime))
                        .andThen(zw.setLastModified(parent, parentMtime))
                        .andThen(zw.setLastModified(nestedParent, nestedMtime))
                        .andThen(zw.commit)
                }.andThen {
                    FileSystem.zip(archive).map { zw =>
                        zw.copy(source, target, copyOptions).andThen {
                            zw.read(source).map { sourceContent =>
                                zw.read(target).map { targetContent =>
                                    zw.stat(target).map((sourceContent, targetContent, _))
                                }
                            }
                        }.map { (sourceContent, targetContent, targetStat) =>
                            assert(sourceContent == "baseline-content")
                            assert(targetContent == "baseline-content")
                            assert(targetStat.lastModifiedMs == sourceMtime)
                        }.andThen(zw.commit)
                    }
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { reopened =>
                        reopened.read(source).map { sourceContent =>
                            reopened.read(target).map { targetContent =>
                                reopened.stat(target).map { targetStat =>
                                    reopened.stat(parent).map { parentStat =>
                                        reopened.stat(nestedParent).map((sourceContent, targetContent, targetStat, parentStat, _))
                                    }
                                }
                            }
                        }
                    }.map { (sourceContent, targetContent, targetStat, parentStat, nestedStat) =>
                        assert(sourceContent == "baseline-content")
                        assert(targetContent == "baseline-content")
                        assert(targetStat.lastModifiedMs == sourceMtime)
                        assert(parentStat.lastModifiedMs == parentMtime)
                        assert(nestedStat.lastModifiedMs == nestedMtime)
                    }
                }
            }
        }
    }

    // A copy of a baseline directory that the target refuses: before it is refused, the copy has a baseline tree to
    // stage and a baseline parent chain to place it in, and none of that may reach the archive.
    "a refused transfer of a baseline directory stages nothing and commits no change" in {
        Scope.run {
            hostTempDir("kyo-zip-transfer-parent-rollback").map { dir =>
                val archive      = dir / "archive.zip"
                val sourceDir    = Path("source")
                val sourceChild  = sourceDir / "child.txt"
                val occupied     = Path("destination")
                val targetParent = occupied / "nested"
                FileSystem.zip(archive).map { zw =>
                    zw.mkDir(sourceDir)
                        .andThen(zw.write(sourceChild, "baseline-content", Path.WriteOptions()))
                        .andThen(zw.mkDir(occupied))
                        .andThen(zw.mkDir(targetParent))
                        .andThen(zw.commit)
                }.andThen {
                    // Normalize once through the baseline traversal order before byte comparison.
                    FileSystem.zip(archive).map(_.commit).andThen {
                        FileSystem.host.readBytes(archive).map { beforeBytes =>
                            FileSystem.zip(archive).map { zw =>
                                Abort.run[FileSystemException](
                                    zw.copy(sourceDir, occupied, Path.CopyOptions(replace = Path.Replace.Existing))
                                ).map { result =>
                                    zw.read(sourceChild).map { content =>
                                        zw.list(occupied).map((result, content, _))
                                    }
                                }.map { (result, content, entries) =>
                                    assert(result == Result.fail(FileDirectoryNotEmptyException(occupied)), s"copy result: $result")
                                    assert(content == "baseline-content")
                                    assert(entries == Chunk(targetParent))
                                }.andThen(zw.commit)
                            }.andThen {
                                FileSystem.host.readBytes(archive).map(afterBytes => assert(afterBytes.is(beforeBytes)))
                            }
                        }
                    }
                }
            }
        }
    }

    "failed baseline move leaves no staged shadow and commits no change" in {
        Scope.run {
            hostTempDir("kyo-zip-move-atomic-failure").map { dir =>
                val archive     = dir / "archive.zip"
                val source      = Path("source.txt")
                val target      = Path("missing") / "target.txt"
                val sourceMtime = 946684800000L
                FileSystem.zip(archive).map { zw =>
                    zw.write(source, "baseline-content", Path.WriteOptions())
                        .andThen(zw.setLastModified(source, sourceMtime))
                        .andThen(zw.commit)
                }.andThen {
                    FileSystem.host.readBytes(archive).map { beforeBytes =>
                        FileSystem.zip(archive).map { zw =>
                            zw.stat(source).map { beforeStat =>
                                Abort.run[FileSystemException](
                                    zw.move(source, target, Path.MoveOptions(createFolders = false))
                                ).map { result =>
                                    zw.read(source).map { sourceContent =>
                                        zw.stat(source).map { afterStat =>
                                            zw.exists(target).map((result, sourceContent, beforeStat, afterStat, _))
                                        }
                                    }
                                }.map { (result, sourceContent, beforeStat, afterStat, targetExists) =>
                                    assert(result.failure.exists(_.isInstanceOf[FileNotFoundException]))
                                    assert(sourceContent == "baseline-content")
                                    assert(afterStat == beforeStat)
                                    assert(!targetExists)
                                }.andThen(zw.commit)
                            }
                        }.andThen {
                            FileSystem.host.readBytes(archive).map { afterBytes =>
                                assert(afterBytes.is(beforeBytes))
                                FileSystem.zipReadOnly(archive).map { reopened =>
                                    reopened.read(source).map(content => reopened.stat(source).map((content, _)))
                                }.map { (content, stat) =>
                                    assert(content == "baseline-content")
                                    assert(stat.lastModifiedMs == sourceMtime)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "failed baseline copy leaves no staged shadow and commits no change" in {
        Scope.run {
            hostTempDir("kyo-zip-copy-atomic-failure").map { dir =>
                val archive     = dir / "archive.zip"
                val source      = Path("source.txt")
                val target      = Path("missing") / "target.txt"
                val sourceMtime = 946684800000L
                FileSystem.zip(archive).map { zw =>
                    zw.write(source, "baseline-content", Path.WriteOptions())
                        .andThen(zw.setLastModified(source, sourceMtime))
                        .andThen(zw.commit)
                }.andThen {
                    FileSystem.host.readBytes(archive).map { beforeBytes =>
                        FileSystem.zip(archive).map { zw =>
                            zw.stat(source).map { beforeStat =>
                                Abort.run[FileSystemException](
                                    zw.copy(source, target, Path.CopyOptions(createFolders = false))
                                ).map { result =>
                                    zw.read(source).map { sourceContent =>
                                        zw.stat(source).map { afterStat =>
                                            zw.exists(target).map((result, sourceContent, beforeStat, afterStat, _))
                                        }
                                    }
                                }.map { (result, sourceContent, beforeStat, afterStat, targetExists) =>
                                    assert(result.failure.exists(_.isInstanceOf[FileNotFoundException]))
                                    assert(sourceContent == "baseline-content")
                                    assert(afterStat == beforeStat)
                                    assert(!targetExists)
                                }.andThen(zw.commit)
                            }
                        }.andThen {
                            FileSystem.host.readBytes(archive).map { afterBytes =>
                                assert(afterBytes.is(beforeBytes))
                                FileSystem.zipReadOnly(archive).map { reopened =>
                                    reopened.read(source).map(content => reopened.stat(source).map((content, _)))
                                }.map { (content, stat) =>
                                    assert(content == "baseline-content")
                                    assert(stat.lastModifiedMs == sourceMtime)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "copyAttributes controls whether a zip rewrite copy preserves source metadata" in {
        Scope.run {
            hostTempDir("kyo-zip-copy-stat").map { dir =>
                val archive     = dir / "archive.zip"
                val source      = Path("source.txt")
                val fresh       = Path("fresh.txt")
                val preserved   = Path("preserved.txt")
                val sourceMtime = 946684800000L
                FileSystem.zip(archive).map { zw =>
                    zw.write(source, "content", Path.WriteOptions())
                        .andThen(zw.setLastModified(source, sourceMtime))
                        .andThen(zw.commit)
                }.andThen {
                    FileSystem.zip(archive).map { zw =>
                        zw.copy(source, fresh, Path.CopyOptions(copyAttributes = false))
                            .andThen(zw.copy(source, preserved, Path.CopyOptions(copyAttributes = true)))
                            .andThen(zw.stat(source).map(sourceStat =>
                                zw.stat(fresh).map(freshStat => zw.stat(preserved).map((sourceStat, freshStat, _)))
                            ))
                            .map { (sourceStat, freshStat, preservedStat) =>
                                assert(sourceStat.lastModifiedMs == sourceMtime)
                                assert(freshStat.lastModifiedMs != sourceMtime)
                                assert(preservedStat == sourceStat)
                            }.andThen(zw.commit)
                    }
                }.andThen {
                    FileSystem.zipReadOnly(archive).map { reopened =>
                        reopened.stat(source).map { sourceStat =>
                            reopened.stat(preserved).map((sourceStat, _))
                        }
                    }.map { (sourceStat, preservedStat) =>
                        assert(sourceStat.lastModifiedMs == sourceMtime)
                        assert(preservedStat == sourceStat)
                    }
                }
            }
        }
    }

    "commit's atomic move never exposes a partially-written archive to a fresh reader" in {
        val baseNames = Chunk.from(0 until 50).map(i => s"base$i.txt")
        val newNames  = Chunk.from(0 until 10).map(i => s"new$i.txt")

        def verifyAll(archive: Path, n: Int): Unit < (Sync & Scope & Abort[FileSystemException]) =
            FileSystem.zipReadOnly(archive).map { zr =>
                Kyo.foreachDiscard(baseNames ++ newNames) { name =>
                    zr.readBytes(Path(name)).map(result => assert(result.is(bytesOf(s"$name-rep$n"))))
                }
            }

        def rep(archive: Path, n: Int): Unit < (Sync & Scope & Abort[FileSystemException | CommitConflict]) =
            FileSystem.zip(archive).map { zw =>
                Kyo.foreachDiscard(baseNames)(name =>
                    zw.writeBytes(Path(name), bytesOf(s"$name-rep$n"), Path.WriteOptions(createFolders = true))
                )
                    .andThen(Kyo.foreachDiscard(newNames)(name =>
                        zw.writeBytes(Path(name), bytesOf(s"$name-rep$n"), Path.WriteOptions(createFolders = true))
                    ))
                    .andThen(zw.commit)
            }.andThen(verifyAll(archive, n))

        def loop(archive: Path, n: Int): Unit < (Sync & Scope & Abort[FileSystemException | CommitConflict]) =
            if n > 20 then ()
            else rep(archive, n).andThen(loop(archive, n + 1))

        Scope.run {
            hostTempDir("kyo-zip-rewrite-atomic").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    Kyo.foreachDiscard(
                        baseNames
                    )(name => zw.writeBytes(Path(name), bytesOf(s"$name-rep0"), Path.WriteOptions(createFolders = true))).andThen(zw.commit)
                }.andThen {
                    loop(archive, 1)
                }
            }
        }
    }

    "a real java.util.zip.ZipFile reads a FileSystem.zip(archive)-committed archive successfully (JVM-only cross-tool leaf)".onlyJvm in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-crosstool").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("alpha"), Path.WriteOptions(createFolders = true))
                        .andThen(zw.writeBytes(Path("nested", "b.txt"), bytesOf("beta"), Path.WriteOptions(createFolders = true)))
                        .andThen(zw.commit)
                }.andThen {
                    Sync.defer {
                        val zf = new java.util.zip.ZipFile(new java.io.File(archive.unsafe.show))
                        try
                            val a = zf.getEntry("a.txt")
                            val b = zf.getEntry("nested/b.txt")
                            assert(a != null && b != null)
                            val aBytes = zf.getInputStream(a).readAllBytes()
                            val bBytes = zf.getInputStream(b).readAllBytes()
                            assert(aBytes.toSeq == "alpha".getBytes("UTF-8").toSeq)
                            assert(bBytes.toSeq == "beta".getBytes("UTF-8").toSeq)
                        finally zf.close()
                        end try
                    }
                }
            }
        }
    }

    /** A rewrite handle over a committed archive holding `base.txt` and `dir/child.txt`, so every entry it starts with is in the
      * baseline and none is staged.
      */
    private def withBaseline[A](prefix: String)(
        use: (FileSystem.Write[Sync] & FileSystem.StagedChanges[Sync & Abort[FileSystemException]], Path) => A <
            (Sync & Scope & Abort[FileSystemException | CommitConflict])
    ): A < (Sync & Scope & Abort[FileSystemException | CommitConflict]) =
        hostTempDir(prefix).map { dir =>
            val archive = dir / "archive.zip"
            FileSystem.zip(archive).map { zw =>
                zw.write(
                    baseFile,
                    "value",
                    Path.WriteOptions()
                ).andThen(zw.write(baseChild, "child", Path.WriteOptions())).andThen(zw.commit)
            }.andThen(FileSystem.zip(archive).map(zw => use(zw, archive)))
        }

    private val baseFile  = Path("base.txt")
    private val baseDir   = Path("dir")
    private val baseChild = baseDir / "child.txt"

    "every way to create an entry beneath a baseline file fails and keeps the file" in {
        Scope.run {
            withBaseline("kyo-zip-beneath-baseline") { (zw, archive) =>
                val beneath                                                                     = baseFile / "child.txt"
                val attempts: Chunk[(String, Unit < (Sync & Abort[FileSystemException]), Path)] = Chunk(
                    ("write", zw.write(beneath, "child", Path.WriteOptions()), beneath),
                    ("append", zw.append(beneath, "child", Path.WriteOptions()), beneath),
                    ("mkFile", zw.mkFile(beneath), beneath),
                    ("mkDir", zw.mkDir(beneath / "nested"), beneath / "nested"),
                    ("openWrite", zw.openWrite(beneath, append = false, Path.WriteOptions()).unit, beneath),
                    ("move", zw.move(baseChild, beneath, Path.MoveOptions()), beneath),
                    ("copy", zw.copy(baseChild, beneath, Path.CopyOptions()), beneath)
                )
                Kyo.foreach(attempts) { (name, attempt, named) =>
                    Abort.run[FileSystemException](attempt).map(result => (name, result, named))
                }.map { results =>
                    zw.read(baseFile).map { staged =>
                        zw.commit.andThen(FileSystem.zipReadOnly(archive)).map { committed =>
                            committed.read(baseFile).map { value =>
                                committed.read(baseChild).map { child =>
                                    results.foreach { (name, result, named) =>
                                        assert(result == Result.fail(FileNotADirectoryException(named)), s"$name reported $result")
                                    }
                                    assert(staged == "value")
                                    assert(value == "value")
                                    assert(child == "child")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "writing a file over a baseline directory fails, and mkFile or mkDir on a baseline entry keeps it" in {
        Scope.run {
            withBaseline("kyo-zip-over-baseline") { (zw, archive) =>
                val attempts: Chunk[(String, Unit < (Sync & Abort[FileSystemException]), Result[FileSystemException, Unit])] = Chunk(
                    ("write", zw.write(baseDir, "file", Path.WriteOptions()), Result.fail(FileIsADirectoryException(baseDir))),
                    ("append", zw.append(baseDir, "file", Path.WriteOptions()), Result.fail(FileIsADirectoryException(baseDir))),
                    (
                        "openWrite",
                        zw.openWrite(baseDir, append = false, Path.WriteOptions()).unit,
                        Result.fail(FileIsADirectoryException(baseDir))
                    ),
                    ("mkDir over a file", zw.mkDir(baseFile), Result.fail(FileAlreadyExistsException(baseFile))),
                    ("mkDir over a directory", zw.mkDir(baseDir), Result.unit),
                    ("mkFile over a file", zw.mkFile(baseFile), Result.unit),
                    ("mkFile over a directory", zw.mkFile(baseDir), Result.unit)
                )
                Kyo.foreach(attempts) { (name, attempt, expected) =>
                    Abort.run[FileSystemException](attempt).map(result => (name, result, expected))
                }.map { results =>
                    zw.commit.andThen(FileSystem.zipReadOnly(archive)).map { committed =>
                        committed.read(baseFile).map { value =>
                            committed.read(baseChild).map { child =>
                                results.foreach { (name, result, expected) =>
                                    assert(result == expected, s"$name reported $result")
                                }
                                assert(value == "value")
                                assert(child == "child")
                            }
                        }
                    }
                }
            }
        }
    }

    "a write into a baseline directory needs no createFolders" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-parent") { (zw, _) =>
                val added = baseDir / "added.txt"
                zw.write(added, "added", Path.WriteOptions(createFolders = false)).andThen(zw.read(added)).map { value =>
                    assert(value == "added")
                }
            }
        }
    }

    "a replacing move or copy onto a baseline directory with entries fails and keeps it" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-nonempty") { (zw, _) =>
                Abort.run[FileSystemException](zw.move(baseFile, baseDir, Path.MoveOptions(replace = Path.Replace.Existing))).map {
                    moved =>
                        Abort.run[FileSystemException](zw.copy(baseFile, baseDir, Path.CopyOptions(replace = Path.Replace.Existing))).map {
                            copied =>
                                zw.read(baseChild).map { child =>
                                    zw.read(baseFile).map { value =>
                                        assert(moved == Result.fail(FileDirectoryNotEmptyException(baseDir)), s"move result: $moved")
                                        assert(copied == Result.fail(FileDirectoryNotEmptyException(baseDir)), s"copy result: $copied")
                                        assert(child == "child")
                                        assert(value == "value")
                                    }
                                }
                        }
                }
            }
        }
    }

    "a removed baseline directory hides its entries, also after it is created again" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-removed") { (zw, archive) =>
                for
                    _              <- zw.removeAll(baseDir)
                    childAfterGone <- zw.exists(baseChild)
                    _              <- zw.mkDir(baseDir)
                    listed         <- zw.list(baseDir)
                    childRecreated <- zw.exists(baseChild)
                    _              <- zw.commit
                    committed      <- FileSystem.zipReadOnly(archive)
                    childCommitted <- committed.exists(baseChild)
                yield
                    assert(!childAfterGone)
                    assert(listed.isEmpty, s"listed $listed")
                    assert(!childRecreated)
                    assert(!childCommitted)
                end for
            }
        }
    }

    "moving or copying a baseline entry onto itself keeps it, and inside itself fails" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-self") { (zw, _) =>
                for
                    _      <- zw.move(baseFile, baseFile, Path.MoveOptions(replace = Path.Replace.Existing))
                    _      <- zw.copy(baseFile, baseFile, Path.CopyOptions(replace = Path.Replace.Existing))
                    _      <- zw.move(baseDir, baseDir, Path.MoveOptions(replace = Path.Replace.Existing))
                    inside <- Abort.run[FileSystemException](zw.move(baseDir, baseDir / "inner", Path.MoveOptions()))
                    value  <- zw.read(baseFile)
                    child  <- zw.read(baseChild)
                    nested <- zw.exists(baseDir / "inner")
                yield
                    assert(inside.isFailure, s"move inside itself reported $inside")
                    assert(value == "value")
                    assert(child == "child")
                    assert(!nested)
                end for
            }
        }
    }

    "remove refuses a baseline directory with entries and keeps it" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-remove") { (zw, _) =>
                for
                    removed  <- Abort.run[FileSystemException](zw.remove(baseDir))
                    existing <- Abort.run[FileSystemException](zw.removeExisting(baseDir))
                    child    <- zw.read(baseChild)
                yield
                    assert(removed == Result.fail(FileDirectoryNotEmptyException(baseDir)), s"remove reported $removed")
                    assert(existing == Result.fail(FileDirectoryNotEmptyException(baseDir)), s"removeExisting reported $existing")
                    assert(child == "child")
                end for
            }
        }
    }

    "moving or copying a baseline directory carries its entries, staged or not" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-directory-transfer") { (zw, archive) =>
                val staged = baseDir / "staged.txt"
                val copied = Path("copied")
                val moved  = Path("moved")
                for
                    _              <- zw.write(staged, "staged", Path.WriteOptions())
                    _              <- zw.copy(baseDir, copied, Path.CopyOptions())
                    _              <- zw.move(baseDir, moved, Path.MoveOptions())
                    _              <- zw.commit
                    committed      <- FileSystem.zipReadOnly(archive)
                    copiedChild    <- committed.read(copied / "child.txt")
                    copiedStaged   <- committed.read(copied / "staged.txt")
                    movedChild     <- committed.read(moved / "child.txt")
                    movedStaged    <- committed.read(moved / "staged.txt")
                    sourceRemains  <- committed.exists(baseDir)
                    sourceChildren <- committed.exists(baseChild)
                yield
                    assert(copiedChild == "child")
                    assert(copiedStaged == "staged")
                    assert(movedChild == "child")
                    assert(movedStaged == "staged")
                    assert(!sourceRemains)
                    assert(!sourceChildren)
                end for
            }
        }
    }

    "walk shows baseline entries with staged ones and hides removed ones" in {
        Scope.run {
            withBaseline("kyo-zip-baseline-walk") { (zw, _) =>
                val staged = baseDir / "staged.txt"
                val nested = baseDir / "nested"
                for
                    _      <- zw.write(staged, "staged", Path.WriteOptions())
                    _      <- zw.write(nested / "deep.txt", "deep", Path.WriteOptions())
                    _      <- zw.remove(baseFile)
                    walked <- Path.runReadOnlyWith(zw)(Path().walk.run)
                    top    <- Path.runReadOnlyWith(zw)(baseDir.walk(1).run)
                yield
                    assert(walked == Chunk(baseDir, baseChild, nested, nested / "deep.txt", staged), s"walked $walked")
                    assert(top == Chunk(baseChild, nested, staged), s"walked $top")
                end for
            }
        }
    }

end ZipRewriteFileSystemTest
