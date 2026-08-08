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

end PathConfinementTest
