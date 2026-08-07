package kyo

import java.nio.file.Files as JFiles
import java.nio.file.Paths as JPaths

/** JVM-only tests for `FileSystem.host(root)` confinement via symlink escape. A symlink placed inside
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

    Chunk("", "/child.txt").foreach { suffix =>
        s"writes through a dangling escaping symlink are rejected: $suffix" in {
            for
                root    <- Path.run(Path.tempDir("conf-dangling-root"))
                outside <- Path.run(Path.tempDir("conf-dangling-outside"))
                missing = outside / "missing"
                link    = root / "link"
                // Unsafe: creates a dangling host symlink to test missing-path confinement.
                _       <- Sync.Unsafe.defer(JFiles.createSymbolicLink(JPaths.get(link.unsafe.show), JPaths.get(missing.unsafe.show)))
                fs      <- FileSystem.host(root)
                result  <- Abort.run[FileSystemException](fs.write(Path(link.unsafe.show + suffix), "escaped", Path.WriteOptions()))
                entries <- FileSystem.host.list(outside)
            yield
                assert(result.isFailure, s"Expected dangling symlink rejection, got $result")
                assert(entries.isEmpty)
        }
    }

    "lock sentinels cannot follow a symlink outside the confinement root" in {
        for
            root    <- Path.run(Path.tempDir("conf-sentinel-root"))
            outside <- Path.run(Path.tempDir("conf-sentinel-outside"))
            target  = root / "data"
            foreign = outside / "foreign-lock"
            _ <- FileSystem.host.mkFile(target)
            _ <- FileSystem.host.mkFile(foreign)
            sentinel = Path(target.unsafe.show + ".custom-lock")
            // Unsafe: creates a sentinel symlink whose target is outside confinement.
            _      <- Sync.Unsafe.defer(JFiles.createSymbolicLink(JPaths.get(sentinel.unsafe.show), JPaths.get(foreign.unsafe.show)))
            fs     <- FileSystem.host(root)
            result <- Abort.run[FileSystemException](Scope.run(fs.tryLock(target, Path.LockMode.Exclusive, ".custom-lock")))
        yield result match
            case Result.Failure(error: FileOutsideRootException) => assert(error.operation == FileSystemOperation.Lock)
            case other                                           => fail(s"Expected sentinel symlink rejection, got $other")
    }

    "recursive walks reject descendant symlinks that escape the confinement root" in {
        withEscapeLink("conf-walk") { (root, _) =>
            FileSystem.host(root).map { fs =>
                Abort.run[FileSystemException](Path.runWith(fs)(root.walk(followLinks = true).run)).map {
                    case Result.Failure(error: FileOutsideRootException) => assert(error.operation == FileSystemOperation.Walk)
                    case other                                           => fail(s"Expected walk confinement failure, got $other")
                }
            }
        }
    }

    "confined walks follow both in-root aliases and respect depth and no-follow" in {
        for
            root <- Path.run(Path.tempDir("conf-walk-inside"))
            directory = root / "directory"
            file      = directory / "file.txt"
            first     = root / "first"
            second    = root / "second"
            _ <- FileSystem.host.write(file, "inside", Path.WriteOptions())
            // Unsafe: creates two aliases to the same in-root directory.
            _ <- Sync.Unsafe.defer {
                discard(JFiles.createSymbolicLink(JPaths.get(first.unsafe.show), JPaths.get(directory.unsafe.show)))
                discard(JFiles.createSymbolicLink(JPaths.get(second.unsafe.show), JPaths.get(directory.unsafe.show)))
            }
            fs       <- FileSystem.host(root)
            all      <- Path.runWith(fs)(root.walk(followLinks = true).run)
            zero     <- Path.runWith(fs)(root.walk(maxDepth = 0, followLinks = true).run)
            one      <- Path.runWith(fs)(root.walk(maxDepth = 1, followLinks = true).run)
            noFollow <- Path.runWith(fs)(root.walk(followLinks = false).run)
        yield
            assert(all.toSet == Set(root, directory, file, first, first / "file.txt", second, second / "file.txt"))
            assert(zero == Chunk(root))
            assert(one.toSet == Set(root, directory, first, second))
            assert(noFollow.toSet == Set(root, directory, file, first, second))
    }

    "confined walk cycles fail through the filesystem error channel" in {
        for
            root <- Path.run(Path.tempDir("conf-walk-cycle"))
            link = root / "cycle"
            // Unsafe: creates a directory cycle without leaving the confinement root.
            _      <- Sync.Unsafe.defer(JFiles.createSymbolicLink(JPaths.get(link.unsafe.show), JPaths.get(root.unsafe.show)))
            fs     <- FileSystem.host(root)
            result <- Abort.run[FileSystemException](Path.runWith(fs)(root.walk(followLinks = true).run))
        yield result match
            case Result.Failure(error: FileIOException) =>
                assert(error.path == link)
                assert(error.operation == FileSystemOperation.Walk)
            case other => fail(s"Expected typed cycle failure, got $other")
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
