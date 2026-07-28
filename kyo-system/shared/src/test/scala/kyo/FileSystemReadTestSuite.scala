package kyo

/** Reusable behavioral contract for readable filesystem backends.
  *
  * Implementations provide a fresh backend, a populated file, and its expected UTF-8 value for
  * each assertion.
  */
abstract class FileSystemReadTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Read[Sync], Path, String) < (Sync & Scope & Abort[FileSystemException])

    "read suite returns the concrete stored value" in {
        createFileSystem.map { (fileSystem, file, expected) =>
            fileSystem.read(file).map(value => assert(value == expected))
        }
    }

    "read suite reports a precise missing-file failure" in {
        createFileSystem.map { (fileSystem, file, _) =>
            Abort.run[FileReadException](fileSystem.read(file.parent.getOrElse(Path()) / "missing-file")).map {
                case Result.Failure(_: FileNotFoundException) => assert(true)
                case other                                    => assert(false, s"expected FileNotFoundException, got $other")
            }
        }
    }

    "read suite supports deterministic concurrent reads" in {
        createFileSystem.map { (fileSystem, file, expected) =>
            Async.zip(fileSystem.read(file), fileSystem.read(file)).map(values => assert(values == (expected, expected)))
        }
    }

    "read suite releases scoped channels" in {
        createFileSystem.map { (fileSystem, file, _) =>
            Scope.run(fileSystem.openReadChannel(file)).map { channel =>
                Abort.run[FileReadException](channel.readAt(0L, 1)).map(result => assert(result.isFailure))
            }
        }
    }

end FileSystemReadTestSuite
