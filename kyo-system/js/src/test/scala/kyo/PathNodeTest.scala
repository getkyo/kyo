package kyo

import kyo.internal.NodeFs

class PathNodeTest extends kyo.test.Test[Any]:

    "Node copy honors followLinks for symbolic links" in {
        Scope.run {
            for
                dir <- Path.tempDir("kyo-node-copy-links")
                source   = dir / "source.txt"
                link     = dir / "source-link"
                noFollow = dir / "no-follow-link"
                follow   = dir / "followed.txt"
                _              <- source.write("content")
                _              <- Sync.Unsafe.defer(NodeFs.symlinkSync(source.unsafe.show, link.unsafe.show))
                _              <- link.copy(noFollow, Path.CopyOptions(followLinks = false))
                _              <- link.copy(follow, Path.CopyOptions(followLinks = true))
                noFollowIsLink <- noFollow.isSymbolicLink
                followIsLink   <- follow.isSymbolicLink
                followedValue  <- follow.read
            yield
                assert(noFollowIsLink)
                assert(!followIsLink)
                assert(followedValue == "content")
        }
    }

    "Node setLastModified round-trips a millisecond exactly" in {
        // Node's utimesSync takes seconds. Dividing milliseconds by 1000.0 puts the value through a
        // double that cannot represent every millisecond, so the timestamp that reaches the
        // filesystem is already wrong: 987654 ms becomes 987.6539999999999850 s, and reading it back
        // gives 987653. Every value below round-trips exactly in whole seconds, so the failure is the
        // conversion rather than the filesystem's resolution.
        Scope.run {
            val values = Chunk(987654L, 1234567L, 1_000_000_000_123L)
            Path.tempDir("kyo-node-mtime").map { dir =>
                Kyo.foreach(values.zipWithIndex) { (target, i) =>
                    val file = dir / s"mtime-$i.bin"
                    file.writeBytes(Span.from(Array[Byte](0x01))).andThen {
                        file.setLastModified(target).andThen {
                            file.stat.map(st => assert(st.lastModifiedMs == target, s"set $target, read back ${st.lastModifiedMs}"))
                        }
                    }
                }.unit
            }
        }
    }

    "Node copyAttributes controls copied modification time" in {
        Scope.run {
            val sourceMtime = 1234567L
            for
                dir <- Path.tempDir("kyo-node-copy-stat")
                source    = dir / "source.txt"
                fresh     = dir / "fresh.txt"
                preserved = dir / "preserved.txt"
                _             <- source.write("content")
                _             <- source.setLastModified(sourceMtime)
                _             <- source.copy(fresh, Path.CopyOptions(copyAttributes = false))
                _             <- source.copy(preserved, Path.CopyOptions(copyAttributes = true))
                sourceStat    <- source.stat
                freshStat     <- fresh.stat
                preservedStat <- preserved.stat
            yield
                assert(sourceStat.lastModifiedMs == sourceMtime)
                assert(freshStat.lastModifiedMs != sourceMtime)
                assert(preservedStat.lastModifiedMs == sourceMtime)
            end for
        }
    }

    "Node directory copy enforces replacement policy for an existing target" in {
        Scope.run {
            for
                dir <- Path.tempDir("kyo-node-copy-directory")
                source = dir / "source"
                target = dir / "target"
                _     <- source.mkDir
                _     <- target.mkDir
                never <- Abort.run[FileSystemException](source.copy(target, Path.CopyOptions(replace = Path.Replace.Never)))
                _     <- source.copy(target, Path.CopyOptions(replace = Path.Replace.Existing))
            yield assert(never.failure.exists(_.isInstanceOf[FileAlreadyExistsException]))
        }
    }
end PathNodeTest
