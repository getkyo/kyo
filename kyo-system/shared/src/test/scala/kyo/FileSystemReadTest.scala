package kyo

/** Reusable behavioral contract for readable filesystem backends.
  *
  * Implementations provide a fresh backend, a populated file, and its expected UTF-8 value for
  * each assertion.
  *
  * `S >: Async` bounds the backend effect to one demanding no more than `Async`. The effect row is contravariant, so
  * this reads backwards from the usual intuition: both `Sync` (`Async <: Sync`) and `Async` satisfy it, while a
  * backend carrying extra state such as `Async & Var[X]` does not, since its operations could not be forked without
  * saying how that state splits. As a bound it lets the concurrency assertions fork backend work with no isolate
  * ceremony: `Fiber.initUnscoped` supplies `Async` to the body it runs, so the residual effect to isolate is `Any`.
  */
abstract class FileSystemReadTest[S >: Async] extends kyo.test.Test[Any]:

    /** Runs one assertion against a fresh backend, a populated file, and that file's expected value.
      *
      * A continuation rather than a factory, parameterized by the backend's own effect `S`: only the fixture can
      * discharge `S`, and a database-backed backend needs to open a client, run the assertion inside it, and
      * translate its own failures, none of which it could do by returning a backend for the suite to call.
      */
    protected def withFileSystem[A](
        use: (FileSystem.Read[S], Path, String) => A < (S & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException])

    /** Declares whether this backend resolves paths against a store that tracks existence.
      *
      * Backends over a real filesystem (host) fail with [[FileNotFoundException]] when asked to
      * resolve an absent path. A backend whose paths are pure keys with no link topology could
      * return the path unchanged instead. Each fixture states which it is, so `realPath` carries
      * an asserted contract on every backend rather than an untested one.
      */
    protected def realPathRequiresExistence: Boolean = false

    /** Declares whether this fixture can create a symbolic link, and creates one when it can.
      *
      * Symbolic links are the one filesystem behaviour this suite cannot exercise from shared code:
      * there is no public link-creation operation, so each platform supplies its own. A backend
      * whose paths are pure keys with no links at all leaves this declared false.
      *
      * Without this the suite asserts only that a regular file is not a link, which every backend
      * satisfies by answering false to everything, including a backend that has stopped resolving
      * links entirely. That one-sided assertion is what let a symlink-escape defect pass a green
      * four-platform CI once already.
      */
    protected def supportsSymbolicLinks: Boolean = false

    /** Creates a symbolic link at `link` pointing at `target`. Only called when
      * [[supportsSymbolicLinks]] is true.
      *
      * Receives the backend the assertion runs against, since for most backends that is the only thing that can make
      * a link. A fixture over the host ignores it and calls the platform.
      */
    protected def createSymbolicLink(fileSystem: FileSystem.Read[S], link: Path, target: Path)(using
        Frame
    ): Unit < (S & Sync & Abort[FileSystemException]) =
        Abort.panic(new UnsupportedOperationException("fixture declares supportsSymbolicLinks but does not implement it"))

    "read suite reports a symbolic link as a link" in {
        if !supportsSymbolicLinks then succeed
        else
            withFileSystem { (fileSystem, file, _) =>
                val link = file.parent.getOrElse(Path()) / "link-to-file"
                createSymbolicLink(fileSystem, link, file).andThen {
                    fileSystem.isSymbolicLink(link).map { isLink =>
                        assert(isLink, "a symbolic link must report as one")
                        // The target must still report as a regular file, so a backend cannot pass
                        // by answering true to everything any more than by answering false.
                        fileSystem.isSymbolicLink(file).map(targetIsLink => assert(!targetIsLink))
                    }
                }
            }
    }

    "read suite resolves a symbolic link to its target" in {
        if !supportsSymbolicLinks then succeed
        else
            withFileSystem { (fileSystem, file, expected) =>
                val link = file.parent.getOrElse(Path()) / "link-for-resolution"
                createSymbolicLink(fileSystem, link, file).andThen {
                    fileSystem.realPath(link).map { resolved =>
                        fileSystem.realPath(file).map { target =>
                            assert(resolved == target, s"link resolved to $resolved, target is $target")
                            // Reading through the link must reach the same content, so resolution is
                            // asserted against observable behaviour and not only against itself.
                            fileSystem.read(link).map(value => assert(value == expected))
                        }
                    }
                }
            }
    }

    "read suite resolves an existing path idempotently" in {
        withFileSystem { (fileSystem, file, _) =>
            fileSystem.realPath(file).map { resolved =>
                fileSystem.realPath(resolved).map(again => assert(again == resolved))
            }
        }
    }

    "read suite reports a regular file as not a symbolic link" in {
        withFileSystem { (fileSystem, file, _) =>
            fileSystem.isSymbolicLink(file).map(isLink => assert(!isLink))
        }
    }

    "read suite resolves an absent path per its declared existence contract" in {
        withFileSystem { (fileSystem, file, _) =>
            val missing = file.parent.getOrElse(Path()) / "missing-for-realpath"
            Abort.run[FileReadException](fileSystem.realPath(missing)).map { result =>
                if realPathRequiresExistence then
                    result match
                        case Result.Failure(_: FileNotFoundException) => assert(true)
                        case other => assert(false, s"expected FileNotFoundException for an absent path, got $other")
                else
                    result match
                        case Result.Success(resolved) => assert(resolved == missing)
                        case other                    => assert(false, s"expected the path returned unchanged, got $other")
            }
        }
    }

    "read suite resolves an absent path to itself via realPathPrefix" in {
        withFileSystem { (fileSystem, file, _) =>
            val parent  = file.parent.getOrElse(Path())
            val missing = parent / "missing-for-prefix"
            // Absence is not a failure here, unlike realPath: the resolved parent fixes where the
            // path would land, and the segment below it is re-appended unchanged.
            fileSystem.realPathPrefix(missing).map { resolved =>
                fileSystem.realPathPrefix(parent).map { resolvedParent =>
                    assert(resolved == resolvedParent / "missing-for-prefix", s"got $resolved")
                }
            }
        }
    }

    "read suite resolves a path with several absent segments via realPathPrefix" in {
        withFileSystem { (fileSystem, file, _) =>
            val parent  = file.parent.getOrElse(Path())
            val missing = parent / "absent-a" / "absent-b"
            fileSystem.realPathPrefix(missing).map { resolved =>
                fileSystem.realPathPrefix(parent).map { resolvedParent =>
                    assert(resolved == resolvedParent / "absent-a" / "absent-b", s"got $resolved")
                }
            }
        }
    }

    "read suite agrees between realPath and realPathPrefix on an existing path" in {
        withFileSystem { (fileSystem, file, _) =>
            fileSystem.realPath(file).map { viaRealPath =>
                fileSystem.realPathPrefix(file).map(viaPrefix => assert(viaPrefix == viaRealPath))
            }
        }
    }

    "read suite returns the concrete stored value" in {
        withFileSystem { (fileSystem, file, expected) =>
            fileSystem.read(file).map(value => assert(value == expected))
        }
    }

    "read suite reports a precise missing-file failure" in {
        withFileSystem { (fileSystem, file, _) =>
            Abort.run[FileReadException](fileSystem.read(file.parent.getOrElse(Path()) / "missing-file")).map {
                case Result.Failure(_: FileNotFoundException) => assert(true)
                case other                                    => assert(false, s"expected FileNotFoundException, got $other")
            }
        }
    }

    "read suite supports deterministic concurrent reads" in {
        withFileSystem { (fileSystem, file, expected) =>
            Async.zip(fileSystem.read(file), fileSystem.read(file)).map(values =>
                assert(values == (expected, expected))
            )
        }
    }

    "read suite releases scoped channels" in {
        withFileSystem { (fileSystem, file, _) =>
            Scope.run(fileSystem.openReadChannel(file)).map { channel =>
                Abort.run[FileReadException](channel.readAt(0L, 1)).map(result => assert(result.isFailure))
            }
        }
    }

end FileSystemReadTest
