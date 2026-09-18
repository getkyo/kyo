package kyo

/** Reusable contract for explicit durable replacement workflows. */
abstract class FileSystemDurabilityTest extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    private[kyo] def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException])

    private[kyo] def supportsDirectorySync: Boolean = true

    "durability suite replaces the target bytes" in {
        createFileSystem.map { (fileSystem, root) =>
            val path  = root / "durable.bin"
            val bytes = Span(1.toByte, 2.toByte, 3.toByte)
            Abort.run[FileSystemException](fileSystem.durableReplace(path, bytes)).map { result =>
                fileSystem.readBytes(path).map { actual =>
                    assert(actual.is(bytes))
                    if supportsDirectorySync then assert(result == Result.unit)
                    else
                        result match
                            case Result.Failure(FileAccessDeniedException(directory)) => assert(directory == root)
                            case Result.Failure(FileIOException(directory, FileSystemOperation.SyncDirectory, _)) =>
                                assert(directory == root)
                            case other => fail(s"Expected unsupported directory synchronization, got $other")
                    end if
                }
            }
        }
    }

    "durability suite cleans sibling temporaries at scope exit" in {
        createFileSystem.map { (fileSystem, root) =>
            val target = root / "target.bin"
            Scope.run {
                Path.runWith(fileSystem)(target.siblingTemporary)
            }.map { temporary =>
                fileSystem.exists(temporary).map(exists => assert(!exists && temporary.parent == target.parent))
            }
        }
    }

end FileSystemDurabilityTest
