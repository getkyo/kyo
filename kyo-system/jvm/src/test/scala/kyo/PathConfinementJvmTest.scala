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

end PathConfinementJvmTest
