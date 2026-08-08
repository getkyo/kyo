package kyo

/** Reusable contract for explicit durable replacement workflows. */
abstract class FileSystemDurabilityTestSuite extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    protected def createFileSystem(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException])

    "durability suite replaces the target bytes" in {
        createFileSystem.map { (fileSystem, root) =>
            val path  = root / "durable.bin"
            val bytes = Span(1.toByte, 2.toByte, 3.toByte)
            fileSystem.durableReplace(path, bytes).andThen(fileSystem.readBytes(path)).map(actual => assert(actual.is(bytes)))
        }
    }

    "durability suite cleans sibling temporaries at scope exit" in {
        createFileSystem.map { (fileSystem, root) =>
            val target = root / "target.bin"
            Scope.run {
                Scope.acquireRelease(fileSystem.siblingTemporary(target))(temporary => Sync.Unsafe.defer(temporary.remove()))
            }.map { temporary =>
                fileSystem.exists(temporary.path).map(exists => assert(!exists && temporary.path.parent == target.parent))
            }
        }
    }

end FileSystemDurabilityTestSuite
