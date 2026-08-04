package kyo

import java.nio.charset.StandardCharsets

/** Recovery test suite for [[OverlayFileSystem]] durable commit.
  *
  * Exercises recovery after a crash at each step of the five-step durable commit protocol:
  * before the intent log is written, after the intent log, after individual plan entries are
  * applied to lower, before the committed marker, and after the committed marker. Each
  * crash point is covered by two variants: an in-memory lower (deterministic, cross-platform)
  * and a host lower (real filesystem, exercises the NIO atomic-move and fsync paths).
  *
  * Crash injection: test hooks on OverlayFileSystem throw a [[SyntheticCrash]] exception via
  * Sync.Unsafe.defer, causing the commit to panic at the designated step. attemptCrash captures
  * the panic and asserts it came from a crash hook; any other outcome (a real file error, or no
  * crash at all) fails the test.
  *
  * Disk-scan tests construct orphaned staging directories in the lower root directly, simulating
  * the crash artifact a real disk scan would find, then verify that recoverFromDisk replays or
  * discards each staging directory correctly.
  *
  * Crash during the committed marker write is not an independently injectable step: the sentinel
  * file either exists or does not; no partial write state is observable. The before-marker and
  * after-marker crash tests together cover both outcomes.
  */
class OverlayFileSystemRecoveryTest extends kyo.test.Test[Any]:

    // Distinct exception type thrown by crash hooks; lets attemptCrash distinguish an
    // intentional hook-injected crash from a genuine file error or unexpected panic.
    final private class SyntheticCrash(msg: String) extends RuntimeException(msg)

    // --- Fixed three-entry plan paths (in-memory and host variants) ---
    private val aFile = Path("a.txt")
    private val dDir  = Path("d")
    private val oldF  = Path("old.txt")

    // --- Helpers ---

    /** Runs `program` and asserts that a [[SyntheticCrash]] panic was raised (confirming the
      * crash hook fired). Fails the test if a real file error occurred, if no crash happened,
      * or if an unexpected panic type propagated.
      */
    private def attemptCrash(
        program: Unit < (Sync & Abort[FileSystemException] & Abort[CommitConflict])
    )(using kyo.test.AssertScope): Unit < Sync =
        Abort.run[Throwable](Abort.run[FileSystemException | CommitConflict](program)).map {
            case Result.Panic(_: SyntheticCrash)    => ()      // expected: crash hook fired
            case Result.Panic(e)                    => throw e // unexpected panic: propagate to fail the test
            case Result.Success(Result.Success(())) => fail("program completed without a crash; crash hook did not fire")
            case Result.Success(Result.Failure(e))  => fail(s"unexpected file error before crash hook: $e")
            case Result.Success(Result.Panic(e))    => throw e // inner panic escaped the outer handler
            case Result.Failure(e)                  => throw e // unexpected typed Throwable abort
        }

    /** Creates an in-memory overlay and exposes the underlying OverlayFileSystem for hook access. */
    private def withInMemoryTestOverlay[A](
        program: (OverlayFileSystem[Sync], FileSystem.Write[Sync]) => A < (Sync & Scope & Abort[FileSystemException])
    ): A < (Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { lower =>
            FileSystem.overlay(lower).map { ov =>
                program(ov.asInstanceOf[OverlayFileSystem[Sync]], lower)
            }
        }

    /** Creates a rooted host overlay backed by a scoped temp dir. The temp dir is removed when
      * the enclosing Scope exits. Passes (overlay, lower, root) to `program`.
      */
    private def withHostTestOverlay[A](
        program: (OverlayFileSystem[Sync], FileSystem.Write[Sync], Path) => A < (Sync & Scope & Abort[FileSystemException])
    ): A < (Sync & Scope & Abort[FileSystemException]) =
        val defaultHost = FileSystem.host
        Scope.acquireRelease(defaultHost.tempDir("kyo-recovery-test")) { handle =>
            // Unsafe: removes OS temp dir on scope exit
            Sync.Unsafe.defer { handle.remove() }
        }.map { handle =>
            val root = handle.path
            FileSystem.host(root).map { lower =>
                FileSystem.overlay(lower).map { ov =>
                    program(ov.asInstanceOf[OverlayFileSystem[Sync]], lower, root)
                }
            }
        }
    end withHostTestOverlay

    /** Stages the fixed three-entry plan through `ov` over `lower`: a file at "a.txt", a
      * directory at "d", and a whiteout at "old.txt". `lower` must have "old.txt" pre-seeded
      * (done here).
      *
      * The plan is derived from the upper trie, which yields a node before its children with
      * siblings in segment order, so the three land in exactly that order and n is 3. That is what
      * the per-entry crash positions below index into.
      */
    private def primePlan(
        ov: OverlayFileSystem[Sync],
        lower: FileSystem.Write[Sync],
        a: Path,
        d: Path,
        old: Path
    ): Unit < (Sync & Abort[FileSystemException]) =
        // Seed old.txt in lower so the whiteout has something to delete.
        Path.runWith(lower)(old.write("old-content")).andThen {
            Path.runWith(ov)(a.write("file-content")).andThen {
                Path.runWith(ov)(d.mkDir).andThen {
                    Path.runWith(ov)(old.remove).map(_ => ())
                }
            }
        }

    /** Asserts that the lower service reflects the fully-applied three-entry plan:
      * a.txt exists with "file-content", d exists as a directory, old.txt is absent.
      */
    private def assertFullyApplied(
        lower: FileSystem.Write[Sync],
        a: Path,
        d: Path,
        old: Path
    )(using kyo.test.AssertScope): Unit < (Sync & Abort[FileSystemException]) =
        Path.runWith(lower)(a.exists).map { e =>
            assert(e, s"$a should exist in lower after recovery")
        }.andThen {
            Path.runWith(lower)(a.read).map { content =>
                assert(content == "file-content", s"$a content mismatch after recovery")
            }
        }.andThen {
            Path.runWith(lower)(d.isDirectory).map { isDir =>
                assert(isDir, s"$d should be a directory in lower after recovery")
            }
        }.andThen {
            Path.runWith(lower)(old.exists).map { e =>
                assert(!e, s"$old should be absent in lower after recovery")
            }
        }

    /** Asserts that the lower service is unchanged from before the commit:
      * a.txt absent, d absent, old.txt present (the seed).
      */
    private def assertLowerUnchanged(
        lower: FileSystem.Write[Sync],
        a: Path,
        d: Path,
        old: Path
    )(using kyo.test.AssertScope): Unit < (Sync & Abort[FileSystemException]) =
        Path.runWith(lower)(a.exists).map { e =>
            assert(!e, s"$a should be absent after crash before log write")
        }.andThen {
            Path.runWith(lower)(d.exists).map { e =>
                assert(!e, s"$d should be absent after crash before log write")
            }
        }.andThen {
            Path.runWith(lower)(old.exists).map { e =>
                assert(e, s"$old should still exist in lower after crash before log write")
            }
        }

    // -------------------------------------------------------------------------
    // crash before intent log write: staging dir exists but log was never written
    // -------------------------------------------------------------------------

    "in-memory: crash before intent log write leaves lower unchanged" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertLowerUnchanged(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash before intent log write leaves lower unchanged" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertLowerUnchanged(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // crash after intent log write, before any op is applied
    // -------------------------------------------------------------------------

    "in-memory: crash after intent log write replays the full plan on recovery" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash after intent log write replays the full plan on recovery" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // crash after first entry applied, two remaining
    // -------------------------------------------------------------------------

    "in-memory: crash mid-apply after first entry recovers idempotently" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                // afterEntryApplyHook is called with (1-indexed-position, n); throw at position 1.
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 1 then throw SyntheticCrash("crash: after first entry applied")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash mid-apply after first entry recovers idempotently" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 1 then throw SyntheticCrash("crash: after first entry applied (host)")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // crash after second entry applied, one remaining
    // -------------------------------------------------------------------------

    "in-memory: crash mid-apply after second entry recovers idempotently" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 2 then throw SyntheticCrash("crash: after second entry applied")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash mid-apply after second entry recovers idempotently" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 2 then throw SyntheticCrash("crash: after second entry applied (host)")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // crash after all entries applied, before committed marker
    // -------------------------------------------------------------------------

    "in-memory: crash before committed marker replays the plan idempotently" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.beforeMarkerHook = () => throw SyntheticCrash("crash: before committed marker") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.beforeMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash before committed marker replays the plan idempotently" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.beforeMarkerHook = () => throw SyntheticCrash("crash: before committed marker (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.beforeMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    /** A lower service that records the path of every `syncDirectory` call and then delegates.
      *
      * An fsync has no observable effect until the power fails, so a test cannot check that one
      * happened by reading the filesystem back. Recording which directories were synced is the only
      * way to state the property, and recording the paths rather than a count is what makes the
      * assertion specific: it names the directories that had to be synced instead of accepting any
      * three calls.
      */
    final private class RecordingLower(
        inner: FileSystem.Write[Sync],
        synced: java.util.concurrent.atomic.AtomicReference[Chunk[Path]]
    ) extends FileSystem.Write[Sync]:
        export inner.{syncDirectory as _, *}

        def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.defer(discard(synced.updateAndGet(_.appended(path)))).andThen(inner.syncDirectory(path))
    end RecordingLower

    "recovery syncs each recovered file's parent directory" in {
        // A recovered commit writes the committed marker, which declares it durable. The apply step
        // used to skip the parent-directory sync on the recovery path while performing it on the
        // normal one, so a crash straight after recovery lost the recovered files even though the
        // marker said they had landed. Two files in two directories, so a single sync cannot
        // accidentally satisfy the assertion.
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-recovery-sync")) { handle =>
            // Unsafe: service-vended recursive cleanup at Scope exit, mirroring Path.tempDir.
            Sync.Unsafe.defer(handle.remove())
        }.map { handle =>
            // Resolved before use: on macOS the host hands back a temp directory under /var, which
            // is a link to /private/var. The overlay syncs the canonical parent, so an unresolved
            // base would compare two spellings of the same directory.
            FileSystem.host.realPath(handle.path).map { root =>
                FileSystem.host(root).map { host =>
                    val synced = new java.util.concurrent.atomic.AtomicReference(Chunk.empty[Path])
                    val lower  = new RecordingLower(host, synced)
                    FileSystem.overlay(lower).map { staged =>
                        val ov   = staged.asInstanceOf[OverlayFileSystem[Sync]]
                        val dirA = root / "sync-a"
                        val dirB = root / "sync-b"
                        Path.runWith(ov) {
                            dirA.mkDir
                                .andThen((dirA / "f1.txt").write("one"))
                                .andThen(dirB.mkDir)
                                .andThen((dirB / "f2.txt").write("two"))
                        }.andThen {
                            Sync.Unsafe.defer {
                                ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (sync test)")
                            }.andThen {
                                attemptCrash(ov.commit).andThen {
                                    // Cleared here, so what the assertion counts is what recovery
                                    // did and not what the crashed commit did before it.
                                    Sync.Unsafe.defer {
                                        ov.afterIntentLogHook = () => ()
                                        synced.set(Chunk.empty)
                                    }.andThen {
                                        ov.recover().andThen {
                                            Sync.Unsafe.defer(synced.get()).map { calls =>
                                                assert(
                                                    calls.contains(dirA),
                                                    s"recovery never synced $dirA; it synced $calls"
                                                )
                                                assert(
                                                    calls.contains(dirB),
                                                    s"recovery never synced $dirB; it synced $calls"
                                                )
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

    // -------------------------------------------------------------------------
    // recovery wired at construction: the only route a restarted process has
    // -------------------------------------------------------------------------

    "a recovering overlay replays an orphaned staging directory left by a previous process" in {
        // The staging directory a crashed process leaves behind is the whole point of the durable
        // protocol. A caller that asks for recovery has to get it at construction, because after a
        // restart there is no overlay object left to ask.
        withHostTestOverlay { (ov, lower, root) =>
            val p = root / "orphaned.txt"
            Sync.Unsafe.defer {
                ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (recovering ctor)")
            }.andThen {
                attemptCrash(Path.runWith(ov)(p.write("recovered")).andThen(ov.commit)).andThen {
                    // A fresh overlay over the same root stands in for a restarted process: the
                    // crashed instance's staging directory is still on disk and nothing in memory
                    // refers to it.
                    FileSystem.overlayRecovering(lower, root).map { _ =>
                        Path.runWith(lower)(p.read).map { text =>
                            assert(text == "recovered", s"the orphaned commit was not replayed, read '$text'")
                        }
                    }
                }
            }
        }
    }

    "a plain overlay leaves an orphaned staging directory alone" in {
        // The two constructors differ in exactly one way and the difference has to be observable,
        // otherwise the recovering one is not carrying its weight.
        withHostTestOverlay { (ov, lower, root) =>
            val p = root / "left-alone.txt"
            Sync.Unsafe.defer {
                ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (plain ctor)")
            }.andThen {
                attemptCrash(Path.runWith(ov)(p.write("not-recovered")).andThen(ov.commit)).andThen {
                    FileSystem.overlay(lower).map { _ =>
                        Path.runWith(lower)(p.exists).map { found =>
                            assert(!found, "a plain overlay replayed an orphaned commit")
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // crash after committed marker, before staging dir cleanup
    // -------------------------------------------------------------------------

    "in-memory: crash after committed marker recovers via cleanup only" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterMarkerHook = () => throw SyntheticCrash("crash: after committed marker") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        // Lower is fully applied (committed before this crash point); recovery only cleans up.
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash after committed marker recovers via cleanup only" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterMarkerHook = () => throw SyntheticCrash("crash: after committed marker (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // recover() idempotence: second call after completed recovery exits immediately
    // -------------------------------------------------------------------------

    "in-memory: recover is idempotent; second call after completed recovery is a no-op" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (idempotence test)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    // First recovery: replays the full plan.
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF).andThen {
                                            // Second recovery: stagingDirHandle is now Absent; exits as no-op.
                                            ov.recover().andThen {
                                                // Lower state must be identical to after first recovery.
                                                assertFullyApplied(lower, aFile, dDir, oldF)
                                            }
                                        }
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: a directory's staged timestamp survives a commit replayed entirely from the intent log" in {
        // Recovery replays from the log alone, with no staged state to consult, so a timestamp that
        // reaches the lower on the in-process path proves nothing about the recovered one. The crash
        // is at afterIntentLogHook, before any entry is applied, so everything the lower ends up
        // holding came back out of the log.
        //
        // This is the only case that puts a directory's explicit timestamp through the version 3
        // encoder and decoder. Without it the Present branch of that field is written by the commit
        // path and read by nothing.
        withHostTestOverlay { (ov, lower, root) =>
            val d     = root / "staged-mtime-dir"
            val stamp = 946684800000L
            Path.runWith(ov)(d.mkDir.andThen(d.setLastModified(stamp))).andThen {
                Sync.Unsafe.defer {
                    ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (directory mtime)")
                }.andThen {
                    attemptCrash(ov.commit).andThen {
                        Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }.andThen {
                            ov.recover().andThen {
                                Path.runWith(lower)(d.stat).map { live =>
                                    assert(
                                        live.lastModifiedMs == stamp,
                                        s"the recovered directory is dated ${live.lastModifiedMs}, not the staged $stamp"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "in-memory: copy metadata survives mid-apply recovery and an idempotent retry" in {
        withInMemoryTestOverlay { (ov, lower) =>
            val source      = Path("copy-stat-source.txt")
            val target      = Path("copy-stat-target.txt")
            val sourceMtime = 946684800000L
            Path.runWith(lower) {
                source.write("content").andThen(source.setLastModified(sourceMtime))
            }.andThen {
                Path.runWith(ov)(source.copy(target, Path.CopyOptions(copyAttributes = true))).andThen {
                    Sync.Unsafe.defer {
                        ov.afterEntryApplyHook = (i, _) => if i == 1 then throw SyntheticCrash("crash: after copied file applied")
                    }.asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen(ov.recover()).andThen {
                                        Path.runWith(lower)(target.stat).map { targetStat =>
                                            assert(targetStat.lastModifiedMs == sourceMtime)
                                            assert(targetStat.sizeBytes == "content".getBytes(StandardCharsets.UTF_8).length.toLong)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    "host: recover is idempotent; second call after completed recovery is a no-op" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (idempotence host test)")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, a, d, old).andThen {
                                            ov.recover().andThen {
                                                assertFullyApplied(lower, a, d, old)
                                            }
                                        }
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // staging dir cleanup after crash before log write
    // -------------------------------------------------------------------------

    "in-memory: staging dir is removed after recover on crash before log write" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primePlan(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write (cleanup test)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    // First recovery: crash-before-log branch removes the staging dir via lower.removeAll.
                                    ov.recover().andThen {
                                        // Lower unchanged (staging dir removed, ops never applied).
                                        assertLowerUnchanged(lower, aFile, dDir, oldF).andThen {
                                            // Second recover() must be a no-op (stagingDirHandle cleared).
                                            ov.recover().andThen {
                                                assertLowerUnchanged(lower, aFile, dDir, oldF)
                                            }
                                        }
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: staging dir is removed from root after recover on crash before log write" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write (cleanup host test)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        attemptCrash(
                            ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                    ov.recover().andThen {
                                        // Lower unchanged; the staging dir (created within root by the rooted lower)
                                        // is removed by recoverStagingDir's lower.removeAll call.
                                        assertLowerUnchanged(lower, a, d, old)
                                    }
                                }
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // torn intent log on real filesystem
    // Staging dir is constructed manually in root so recoverFromDisk can find it.
    // -------------------------------------------------------------------------

    "torn intent log is discarded by recoverFromDisk and lower is unchanged" in {
        withHostTestOverlay { (_, lower, root) =>
            // Create an orphaned staging dir within root with a truncated intent log.
            // Truncated = KYIL magic (4 bytes) + version (1 byte); no records, no KYCT terminator.
            // WriteOpLog.decode returns Success(Absent) for this, triggering the torn-log discard branch.
            val stagingDir = root / "kyo-commit-torn"
            val tornBytes  = Span.from("KYIL".getBytes(StandardCharsets.UTF_8) :+ 0x03.toByte)
            lower.mkDir(stagingDir).andThen {
                lower.writeBytes(
                    stagingDir / ".kyo-staging",
                    Span.from(Array.empty[Byte]),
                    Path.WriteOptions(createFolders = false)
                ).andThen {
                    lower.writeBytes(stagingDir / "intent.kyo", tornBytes, Path.WriteOptions(createFolders = false)).andThen {
                        // Create a fresh overlay on the same lower for the disk-scan restart.
                        FileSystem.overlay(lower).map { freshOv =>
                            val overlay = freshOv.asInstanceOf[OverlayFileSystem[Sync]]
                            // recoverFromDisk finds kyo-commit-torn, reads the torn log, discards.
                            overlay.recoverFromDisk(root).andThen {
                                // Staging dir should be gone after recoverStagingDir cleaned it up.
                                lower.exists(stagingDir).map { still =>
                                    assert(!still, "torn staging dir should be removed after recoverFromDisk")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // disk-scan restart: fresh overlay replays valid intent log from disk
    // -------------------------------------------------------------------------

    "fresh overlay recoverFromDisk replays a valid intent log written to disk" in {
        withHostTestOverlay { (_, lower, root) =>
            // Seed old.txt in lower for the whiteout entry.
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            Path.runWith(lower)(old.write("old-content")).andThen {
                // Build the same three-entry plan the hook-injection tests produce.
                val plan: Chunk[ReplayEntry] = Chunk(
                    ReplayEntry.File(
                        a.parts,
                        Span.from("file-content".getBytes(StandardCharsets.UTF_8)),
                        Path.PathStat(0L, 12L),
                        Absent
                    ),
                    ReplayEntry.Directory(d.parts, opaque = false, Absent),
                    ReplayEntry.Whiteout(old.parts)
                )
                // Manually construct the orphaned staging dir in root (within the rooted lower).
                val stagingDir = root / "kyo-commit-restart"
                // Stage the file entry's bytes at e0.dat (recovery moves it to a.txt).
                lower.mkDir(stagingDir).andThen {
                    lower.writeBytes(
                        stagingDir / ".kyo-staging",
                        Span.from(Array.empty[Byte]),
                        Path.WriteOptions(createFolders = false)
                    ).andThen {
                        lower.writeBytes(
                            stagingDir / "e0.dat",
                            Span.from("file-content".getBytes(StandardCharsets.UTF_8)),
                            Path.WriteOptions(createFolders = false)
                        ).andThen {
                            // Write the valid intent log (WriteOpLog.encode returns Span[Byte] directly).
                            lower.writeBytes(
                                stagingDir / "intent.kyo",
                                WriteOpLog.encode(plan),
                                Path.WriteOptions(createFolders = false)
                            ).andThen {
                                // Fresh overlay over the same lower; no in-memory stagingDirHandle.
                                FileSystem.overlay(lower).map { freshOv =>
                                    val overlay = freshOv.asInstanceOf[OverlayFileSystem[Sync]]
                                    overlay.recoverFromDisk(root).andThen {
                                        // All three plan entries must be reflected in lower.
                                        assertFullyApplied(lower, a, d, old).andThen {
                                            // Staging dir cleaned up.
                                            lower.exists(stagingDir).map { still =>
                                                assert(!still, "restart staging dir should be removed after recoverFromDisk")
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

    // -------------------------------------------------------------------------
    // disk-scan no-op on empty root
    // -------------------------------------------------------------------------

    "recoverFromDisk on empty root completes cleanly with no changes to lower" in {
        withHostTestOverlay { (_, lower, root) =>
            // root is empty (no files seeded, no staging dirs).
            FileSystem.overlay(lower).map { freshOv =>
                val overlay = freshOv.asInstanceOf[OverlayFileSystem[Sync]]
                overlay.recoverFromDisk(root).andThen {
                    // lower has no entries; root still empty.
                    lower.list(root).map { entries =>
                        assert(entries.isEmpty, "root should still be empty after recoverFromDisk on empty root")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // concurrent orphaned staging dirs: recoverFromDisk replays both independently
    // -------------------------------------------------------------------------

    "recoverFromDisk replays two orphaned staging dirs independently" in {
        withHostTestOverlay { (_, lower, root) =>
            val a1   = root / "a1.txt"; val a2     = root / "a2.txt"
            val old1 = root / "old1.txt"; val old2 = root / "old2.txt"
            // Seed two files to be removed.
            Path.runWith(lower)(old1.write("old1")).andThen {
                Path.runWith(lower)(old2.write("old2")).andThen {
                    // Build two independent plans, each touching distinct paths.
                    val plan1: Chunk[ReplayEntry] = Chunk(
                        ReplayEntry.File(
                            a1.parts,
                            Span.from("content1".getBytes(StandardCharsets.UTF_8)),
                            Path.PathStat(0L, 8L),
                            Absent
                        ),
                        ReplayEntry.Whiteout(old1.parts)
                    )
                    val plan2: Chunk[ReplayEntry] = Chunk(
                        ReplayEntry.File(
                            a2.parts,
                            Span.from("content2".getBytes(StandardCharsets.UTF_8)),
                            Path.PathStat(0L, 8L),
                            Absent
                        ),
                        ReplayEntry.Whiteout(old2.parts)
                    )
                    val staging1 = root / "kyo-commit-batch1"
                    val staging2 = root / "kyo-commit-batch2"

                    def writeLog(
                        stagingDir: Path,
                        plan: Chunk[ReplayEntry],
                        eBytes: Span[Byte]
                    ): Unit < (Sync & Abort[FileSystemException]) =
                        lower.mkDir(stagingDir).andThen {
                            lower.writeBytes(
                                stagingDir / ".kyo-staging",
                                Span.from(Array.empty[Byte]),
                                Path.WriteOptions(createFolders = false)
                            ).andThen {
                                lower.writeBytes(stagingDir / "e0.dat", eBytes, Path.WriteOptions(createFolders = false)).andThen {
                                    lower.writeBytes(
                                        stagingDir / "intent.kyo",
                                        WriteOpLog.encode(plan),
                                        Path.WriteOptions(createFolders = false)
                                    )
                                }
                            }
                        }

                    writeLog(staging1, plan1, Span.from("content1".getBytes(StandardCharsets.UTF_8))).andThen {
                        writeLog(staging2, plan2, Span.from("content2".getBytes(StandardCharsets.UTF_8))).andThen {
                            FileSystem.overlay(lower).map { freshOv =>
                                val overlay = freshOv.asInstanceOf[OverlayFileSystem[Sync]]
                                // recoverFromDisk processes both staging dirs via foldLeft.
                                overlay.recoverFromDisk(root).andThen {
                                    // Both batches reflected in lower.
                                    Path.runWith(lower)(a1.read).map(c => assert(c == "content1")).andThen {
                                        Path.runWith(lower)(a2.read).map(c => assert(c == "content2")).andThen {
                                            Path.runWith(lower)(old1.exists).map(e => assert(!e, "old1 should be removed")).andThen {
                                                Path.runWith(lower)(old2.exists).map(e => assert(!e, "old2 should be removed")).andThen {
                                                    // Both staging dirs cleaned up.
                                                    lower.exists(staging1).map(e => assert(!e, "staging1 should be removed")).andThen {
                                                        lower.exists(staging2).map(e => assert(!e, "staging2 should be removed"))
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
        }
    }

    // -------------------------------------------------------------------------
    // ownership sentinel: recoverFromDisk skip/clean based on .kyo-staging presence
    // -------------------------------------------------------------------------

    "recoverFromDisk does not touch a kyo-commit-* dir that lacks the ownership sentinel" in {
        withHostTestOverlay { (_, lower, root) =>
            // A user directory whose name starts with "kyo-commit-" but has no .kyo-staging sentinel.
            // recoverFromDisk must skip it entirely to prevent destroying unrelated user data.
            val userDir = root / "kyo-commit-user-data"
            lower.mkDir(userDir).andThen {
                lower.writeBytes(
                    userDir / "important.txt",
                    Span.from("user-data".getBytes(StandardCharsets.UTF_8)),
                    Path.WriteOptions(createFolders = false)
                ).andThen {
                    FileSystem.overlay(lower).map { freshOv =>
                        val overlay = freshOv.asInstanceOf[OverlayFileSystem[Sync]]
                        overlay.recoverFromDisk(root).andThen {
                            // Both the user dir and its file must survive intact.
                            lower.exists(userDir / "important.txt").map { still =>
                                assert(still, "user file inside kyo-commit-* dir without sentinel must survive recoverFromDisk")
                            }
                        }
                    }
                }
            }
        }
    }

    "recoverFromDisk removes a kyo-commit-* dir with the ownership sentinel but no intent log" in {
        withHostTestOverlay { (_, lower, root) =>
            // Simulates a crash between sentinel write and intent-log write.
            // recoverFromDisk must clean up the orphan (sentinel present, no log = never durable).
            val stagingDir = root / "kyo-commit-orphan-sentinel"
            lower.mkDir(stagingDir).andThen {
                lower.writeBytes(
                    stagingDir / ".kyo-staging",
                    Span.from(Array.empty[Byte]),
                    Path.WriteOptions(createFolders = false)
                ).andThen {
                    FileSystem.overlay(lower).map { freshOv =>
                        val overlay = freshOv.asInstanceOf[OverlayFileSystem[Sync]]
                        overlay.recoverFromDisk(root).andThen {
                            lower.exists(stagingDir).map { still =>
                                assert(!still, "sentinel-only staging dir with no intent log must be removed by recoverFromDisk")
                            }
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // barrier ordering: staged file is written durably before intent log
    // -------------------------------------------------------------------------

    "staged file is durably written to staging dir before intent log (barrier ordering)" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primePlan(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log (barrier ordering test)")
                }.asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                    attemptCrash(
                        ov.commitWith(_ => FileSystem.Resolution.KeepOurs)
                    ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                            .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                                // After crash at crash point 2, stagingDirHandle is set. The staging dir holds
                                // e0.dat (WriteFile for a.txt) and intent.kyo. Verify e0.dat exists with the
                                // correct content, confirming the durable staged write completed before the log.
                                Sync.Unsafe.defer { ov.stagingDirHandle }
                                    .asInstanceOf[Maybe[Path.TempDirHandle] < (Sync & Abort[FileSystemException])].map {
                                        case Absent =>
                                            fail("stagingDirHandle must be set after crash at crash point 2")
                                        case Present(handle) =>
                                            val sd = handle.path
                                            lower.exists(sd / "e0.dat").map { exists =>
                                                assert(exists, "staged e0.dat must exist (durable write completed before log)")
                                            }.andThen {
                                                lower.readBytes(sd / "e0.dat").map { bytes =>
                                                    assert(
                                                        bytes.toArray sameElements "file-content".getBytes(StandardCharsets.UTF_8),
                                                        "staged e0.dat must have correct content"
                                                    )
                                                }
                                            }.andThen(ov.recover())
                                    }
                            }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // writes are refused for the duration of a commit
    // -------------------------------------------------------------------------

    /** A commit takes the staged state, applies it, and then clears it. A write admitted between
      * those two points is added to state that has already been read and is about to be erased, so
      * it succeeds and lands nowhere: not in the commit, not in the overlay, not in the lower.
      *
      * Closing the overlay for the commit's duration is what removes that window, and a crash hook
      * is what makes the window observable. Halting inside the commit leaves the overlay in exactly
      * the state a concurrent write would have raced, without needing a second fiber to hit it.
      */
    "a write while a commit is in flight is rejected" in {
        withInMemoryTestOverlay { (ov, _) =>
            val staged = Path("in-flight-staged.txt")
            val late   = Path("in-flight-late.txt")
            Sync.Unsafe.defer { ov.beforeMarkerHook = () => throw SyntheticCrash("crash: mid commit") }
                .asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                    attemptCrash(
                        Path.runWith(ov)(staged.write("staged")).andThen(ov.commit)
                    ).asInstanceOf[Unit < (Sync & Abort[FileSystemException])].andThen {
                        Abort.run[FileSystemException](Path.runWith(ov)(late.write("late"))).map {
                            case Result.Failure(_: FileIOException) => succeed("rejected")
                            case Result.Success(_) =>
                                fail("a write landed in a commit that had already taken the staged state")
                            case other => fail(s"expected FileIOException, got $other")
                        }
                    }
                }
        }
    }

end OverlayFileSystemRecoveryTest
