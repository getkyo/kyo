package kyo

/** Reusable contract for explicit staged-change lifecycle implementations. */
abstract class FileSystemStagedChangesTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def createFileSystem(using
        Frame
    ): (
        FileSystem.Write[Sync],
        FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
        Path
    ) < (Sync & Scope & Abort[FileSystemException])

    private[kyo] def supportsCommit: Boolean = true

    "staged suite isolates writes until commit" in {
        createFileSystem.map { (fileSystem, staged, root) =>
            val path = root / "staged.txt"
            fileSystem.write(path, "value", Path.WriteOptions()).andThen {
                if supportsCommit then staged.commit
                else
                    Abort.run[FileSystemException](staged.commit).map { result =>
                        FileSystem.host.realPath(root).map { parent =>
                            assert(OverlayFileSystemWindowsTest.directoryFailure(result, parent), s"Directory barrier result: $result")
                            FileSystem.host.exists(path).map(exists => assert(!exists))
                        }
                    }
            }.andThen {
                fileSystem.read(path).map(value => assert(value == "value"))
            }
        }
    }

    "staged suite is one-shot" in {
        createFileSystem.map { (_, staged, _) =>
            staged.discard.andThen {
                Abort.run[CommitConflict](staged.commit).map {
                    case Result.Failure(_: FileSystem.StagedChanges.AlreadyTerminated) => assert(true)
                    case other => assert(false, s"expected terminal staged changes, got $other")
                }
            }
        }
    }

end FileSystemStagedChangesTestSuite
