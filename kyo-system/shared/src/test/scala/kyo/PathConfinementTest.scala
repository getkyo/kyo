package kyo

/** Tests for `FileSystem.host(root)`, which confines all path operations to a real directory tree.
  * This file covers the missing-path arm: a write whose nearest existing parent resolves outside
  * the confinement root is rejected with `FileOutsideRootException`. The symlink-escape arm
  * (which requires `JFiles.createSymbolicLink`) lives in `PathConfinementJvmTest`.
  */
class PathConfinementTest extends kyo.test.Test[Any]:

    "write to a path whose nearest existing parent is outside the root aborts FileOutsideRootException" in {
        Scope.run {
            // Create a real temp directory to use as the confinement root.
            Path.run(Path.tempDir("conf-root")).map { root =>
                Path.runReadOnly(root.realPath).map { rootReal =>
                    // The parent of root exists and is NOT inside root.
                    // A write to (root.parent / "escaped.txt") has nearest existing parent = root.parent,
                    // which is outside the root, so confined() aborts FileOutsideRootException.
                    root.parent match
                        case Absent =>
                            fail("root has no parent; cannot construct an escape path")
                        case Present(parentDir) =>
                            val escapePath = parentDir / "conf-escaped.txt"
                            Abort.run[FileSystemException](
                                FileSystem.host(root).map { confined =>
                                    Path.runWith(confined)(escapePath.write("should not land"))
                                }
                            ).map { result =>
                                result.failure match
                                    case Present(error: FileOutsideRootException) =>
                                        assert(error.root == rootReal)
                                        assert(error.path == escapePath)
                                        assert(error.operation == FileSystemOperation.Write)
                                    case other => fail(s"expected FileOutsideRootException but got: $other")
                            }
                }
            }
        }
    }

    "a confined temp file is created inside the root" in {
        Scope.run {
            Path.run(Path.tempDir("conf-temp-root")).map { root =>
                Path.runReadOnly(root.realPath).map { rootReal =>
                    FileSystem.host(root).map { confined =>
                        Path.runWith(confined) {
                            Path.temp("conf-temp-", ".txt").map { file =>
                                file.write("inside").andThen(file.read).map { contents =>
                                    // The OS temp dir is outside the root, so a temp file placed there would
                                    // fail its own confinement check on the very next use.
                                    assert(file.parent == Present(rootReal))
                                    assert(contents == "inside")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "locking the confinement root cannot create a sentinel beside it" in {
        for
            base <- Path.run(Path.tempDir("conf-lock-root"))
            root = base / "root"
            _      <- FileSystem.host.mkDir(root)
            fs     <- FileSystem.host(root)
            result <- Abort.run[FileSystemException](Scope.run(fs.tryLock(root, Path.LockMode.Exclusive)))
        yield result match
            case Result.Failure(error: FileOutsideRootException) => assert(error.operation == FileSystemOperation.Lock)
            case other                                           => fail(s"Expected confined sentinel rejection, got $other")
    }

    Chunk(("../escape", ".tmp"), ("safe", "/../../escape")).foreach { (prefix, suffix) =>
        s"temporary file names cannot escape confinement: $prefix $suffix" in {
            for
                base <- Path.run(Path.tempDir("conf-temp-escape"))
                root = base / "root"
                _      <- FileSystem.host.mkDir(root)
                fs     <- FileSystem.host(root)
                result <- Abort.run[FileSystemException](Scope.acquireRelease(fs.temp(prefix, suffix))(h => Sync.Unsafe.defer(h.remove())))
            yield result match
                case Result.Failure(error: FileInvalidPathException) => assert(error.operation == FileSystemOperation.Create)
                case other                                           => fail(s"Expected invalid temporary name, got $other")
        }
    }

    "temporary directory names cannot escape confinement" in {
        for
            base <- Path.run(Path.tempDir("conf-tempdir-escape"))
            root = base / "root"
            _      <- FileSystem.host.mkDir(root)
            fs     <- FileSystem.host(root)
            result <- Abort.run[FileSystemException](Scope.acquireRelease(fs.tempDir("../escape"))(h => Sync.Unsafe.defer(h.remove())))
        yield result match
            case Result.Failure(error: FileInvalidPathException) => assert(error.operation == FileSystemOperation.Create)
            case other                                           => fail(s"Expected invalid temporary name, got $other")
    }

    "reevaluating a confined temporary file acquisition creates independent owners" in {
        for
            root <- Path.run(Path.tempDir("conf-temp-repeat"))
            fs   <- FileSystem.host(root)
            acquire = fs.temp("repeat", ".tmp")
            first        <- Scope.acquireRelease(acquire)(h => Sync.Unsafe.defer(h.remove()))
            second       <- Scope.acquireRelease(acquire)(h => Sync.Unsafe.defer(h.remove()))
            _            <- Sync.Unsafe.defer(first.remove())
            secondExists <- fs.exists(second.path)
        yield
            assert(first.path != second.path)
            assert(secondExists)
    }

    "reevaluating a confined temporary directory acquisition creates independent owners" in {
        for
            root <- Path.run(Path.tempDir("conf-tempdir-repeat"))
            fs   <- FileSystem.host(root)
            acquire = fs.tempDir("repeat")
            first        <- Scope.acquireRelease(acquire)(h => Sync.Unsafe.defer(h.remove()))
            second       <- Scope.acquireRelease(acquire)(h => Sync.Unsafe.defer(h.remove()))
            _            <- Sync.Unsafe.defer(first.remove())
            secondExists <- fs.exists(second.path)
        yield
            assert(first.path != second.path)
            assert(secondExists)
    }

    "a followed confined walk rejects a missing starting path" in {
        for
            root <- Path.run(Path.tempDir("conf-walk-missing"))
            fs   <- FileSystem.host(root)
            missing = root / "missing"
            result <- Abort.run[FileSystemException](Path.runWith(fs)(missing.walk(followLinks = true).run))
        yield assert(result == Result.fail(FileNotFoundException(missing)), s"Missing walk result: $result")
    }

end PathConfinementTest
