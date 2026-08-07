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

    "write suite gives distinct temporary directories for one prefix" in {
        createFileSystem.map { (fileSystem, _) =>
            // Two calls with the same prefix, which is the ordinary case: Path.tempDir defaults its
            // prefix to a literal, and literals are interned, so both calls receive the identical
            // String instance. A name derived from that instance is the same name twice, and the
            // first handle's removal then destroys the second caller's directory.
            Scope.run {
                fileSystem.tempDir("conformance-temp").map { first =>
                    fileSystem.tempDir("conformance-temp").map { second =>
                        assert(first.path != second.path, s"both calls returned ${first.path}")
                    }
                }
            }
        }
    }

    /** Declares whether the backend's own view honours the case policy it reports.
      *
      * A staged-write layer keys its pending entries by exact segments while reporting the policy of
      * the volume beneath it. On a case-insensitive volume those disagree: a staged write is found
      * under the name it was written with and not under an equivalent one. Declared per fixture so
      * the gap is stated rather than silently untested.
      */
    protected def caseSensitivityCoversPendingWrites: Boolean = true

    "write suite agrees with its declared case sensitivity" in {
        if !caseSensitivityCoversPendingWrites then succeed
        else
            createFileSystem.map { (fileSystem, root) =>
                // Asserted against observed behaviour rather than against the declared value, which is
                // what makes this two-sided: a backend that reports the wrong policy fails here no
                // matter which value it reports. Checking a policy by branching on that same policy is
                // satisfied by any self-consistent answer, including a wrong one.
                val file = root / "CaseProbe.txt"
                fileSystem.write(file, "probe", Path.WriteOptions()).andThen {
                    fileSystem.defaultCaseSensitivity.map { declared =>
                        fileSystem.exists(root / "caseprobe.txt").map { lowerFound =>
                            declared match
                                case Glob.CaseSensitivity.Insensitive =>
                                    assert(lowerFound, "declared case-insensitive, but a differently-cased name was not found")
                                case Glob.CaseSensitivity.Sensitive =>
                                    assert(!lowerFound, "declared case-sensitive, but a differently-cased name resolved to the same file")
                        }
                    }
                }
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
