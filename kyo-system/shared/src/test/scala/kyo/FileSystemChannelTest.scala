package kyo

/** Reusable contract for typed positioned channels on mutable backends.
  *
  * `S >: Async` bounds the backend effect as described on [[FileSystemReadTest]].
  */
abstract class FileSystemChannelTest[S >: Async] extends kyo.test.Test[Any]:

    /** Runs one assertion against a fresh backend and a root to work under, shaped as a continuation for the reason
      * given on [[FileSystemReadTest.withFileSystem]].
      */
    protected def withFileSystem[A](
        use: (FileSystem.Write[S], Path) => A < (S & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException])

    "channel suite supports positioned sparse writes and short reads" in {
        withFileSystem { (fileSystem, root) =>
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
        withFileSystem { (fileSystem, root) =>
            val path = root / "create-new.bin"
            Scope.run(fileSystem.openWriteChannel(path, FileSystem.WriteOpen.Create)).andThen {
                Abort.run[FileSystemException](Scope.run(fileSystem.openWriteChannel(path, FileSystem.WriteOpen.CreateNew))).map {
                    case Result.Failure(_: FileAlreadyExistsException) => assert(true)
                    case other                                         => assert(false, s"expected FileAlreadyExistsException, got $other")
                }
            }
        }
    }

end FileSystemChannelTest
