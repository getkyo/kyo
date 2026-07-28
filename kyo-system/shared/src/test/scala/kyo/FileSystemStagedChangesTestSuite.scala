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

    "staged suite isolates writes until commit" in {
        createFileSystem.map { (fileSystem, staged, root) =>
            val path = root / "staged.txt"
            fileSystem.write(path, "value", Path.WriteOptions()).andThen(staged.commit).andThen {
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
