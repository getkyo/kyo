package kyo

/** Reusable contract for typed positioned channels on mutable backends. */
abstract class FileSystemChannelTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException])

    "channel suite supports positioned sparse writes and short reads" in {
        createFileSystem.map { (fileSystem, root) =>
            Scope.run {
                fileSystem.openReadWriteChannel(root / "positioned.bin", FileSystem.WriteOpen.Create).map { channel =>
                    channel.writeAt(3L, Span(7.toByte)).andThen {
                        channel.readAt(0L, 10).map(bytes => assert(bytes.is(Span(0.toByte, 0.toByte, 0.toByte, 7.toByte))))
                    }
                }
            }
        }
    }

    "channel suite enforces create-new atomically" in {
        createFileSystem.map { (fileSystem, root) =>
            val path = root / "create-new.bin"
            Scope.run(fileSystem.openWriteChannel(path, FileSystem.WriteOpen.Create)).andThen {
                Abort.run[FileSystemException](Scope.run(fileSystem.openWriteChannel(path, FileSystem.WriteOpen.CreateNew))).map {
                    case Result.Failure(_: FileAlreadyExistsException) => assert(true)
                    case other                                         => assert(false, s"expected FileAlreadyExistsException, got $other")
                }
            }
        }
    }

end FileSystemChannelTestSuite
