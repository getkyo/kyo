package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** JVM-only tests for `Service.host(root)` confinement via symlink escape. A symlink placed inside
  * the confinement root that resolves to a path outside must be rejected with
  * `FileOutsideRootException` when an operation follows it. Symlink creation uses
  * `JFiles.createSymbolicLink`, which is JVM-only; the platform-neutral missing-path arm lives in
  * `PathConfinementTest`.
  */
class PathConfinementJvmTest extends kyo.test.Test[Any]:

    "symlink inside the confinement root that resolves outside is rejected with FileOutsideRootException" in {
        Scope.run {
            Path.run(Path.tempDir("conf-jvm-root")).map { root =>
                Path.runReadOnly(root.realPath).map { rootReal =>
                    Path.run(Path.tempDir("conf-jvm-outside")).map { outside =>
                        // Write a sentinel file in the outside directory.
                        Path.run(
                            (outside / "sentinel.txt").write("outside")
                        ).andThen {
                            // Create a symlink inside root -> outside using java.nio directly.
                            val rootNio    = JPaths.get(root.parts.mkString("/"))
                            val outsideNio = JPaths.get(outside.parts.mkString("/"))
                            val linkNio    = rootNio.resolve("escape-link")
                            // Unsafe: creates a JVM-level symlink to test confinement realpath defense
                            Sync.Unsafe.defer(JFiles.createSymbolicLink(linkNio, outsideNio)).andThen {
                                // Build the path that resolves THROUGH the symlink.
                                val throughLink = root / "escape-link" / "sentinel.txt"
                                Abort.run[FileSystemException](
                                    FileSystem.host(root).map { confined =>
                                        Path.runWith(confined)(throughLink.read)
                                    }
                                ).map { result =>
                                    result.failure match
                                        case Present(error: FileOutsideRootException) =>
                                            assert(error.root == rootReal)
                                            assert(error.path == throughLink)
                                            assert(error.operation == FileSystemOperation.Read)
                                        case other => fail(s"expected FileOutsideRootException but got: $other")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Builds a confinement root containing a symlink that escapes to an outside directory, then
      * runs `check` with the root, the path that resolves through the link, and the outside target.
      */
    private def withEscapeLink[A](label: String)(
        check: (Path, Path) => A < (Sync & Scope & Abort[FileSystemException] & Async)
    )(using Frame): A < (Sync & Scope & Abort[FileSystemException] & Async) =
        Path.run(Path.tempDir(s"$label-root")).map { root =>
            Path.run(Path.tempDir(s"$label-outside")).map { outside =>
                Path.run((outside / "sentinel.txt").write("outside")).andThen {
                    val rootNio    = JPaths.get(root.parts.mkString("/"))
                    val outsideNio = JPaths.get(outside.parts.mkString("/"))
                    val linkNio    = rootNio.resolve("escape-link")
                    // Unsafe: creates a JVM-level symlink to test the overlay's realpath defense
                    Sync.Unsafe.defer(JFiles.createSymbolicLink(linkNio, outsideNio)).andThen {
                        check(root, root / "escape-link" / "sentinel.txt")
                    }
                }
            }
        }

    "confinedTo rejects a symlink escape identically with and without a staged-write overlay" in {
        Scope.run {
            withEscapeLink("conf-overlay") { (root, throughLink) =>
                // Control: no overlay installed. The host backend resolves the link and rejects.
                Abort.run[FileSystemException](Path.runReadOnly(throughLink.confinedTo(root))).map { control =>
                    assert(
                        control.failure.exists(_.isInstanceOf[FileAccessDeniedException]),
                        s"control (no overlay) should reject the escape, got: $control"
                    )
                    // Same check inside a staged-write overlay must reach the same verdict.
                    Abort.run[FileSystemException](Path.run(Path.discardWrites(throughLink.confinedTo(root)))).map { staged =>
                        staged.failure match
                            case Present(_: FileAccessDeniedException) => assert(true)
                            case other =>
                                fail(s"overlay accepted a symlink escape that the host rejected: $other")
                    }
                }
            }
        }
    }

    "isSymbolicLink reports a lower symlink identically with and without a staged-write overlay" in {
        Scope.run {
            withEscapeLink("symlink-overlay") { (root, _) =>
                val link = root / "escape-link"
                Path.runReadOnly(link.isSymbolicLink).map { control =>
                    assert(control, "control (no overlay) should report the path as a symlink")
                    Path.run(Path.discardWrites(link.isSymbolicLink)).map { staged =>
                        assert(staged, "overlay reported false for a path the host reports as a symlink")
                    }
                }
            }
        }
    }

end PathConfinementJvmTest
