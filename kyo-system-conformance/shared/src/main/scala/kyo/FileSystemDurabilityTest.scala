package kyo

/** The durable-replacement contract of a mutable filesystem backend, one of the suites [[FileSystemWriteConformanceTest]] mixes in.
  *
  * `S >: Async` bounds the backend effect as described on [[FileSystemReadConformanceTest]].
  */
trait FileSystemDurabilityTest[S >: Async] extends kyo.test.Test[Any]:

    /** Runs one assertion against a fresh backend and a root to work under, shaped as a continuation for the reason
      * given on [[FileSystemReadConformanceTest.withFileSystem]].
      */
    protected def withFileSystem[A](
        use: (FileSystem.Write[S], Path) => A < (S & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException])

    /** Declares whether this backend can synchronize a directory's entries.
      *
      * A backend that cannot must say so through a failure naming the directory, never through a silent success, so
      * the suite asserts the failure when this is false.
      */
    protected def supportsDirectorySync: Boolean = true

    "durability" - {

        "durability suite replaces the target bytes" in {
            withFileSystem { (fileSystem, root) =>
                val path  = root / "durable.bin"
                val bytes = Span(1.toByte, 2.toByte, 3.toByte)
                fileSystem.write(path, "previous", Path.WriteOptions()).andThen {
                    Abort.run[FileSystemException](fileSystem.durableReplace(path, bytes)).map { result =>
                        fileSystem.readBytes(path).map { actual =>
                            assert(actual.is(bytes))
                            if supportsDirectorySync then assert(result == Result.unit)
                            else
                                result match
                                    case Result.Failure(FileAccessDeniedException(directory)) => assert(directory == root)
                                    case Result.Failure(FileIOException(directory, FileSystemOperation.SyncDirectory, _)) =>
                                        assert(directory == root)
                                    case other => assert(false, s"expected unsupported directory synchronization, got $other")
                            end if
                        }
                    }
                }
            }
        }

        "durability suite leaves no temporary beside the replaced target" in {
            withFileSystem { (fileSystem, root) =>
                val directory = root / "replaced"
                val path      = directory / "durable.bin"
                Abort.run[FileSystemException](fileSystem.durableReplace(path, Span(4.toByte))).andThen {
                    fileSystem.list(directory).map(entries => assert(entries == Chunk(path)))
                }
            }
        }

        "durability suite rejects a regular file as a directory to synchronize" in {
            withFileSystem { (fileSystem, root) =>
                val file = root / "file.txt"
                fileSystem.write(file, "value", Path.WriteOptions()).andThen {
                    Abort.run[FileWriteException](fileSystem.syncDirectory(file)).map {
                        case Result.Failure(FileNotADirectoryException(path)) => assert(path == file)
                        case other => assert(false, s"expected FileNotADirectoryException, got $other")
                    }
                }
            }
        }

        "durability suite reports a missing directory to synchronize" in {
            withFileSystem { (fileSystem, root) =>
                val missing = root / "missing"
                Abort.run[FileWriteException](fileSystem.syncDirectory(missing)).map {
                    case Result.Failure(FileNotFoundException(path)) => assert(path == missing)
                    case other                                       => assert(false, s"expected FileNotFoundException, got $other")
                }
            }
        }

        "durability suite rejects a path beneath a regular file as a directory to synchronize" in {
            withFileSystem { (fileSystem, root) =>
                val file = root / "file.txt"
                val path = file / "child"
                fileSystem.write(file, "value", Path.WriteOptions()).andThen {
                    Abort.run[FileWriteException](fileSystem.syncDirectory(path)).map {
                        case Result.Failure(FileNotADirectoryException(failed)) => assert(failed == path)
                        case other => assert(false, s"expected FileNotADirectoryException, got $other")
                    }
                }
            }
        }

        "durability suite cleans sibling temporaries at scope exit" in {
            withFileSystem { (fileSystem, root) =>
                val target = root / "target.bin"
                Scope.run {
                    Path.runWith(fileSystem)(target.siblingTemporary)
                }.map { temporary =>
                    fileSystem.exists(temporary).map(exists => assert(!exists && temporary.parent == target.parent))
                }
            }
        }
    }

end FileSystemDurabilityTest
