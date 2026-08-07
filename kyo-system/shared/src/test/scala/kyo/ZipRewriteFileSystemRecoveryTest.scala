package kyo

/** What survives when a zip commit is cut short.
  *
  * [[ZipRewriteFileSystem.commit]] serializes the whole merged view into a temporary directory and
  * then moves that archive over the original, so unlike the overlay it has no journal and no
  * recovery pass: the move either happened or it did not. That makes the atomicity question simpler
  * to state and more important to pin, because a commit cut part-way through is the case that
  * decides whether the caller still has a readable archive.
  *
  * Cut points are injected through the hooks on [[ZipRewriteFileSystem]] rather than by timing, so
  * each case names the step it interrupts. Mirrors [[OverlayFileSystemRecoveryTest]].
  */
class ZipRewriteFileSystemRecoveryTest extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    // Distinct type thrown by the hooks, so a cut commit is told apart from a genuine file error.
    final private class SyntheticCrash(msg: String) extends RuntimeException(msg)

    private val baselineEntry = Path("baseline.txt")
    private val stagedEntry   = Path("staged.txt")

    /** Runs `program` and asserts a [[SyntheticCrash]] escaped, confirming the hook fired rather
      * than the commit completing or failing for an unrelated reason.
      */
    private def attemptCrash(
        program: Unit < (Sync & Abort[FileSystemException] & Abort[CommitConflict])
    )(using kyo.test.AssertScope): Unit < Sync =
        Abort.run[Throwable](Abort.run[FileSystemException | CommitConflict](program)).map {
            case Result.Panic(_: SyntheticCrash)    => ()
            case Result.Panic(e)                    => throw e
            case Result.Success(Result.Success(())) => fail("commit completed; the hook did not fire")
            case Result.Success(Result.Failure(e))  => fail(s"unexpected file error before the hook fired: $e")
            case Result.Success(Result.Panic(e))    => throw e
            case Result.Failure(e)                  => throw e
        }

    /** An archive holding one committed entry. It is produced by a completed commit rather than
      * assembled by hand, so every case starts from a baseline the implementation itself wrote.
      */
    private def withBaselineArchive[A](
        program: Path => A < (Sync & Scope & Abort[FileSystemException] & Abort[CommitConflict])
    ): A < (Sync & Scope & Abort[FileSystemException] & Abort[CommitConflict]) =
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-zip-recovery")) { handle =>
            // Unsafe: removes the OS temp directory on scope exit
            Sync.Unsafe.defer(handle.remove())
        }.map { handle =>
            val archive = handle.path / "recovery.zip"
            FileSystem.zip(archive).map { seed =>
                seed.write(baselineEntry, "baseline-content", Path.WriteOptions())
                    .andThen(seed.commit)
                    .andThen(program(archive))
            }
        }

    /** Opens the archive again, stages one further entry, and hands back the backend so a case can
      * install a hook before committing.
      */
    private def stagedOver[A](archive: Path)(
        program: ZipRewriteFileSystem => A < (Sync & Scope & Abort[FileSystemException] & Abort[CommitConflict])
    ): A < (Sync & Scope & Abort[FileSystemException] & Abort[CommitConflict]) =
        FileSystem.zip(archive).map { fileSystem =>
            fileSystem.write(stagedEntry, "staged-content", Path.WriteOptions()).andThen {
                program(fileSystem.asInstanceOf[ZipRewriteFileSystem])
            }
        }

    /** The archive still holds only what the baseline commit put there. */
    private def assertArchiveUnchanged(archive: Path)(using
        kyo.test.AssertScope
    ): Unit < (Sync & Scope & Abort[FileSystemException]) =
        FileSystem.zipReadOnly(archive).map { readOnly =>
            readOnly.read(baselineEntry).map { content =>
                assert(content == "baseline-content", s"baseline entry was altered: $content")
            }.andThen {
                readOnly.exists(stagedEntry).map { present =>
                    assert(!present, "the staged entry reached the archive from a commit that was cut short")
                }
            }
        }

    /** The archive holds the staged entry as well, so the move completed. */
    private def assertArchiveCommitted(archive: Path)(using
        kyo.test.AssertScope
    ): Unit < (Sync & Scope & Abort[FileSystemException]) =
        FileSystem.zipReadOnly(archive).map { readOnly =>
            readOnly.read(baselineEntry).map { content =>
                assert(content == "baseline-content", s"baseline entry was altered: $content")
            }.andThen {
                readOnly.read(stagedEntry).map { content =>
                    assert(content == "staged-content", s"staged entry missing or wrong after the move: $content")
                }
            }
        }

    "commit cut short after the staging directory is created leaves the archive untouched" in {
        withBaselineArchive { archive =>
            stagedOver(archive) { fileSystem =>
                val staging = new java.util.concurrent.atomic.AtomicReference[Maybe[Path]](Absent)
                fileSystem.afterTempDirHook = dir =>
                    staging.set(Present(dir))
                    throw new SyntheticCrash("after temp dir")
                attemptCrash(fileSystem.commit).andThen {
                    assertArchiveUnchanged(archive).andThen {
                        // The staging directory holds a second full copy of the archive, so it must
                        // not outlive the commit that created it. This is the finalizer added for
                        // D16, asserted from the crash path rather than the success path.
                        Sync.defer(staging.get()).map {
                            case Present(dir) =>
                                // Unsafe: existence probe on a directory outside any backend
                                Sync.Unsafe.defer(dir.unsafe.exists()).map { present =>
                                    assert(present.contains(false), s"staging directory $dir outlived the commit")
                                }
                            case Absent => fail("the hook did not observe a staging directory")
                        }
                    }
                }
            }
        }
    }

    "commit cut short after the rewritten archive is written but before the move leaves the archive untouched" in {
        withBaselineArchive { archive =>
            stagedOver(archive) { fileSystem =>
                fileSystem.afterArchiveWriteHook = () => throw new SyntheticCrash("after archive write")
                // The complete new archive exists on disk at this point. Only the move replaces the
                // original, so cutting here must leave the caller reading exactly what they had.
                attemptCrash(fileSystem.commit).andThen(assertArchiveUnchanged(archive))
            }
        }
    }

    "commit cut short after the move has already applied it leaves the new archive in place" in {
        withBaselineArchive { archive =>
            stagedOver(archive) { fileSystem =>
                fileSystem.afterMoveHook = () => throw new SyntheticCrash("after move")
                // The move is the commit. Nothing failing after it can un-commit, so the staged entry
                // must be readable even though the caller saw the commit raise.
                attemptCrash(fileSystem.commit).andThen(assertArchiveCommitted(archive))
            }
        }
    }

    "discard never enters the materialize path and leaves the archive untouched" in {
        withBaselineArchive { archive =>
            stagedOver(archive) { fileSystem =>
                val entered = new java.util.concurrent.atomic.AtomicBoolean(false)
                fileSystem.afterTempDirHook = _ => entered.set(true)
                fileSystem.discard.andThen {
                    Sync.defer(entered.get()).map { touched =>
                        assert(!touched, "discard reached the materialize path")
                    }.andThen(assertArchiveUnchanged(archive))
                }
            }
        }
    }

end ZipRewriteFileSystemRecoveryTest
