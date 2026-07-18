package kyo

/** Tests for [[FileSystem.zip]]'s implementor: stage-then-commit materialization, `commitOverwrite`,
  * `rollback`, staged-write shadowing over a baseline entry, tombstoning a baseline-only entry, the
  * commit atomic-move guarantee, and cross-tool interop with a real `java.util.zip.ZipFile`.
  */
class ZipRewriteFileSystemTest extends kyo.test.Test[Any]:

    private def bytesOf(s: String): Span[Byte] = Span.from(s.getBytes("UTF-8"))

    /** A fresh real host temp dir, removed at the enclosing [[Scope]]'s exit. */
    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(h.remove())
        ).map(_.path)

    "stage-then-commit materializes a fresh archive when the target path does not yet exist" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-fresh").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("content"), createFolders = true).andThen(zw.commit)
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

    "commitOverwrite rewrites the whole archive unconditionally" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-overwrite").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map(zw =>
                    zw.writeBytes(Path("old.txt"), bytesOf("old"), createFolders = true).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw2 =>
                        zw2.removeExisting(Path("old.txt"))
                            .andThen(zw2.writeBytes(Path("new.txt"), bytesOf("new"), createFolders = true))
                            .andThen(zw2.commitOverwrite)
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

    "rollback discards every staged write; the archive's bytes on disk are unchanged" in {
        Scope.run {
            hostTempDir("kyo-zip-rewrite-rollback").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map(zw =>
                    zw.writeBytes(Path("a.txt"), bytesOf("a"), createFolders = true).andThen(zw.commit)
                ).andThen {
                    FileSystem.host.readBytes(archive).map { before =>
                        FileSystem.zip(archive).map { zw2 =>
                            zw2.writeBytes(Path("new.txt"), bytesOf("staged"), createFolders = true).andThen(zw2.rollback)
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
                    zw.writeBytes(Path("a.txt"), bytesOf("old"), createFolders = true).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw2 =>
                        zw2.writeBytes(Path("a.txt"), bytesOf("new"), createFolders = true).andThen {
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
                    zw.writeBytes(Path("a.txt"), bytesOf("a"), createFolders = true).andThen(zw.commit)
                ).andThen {
                    FileSystem.zip(archive).map { zw2 =>
                        zw2.removeExisting(Path("a.txt")).andThen(zw2.commitOverwrite)
                    }.andThen {
                        FileSystem.zipReadOnly(archive).map { zr =>
                            zr.exists(Path("a.txt")).map(e => assert(!e))
                        }
                    }
                }
            }
        }
    }

    "commit's atomic move never exposes a partially-written archive to a fresh reader" in {
        val baseNames = Chunk.from(0 until 50).map(i => s"base$i.txt")
        val newNames  = Chunk.from(0 until 10).map(i => s"new$i.txt")

        def verifyAll(archive: Path, n: Int): Unit < (Sync & Scope & Abort[FileException]) =
            FileSystem.zipReadOnly(archive).map { zr =>
                Kyo.foreachDiscard(baseNames ++ newNames) { name =>
                    zr.readBytes(Path(name)).map(result => assert(result.is(bytesOf(s"$name-rep$n"))))
                }
            }

        def rep(archive: Path, n: Int): Unit < (Sync & Scope & Abort[FileException | CommitConflict]) =
            FileSystem.zip(archive).map { zw =>
                Kyo.foreachDiscard(baseNames)(name => zw.writeBytes(Path(name), bytesOf(s"$name-rep$n"), createFolders = true))
                    .andThen(Kyo.foreachDiscard(newNames)(name => zw.writeBytes(Path(name), bytesOf(s"$name-rep$n"), createFolders = true)))
                    .andThen(zw.commit)
            }.andThen(verifyAll(archive, n))

        def loop(archive: Path, n: Int): Unit < (Sync & Scope & Abort[FileException | CommitConflict]) =
            if n > 20 then ()
            else rep(archive, n).andThen(loop(archive, n + 1))

        Scope.run {
            hostTempDir("kyo-zip-rewrite-atomic").map { dir =>
                val archive = dir / "archive.zip"
                FileSystem.zip(archive).map { zw =>
                    Kyo.foreachDiscard(
                        baseNames
                    )(name => zw.writeBytes(Path(name), bytesOf(s"$name-rep0"), createFolders = true)).andThen(zw.commit)
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
                    zw.writeBytes(Path("a.txt"), bytesOf("alpha"), createFolders = true)
                        .andThen(zw.writeBytes(Path("nested", "b.txt"), bytesOf("beta"), createFolders = true))
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
