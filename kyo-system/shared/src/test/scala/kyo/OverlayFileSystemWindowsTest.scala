package kyo

import kyo.internal.Platform

/** Windows host durability failures must retain enough state for inspection and recovery. */
class OverlayFileSystemWindowsTest extends kyo.test.Test[Any]:

    private def withHost[A, S](
        body: (OverlayFileSystem[Sync], FileSystem.Write[Sync], Path) => A < S
    )(using Frame): A < (S & Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir("kyo-overlay-windows")) { handle =>
            // Unsafe: the service owns recursive removal of this scoped fixture.
            Sync.Unsafe.defer(handle.remove())
        }.map { handle =>
            FileSystem.host.realPath(handle.path).map { root =>
                FileSystem.host(root).map { lower =>
                    FileSystem.overlay(lower).map(overlay => body(overlay.asInstanceOf[OverlayFileSystem[Sync]], lower, root))
                }
            }
        }

    if Platform.isWindows then
        for mode <- Chunk("commit", "empty", "KeepOurs", "KeepTheirs", "Write", "Remove") do
            s"Windows $mode fails at the staging parent barrier without publishing content" in {
                withHost { (overlay, lower, root) =>
                    val target   = root / "target.txt"
                    val resolved = Span.from("resolved".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    for
                        _        <- lower.write(target, "original", Path.WriteOptions())
                        observed <- overlay.read(target)
                        _        <- if mode == "empty" then Kyo.unit else overlay.write(target, "staged", Path.WriteOptions())
                        _ <- if mode == "commit" || mode == "empty" then Kyo.unit else lower.write(target, "diverged", Path.WriteOptions())
                        result <- Abort.run[FileSystemException | CommitConflict] {
                            if mode == "commit" || mode == "empty" then overlay.commit
                            else
                                overlay.commitWith { _ =>
                                    mode match
                                        case "KeepOurs"   => FileSystem.Resolution.KeepOurs
                                        case "KeepTheirs" => FileSystem.Resolution.KeepTheirs
                                        case "Write"      => FileSystem.Resolution.Write(Path.Entry.File(resolved, Path.PathStat(0L, 8L)))
                                        case _            => FileSystem.Resolution.Remove
                                }
                        }
                        actual  <- lower.read(target)
                        visible <- overlay.exists(target)
                        staged  <- if visible then overlay.read(target) else ("absent": String < Sync)
                        // Unsafe: inspect the retained handle after the commit effect has completed.
                        handle <- Sync.Unsafe.defer(overlay.stagingDirHandle)
                        staging = handle.get.path
                        entries  <- lower.list(staging)
                        _        <- lower.privateMkDir(staging)
                        late     <- Abort.run(overlay.write(target, "late", Path.WriteOptions()))
                        _        <- overlay.recover()
                        remains  <- lower.exists(staging)
                        after    <- lower.read(target)
                        retained <- overlay.exists(target)
                    yield
                        assert(observed == "original")
                        assert(OverlayFileSystemWindowsTest.directoryFailure(result, root), s"Directory barrier result: $result")
                        assert(actual == (if mode == "commit" || mode == "empty" then "original" else "diverged"))
                        assert(staged == (mode match
                            case "commit" | "KeepOurs" => "staged"
                            case "empty"               => "original"
                            case "KeepTheirs"          => "diverged"
                            case "Write"               => "resolved"
                            case _                     => "absent"))
                        assert(entries.flatMap(_.name) == Chunk(".kyo-staging"))
                        late match
                            case Result.Failure(FileIOException(path, FileSystemOperation.Write, cause)) =>
                                assert(path == target)
                                assert(cause.getMessage == "staged changes are no longer accepting writes")
                            case other => fail(s"Expected the failed commit to keep writes closed, got $other")
                        end match
                        assert(!remains)
                        assert(after == actual)
                        assert(retained == visible)
                    end for
                }
            }
        end for

        for mode <- Chunk("file", "default-file", "directory", "opaque-directory", "remove") do
            s"Windows recovery retains the intent after partial $mode replay and repeated barrier failure" in {
                withHost { (overlay, lower, root) =>
                    val target  = root / "target"
                    val later   = root / "later.txt"
                    val staging = root / "kyo-commit-windows-replay"
                    val bytes   = Span.from("recovered".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    val entry: ReplayEntry = mode match
                        case "file" | "default-file" =>
                            ReplayEntry.File(
                                target.parts,
                                bytes,
                                Path.PathStat(0L, 9L),
                                Absent,
                                defaultPermissions = mode == "default-file"
                            )
                        case "directory" | "opaque-directory" =>
                            ReplayEntry.Directory(
                                target.parts,
                                mode == "opaque-directory",
                                Absent,
                                syncDirectories = Chunk(target.parts, root.parts)
                            )
                        case _ => ReplayEntry.Whiteout(target.parts)
                    val plan    = Chunk(entry, ReplayEntry.File(later.parts, bytes, Path.PathStat(0L, 9L), Absent))
                    val log     = WriteOpLog.encode(plan)
                    val barrier = if mode.endsWith("directory") then target else root
                    for
                        _ <- if mode == "remove" then lower.write(target, "original", Path.WriteOptions()) else Kyo.unit
                        _ <- if mode == "opaque-directory" then
                            lower.write(target / "old.txt", "old", Path.WriteOptions())
                        else Kyo.unit
                        _            <- lower.privateMkDir(staging)
                        _            <- lower.writeBytes(staging / ".kyo-staging", Span.empty[Byte], Path.WriteOptions())
                        _            <- lower.writeBytes(staging / "e0.dat", bytes, Path.WriteOptions())
                        _            <- lower.writeBytes(staging / "e1.dat", bytes, Path.WriteOptions())
                        _            <- lower.writeBytes(staging / "intent.kyo", log, Path.WriteOptions())
                        first        <- Abort.run(overlay.recoverFromDisk(root))
                        firstExists  <- lower.exists(target)
                        firstContent <- if mode.endsWith("file") then lower.read(target) else ("": String < Sync)
                        second       <- Abort.run(FileSystem.overlayRecovering(lower, root))
                        exists       <- lower.exists(target)
                        content      <- if mode.endsWith("file") then lower.read(target) else ("": String < Sync)
                        intent       <- lower.readBytes(staging / "intent.kyo")
                        firstStaged  <- lower.exists(staging / "e0.dat")
                        secondStaged <- lower.readBytes(staging / "e1.dat")
                        laterExists  <- lower.exists(later)
                        marker       <- lower.exists(staging / "committed.marker")
                        applied      <- lower.exists(staging / "applied-0.marker")
                        old          <- lower.exists(target / "old.txt")
                        _            <- lower.privateMkDir(staging)
                    yield
                        assert(OverlayFileSystemWindowsTest.directoryFailure(first, barrier), s"Directory barrier result: $first")
                        assert(
                            OverlayFileSystemWindowsTest.directoryFailure(second, barrier),
                            s"Repeated directory barrier result: $second"
                        )
                        assert(firstExists == (mode != "remove") && exists == firstExists)
                        assert(firstContent == (if mode.endsWith("file") then "recovered" else ""))
                        assert(content == firstContent)
                        assert(intent.is(log))
                        assert(firstStaged == (mode != "file"))
                        assert(secondStaged.is(bytes))
                        assert(!laterExists && !marker && !applied && !old)
                    end for
                }
            }
        end for
    end if
end OverlayFileSystemWindowsTest

object OverlayFileSystemWindowsTest:
    def directoryFailure[E, A](result: Result[E, A], expected: Path): Boolean =
        result match
            case Result.Failure(FileAccessDeniedException(path)) =>
                !Platform.isNative && path == expected
            case Result.Failure(FileIOException(path, FileSystemOperation.SyncDirectory, cause)) =>
                Platform.isNative && path == expected && cause.getMessage == "Directory synchronization is unsupported on Windows"
            case _ => false
end OverlayFileSystemWindowsTest
