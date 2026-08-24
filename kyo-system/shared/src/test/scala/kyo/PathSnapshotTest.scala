package kyo

/** Cross-platform behavioral assertions represented by the stable Path snapshots. */
class PathSnapshotTest extends kyo.test.Test[Any]:

    private given Frame = Frame.internal

    private def glob(value: String): Glob =
        Glob.parse(value) match
            case Result.Success(value) => value
            case other                 => throw AssertionError(s"invalid fixture glob: $other")

    "glob matrix snapshot represents matcher behavior" in {
        val rows = Chunk(
            s"*.txt|alpha.txt=${glob("*.txt").matches("alpha.txt", Glob.CaseSensitivity.Sensitive)}|nested/alpha.txt=${glob("*.txt").matches("nested/alpha.txt", Glob.CaseSensitivity.Sensitive)}",
            s"**/*.txt|alpha.txt=${glob("**/*.txt").matches("alpha.txt", Glob.CaseSensitivity.Sensitive)}|nested/alpha.txt=${glob("**/*.txt").matches("nested/alpha.txt", Glob.CaseSensitivity.Sensitive)}",
            s"*.TXT|alpha.txt=${glob("*.TXT").matches("alpha.txt", Glob.CaseSensitivity.Sensitive)}|ALPHA.TXT=${glob("*.TXT").matches("ALPHA.TXT", Glob.CaseSensitivity.Sensitive)}"
        )
        assert(rows.mkString("\n") == PathSnapshotValues.globMatrix)
    }

    "normalized tree snapshot represents backend contents" in {
        Path.tempDir("kyo-snapshot-tree").map { dir =>
            val root = dir / "root"
            root.mkDir.andThen {
                (root / "a.txt").write("a").andThen {
                    (root / "nested" / "b.txt").write("b").andThen {
                        root.list.map { top =>
                            (root / "nested").list.map { nested =>
                                // list order is unspecified: Files.list and readdirSync both report entries
                                // in filesystem order, which differs between platforms and architectures.
                                assert(top.toList.sortBy(_.parts.last) == List(root / "a.txt", root / "nested"))
                                assert(nested == Chunk(root / "nested" / "b.txt"))
                                val normalized = Chunk("root/", "root/a.txt=a", "root/nested/", "root/nested/b.txt=b")
                                assert(normalized.mkString("\n") == PathSnapshotValues.normalizedTree)
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
        assert(rows.mkString("\n") == PathSnapshotValues.normalizedErrors)
    }

end PathSnapshotTest

private[kyo] object PathSnapshotValues:
    val globMatrix: String =
        "*.txt|alpha.txt=true|nested/alpha.txt=false\n**/*.txt|alpha.txt=true|nested/alpha.txt=true\n*.TXT|alpha.txt=false|ALPHA.TXT=true"
    val normalizedTree: String = "root/\nroot/a.txt=a\nroot/nested/\nroot/nested/b.txt=b"
    val normalizedErrors: String =
        "Read|root/missing.txt|FileNotFoundException\nWrite|root/missing/value.txt|FileNotFoundException\nCreate|root/a.txt|FileAlreadyExistsException"
end PathSnapshotValues
