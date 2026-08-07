package kyo

import kyo.PathPermissionTestSupport.*
import kyo.internal.Platform

class OverlayFileSystemPermissionTest extends kyo.test.Test[Any]:

    final private class IntentWritten(using Frame) extends KyoException("Paused after overlay intent log")

    if !Platform.isWindows then
        for remove <- Seq(false, true) do
            s"resolving a private staging directory conflict cannot expose its child intent log, remove: $remove" in {
                directory.map { root =>
                    val target      = root / "private.bin"
                    val staging     = root / "conflicting-staging"
                    val intent      = staging / "intent.kyo"
                    val original    = Span(7.toByte, 8.toByte)
                    val replacement = Span(1.toByte, 2.toByte, 3.toByte)
                    val plan        = Chunk(ReplayEntry.File(target.parts, replacement, Path.PathStat(0L, replacement.size.toLong), Absent))
                    for
                        host             <- FileSystem.host(root)
                        _                <- host.writeBytes(target, original, Path.WriteOptions())
                        _                <- configure(target, 1)
                        before           <- snapshot(target)
                        overlay          <- FileSystem.overlay(host)
                        _                <- overlay.privateMkDir(staging)
                        _                <- overlay.writeBytes(intent, WriteOpLog.encode(plan), Path.WriteOptions(createFolders = false))
                        _                <- host.mkDir(staging)
                        _                <- configure(staging, 2)
                        publicBefore     <- snapshot(staging)
                        canonicalStaging <- host.realPath(staging)
                        result <- Abort.run[FileSystemException | CommitConflict](overlay.commitWith { conflict =>
                            assert(
                                conflict.path == canonicalStaging,
                                s"Unexpected conflict at ${conflict.path}, expected $canonicalStaging"
                            )
                            if remove then FileSystem.Resolution.Remove else FileSystem.Resolution.KeepTheirs
                        })
                        intentExists  <- host.exists(intent)
                        stagingExists <- host.exists(staging)
                        after         <- snapshot(target)
                        actual        <- host.readBytes(target)
                        _ <-
                            val verify: Unit < (Sync & Abort[FileSystemException]) =
                                if remove then
                                    privateAccess(staging).map { access =>
                                        host.readBytes(intent).map { logged =>
                                            assert(result == Result.unit)
                                            assert(access == 1, "A retained intent log was published beneath a public directory")
                                            assert(logged.is(WriteOpLog.encode(plan)))
                                        }
                                    }
                                else
                                    snapshot(staging).map { current =>
                                        assert(current.is(publicBefore))
                                        result match
                                            case Result.Failure(_: FileIOException) => ()
                                            case other =>
                                                fail(s"Expected private-directory verification to reject public access, got $other")
                                        end match
                                    }
                            verify
                    yield
                        assert(intentExists == remove)
                        assert(stagingExists)
                        assert(after.is(before))
                        assert(actual.is(original))
                    end for
                }
            }
        end for

        for
            nested  <- Seq(false, true)
            recover <- Seq(false, true)
        do
            s"new overlay files inherit the destination parent access controls, nested: $nested, recover: $recover" in {
                directory.map { root =>
                    val destination = root / "restricted-defaults"
                    val reference   = destination / "ordinary.bin"
                    val target      = destination / "overlay.bin"
                    val bytes       = Span(1.toByte, 2.toByte, 3.toByte)
                    val pause       = new IntentWritten
                    for
                        host     <- FileSystem.host(root)
                        _        <- host.mkDir(destination)
                        _        <- configure(destination, 10)
                        _        <- host.writeBytes(reference, bytes, Path.WriteOptions())
                        expected <- snapshot(reference)
                        state <- Scope.acquireRelease(AtomicRef.init(OverlayFileSystem.OverlayState.empty))(
                            _.set(OverlayFileSystem.OverlayState.terminated)
                        )
                        sequence <- AtomicLong.init(0L)
                        parent = new OverlayFileSystem[Sync](host, state, sequence):
                            override def tempDir(prefix: String)(using
                                Frame
                            ): Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
                                super.tempDir((root / prefix).toString)
                        overlay <-
                            val selected: (FileSystem.Write[Sync] & FileSystem.StagedChanges[Sync & Abort[FileSystemException]]) <
                                (Sync & Scope) = if nested then FileSystem.overlay(parent) else parent
                            selected
                        _ <- overlay.writeBytes(target, bytes, Path.WriteOptions())
                        _ <-
                            val commitChild: Unit < (Sync & Abort[FileSystemException | CommitConflict]) =
                                if nested then overlay.commitWith(_ => FileSystem.Resolution.KeepOurs) else ()
                            commitChild
                        // Unsafe: halt the physical host commit after its complete recovery log is durable.
                        _ <- Sync.Unsafe.defer {
                            if recover then parent.afterIntentLogHook = () => throw pause
                        }
                        result <- Abort.run[Throwable](Abort.run[FileSystemException | CommitConflict](
                            parent.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ))
                        _ <- Sync.defer {
                            if recover then
                                result match
                                    case Result.Panic(error) if error eq pause                 => ()
                                    case Result.Success(Result.Panic(error)) if error eq pause => ()
                                    case other => fail(s"Expected the physical intent-log hook to stop commit, got $other")
                            else assert(result == Result.succeed(Result.unit))
                        }
                        existedBeforeRecovery <- host.exists(target)
                        _ <-
                            val recovery: Unit < (Sync & Scope & Abort[FileSystemException]) =
                                if recover then FileSystem.overlayRecovering(host, root).unit else ()
                            recovery
                        actual   <- snapshot(target)
                        contents <- host.readBytes(target)
                    yield
                        assert(existedBeforeRecovery == !recover)
                        assert(actual.is(expected), "Overlay creation bypassed the destination parent's default ACL or group")
                        assert(contents.is(bytes))
                    end for
                }
            }
        end for

        for recoverParent <- Seq(false, true) do
            s"nested overlays preserve private recovery staging, recover parent: $recoverParent" in {
                directory.map { root =>
                    val target   = root / "nested-private.bin"
                    val original = Span(7.toByte, 8.toByte)
                    val bytes    = Span(1.toByte, 2.toByte, 3.toByte)
                    val pause    = new IntentWritten
                    for
                        _      <- configure(root, 2)
                        host   <- FileSystem.host(root)
                        _      <- host.writeBytes(target, original, Path.WriteOptions())
                        _      <- configure(target, 1)
                        before <- snapshot(target)
                        state <- Scope.acquireRelease(AtomicRef.init(OverlayFileSystem.OverlayState.empty))(
                            _.set(OverlayFileSystem.OverlayState.terminated)
                        )
                        sequence <- AtomicLong.init(0L)
                        // Pin volatile temporary names beneath this test's root. Overlay has no root of its own.
                        parent = new OverlayFileSystem[Sync](host, state, sequence):
                            override def tempDir(prefix: String)(using
                                Frame
                            ): Path.TempDirHandle < (Sync & Abort[FileStructureException]) =
                                super.tempDir((root / prefix).toString)
                        child <- FileSystem.overlay(parent)
                        overlay = child.asInstanceOf[OverlayFileSystem[Sync]]
                        _ <- overlay.durableReplace(target, bytes)
                        // Unsafe: the hook leaves the child's complete recovery log staged in its parent.
                        _ <- Sync.Unsafe.defer { overlay.afterIntentLogHook = () => throw pause }
                        result <- Abort.run[Throwable](Abort.run[FileSystemException | CommitConflict](
                            overlay.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ))
                        _ <- Sync.defer {
                            result match
                                case Result.Panic(error) if error eq pause                 => ()
                                case Result.Success(Result.Panic(error)) if error eq pause => ()
                                case other => fail(s"Expected the intent-log hook to stop commit, got $other")
                        }
                        staging       <- Sync.defer(overlay.stagingDirHandle.get.path)
                        beforeEntries <- host.list(root)
                        // Unsafe: the second crash forces private-directory requirements through journal decoding.
                        _ <- Sync.Unsafe.defer {
                            if recoverParent then parent.afterIntentLogHook = () => throw pause
                        }
                        parentResult <- Abort.run[Throwable](Abort.run[FileSystemException | CommitConflict](
                            parent.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        ))
                        _ <- Sync.defer {
                            if recoverParent then
                                parentResult match
                                    case Result.Panic(error) if error eq pause                 => ()
                                    case Result.Success(Result.Panic(error)) if error eq pause => ()
                                    case other => fail(s"Expected the parent's intent-log hook to stop commit, got $other")
                            else assert(parentResult == Result.succeed(Result.unit))
                        }
                        _ <-
                            val recovery: Unit < (Sync & Scope & Abort[FileSystemException]) =
                                if recoverParent then FileSystem.overlayRecovering(host, root).unit else ()
                            recovery
                        persisted   <- host.realPath(staging)
                        privateDir  <- privateAccess(persisted)
                        logBytes    <- host.readBytes(staging / "intent.kyo")
                        uncommitted <- host.readBytes(target)
                        _           <- FileSystem.overlayRecovering(host, root)
                        after       <- snapshot(target)
                        actual      <- host.readBytes(target)
                        entries     <- host.list(root)
                    yield
                        WriteOpLog.decode(staging / "intent.kyo", logBytes) match
                            case Result.Success(Present(plan)) =>
                                val loggedBytes = plan.collect { case ReplayEntry.File(_, content, _, _, _, _) => content }
                                assert(loggedBytes.size == 1 && loggedBytes.headOption.exists(_.is(bytes)))
                            case other => fail(s"Expected the child's complete recovery log, got $other")
                        end match
                        assert(beforeEntries == Chunk(target))
                        assert(uncommitted.is(original))
                        assert(privateDir == 1, "The parent committed the child's private recovery directory with public access")
                        assert(after.is(before), "Recovering the child's replacement changed the original access ACL")
                        assert(actual.is(bytes))
                        assert(entries == Chunk(target))
                    end for
                }
            }

        end for

        "the staging directory protects private bytes duplicated in the intent log" in {
            directory.map { root =>
                val target   = root / "private.bin"
                val original = Span(7.toByte, 8.toByte)
                val bytes    = Span(1.toByte, 2.toByte, 3.toByte)
                val pause    = new IntentWritten
                for
                    _       <- configure(root, 2)
                    lower   <- FileSystem.host(root)
                    _       <- lower.writeBytes(target, original, Path.WriteOptions())
                    _       <- configure(target, 1)
                    created <- FileSystem.overlay(lower)
                    // The factory returns this concrete implementation; its hooks let the test stop before apply.
                    overlay = created.asInstanceOf[OverlayFileSystem[Sync]]
                    _ <- overlay.durableReplace(target, bytes)
                    // Unsafe: this synchronous hook halts commit after the log is durable, before target mutation.
                    _ <- Sync.Unsafe.defer { overlay.afterIntentLogHook = () => throw pause }
                    result <- Abort.run[Throwable](Abort.run[FileSystemException | CommitConflict](
                        overlay.commitWith(_ => FileSystem.Resolution.KeepOurs)
                    ))
                    _ <- Sync.defer {
                        result match
                            case Result.Panic(error) if error eq pause                 => ()
                            case Result.Success(Result.Panic(error)) if error eq pause => ()
                            case other => fail(s"Expected the intent-log hook to stop commit, got $other")
                    }
                    staging <- Sync.defer(overlay.stagingDirHandle.get.path)
                    intent = staging / "intent.kyo"
                    privateDirectory <- privateAccess(staging)
                    logBytes         <- lower.readBytes(intent)
                    uncommitted      <- lower.readBytes(target)
                    // Unsafe: recovery resumes the same completed log after inspection.
                    _      <- Sync.Unsafe.defer { overlay.afterIntentLogHook = () => () }
                    _      <- overlay.recover()
                    actual <- lower.readBytes(target)
                yield
                    WriteOpLog.decode(intent, logBytes) match
                        case Result.Success(Present(plan)) =>
                            val loggedBytes = plan.collect { case ReplayEntry.File(_, content, _, _, _, _) => content }
                            assert(loggedBytes.size == 1 && loggedBytes.headOption.exists(_.is(bytes)))
                        case other => fail(s"Expected a complete intent log containing the replacement bytes, got $other")
                    end match
                    assert(uncommitted.is(original))
                    assert(privateDirectory == 1, "The intent log contains private bytes behind a directory with inherited public access")
                    assert(actual.is(bytes))
                end for
            }
        }

        for moveDirectory <- Seq(false, true) do
            s"durable replacement retains the permission source after an overlay move, directory: $moveDirectory" in {
                directory.map { root =>
                    val sourceDir = root / "source"
                    val source    = sourceDir / "private.bin"
                    val movedDir  = root / "moved"
                    val target    = if moveDirectory then movedDir / "private.bin" else root / "renamed.bin"
                    val bytes     = Span(1.toByte, 2.toByte, 3.toByte)
                    for
                        host    <- FileSystem.host(root)
                        _       <- host.mkDir(sourceDir)
                        _       <- host.writeBytes(source, Span(7.toByte, 8.toByte), Path.WriteOptions())
                        _       <- configure(source, 1)
                        before  <- snapshot(source)
                        overlay <- FileSystem.overlay(host)
                        _ <- overlay.move(
                            if moveDirectory then sourceDir else source,
                            if moveDirectory then movedDir else target,
                            Path.MoveOptions()
                        )
                        _            <- overlay.durableReplace(target, bytes)
                        _            <- overlay.commitWith(_ => FileSystem.Resolution.KeepOurs)
                        after        <- snapshot(target)
                        actual       <- host.readBytes(target)
                        sourceExists <- host.exists(source)
                    yield
                        assert(after.is(before), "Replacing the moved file discarded the original host access ACL")
                        assert(actual.is(bytes))
                        assert(!sourceExists)
                    end for
                }
            }
        end for

        "overlay commit preserves host permissions after a staged durable replacement" in {
            directory.map { root =>
                val target   = root / "overlay.bin"
                val original = Span(7.toByte, 8.toByte)
                val bytes    = Span(1.toByte, 2.toByte, 3.toByte)
                for
                    lower                  <- FileSystem.host(root)
                    _                      <- lower.writeBytes(target, original, Path.WriteOptions())
                    _                      <- configure(target, 1)
                    before                 <- snapshot(target)
                    overlay                <- FileSystem.overlay(lower)
                    _                      <- overlay.durableReplace(target, bytes)
                    staged                 <- overlay.readBytes(target)
                    uncommittedBytes       <- lower.readBytes(target)
                    uncommittedPermissions <- snapshot(target)
                    _                      <- overlay.commitWith(_ => FileSystem.Resolution.KeepOurs)
                    after                  <- snapshot(target)
                    actual                 <- lower.readBytes(target)
                    entries                <- lower.list(root)
                yield
                    assert(staged.is(bytes))
                    assert(uncommittedBytes.is(original))
                    assert(uncommittedPermissions.is(before))
                    assert(after.is(before), "Committing the staged replacement changed the host access ACL")
                    assert(actual.is(bytes))
                    assert(entries == Chunk(target))
                end for
            }
        }
    end if

end OverlayFileSystemPermissionTest
