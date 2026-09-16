package kyo

/** The version-control behaviour of the filesystem: branching, committing, diffing and merging through ordinary `Path`
  * code. Filesystem correctness itself is settled by `DoltFileSystemConformanceTest`, so nothing here re-checks that a
  * write can be read back.
  *
  * Every leaf drives a single connection over `:memory:`, without which that name means one database per connection.
  */
class DoltFileSystemTest extends Test:

    private def withFiles[A](f: (DoltClient, DoltFileSystem) => A < (Async & Abort[SqlException | FileSystemException] & Scope & DB))(
        using Frame
    ): A < (Async & Abort[SqlException | FileSystemException]) =
        Scope.run {
            SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client) {
                    DoltClient.use(dolt => DoltFileSystem.init(dolt).map(files => f(dolt, files)))
                }
            }
        }

    private val options = Path.WriteOptions()

    "a file written through Path is a row the tree can commit" in {
        withFiles { (dolt, files) =>
            val conf = Path("etc", "app.conf")
            for
                _      <- files.write(conf, "mode = live", options)
                commit <- dolt.commit("add app.conf")
                log    <- dolt.log()
            yield
                assert(log.nonEmpty && log.head.hash == commit.hash, s"the commit is not at the head: $log")
                assert(commit.message == "add app.conf", s"got ${commit.message}")
            end for
        }
    }

    "a branch gives the same path two different contents" in {
        withFiles { (dolt, files) =>
            val conf = Path("etc", "app.conf")
            for
                _ <- files.write(conf, "mode = live", options)
                _ <- dolt.commit("baseline")
                _ <- dolt.createBranch("draft")
                onDraft <- dolt.onBranch("draft") {
                    files.write(conf, "mode = draft", options)
                        .andThen(dolt.commit("switch to draft"))
                        .andThen(files.read(conf))
                }
                onMain <- files.read(conf)
            yield
                assert(onDraft == "mode = draft", s"on the branch, got $onDraft")
                assert(onMain == "mode = live", s"back on main, got $onMain")
            end for
        }
    }

    "a diff reports which files a branch changed, and how" in {
        withFiles { (dolt, files) =>
            for
                _ <- files.write(Path("etc", "kept.conf"), "same", options)
                _ <- files.write(Path("etc", "edited.conf"), "before", options)
                _ <- dolt.commit("baseline")
                _ <- dolt.createBranch("work")
                _ <- dolt.onBranch("work") {
                    files.write(Path("etc", "edited.conf"), "after", options)
                        .andThen(files.write(Path("etc", "added.conf"), "new", options))
                        .andThen(dolt.commit("edit and add"))
                }
                changed <- dolt.diff(DoltRef.Branch("main"), DoltRef.Branch("work"), "vfs_node")
                // A diff row carries the changed row itself; `to_path` is absent only for a removal.
                landed <- Kyo.foreach(changed)(_.row.decode[Maybe[String]]("to_path"))
            yield
                val paths = landed.flatMap(p => p.fold(Chunk.empty[String])(Chunk(_)))
                assert(
                    paths.exists(_.contains("edited.conf")),
                    s"edited file missing from the diff: $paths"
                )
                assert(paths.exists(_.contains("added.conf")), s"added file missing from the diff: $paths")
                assert(!paths.exists(_.contains("kept.conf")), s"unchanged file present in the diff: $paths")
        }
    }

    "a merge brings another branch's files onto this one" in {
        withFiles { (dolt, files) =>
            val added = Path("etc", "from-branch.conf")
            for
                _      <- files.write(Path("etc", "base.conf"), "base", options)
                _      <- dolt.commit("baseline")
                _      <- dolt.createBranch("feature")
                _      <- dolt.onBranch("feature")(files.write(added, "feature value", options).andThen(dolt.commit("add a file")))
                before <- files.exists(added)
                _      <- dolt.merge(DoltRef.Branch("feature"))
                after  <- files.exists(added)
                value  <- files.read(added)
            yield
                assert(!before, "the branch's file was visible on main before the merge")
                assert(after, "the branch's file is missing from main after the merge")
                assert(value == "feature value", s"got $value")
            end for
        }
    }

    "a directory tree round-trips through a commit with its structure intact" in {
        withFiles { (dolt, files) =>
            for
                _       <- files.write(Path("src", "main", "a.txt"), "a", options)
                _       <- files.write(Path("src", "main", "b.txt"), "b", options)
                _       <- files.write(Path("src", "test", "c.txt"), "c", options)
                _       <- dolt.commit("a tree")
                listing <- files.list(Path("src"))
                mainDir <- files.list(Path("src", "main"))
                isDir   <- files.isDirectory(Path("src", "main"))
            yield
                assert(listing.map(_.toString).size == 2, s"expected two subdirectories, got $listing")
                assert(mainDir.size == 2, s"expected two files, got $mainDir")
                assert(isDir, "an intermediate directory did not report as one")
        }
    }

    "a large file is stored in blocks and reads back byte for byte" in {
        withFiles { (_, files) =>
            // Spans several blocks, so the assembly path and the block boundaries are exercised.
            val size    = DoltFileSystemSchema.BlockBytes * 2 + 1234
            val content = Span.fromUnsafe(Array.tabulate(size)(i => (i % 251).toByte))
            val path    = Path("data", "large.bin")
            for
                _      <- files.writeBytes(path, content, options)
                back   <- files.readBytes(path)
                tail   <- Scope.run(files.openReadChannel(path).map(_.readAt(size - 10L, 10)))
                length <- files.size(path)
            yield
                assert(length == size.toLong, s"recorded size is $length, wrote $size")
                assert(back.is(content), "content did not survive the block round trip")
                assert(tail.is(content.slice(size - 10, size)), "a positioned read at the end returned the wrong bytes")
            end for
        }
    }

    "an abandoned write handle leaves no entry behind" in {
        withFiles { (_, files) =>
            val path = Path("tmp", "abandoned.bin")
            for
                handle <- files.openWrite(path, append = false, options)
                _      <- Sync.Unsafe.defer(handle.close()) // closed without finish, so the partial entry is forfeit
                // The removal is queued rather than immediate, so this read is the operation that drains it.
                exists <- files.exists(path)
            yield assert(!exists, "a write handle closed without finishing left its entry behind")
            end for
        }
    }

    "the tree is queryable as ordinary SQL" in {
        withFiles { (dolt, files) =>
            for
                _    <- files.write(Path("etc", "a.conf"), "aaa", options)
                _    <- files.write(Path("etc", "b.conf"), "bbbbbb", options)
                rows <- dolt.query("SELECT path, size_bytes FROM vfs_node WHERE kind = 'file' ORDER BY path")
            yield assert(rows.size == 2, s"expected two files, got ${rows.size}")
        }
    }

end DoltFileSystemTest
