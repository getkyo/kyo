package kyo

class OverlayFileSystemCaseTest extends kyo.test.Test[Any]:
    private class InsensitiveLower(base: FileSystem.Write[Sync]) extends FileSystem.Write[Sync]:
        export base.{defaultCaseSensitivity as _, *}
        override def defaultCaseSensitivity(using Frame): Glob.CaseSensitivity < Sync = Glob.CaseSensitivity.Insensitive

    private def withOverlay[A, S](
        use: (
            FileSystem.Write[Sync] & FileSystem.StagedChanges[Sync & Abort[FileSystemException]],
            FileSystem.Write[Sync]
        ) => A < (S & Sync & Abort[FileSystemException])
    )(using Frame): A < (S & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { lower =>
            FileSystem.overlay(new InsensitiveLower(lower)).map(overlay => use(overlay, lower))
        }

    "an absent probe does not choose a new file's spelling" in {
        withOverlay { (overlay, lower) =>
            for
                before    <- overlay.exists(Path("mixedcase.txt"))
                _         <- overlay.write(Path("MixedCase.txt"), "value", Path.WriteOptions())
                value     <- overlay.read(Path("MIXEDCASE.TXT"))
                listed    <- overlay.list(Path())
                _         <- overlay.commit
                stored    <- lower.read(Path("MixedCase.txt"))
                wrongName <- lower.exists(Path("mixedcase.txt"))
            yield
                assert(!before)
                assert(value == "value")
                assert(listed == Chunk(Path("MixedCase.txt")))
                assert(stored == "value")
                assert(!wrongName)
        }
    }

    "overwrites share a key and removal permits a new creation spelling" in {
        withOverlay { (overlay, _) =>
            for
                _           <- overlay.write(Path("First.txt"), "first", Path.WriteOptions())
                _           <- overlay.write(Path("FIRST.TXT"), "second", Path.WriteOptions())
                value       <- overlay.read(Path("first.txt"))
                overwritten <- overlay.list(Path())
                _           <- overlay.removeExisting(Path("first.txt"))
                _           <- overlay.write(Path("FIRST.txt"), "third", Path.WriteOptions())
                recreated   <- overlay.list(Path())
            yield
                assert(value == "second")
                assert(overwritten == Chunk(Path("First.txt")))
                assert(recreated == Chunk(Path("FIRST.txt")))
        }
    }

    "subtree copies share keys with previously probed aliases" in {
        withOverlay { (overlay, _) =>
            for
                before <- overlay.exists(Path("target/child.txt"))
                _      <- overlay.write(Path("Source/Child.txt"), "child", Path.WriteOptions())
                _      <- overlay.copy(Path("source"), Path("Target"), Path.CopyOptions())
                value  <- overlay.read(Path("TARGET/CHILD.TXT"))
                listed <- overlay.list(Path("Target"))
            yield
                assert(!before)
                assert(value == "child")
                assert(listed == Chunk(Path("Target/Child.txt")))
        }
    }

    Chunk(false, true).foreach { directory =>
        s"moving to an alias preserves contents and identity: directory=$directory" in {
            withOverlay { (overlay, _) =>
                val source = Path("Original")
                val file   = if directory then source / "Child.txt" else source
                for
                    _      <- overlay.write(file, "retained", Path.WriteOptions())
                    before <- overlay.stableIdentity(file)
                    _      <- overlay.move(source, Path("ORIGINAL"), Path.MoveOptions(replace = Path.Replace.Existing))
                    value  <- overlay.read(file)
                    after  <- overlay.stableIdentity(file)
                yield
                    assert(value == "retained")
                    assert(before.isDefined)
                    assert(after == before)
                end for
            }
        }
    }

    "whiteouts hide differently cased lower names in listings" in {
        withOverlay { (overlay, lower) =>
            for
                _       <- overlay.write(Path("entry.txt"), "staged", Path.WriteOptions())
                _       <- lower.write(Path("Entry.txt"), "lower", Path.WriteOptions())
                _       <- overlay.removeExisting(Path("ENTRY.TXT"))
                listed  <- overlay.list(Path())
                visible <- overlay.exists(Path("Entry.txt"))
            yield
                assert(listed.isEmpty)
                assert(!visible)
        }
    }
end OverlayFileSystemCaseTest
