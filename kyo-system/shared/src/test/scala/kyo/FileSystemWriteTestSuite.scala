package kyo

/** Reusable behavioral contract for mutable filesystem backends. */
abstract class FileSystemWriteTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException])

    "write suite round-trips a concrete value" in {
        createFileSystem.map { (fileSystem, root) =>
            val file = root / "value.txt"
            fileSystem.write(file, "value", Path.WriteOptions()).andThen(fileSystem.read(file)).map(value => assert(value == "value"))
        }
    }

    "write suite reports missing parents" in {
        createFileSystem.map { (fileSystem, root) =>
            val file = root / "missing" / "value.txt"
            Abort.run[FileWriteException](fileSystem.write(file, "value", Path.WriteOptions(createFolders = false))).map {
                case Result.Failure(_: FileNotFoundException) => assert(true)
                case other                                    => assert(false, s"expected FileNotFoundException, got $other")
            }
        }
    }

    "write suite preserves concurrent sibling writes" in {
        createFileSystem.map { (fileSystem, root) =>
            val first  = root / "first.txt"
            val second = root / "second.txt"
            for
                gate   <- Latch.init(1)
                left   <- Fiber.initUnscoped(gate.await.andThen(fileSystem.write(first, "left", Path.WriteOptions())))
                right  <- Fiber.initUnscoped(gate.await.andThen(fileSystem.write(second, "right", Path.WriteOptions())))
                _      <- gate.release
                _      <- left.get
                _      <- right.get
                values <- Async.zip(fileSystem.read(first), fileSystem.read(second))
            yield assert(values == ("left", "right"))
            end for
        }
    }

    "write suite releases scoped channels" in {
        createFileSystem.map { (fileSystem, root) =>
            Scope.run(fileSystem.openWriteChannel(root / "scoped.bin", FileSystem.WriteOpen.Create)).map { channel =>
                Abort.run[FileWriteException](channel.writeAt(0L, Span(1.toByte))).map(result => assert(result.isFailure))
            }
        }
    }

end FileSystemWriteTestSuite
