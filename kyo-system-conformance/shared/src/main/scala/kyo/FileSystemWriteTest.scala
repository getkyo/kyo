package kyo

/** The write contract of a mutable filesystem backend, one of the suites [[FileSystemWriteConformanceTest]] mixes in.
  *
  * `S >: Async` bounds the backend effect as described on [[FileSystemReadConformanceTest]].
  */
trait FileSystemWriteTest[S >: Async] extends kyo.test.Test[Any]:

    /** Runs one assertion against a fresh backend and a root to work under, shaped as a continuation for the reason
      * given on [[FileSystemReadConformanceTest.withFileSystem]].
      */
    protected def withFileSystem[A](
        use: (FileSystem.Write[S], Path) => A < (S & Async & Scope & Abort[FileSystemException])
    )(using Frame): A < (Async & Scope & Abort[FileSystemException])

    "write" - {

        "write suite round-trips a concrete value" in {
            withFileSystem { (fileSystem, root) =>
                val file = root / "value.txt"
                fileSystem.write(file, "value", Path.WriteOptions()).andThen(fileSystem.read(file)).map(value => assert(value == "value"))
            }
        }

        "write suite reports missing parents" in {
            withFileSystem { (fileSystem, root) =>
                val file = root / "missing" / "value.txt"
                Abort.run[FileWriteException](fileSystem.write(file, "value", Path.WriteOptions(createFolders = false))).map {
                    case Result.Failure(_: FileNotFoundException) => assert(true)
                    case other                                    => assert(false, s"expected FileNotFoundException, got $other")
                }
            }
        }

        "write suite refuses to create an entry beneath a regular file and keeps the file" in {
            withFileSystem { (fileSystem, root) =>
                val file = root / "file.txt"
                fileSystem.write(file, "value", Path.WriteOptions()).andThen {
                    Abort.run[FileWriteException](fileSystem.write(file / "child.txt", "child", Path.WriteOptions())).map { result =>
                        // The host reports this as an I/O failure naming the entry, since the precise type is not in a write's row.
                        assert(result.isFailure, s"a write beneath a regular file reported $result")
                        fileSystem.read(file).map(value => assert(value == "value"))
                    }
                }
            }
        }

        "write suite names the existing target a non-replacing move or copy refuses" in {
            withFileSystem { (fileSystem, root) =>
                val source = root / "source.txt"
                val target = root / "target.txt"
                fileSystem.write(
                    source,
                    "source",
                    Path.WriteOptions()
                ).andThen(fileSystem.write(target, "target", Path.WriteOptions())).andThen {
                    Abort.run[FileSystemException](fileSystem.move(source, target, Path.MoveOptions())).map { moved =>
                        Abort.run[FileSystemException](fileSystem.copy(source, target, Path.CopyOptions())).map { copied =>
                            assert(moved == Result.fail(FileAlreadyExistsException(target)), s"move result: $moved")
                            assert(copied == Result.fail(FileAlreadyExistsException(target)), s"copy result: $copied")
                            fileSystem.read(target).map(value => assert(value == "target"))
                        }
                    }
                }
            }
        }

        "write suite replaces an empty directory with a replacing move or copy" in {
            withFileSystem { (fileSystem, root) =>
                val source = root / "source.txt"
                val moved  = root / "moved"
                val copied = root / "copied"
                for
                    _          <- fileSystem.write(source, "source", Path.WriteOptions())
                    _          <- fileSystem.mkDir(moved)
                    _          <- fileSystem.mkDir(copied)
                    _          <- fileSystem.copy(source, copied, Path.CopyOptions(replace = Path.Replace.Existing))
                    _          <- fileSystem.move(source, moved, Path.MoveOptions(replace = Path.Replace.Existing))
                    copyValue  <- fileSystem.read(copied)
                    moveValue  <- fileSystem.read(moved)
                    sourceLeft <- fileSystem.exists(source)
                yield
                    assert(copyValue == "source")
                    assert(moveValue == "source")
                    assert(!sourceLeft)
                end for
            }
        }

        "write suite refuses a replacing move onto a non-empty directory and keeps it" in {
            withFileSystem { (fileSystem, root) =>
                val source    = root / "source.txt"
                val directory = root / "directory"
                val child     = directory / "child.txt"
                fileSystem.write(
                    source,
                    "source",
                    Path.WriteOptions()
                ).andThen(fileSystem.write(child, "child", Path.WriteOptions())).andThen {
                    Abort.run[FileSystemException](fileSystem.move(
                        source,
                        directory,
                        Path.MoveOptions(replace = Path.Replace.Existing)
                    )).map {
                        result =>
                            assert(result == Result.fail(FileDirectoryNotEmptyException(directory)), s"move result: $result")
                            fileSystem.read(child).map(value => assert(value == "child"))
                    }
                }
            }
        }

        "write suite refuses a replacing copy onto a non-empty directory and keeps it" in {
            withFileSystem { (fileSystem, root) =>
                val source    = root / "source.txt"
                val directory = root / "directory"
                val child     = directory / "child.txt"
                fileSystem.write(
                    source,
                    "source",
                    Path.WriteOptions()
                ).andThen(fileSystem.write(child, "child", Path.WriteOptions())).andThen {
                    Abort.run[FileSystemException](fileSystem.copy(
                        source,
                        directory,
                        Path.CopyOptions(replace = Path.Replace.Existing)
                    )).map {
                        result =>
                            assert(result == Result.fail(FileDirectoryNotEmptyException(directory)), s"copy result: $result")
                            fileSystem.read(child).map(value => assert(value == "child"))
                    }
                }
            }
        }

        "write suite never shows a required atomic move half done" in {
            withFileSystem { (fileSystem, root) =>
                val target  = root / "atomic.txt"
                val options = Path.MoveOptions(replace = Path.Replace.Existing, atomicity = Path.Atomicity.Required)
                // A reader racing each move must see the old content or the new, never an absent target. Repeated, since a backend
                // that clears the target and then places the source leaves a window only some interleavings reach.
                fileSystem.write(target, "0", Path.WriteOptions()).andThen {
                    Kyo.foreachDiscard(Chunk.from(1 to 20)) { round =>
                        val source = root / s"source-$round.txt"
                        fileSystem.write(source, round.toString, Path.WriteOptions()).andThen {
                            for
                                moved  <- AtomicBoolean.init(false)
                                reader <- Fiber.initUnscoped {
                                    Loop(Chunk.empty[Result[FileReadException, String]]) { seen =>
                                        moved.get.map { done =>
                                            Abort.run[FileReadException](fileSystem.read(target)).map { read =>
                                                if done then Loop.done(seen.append(read)) else Loop.continue(seen.append(read))
                                            }
                                        }
                                    }
                                }
                                _     <- fileSystem.move(source, target, options)
                                _     <- moved.set(true)
                                reads <- reader.get
                            yield
                                val allowed = Set((round - 1).toString, round.toString)
                                assert(
                                    reads.forall(read => read.toMaybe.exists(allowed.contains)),
                                    s"round $round observed ${reads.filterNot(read => read.toMaybe.exists(allowed.contains))}"
                                )
                            end for
                        }
                    }
                }
            }
        }

        "write suite preserves concurrent sibling writes" in {
            withFileSystem { (fileSystem, root) =>
                val first  = root / "first.txt"
                val second = root / "second.txt"
                for
                    gate  <- Latch.init(1)
                    left  <- Fiber.initUnscoped(gate.await.andThen(fileSystem.write(first, "left", Path.WriteOptions())))
                    right <-
                        Fiber.initUnscoped(gate.await.andThen(fileSystem.write(second, "right", Path.WriteOptions())))
                    _      <- gate.release
                    _      <- left.get
                    _      <- right.get
                    values <- Async.zip(fileSystem.read(first), fileSystem.read(second))
                yield assert(values == ("left", "right"))
                end for
            }
        }

        "write suite gives distinct temporary directories for one prefix" in {
            withFileSystem { (fileSystem, _) =>
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

        "write suite agrees with its declared case sensitivity" in {
            withFileSystem { (fileSystem, root) =>
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
            withFileSystem { (fileSystem, root) =>
                Scope.run(fileSystem.openWriteChannel(root / "scoped.bin", FileSystem.WriteOpen.Create)).map { channel =>
                    Abort.run[FileWriteException](channel.writeAt(0L, Span(1.toByte))).map(result => assert(result.isFailure))
                }
            }
        }
    }

end FileSystemWriteTest
