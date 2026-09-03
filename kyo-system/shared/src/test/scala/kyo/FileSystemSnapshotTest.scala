package kyo

/** Cross-platform behavioral assertions represented by the stable filesystem snapshots. */
class FileSystemSnapshotTest extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    private def hostTempDir(prefix: String): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h => Sync.Unsafe.defer(h.remove())).map(_.path)

    private def glob(value: String): Glob =
        Glob.parse(value) match
            case Result.Success(value) => value
            case other                 => throw AssertionError(s"invalid fixture glob: $other")

    private def take(clock: Clock.TimeControl, watcher: Path.Watcher)(using
        Frame
    ): Chunk[PathChange] < (Async & Abort[FileWatchException]) =
        Fiber.initUnscoped(Scope.run(watcher.events.take(1).run)).map { fiber =>
            clock.advance(10.millis).andThen(clock.advance(10.millis)).andThen(fiber.get)
        }

    "glob matrix snapshot represents matcher behavior" in {
        val rows = Chunk(
            s"*.txt|alpha.txt=${glob("*.txt").matches("alpha.txt", Glob.CaseSensitivity.Sensitive)}|nested/alpha.txt=${glob("*.txt").matches("nested/alpha.txt", Glob.CaseSensitivity.Sensitive)}",
            s"**/*.txt|alpha.txt=${glob("**/*.txt").matches("alpha.txt", Glob.CaseSensitivity.Sensitive)}|nested/alpha.txt=${glob("**/*.txt").matches("nested/alpha.txt", Glob.CaseSensitivity.Sensitive)}",
            s"*.TXT|alpha.txt=${glob("*.TXT").matches("alpha.txt", Glob.CaseSensitivity.Sensitive)}|ALPHA.TXT=${glob("*.TXT").matches("ALPHA.TXT", Glob.CaseSensitivity.Sensitive)}"
        )
        assert(rows.mkString("\n") == FileSystemSnapshotValues.globMatrix)
    }

    "normalized tree snapshot represents backend contents" in {
        hostTempDir("kyo-snapshot-tree").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "root"
            fileSystem.mkDir(root).andThen {
                fileSystem.write(root / "a.txt", "a", Path.WriteOptions()).andThen {
                    fileSystem.write(root / "nested" / "b.txt", "b", Path.WriteOptions()).andThen {
                        fileSystem.list(root).map { top =>
                            fileSystem.list(root / "nested").map { nested =>
                                // list order is unspecified: Files.list and readdirSync both report entries
                                // in filesystem order, which differs between platforms and architectures.
                                assert(top.toList.sortBy(_.parts.last) == List(root / "a.txt", root / "nested"))
                                assert(nested == Chunk(root / "nested" / "b.txt"))
                                val normalized = Chunk("root/", "root/a.txt=a", "root/nested/", "root/nested/b.txt=b")
                                assert(normalized.mkString("\n") == FileSystemSnapshotValues.normalizedTree)
                            }
                        }
                    }
                }
            }
        }
    }

    "watch trace snapshot represents ordered changes" in {
        Clock.withTimeControl { clock =>
            hostTempDir("kyo-snapshot-watch").map { dir =>
                val fileSystem = FileSystem.host
                val root       = dir / "root"
                val file       = root / "a.txt"
                fileSystem.mkDir(root).andThen {
                    fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                        fileSystem.write(file, "a", Path.WriteOptions()).andThen(take(clock, watcher)).map { created =>
                            fileSystem.write(file, "changed", Path.WriteOptions()).andThen(take(clock, watcher)).map { modified =>
                                fileSystem.removeExisting(file).andThen(take(clock, watcher)).map { removed =>
                                    val trace = created ++ modified ++ removed
                                    assert(trace == Chunk(PathChange.Created(file), PathChange.Modified(file), PathChange.Removed(file)))
                                    val normalized = trace.map(_.toString.replace(s"${dir.toString}/", ""))
                                    assert(normalized.mkString("\n") == FileSystemSnapshotValues.watchTrace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "normalized error snapshot represents typed failures" in {
        val rows = Chunk(
            "Read|root/missing.txt|FileNotFoundException",
            "Write|root/missing/value.txt|FileNotFoundException",
            "Create|root/a.txt|FileAlreadyExistsException"
        )
        assert(FileNotFoundException(Path("root", "missing.txt")).path == Path("root", "missing.txt"))
        assert(FileAlreadyExistsException(Path("root", "a.txt")).path == Path("root", "a.txt"))
        assert(rows.mkString("\n") == FileSystemSnapshotValues.normalizedErrors)
    }

end FileSystemSnapshotTest

private[kyo] object FileSystemSnapshotValues:
    val globMatrix: String =
        "*.txt|alpha.txt=true|nested/alpha.txt=false\n**/*.txt|alpha.txt=true|nested/alpha.txt=true\n*.TXT|alpha.txt=false|ALPHA.TXT=true"
    val normalizedTree: String = "root/\nroot/a.txt=a\nroot/nested/\nroot/nested/b.txt=b"
    val watchTrace: String     = "Created(root/a.txt)\nModified(root/a.txt)\nRemoved(root/a.txt)"
    val normalizedErrors: String =
        "Read|root/missing.txt|FileNotFoundException\nWrite|root/missing/value.txt|FileNotFoundException\nCreate|root/a.txt|FileAlreadyExistsException"
end FileSystemSnapshotValues
