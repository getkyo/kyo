package kyo

/** Tests for [[FileSystem.zip]]'s implementor: stage-then-commit materialization, discard,
  * staged-write shadowing over a baseline entry, tombstoning a baseline-only entry, the
  * commit atomic-move guarantee, and cross-tool interop with a real `java.util.zip.ZipFile`.
  */
class ZipRewriteFileSystemTest extends kyo.test.Test[Any]:

    private def bytesOf(s: String): Span[Byte] = Span.from(s.getBytes("UTF-8"))

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
                val moveOptions = Path.MoveOptions(
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
                val copyOptions = Path.CopyOptions(
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

    "failed transfer after baseline parent materialization commits no staged state" in {
        Scope.run {
            hostTempDir("kyo-zip-transfer-parent-rollback").map { dir =>
                val archive      = dir / "archive.zip"
                val sourceDir    = Path("source")
                val sourceChild  = sourceDir / "child.txt"
                val targetParent = Path("destination") / "nested"
                val target       = targetParent / "copied"
                FileSystem.zip(archive).map { zw =>
                    zw.mkDir(sourceDir)
                        .andThen(zw.write(sourceChild, "baseline-content", Path.WriteOptions()))
                        .andThen(zw.mkDir(Path("destination")))
                        .andThen(zw.mkDir(targetParent))
                        .andThen(zw.commit)
                }.andThen {
                    // Normalize once through the baseline traversal order before byte comparison.
                    FileSystem.zip(archive).map(_.commit).andThen {
                        FileSystem.host.readBytes(archive).map { beforeBytes =>
                            FileSystem.zip(archive).map { zw =>
                                Abort.run[FileSystemException](
                                    zw.copy(sourceDir, target, Path.CopyOptions(createFolders = false))
                                ).map { result =>
                                    zw.read(sourceChild).map { content =>
                                        zw.exists(target).map((result, content, _))
                                    }
                                }.map { (result, content, targetExists) =>
                                    assert(result.isFailure)
                                    assert(content == "baseline-content")
                                    assert(!targetExists)
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

end ZipRewriteFileSystemTest
