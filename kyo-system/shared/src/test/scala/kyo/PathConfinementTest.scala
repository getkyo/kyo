package kyo

/** Tests for `Service.host(root)`, which confines all path operations to a real directory tree.
  * This file covers the missing-path arm: a write whose nearest existing parent resolves outside
  * the confinement root is rejected with `FileAccessDeniedException`. The symlink-escape arm
  * (which requires `JFiles.createSymbolicLink`) lives in `PathConfinementJvmTest`.
  */
class PathConfinementTest extends kyo.test.Test[Any]:

    "write to a path whose nearest existing parent is outside the root aborts FileAccessDeniedException" in {
        Scope.run {
            // Create a real temp directory to use as the confinement root.
            Path.run(Path.tempDir("conf-root")).map { root =>
                // The parent of root exists and is NOT inside root.
                // A write to (root.parent / "escaped.txt") has nearest existing parent = root.parent,
                // which is outside the root, so confined() aborts FileAccessDeniedException.
                root.parent match
                    case Absent =>
                        fail("root has no parent; cannot construct an escape path")
                    case Present(parentDir) =>
                        val escapePath = parentDir / "conf-escaped.txt"
                        Abort.run[FileException](
                            PathService.host(root).map { confined =>
                                Path.runWith(confined)(escapePath.write("should not land"))
                            }
                        ).map { result =>
                            assert(result.isFailure, s"expected FileAccessDeniedException but got: $result")
                            assert(
                                result.failure.exists(_.isInstanceOf[FileAccessDeniedException]),
                                s"expected FileAccessDeniedException but got: ${result.failure}"
                            )
                        }
            }
        }
    }

end PathConfinementTest
