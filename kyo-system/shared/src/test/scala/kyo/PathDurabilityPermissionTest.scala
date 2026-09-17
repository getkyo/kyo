package kyo

import kyo.PathPermissionTestSupport.*
import kyo.internal.Platform

class PathDurabilityPermissionTest extends kyo.test.Test[Any]:

    "private directory replay retains existing private contents" in {
        directory.map { root =>
            val staging = root / "private-staging"
            val content = staging / "record"
            val bytes   = Span(1.toByte, 2.toByte, 3.toByte)
            for
                _                <- FileSystem.host.privateMkDir(staging)
                _                <- FileSystem.host.writeBytes(content, bytes, Path.WriteOptions())
                before           <- snapshot(staging)
                _                <- FileSystem.host.privateMkDir(staging)
                after            <- snapshot(staging)
                privateDirectory <- privateAccess(staging)
                actual           <- FileSystem.host.readBytes(content)
            yield
                assert(privateDirectory == 1)
                assert(after.is(before))
                assert(actual.is(bytes))
            end for
        }
    }

    "private directory replay rejects public directories without modifying their contents" in {
        directory.map { root =>
            val staging = root / "public-staging"
            val content = staging / "record"
            val bytes   = Span(1.toByte, 2.toByte, 3.toByte)
            for
                _      <- FileSystem.host.mkDir(staging)
                _      <- configure(staging, 2)
                _      <- Sync.defer { if !Platform.isWindows then PathPermissionTestPlatform.setPermissions(staging, "rwxr-xr-x") }
                _      <- FileSystem.host.writeBytes(content, bytes, Path.WriteOptions())
                before <- snapshot(staging)
                result <- Abort.run[FileSystemException](FileSystem.host.privateMkDir(staging))
                after  <- snapshot(staging)
                actual <- FileSystem.host.readBytes(content)
            yield
                result match
                    case Result.Failure(_) => ()
                    case other             => fail(s"Expected rejection of a public directory, got $other")
                assert(after.is(before))
                assert(actual.is(bytes))
            end for
        }
    }

    private def replace(target: Path, bytes: Span[Byte])(using
        Frame,
        kyo.test.AssertScope
    ): Unit <
        (Sync & Abort[FileSystemException]) =
        Abort.run[FileSystemException](FileSystem.host.durableReplace(target, bytes)).map {
            case Result.Success(_) => ()
            case Result.Failure(FileAccessDeniedException(path)) if Platform.isWindows =>
                assert(path == target.parent.get)
                ()
            case Result.Failure(FileIOException(path, FileSystemOperation.SyncDirectory, _)) if Platform.isWindows =>
                assert(path == target.parent.get)
                ()
            case other => fail(s"Expected durable replacement or unsupported Windows directory sync, got $other")
        }

    for nested <- Seq(false, true) do
        s"durable replacement preserves unmodified host access controls, nested: $nested" in {
            directory.map { root =>
                val fs     = FileSystem.host
                val parent = if nested then root / "nested" else root
                val target = parent / "ordinary.bin"
                val bytes  = Span(1.toByte, 2.toByte)
                for
                    _       <- fs.writeBytes(target, Span(9.toByte), Path.WriteOptions())
                    before  <- snapshot(target)
                    _       <- replace(target, bytes)
                    after   <- snapshot(target)
                    actual  <- fs.readBytes(target)
                    entries <- fs.list(parent)
                yield
                    assert(after.is(before), "Replacement changed unmodified host access controls")
                    assert(actual.is(bytes))
                    assert(entries == Chunk(target))
                end for
            }
        }
    end for

    if !Platform.isWindows then
        "private directories retain owner traversal beneath an inherited default ACL" in {
            directory.map { root =>
                val staging = root / "private-staging"
                val content = staging / "record"
                val bytes   = Span(1.toByte, 2.toByte, 3.toByte)
                for
                    _                <- configure(root, 2)
                    parentBefore     <- snapshot(root)
                    _                <- FileSystem.host.privateMkDir(staging)
                    permissions      <- Sync.defer(PathPermissionTestPlatform.permissions(staging))
                    privateDirectory <- privateAccess(staging)
                    _                <- FileSystem.host.writeBytes(content, bytes, Path.WriteOptions())
                    _                <- FileSystem.host.privateMkDir(staging)
                    actual           <- FileSystem.host.readBytes(content)
                    parentAfter      <- snapshot(root)
                yield
                    assert(permissions == "rwx------")
                    assert(privateDirectory == 1)
                    assert(actual.is(bytes))
                    assert(parentAfter.is(parentBefore))
                end for
            }
        }

        "durable creation inherits the destination parent's default access controls" in {
            directory.map { root =>
                val reference = root / "ordinary.bin"
                val target    = root / "durable.bin"
                val bytes     = Span(1.toByte, 2.toByte, 3.toByte)
                for
                    _        <- configure(root, 10)
                    _        <- FileSystem.host.writeBytes(reference, bytes, Path.WriteOptions())
                    expected <- snapshot(reference)
                    _        <- FileSystem.host.durableReplace(target, bytes)
                    actual   <- snapshot(target)
                    contents <- FileSystem.host.readBytes(target)
                yield
                    assert(actual.is(expected), "Durable creation changed the destination parent's default ACL or group")
                    assert(contents.is(bytes))
                end for
            }
        }

        for permissions <- Seq("rw-------", "rw-r-----") do
            s"durable replacement preserves existing $permissions permissions" in {
                directory.map { root =>
                    val fs     = FileSystem.host
                    val target = root / "state.bin"
                    val bytes  = Span(1.toByte, 2.toByte, 3.toByte)
                    for
                        _       <- fs.writeBytes(target, Span(9.toByte), Path.WriteOptions())
                        _       <- Sync.defer(PathPermissionTestPlatform.setPermissions(target, permissions))
                        before  <- Sync.defer(PathPermissionTestPlatform.permissions(target))
                        _       <- fs.durableReplace(target, bytes)
                        after   <- Sync.defer(PathPermissionTestPlatform.permissions(target))
                        _       <- Sync.defer(PathPermissionTestPlatform.setPermissions(target, "rw-------"))
                        actual  <- fs.readBytes(target)
                        entries <- fs.list(root)
                    yield
                        assert(before == permissions)
                        assert(after == permissions)
                        assert(actual.is(bytes))
                        assert(entries == Chunk(target))
                    end for
                }
            }
        end for

    end if

    if !Platform.isWindows then
        for permissions <- Seq("---------", "-w-------") do
            s"replacement with $permissions follows the ability to inspect security metadata" in {
                directory.map { root =>
                    val fs       = FileSystem.host
                    val target   = root / "unreadable-security.bin"
                    val original = Span(8.toByte, 9.toByte)
                    for
                        _          <- fs.writeBytes(target, original, Path.WriteOptions())
                        _          <- Sync.defer(PathPermissionTestPlatform.setPermissions(target, permissions))
                        canInspect <- canInspectSecurity(target)
                        result     <- Abort.run[FileSystemException](fs.durableReplace(target, Span(1.toByte)))
                        after      <- Sync.defer(PathPermissionTestPlatform.permissions(target))
                        _          <- Sync.defer(PathPermissionTestPlatform.setPermissions(target, "rw-------"))
                        actual     <- fs.readBytes(target)
                        entries    <- fs.list(root)
                    yield
                        if canInspect then assert(result == Result.unit)
                        else
                            result match
                                case Result.Failure(FileAccessDeniedException(path)) => assert(path == target)
                                case other => fail(s"Expected denied access to the target security attributes, got $other")
                        end if
                        assert(after == permissions)
                        assert(actual.is(if canInspect then Span(1.toByte) else original))
                        assert(entries == Chunk(target))
                    end for
                }
            }
        end for
    end if

    if Platform.isMac then
        "a deny-delete ACL fails before copying an unremovable temporary" in {
            directory.map { root =>
                val fs       = FileSystem.host
                val target   = root / "deny-delete.bin"
                val original = Span(8.toByte, 9.toByte)
                fs.writeBytes(target, original, Path.WriteOptions())
                    .andThen(configure(target, 7))
                    .andThen {
                        Sync.ensure(configure(target, 0)) {
                            for
                                before  <- snapshot(target)
                                result  <- Abort.run[FileSystemException](fs.durableReplace(target, Span(1.toByte)))
                                after   <- snapshot(target)
                                actual  <- fs.readBytes(target)
                                entries <- fs.list(root)
                            yield
                                result match
                                    case Result.Failure(FileAccessDeniedException(path)) =>
                                        assert(path == target, s"Expected target denial at $target, got $path")
                                    case other => fail(s"Expected denied replacement of a deny-delete target, got $other")
                                end match
                                assert(after.is(before))
                                assert(actual.is(original))
                                assert(entries == Chunk(target))
                        }
                    }
            }
        }

        for (description, kind) <- Seq(("inherited deny-delete", 8), ("parent deny-delete-child", 9)) do
            s"a missing target with $description fails without leaving a temporary" in {
                directory.map { root =>
                    val fs     = FileSystem.host
                    val target = root / "new.bin"
                    configure(root, kind).andThen {
                        Sync.ensure(configure(root, 0)) {
                            for
                                before  <- snapshot(root)
                                result  <- Abort.run[FileSystemException](fs.durableReplace(target, Span(1.toByte)))
                                after   <- snapshot(root)
                                entries <- fs.list(root)
                            yield
                                if kind == 8 then
                                    result match
                                        case Result.Failure(FileIOException(path, FileSystemOperation.Channel, _)) => assert(path == target)
                                        case other => fail(s"Expected unsupported inheritable deny-delete ACL, got $other")
                                else
                                    result match
                                        case Result.Failure(FileAccessDeniedException(path)) => assert(path == target)
                                        case other => fail(s"Expected denied child deletion, got $other")
                                end if
                                assert(after.is(before))
                                assert(entries == Chunk.empty)
                        }
                    }
                }
            }
        end for
    end if

    if Platform.isWindows then
        "replacement requires DELETE on the target or FILE_DELETE_CHILD on its parent" in {
            directory.map { root =>
                val fs       = FileSystem.host
                val target   = root / "denied-delete.bin"
                val original = Span(8.toByte, 9.toByte)
                fs.writeBytes(target, original, Path.WriteOptions())
                    .andThen(configure(target, 6))
                    .andThen(configure(root, 5))
                    .andThen {
                        Sync.ensure(configure(root, 0).andThen(configure(target, 0))) {
                            for
                                before  <- snapshot(target)
                                result  <- Abort.run[FileSystemException](fs.durableReplace(target, Span(1.toByte)))
                                after   <- snapshot(target)
                                actual  <- fs.readBytes(target)
                                entries <- fs.list(root)
                            yield
                                result match
                                    case Result.Failure(FileAccessDeniedException(path)) =>
                                        assert(path == target, s"Expected target denial at $target, got $path")
                                    case other => fail(s"Expected denied replacement without DELETE permission, got $other")
                                end match
                                assert(after.is(before))
                                assert(actual.is(original))
                                assert(entries == Chunk(target))
                        }
                    }
            }
        }
    end if

    for (description, kind) <-
            if Platform.isWindows then
                Seq(
                    ("ordered Windows ACL", 1),
                    ("empty Windows DACL", 3),
                    ("null Windows DACL", 4),
                    ("protected legacy owner-only Windows ACL", 11),
                    ("protected legacy ordered Windows ACL", 12),
                    ("protected legacy empty Windows DACL", 13),
                    ("protected legacy null Windows DACL", 14),
                    ("Windows mandatory label with a legacy DACL", 15),
                    ("Windows mandatory label with a modern DACL", 16)
                )
            else Seq(("host access ACL", 1))
    do
        s"durable replacement preserves the complete $description" in {
            directory.map { root =>
                val fs     = FileSystem.host
                val target = root / "acl.bin"
                val bytes  = Span(4.toByte, 5.toByte)
                for
                    _        <- fs.writeBytes(target, Span(9.toByte), Path.WriteOptions())
                    ordinary <- snapshot(target)
                    _        <- configure(target, kind)
                    before   <- snapshot(target)
                    _        <- replace(target, bytes)
                    after    <- snapshot(target)
                    _        <- configure(target, 0)
                    actual   <- fs.readBytes(target)
                    entries  <- fs.list(root)
                yield
                    assert(!before.is(ordinary), "The fixture must install an ACL distinct from ordinary file creation")
                    assert(after.is(before), "Replacement changed the owner, group, mode, ACL entries, or ACL flags")
                    assert(actual.is(bytes))
                    assert(entries == Chunk(target))
                end for
            }
        }
    end for

    "durable replacement retains inherited ACL entries and flags" in {
        directory.map { root =>
            val fs        = FileSystem.host
            val reference = root / "ordinary.bin"
            val target    = root / "inherited.bin"
            val bytes     = Span(3.toByte, 4.toByte)
            for
                _        <- fs.writeBytes(reference, Span(9.toByte), Path.WriteOptions())
                ordinary <- snapshot(reference)
                _        <- configure(root, 2)
                _        <- fs.writeBytes(target, Span(8.toByte), Path.WriteOptions())
                before   <- snapshot(target)
                _        <- replace(target, bytes)
                after    <- snapshot(target)
                actual   <- fs.readBytes(target)
            yield
                assert(!before.is(ordinary), "The fixture must make new files inherit a distinct ACL")
                assert(after.is(before), "Replacement changed inherited ACL entries or control flags")
                assert(actual.is(bytes))
            end for
        }
    }

    "the temporary blocks inherited access before writing and retains the target ACL at sync" in {
        directory.map { root =>
            val fs        = FileSystem.host
            val target    = root / "target.bin"
            val temporary = root / "temporary.bin"
            val original  = Span(8.toByte)
            val bytes     = Span(1.toByte, 2.toByte)
            for
                _      <- configure(root, 2)
                _      <- fs.writeBytes(target, original, Path.WriteOptions())
                _      <- configure(target, 1)
                before <- snapshot(target)
                opened <- Scope.acquireRelease(
                    fs.openWriteChannelUnscoped(temporary, FileSystem.WriteOpen.CreateNew, _ => (), Present(target))
                ) { (_, release, _) => release() }
                (channel, release, _) = opened
                protectedBeforeWrite <- privateAccess(temporary)
                _                    <- channel.writeAt(0L, bytes)
                protectedAfterWrite  <- privateAccess(temporary)
                unchangedBeforeSync  <- snapshot(target)
                _                    <- channel.sync(metadata = true)
                sealedPermissions    <- snapshot(temporary)
                _                    <- release()
                _                    <- configure(temporary, 0)
                actual               <- fs.readBytes(temporary)
                targetBytes          <- fs.readBytes(target)
            yield
                assert(protectedBeforeWrite == 1, "The empty temporary inherited access before its first write")
                assert(protectedAfterWrite == 1, "The temporary exposed bytes before final permissions were restored")
                assert(unchangedBeforeSync.is(before))
                assert(sealedPermissions.is(before))
                assert(actual.is(bytes))
                assert(targetBytes.is(original))
            end for
        }
    }

    "a non-file permission source fails before changing the target or retaining a temporary" in {
        directory.map { root =>
            val fs       = FileSystem.host
            val target   = root / "target"
            val sentinel = target / "original.bin"
            val bytes    = Span(8.toByte, 9.toByte)
            for
                _       <- fs.mkDir(target)
                _       <- fs.writeBytes(sentinel, bytes, Path.WriteOptions())
                before  <- snapshot(target)
                result  <- Abort.run[FileSystemException](fs.durableReplace(target, Span(1.toByte)))
                after   <- snapshot(target)
                actual  <- fs.readBytes(sentinel)
                entries <- fs.list(root)
            yield
                assert(result.isFailure)
                assert(after.is(before))
                assert(actual.is(bytes))
                assert(entries == Chunk(target))
            end for
        }
    }

end PathDurabilityPermissionTest
