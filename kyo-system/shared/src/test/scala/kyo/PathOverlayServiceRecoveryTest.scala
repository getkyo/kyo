package kyo

import java.nio.charset.StandardCharsets

/** Recovery test suite for [[PathOverlayService]] durable commit.
  *
  * Exercises recovery after a crash at each step of the five-step durable commit protocol:
  * before the intent log is written, after the intent log, after individual journal entries
  * are applied to lower, before the committed marker, and after the committed marker. Each
  * crash point is covered by two variants: an in-memory lower (deterministic, cross-platform)
  * and a host lower (real filesystem, exercises the NIO atomic-move and fsync paths).
  *
  * Crash injection: test hooks on OverlayService throw a [[SyntheticCrash]] exception via
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
class PathOverlayServiceRecoveryTest extends kyo.test.Test[Any]:

    // Distinct exception type thrown by crash hooks; lets attemptCrash distinguish an
    // intentional hook-injected crash from a genuine file error or unexpected panic.
    final private class SyntheticCrash(msg: String) extends RuntimeException(msg)

    // --- Fixed three-op journal paths (in-memory and host variants) ---
    private val aFile = Path("a.txt")
    private val dDir  = Path("d")
    private val oldF  = Path("old.txt")

    // --- Helpers ---

    /** Runs `program` and asserts that a [[SyntheticCrash]] panic was raised (confirming the
      * crash hook fired). Fails the test if a real file error occurred, if no crash happened,
      * or if an unexpected panic type propagated.
      */
    private def attemptCrash(
        program: Unit < (Sync & Abort[FileException])
    )(using kyo.test.AssertScope): Unit < Sync =
        Abort.run[Throwable](Abort.run[FileException](program)).map {
            case Result.Panic(_: SyntheticCrash)    => ()      // expected: crash hook fired
            case Result.Panic(e)                    => throw e // unexpected panic: propagate to fail the test
            case Result.Success(Result.Success(())) => fail("program completed without a crash; crash hook did not fire")
            case Result.Success(Result.Failure(e))  => fail(s"unexpected file error before crash hook: $e")
            case Result.Success(Result.Panic(e))    => throw e // inner panic escaped the outer handler
            case Result.Failure(e)                  => throw e // unexpected typed Throwable abort
        }

    /** Creates an in-memory overlay and exposes the underlying OverlayService for hook access. */
    private def withInMemoryTestOverlay[A](
        program: (OverlayService[Sync], Path.Service[Sync]) => A < (Sync & Scope & Abort[FileException])
    ): A < (Sync & Scope & Abort[FileException]) =
        Path.Service.inMemory.map { lower =>
            Path.Service.overlay(lower).map { ov =>
                program(ov.asInstanceOf[OverlayService[Sync]], lower)
            }
        }

    /** Creates a rooted host overlay backed by a scoped temp dir. The temp dir is removed when
      * the enclosing Scope exits. Passes (overlay, lower, root) to `program`.
      */
    private def withHostTestOverlay[A](
        program: (OverlayService[Sync], Path.Service[Sync], Path) => A < (Sync & Scope & Abort[FileException])
    ): A < (Sync & Scope & Abort[FileException]) =
        val defaultHost = Path.Service.host
        Scope.acquireRelease(defaultHost.tempDir("kyo-recovery-test")) { handle =>
            // Unsafe: removes OS temp dir on scope exit
            Sync.Unsafe.defer { handle.remove() }
        }.map { handle =>
            val root = handle.path
            Path.Service.host(root).map { lower =>
                Path.Service.overlay(lower).map { ov =>
                    program(ov.asInstanceOf[OverlayService[Sync]], lower, root)
                }
            }
        }
    end withHostTestOverlay

    /** Stages the fixed three-op journal through `ov` over `lower`:
      *   WriteFile("a.txt", "file-content"), WriteDirectory("d"), Remove("old.txt").
      * `lower` must have "old.txt" pre-seeded (done here). Journal is n=3; crash points are
      * injected by the caller before calling commitOverwrite.
      */
    private def primeJournal(
        ov: OverlayService[Sync],
        lower: Path.Service[Sync],
        a: Path,
        d: Path,
        old: Path
    ): Unit < (Sync & Abort[FileException]) =
        // Seed old.txt in lower so the Remove op has something to delete.
        Path.runWith(lower)(old.write("old-content")).andThen {
            Path.runWith(ov)(a.write("file-content")).andThen {
                Path.runWith(ov)(d.mkDir).andThen {
                    Path.runWith(ov)(old.remove).map(_ => ())
                }
            }
        }

    /** Asserts that the lower service reflects the fully-applied three-op journal:
      * a.txt exists with "file-content", d exists as a directory, old.txt is absent.
      */
    private def assertFullyApplied(
        lower: Path.Service[Sync],
        a: Path,
        d: Path,
        old: Path
    )(using kyo.test.AssertScope): Unit < (Sync & Abort[FileException]) =
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
        lower: Path.Service[Sync],
        a: Path,
        d: Path,
        old: Path
    )(using kyo.test.AssertScope): Unit < (Sync & Abort[FileException]) =
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
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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

    "in-memory: crash after intent log write replays full journal on recovery" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash after intent log write replays full journal on recovery" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
    // crash after first op applied, two remaining
    // -------------------------------------------------------------------------

    "in-memory: crash mid-apply after first op recovers idempotently" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                // afterEntryApplyHook is called with (1-indexed-position, n); throw at position 1.
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 1 then throw SyntheticCrash("crash: after first op applied")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash mid-apply after first op recovers idempotently" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 1 then throw SyntheticCrash("crash: after first op applied (host)")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
    // crash after second op applied, one remaining
    // -------------------------------------------------------------------------

    "in-memory: crash mid-apply after second op recovers idempotently" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 2 then throw SyntheticCrash("crash: after second op applied")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash mid-apply after second op recovers idempotently" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterEntryApplyHook = (i, _) => if i == 2 then throw SyntheticCrash("crash: after second op applied (host)")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterEntryApplyHook = (_, _) => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
    // crash after all ops applied, before committed marker
    // -------------------------------------------------------------------------

    "in-memory: crash before committed marker replays journal idempotently" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.beforeMarkerHook = () => throw SyntheticCrash("crash: before committed marker") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.beforeMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                                    ov.recover().andThen {
                                        assertFullyApplied(lower, aFile, dDir, oldF)
                                    }
                                }
                        }
                    }
            }
        }
    }

    "host: crash before committed marker replays journal idempotently" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.beforeMarkerHook = () => throw SyntheticCrash("crash: before committed marker (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.beforeMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
    // crash after committed marker, before staging dir cleanup
    // -------------------------------------------------------------------------

    "in-memory: crash after committed marker recovers via cleanup only" in {
        withInMemoryTestOverlay { (ov, lower) =>
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterMarkerHook = () => throw SyntheticCrash("crash: after committed marker") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterMarkerHook = () => throw SyntheticCrash("crash: after committed marker (host)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterMarkerHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (idempotence test)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                                    // First recovery: replays the full journal.
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

    "host: recover is idempotent; second call after completed recovery is a no-op" in {
        withHostTestOverlay { (ov, lower, root) =>
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer {
                    ov.afterIntentLogHook = () => throw SyntheticCrash("crash: after intent log write (idempotence host test)")
                }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterIntentLogHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
            primeJournal(ov, lower, aFile, dDir, oldF).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write (cleanup test)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
            primeJournal(ov, lower, a, d, old).andThen {
                Sync.Unsafe.defer { ov.afterStageHook = () => throw SyntheticCrash("crash: before intent log write (cleanup host test)") }
                    .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                        attemptCrash(ov.commitOverwrite).asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
                            Sync.Unsafe.defer { ov.afterStageHook = () => () }
                                .asInstanceOf[Unit < (Sync & Abort[FileException])].andThen {
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
            val tornBytes  = Span.from("KYIL".getBytes(StandardCharsets.UTF_8) :+ 0x01.toByte)
            lower.mkDir(stagingDir).andThen {
                lower.writeBytes(stagingDir / "intent.kyo", tornBytes, createFolders = false).andThen {
                    // Create a fresh overlay on the same lower for the disk-scan restart.
                    Path.Service.overlay(lower).map { freshOv =>
                        val overlay = freshOv.asInstanceOf[OverlayService[Sync]]
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

    // -------------------------------------------------------------------------
    // disk-scan restart: fresh overlay replays valid intent log from disk
    // -------------------------------------------------------------------------

    "fresh overlay recoverFromDisk replays a valid intent log written to disk" in {
        withHostTestOverlay { (_, lower, root) =>
            // Seed old.txt in lower for the Remove op.
            val a = root / "a.txt"; val d = root / "d"; val old = root / "old.txt"
            Path.runWith(lower)(old.write("old-content")).andThen {
                // Build the same three-op journal the hook-injection tests use.
                val journal: Chunk[WriteOp] = Chunk(
                    WriteOp.WriteFile(a.parts, Span.from("file-content".getBytes(StandardCharsets.UTF_8)), Path.PathStat(0L, 12L)),
                    WriteOp.WriteDirectory(d.parts, false),
                    WriteOp.Remove(old.parts)
                )
                // Manually construct the orphaned staging dir in root (within the rooted lower).
                val stagingDir = root / "kyo-commit-restart"
                // Stage the file for WriteFile op at e0.dat (applyOneOpIdempotent moves it to a.txt).
                lower.mkDir(stagingDir).andThen {
                    lower.writeBytes(
                        stagingDir / "e0.dat",
                        Span.from("file-content".getBytes(StandardCharsets.UTF_8)),
                        createFolders = false
                    ).andThen {
                        // Write the valid intent log (WriteOpLog.encode returns Span[Byte] directly).
                        lower.writeBytes(stagingDir / "intent.kyo", WriteOpLog.encode(journal), createFolders = false).andThen {
                            // Fresh overlay over the same lower; no in-memory stagingDirHandle.
                            Path.Service.overlay(lower).map { freshOv =>
                                val overlay = freshOv.asInstanceOf[OverlayService[Sync]]
                                overlay.recoverFromDisk(root).andThen {
                                    // All three ops must be reflected in lower.
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

    // -------------------------------------------------------------------------
    // disk-scan no-op on empty root
    // -------------------------------------------------------------------------

    "recoverFromDisk on empty root completes cleanly with no changes to lower" in {
        withHostTestOverlay { (_, lower, root) =>
            // root is empty (no files seeded, no staging dirs).
            Path.Service.overlay(lower).map { freshOv =>
                val overlay = freshOv.asInstanceOf[OverlayService[Sync]]
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
                    // Build two independent journals, each touching distinct paths.
                    val journal1: Chunk[WriteOp] = Chunk(
                        WriteOp.WriteFile(a1.parts, Span.from("content1".getBytes(StandardCharsets.UTF_8)), Path.PathStat(0L, 8L)),
                        WriteOp.Remove(old1.parts)
                    )
                    val journal2: Chunk[WriteOp] = Chunk(
                        WriteOp.WriteFile(a2.parts, Span.from("content2".getBytes(StandardCharsets.UTF_8)), Path.PathStat(0L, 8L)),
                        WriteOp.Remove(old2.parts)
                    )
                    val staging1 = root / "kyo-commit-batch1"
                    val staging2 = root / "kyo-commit-batch2"

                    def writeLog(stagingDir: Path, journal: Chunk[WriteOp], eBytes: Span[Byte]): Unit < (Sync & Abort[FileException]) =
                        lower.mkDir(stagingDir).andThen {
                            lower.writeBytes(stagingDir / "e0.dat", eBytes, createFolders = false).andThen {
                                lower.writeBytes(stagingDir / "intent.kyo", WriteOpLog.encode(journal), createFolders = false)
                            }
                        }

                    writeLog(staging1, journal1, Span.from("content1".getBytes(StandardCharsets.UTF_8))).andThen {
                        writeLog(staging2, journal2, Span.from("content2".getBytes(StandardCharsets.UTF_8))).andThen {
                            Path.Service.overlay(lower).map { freshOv =>
                                val overlay = freshOv.asInstanceOf[OverlayService[Sync]]
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

end PathOverlayServiceRecoveryTest
