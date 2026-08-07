package kyo

import scala.compiletime.testing.typeCheckErrors

class PathWatchTest extends FileSystemWatchTestSuite:

    /** Counts the scans the polling watcher performs, so its pacing can be asserted rather than
      * assumed.
      */
    final private class CountingRead(
        delegate: FileSystem.Write[Sync],
        scans: AtomicInt
    ) extends FileSystem.Read[Sync], FileSystem.Watch[Sync]:
        export delegate.{list as _, *}

        override def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
            scans.incrementAndGet.andThen(delegate.list(path))

        def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
            Frame
        ): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
            delegate.list(path, glob, caseSensitivity)

        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
            PathWatch.polling(this, path, options)
    end CountingRead

    final private class FaultRead(
        delegate: FileSystem.Write[Sync],
        failList: AtomicBoolean,
        rootIsDirectory: AtomicBoolean
    ) extends FileSystem.Read[Sync], FileSystem.Watch[Sync]:
        export delegate.{isDirectory as _, list as _, *}

        override def isDirectory(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            rootIsDirectory.get.map {
                case false => false
                case true  => delegate.isDirectory(path)
            }

        override def list(path: Path)(using Frame): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
            failList.get.map {
                case false => delegate.list(path)
                case true  => Abort.fail(FileIOException(path, FileSystemOperation.List, new java.io.IOException("watch list failed")))
            }

        def list(path: Path, glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using
            Frame
        ): Chunk[Path] < (Sync & Abort[FileReadException | FileStructureException]) =
            delegate.list(path, glob, caseSensitivity)

        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
            PathWatch.polling(this, path, options)
    end FaultRead

    final private class RecoveryFaultRead(
        delegate: FileSystem.Write[Sync],
        child: Path,
        failObservation: AtomicBoolean,
        panicCheck: java.util.concurrent.atomic.AtomicBoolean,
        failCheck: AtomicBoolean,
        panic: Throwable
    ) extends FileSystem.Read[Sync], FileSystem.Watch[Sync]:
        export delegate.{exists as _, stat as _, *}

        override def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
            if panicCheck.get() && path == child then
                Abort.fail(FileIOException(path, FileSystemOperation.Inspect, new java.io.IOException("observation failed")))
            else
                failObservation.get.map {
                    case true if path == child =>
                        Abort.fail(FileIOException(path, FileSystemOperation.Inspect, new java.io.IOException("observation failed")))
                    case _ => delegate.stat(path)
                }

        override def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            exists(path, false)

        override def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) =
            if path != child then delegate.exists(path, followLinks)
            else
                if panicCheck.get() then
                    Abort.panic[FileReadException](panic)
                else
                    failCheck.get.map {
                        case true  => Abort.fail(FileIOException(path, FileSystemOperation.Exists, new java.io.IOException("check failed")))
                        case false => delegate.exists(path, followLinks)
                    }

        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
            PathWatch.polling(this, path, options)
    end RecoveryFaultRead

    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch[Sync], Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("watch-suite-root")
            fileSystem.mkDir(root).andThen(use(fileSystem, root))
        }

    private def glob(value: String): Glob =
        Glob.parse(value) match
            case Result.Success(glob) => glob
            case Result.Failure(error) =>
                throw new AssertionError(s"invalid test glob at ${error.offset}: ${error.reason}")
            case Result.Panic(error) => throw error

    "in-memory watcher emits Created after registration" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("watched")
            val file = root / "created.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- root.mkDir
                            watcher <- root.openWatcher()
                            _       <- file.write("created")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Created(file)))
                    }
                }
            }
        }
    }

    "mutation during an initial walk is queued by the already-open watcher" in {
        FileSystem.inMemory.map { fileSystem =>
            val root    = Path("walk-race-root")
            val created = root / "during-walk.txt"
            Latch.init(1).map { walkStarted =>
                Latch.init(1).map { finishWalk =>
                    Scope.run {
                        Path.runWatchWith(fileSystem) {
                            Path.runWith(fileSystem) {
                                for
                                    _       <- (root / "seed.txt").write("seed")
                                    watcher <- root.openWatcher()
                                    walk <- Fiber.initUnscoped(
                                        Scope.run(
                                            Path.runReadOnlyWith(fileSystem)(
                                                root.walk
                                                    .map(path => walkStarted.release.andThen(finishWalk.await).map(_ => path))
                                                    .run
                                            )
                                        )
                                    )
                                    _      <- walkStarted.await
                                    _      <- created.write("created")
                                    _      <- finishWalk.release
                                    _      <- walk.get
                                    events <- watcher.events.take(1).run
                                yield assert(events == Chunk(PathChange.Created(created)))
                            }
                        }
                    }
                }
            }
        }
    }

    "in-memory watcher emits Modified for an existing file" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("modified-root")
            val file = root / "modified.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- file.write("before")
                            watcher <- root.openWatcher()
                            _       <- file.write("after")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Modified(file)))
                    }
                }
            }
        }
    }

    "in-memory watcher emits Removed for a removed child" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("removed-root")
            val file = root / "removed.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- file.write("before")
                            watcher <- root.openWatcher()
                            _       <- file.removeExisting
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Removed(file)))
                    }
                }
            }
        }
    }

    "in-memory watcher emits Moved for a rename within the selected view" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("moved-root")
            val from = root / "from.txt"
            val to   = root / "to.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- from.write("before")
                            watcher <- root.openWatcher()
                            _       <- from.move(to)
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Moved(from, to)))
                    }
                }
            }
        }
    }

    "in-memory watcher emits Invalidated when its root is removed" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("invalidated-root")
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- root.mkDir
                            watcher <- root.openWatcher()
                            _       <- root.removeAll
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Invalidated(root)))
                    }
                }
            }
        }
    }

    "in-memory invalidation is terminal and cannot resume after root recreation" in {
        FileSystem.inMemory.map { fileSystem =>
            val root  = Path("terminal-in-memory-root")
            val later = root / "later.txt"
            Scope.run {
                fileSystem.mkDir(root).andThen {
                    fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                        fileSystem.removeAll(root).andThen {
                            fileSystem.mkDir(root).andThen {
                                fileSystem.write(later, "later", Path.WriteOptions()).andThen {
                                    watcher.events.run.map(events => assert(events == Chunk(PathChange.Invalidated(root))))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "in-memory watcher does not invent moves for equal files or directories" in {
        FileSystem.inMemory.map { fileSystem =>
            val root    = Path("identity-root")
            val oldFile = root / "old.txt"
            val newFile = root / "new.txt"
            val oldDir  = root / "old-dir"
            val newDir  = root / "new-dir"
            Scope.run {
                fileSystem.write(oldFile, "same", Path.WriteOptions()).andThen(fileSystem.mkDir(oldDir)).andThen {
                    fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                        fileSystem.removeExisting(oldFile).andThen(fileSystem.write(newFile, "same", Path.WriteOptions())).andThen {
                            fileSystem.removeAll(oldDir).andThen(fileSystem.mkDir(newDir)).andThen {
                                watcher.events.take(4).run.map { events =>
                                    assert(!events.exists { case PathChange.Moved(_, _) => true; case _ => false })
                                    assert(events.toSet == Set(
                                        PathChange.Removed(oldFile),
                                        PathChange.Created(newFile),
                                        PathChange.Removed(oldDir),
                                        PathChange.Created(newDir)
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "in-memory watcher invalidates when its root is moved away" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("moved-watch-root")
            Scope.run {
                fileSystem.mkDir(root).andThen {
                    fileSystem.openWatcher(root, WatchOptions(capacity = 1)).map { watcher =>
                        fileSystem.move(root, Path("moved-watch-target"), Path.MoveOptions()).andThen {
                            watcher.events.take(1).run.map(events => assert(events == Chunk(PathChange.Invalidated(root))))
                        }
                    }
                }
            }
        }
    }

    "in-memory watcher invalidates when an ancestor is moved away" in {
        FileSystem.inMemory.map { fileSystem =>
            val ancestor = Path("moved-watch-ancestor")
            val root     = ancestor / "root"
            Scope.run {
                fileSystem.mkDir(root).andThen {
                    fileSystem.openWatcher(root, WatchOptions(capacity = 1)).map { watcher =>
                        fileSystem.move(ancestor, Path("moved-ancestor-target"), Path.MoveOptions()).andThen {
                            watcher.events.take(1).run.map(events => assert(events == Chunk(PathChange.Invalidated(root))))
                        }
                    }
                }
            }
        }
    }

    "in-memory multi-event traces are stably path sorted" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("sorted-watch-root")
            val dir  = root / "dir"
            val a    = dir / "a.txt"
            val b    = dir / "b.txt"
            fileSystem.write(a, "a", Path.WriteOptions()).andThen(fileSystem.write(b, "b", Path.WriteOptions())).andThen {
                Scope.run {
                    fileSystem.openWatcher(root, WatchOptions(depth = WatchDepth.Recursive)).map { watcher =>
                        fileSystem.removeAll(dir).andThen {
                            watcher.events.take(3).run.map { events =>
                                assert(events == Chunk(PathChange.Removed(dir), PathChange.Removed(a), PathChange.Removed(b)))
                            }
                        }
                    }
                }
            }
        }
    }

    "in-memory watcher emits Overflow when its bounded queue loses changes" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("overflow-root")
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- root.mkDir
                            watcher <- root.openWatcher(WatchOptions(capacity = 1))
                            _       <- (root / "first").write("first")
                            _       <- (root / "second").write("second")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Overflow(root)))
                    }
                }
            }
        }
    }

    "watch capacity is an exact logical bound" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("capacity-root")
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- root.mkDir
                            watcher <- root.openWatcher(WatchOptions(capacity = 3))
                            _       <- Kyo.foreachDiscard(0 until 4)(index => (root / s"$index.txt").write(index.toString))
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Overflow(root)))
                    }
                }
            }
        }
    }

    "immediate depth excludes descendants below direct children" in {
        FileSystem.inMemory.map { fileSystem =>
            val root   = Path("immediate-root")
            val nested = root / "nested"
            val direct = root / "direct.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- nested.mkDir
                            watcher <- root.openWatcher()
                            _       <- (nested / "ignored.txt").write("ignored")
                            _       <- direct.write("selected")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Created(direct)))
                    }
                }
            }
        }
    }

    "recursive depth includes descendants" in {
        FileSystem.inMemory.map { fileSystem =>
            val root   = Path("recursive-root")
            val nested = root / "nested"
            val file   = nested / "selected.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- nested.mkDir
                            watcher <- root.openWatcher(WatchOptions(depth = WatchDepth.Recursive))
                            _       <- file.write("selected")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Created(file)))
                    }
                }
            }
        }
    }

    "glob matching is root relative and observes explicit case policy" in {
        FileSystem.inMemory.map { fileSystem =>
            val root     = Path("glob-root")
            val ignored  = root / "other" / "FILE.TXT"
            val selected = root / "selected" / "FILE.TXT"
            val options = WatchOptions(
                depth = WatchDepth.Recursive,
                glob = glob("selected/*.txt"),
                caseSensitivity = MatchCase.Insensitive
            )
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- root.mkDir
                            watcher <- root.openWatcher(options)
                            _       <- ignored.write("ignored")
                            _       <- selected.write("selected")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Created(selected)))
                    }
                }
            }
        }
    }

    "FileSystemDefault uses the backend case policy" in {
        FileSystem.inMemory.map { fileSystem =>
            val root     = Path("default-case-root")
            val ignored  = root / "IGNORED.TXT"
            val selected = root / "selected.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- root.mkDir
                            watcher <- root.openWatcher(WatchOptions(glob = glob("*.txt")))
                            _       <- ignored.write("ignored")
                            _       <- selected.write("selected")
                            events  <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Created(selected)))
                    }
                }
            }
        }
    }

    "glob-filtered moves normalize entering and leaving the selected view" in {
        FileSystem.inMemory.map { fileSystem =>
            val root         = Path("move-filter-root")
            val outside      = root / "value.bin"
            val inside       = root / "value.txt"
            val outsideAgain = root / "renamed.bin"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _       <- outside.write("value")
                            watcher <- root.openWatcher(WatchOptions(glob = glob("*.txt")))
                            _       <- outside.move(inside)
                            entered <- watcher.events.take(1).run
                            _       <- inside.move(outsideAgain)
                            left    <- watcher.events.take(1).run
                        yield
                            assert(entered == Chunk(PathChange.Created(inside)))
                            assert(left == Chunk(PathChange.Removed(inside)))
                    }
                }
            }
        }
    }

    "watcher resources are released with their acquisition scope" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("scope-root")
            Path.runWith(fileSystem)(root.mkDir).andThen {
                Scope.run(fileSystem.openWatcher(root, WatchOptions())).map { watcher =>
                    Scope.run(watcher.events.run).map(events => assert(events.isEmpty))
                }
            }
        }
    }

    "zip backends do not claim the optional Watch tier" in {
        val readOnlyErrors = typeCheckErrors("""
            val archive = Path("archive.zip")
            val watched: FileSystem.Watch[Sync] < (Sync & Scope & Abort[FileSystemException]) =
                FileSystem.zipReadOnly(archive)
        """)
        val rewriteErrors = typeCheckErrors("""
            val archive = Path("archive.zip")
            val watched: FileSystem.Watch[Sync] < (Sync & Scope) = FileSystem.zip(archive)
        """)
        assert(readOnlyErrors.nonEmpty)
        assert(rewriteErrors.nonEmpty)
    }

    "PathWatch runner uses the Local-selected watch backend" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("local-watch-root")
            val file = root / "created.txt"
            FileSystem.let(fileSystem) {
                Scope.run {
                    Path.runWatch {
                        Path.run {
                            for
                                _       <- root.mkDir
                                watcher <- root.openWatcher()
                                _       <- file.write("created")
                                events  <- watcher.events.take(1).run
                            yield assert(events == Chunk(PathChange.Created(file)))
                        }
                    }
                }
            }
        }
    }

    "polling watcher surfaces a terminal typed scan failure" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { delegate =>
                AtomicBoolean.init(false).map { failList =>
                    AtomicBoolean.init(true).map { rootIsDirectory =>
                        val root = Path("fault-watch-root")
                        val fs   = new FaultRead(delegate, failList, rootIsDirectory)
                        delegate.mkDir(root).andThen {
                            fs.openWatcher(root, WatchOptions()).map { watcher =>
                                failList.set(true).andThen {
                                    Fiber.initUnscoped(Abort.run[FileWatchException](Scope.run(watcher.events.run))).map { fiber =>
                                        clock.advance(10.millis).andThen(fiber.get).map {
                                            case Result.Failure(error: FileIOException) =>
                                                assert(error.path == root)
                                                assert(error.operation == FileSystemOperation.List)
                                            case other => fail(s"expected terminal watch failure, found $other")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "polling watcher invalidates when its root becomes a file" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { delegate =>
                AtomicBoolean.init(false).map { failList =>
                    AtomicBoolean.init(true).map { rootIsDirectory =>
                        val root = Path("file-watch-root")
                        val fs   = new FaultRead(delegate, failList, rootIsDirectory)
                        delegate.mkDir(root).andThen {
                            fs.openWatcher(root, WatchOptions()).map { watcher =>
                                rootIsDirectory.set(false).andThen {
                                    Fiber.initUnscoped(Scope.run(watcher.events.take(1).run)).map { fiber =>
                                        clock.advance(10.millis).andThen(fiber.get).map { events =>
                                            assert(events == Chunk(PathChange.Invalidated(root)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "polling invalidation is terminal and cannot resume after root recreation" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { delegate =>
                AtomicBoolean.init(false).map { failList =>
                    AtomicBoolean.init(true).map { rootIsDirectory =>
                        val root = Path("terminal-polling-root")
                        val fs   = new FaultRead(delegate, failList, rootIsDirectory)
                        delegate.mkDir(root).andThen {
                            fs.openWatcher(root, WatchOptions()).map { watcher =>
                                Fiber.initUnscoped(Scope.run(watcher.events.run)).map { fiber =>
                                    rootIsDirectory.set(false).andThen(clock.advance(10.millis)).andThen {
                                        rootIsDirectory.set(true).andThen(delegate.write(
                                            root / "later",
                                            "later",
                                            Path.WriteOptions()
                                        )).andThen {
                                            clock.advance(10.millis).andThen(fiber.get).map(events =>
                                                assert(events == Chunk(PathChange.Invalidated(root)))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "polling paces its scans by the poll interval rather than spinning" in {
        // Deliberately on the live clock: the pacing under test is the loop awaiting its own sleep,
        // and a controlled clock would make the loop's progress a property of the test instead.
        //
        // The bound is one-sided, which is what keeps this from becoming another timing-sensitive
        // test: a slower or busier machine completes fewer scans, never more, so load can only move
        // the result away from the failure. At a 5ms interval a paced loop performs roughly 40 scans
        // in 200ms; the bound sits far above that and orders of magnitude below a free-running loop.
        FileSystem.inMemory.map { delegate =>
            val root = Path("poll-pacing-root")
            AtomicInt.init(0).map { scans =>
                val fs = new CountingRead(delegate, scans)
                delegate.mkDir(root).andThen {
                    Scope.run {
                        fs.openWatcher(root, WatchOptions()).map { watcher =>
                            Fiber.initUnscoped(Scope.run(watcher.events.run)).map { _ =>
                                Async.sleep(200.millis).andThen {
                                    scans.get.map { count =>
                                        assert(
                                            count <= 500,
                                            s"polling performed $count scans in 200ms at a 5ms interval; the loop is not awaiting its sleep"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "polling recovery preserves the precise existence-check failure" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { delegate =>
                val root  = Path("recovery-failure-root")
                val child = root / "child.txt"
                AtomicBoolean.init(false).map { failObservation =>
                    val panicCheck = new java.util.concurrent.atomic.AtomicBoolean(false)
                    Kyo.unit.map { _ =>
                        AtomicBoolean.init(false).map { failCheck =>
                            val fs = new RecoveryFaultRead(delegate, child, failObservation, panicCheck, failCheck, new RuntimeException)
                            delegate.write(child, "value", Path.WriteOptions()).andThen {
                                fs.openWatcher(root, WatchOptions()).map { watcher =>
                                    failObservation.set(true).andThen(failCheck.set(true)).andThen {
                                        Fiber.initUnscoped(Scope.run(Abort.run[FileWatchException](watcher.events.run))).map { fiber =>
                                            clock.advance(10.millis).andThen(fiber.get).map {
                                                case Result.Failure(error: FileIOException) =>
                                                    assert(error.path == child)
                                                    assert(error.operation == FileSystemOperation.Exists)
                                                case other => fail(s"expected precise existence failure, found $other")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "polling recovery preserves existence-check panics".timeout(5.seconds) in {
        FileSystem.inMemory.map { delegate =>
            val root       = Path("recovery-panic-root")
            val child      = root / "child.txt"
            val marker     = new RuntimeException("check panic")
            val panicCheck = new java.util.concurrent.atomic.AtomicBoolean(true)
            AtomicBoolean.init(false).map { failObservation =>
                AtomicBoolean.init(false).map { failCheck =>
                    val fs = new RecoveryFaultRead(delegate, child, failObservation, panicCheck, failCheck, marker)
                    delegate.write(child, "value", Path.WriteOptions()).andThen {
                        Abort.run[FileWatchException](fs.openWatcher(root, WatchOptions())).map {
                            case Result.Panic(error) => assert(error eq marker)
                            case other               => fail(s"expected existence-check panic, found $other")
                        }
                    }
                }
            }
        }
    }

    "terminal panic channel mapping preserves the exact panic" in {
        val marker = new RuntimeException("terminal panic")
        Channel.init[Result[FileWatchException, PathChange]](1).map { channel =>
            val events = Stream:
                Abort.run[Closed](channel.take).map {
                    case Result.Success(Result.Success(event)) => Emit.value(Chunk(event))
                    case Result.Success(Result.Failure(error)) => Abort.fail(error)
                    case Result.Success(Result.Panic(error))   => Abort.panic[FileWatchException](error)
                    case Result.Failure(_)                     => ()
                    case Result.Panic(error)                   => Abort.panic[FileWatchException](error)
                }
            Abort.run[Closed](channel.offer(Result.Panic(marker))).map { offered =>
                assert(offered == Result.Success(true))
            }.andThen(Abort.run[FileWatchException](events.run)).map {
                case Result.Panic(error) => assert(error eq marker)
                case other               => fail(s"expected terminal panic, found $other")
            }
        }
    }

    "FileSystem.let coherently selects the watch backend" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("coherent-local-watch")
            val file = root / "created.txt"
            FileSystem.let(fileSystem) {
                Scope.run {
                    Path.runWatch {
                        Path.run {
                            for
                                _       <- root.mkDir
                                watcher <- root.openWatcher()
                                _       <- file.write("created")
                                events  <- watcher.events.take(1).run
                            yield assert(events == Chunk(PathChange.Created(file)))
                        }
                    }
                }
            }
        }
    }

    "PathWatch is isolatable across concurrent child fibers" in {
        val errors = typeCheckErrors("""
            given Frame = Frame.internal
            val root = Path("isolated-watch")
            val program: Chunk[Path.Watcher] < (PathWatch & Async) =
                Async.foreach(0 until 2, 2)(_ => root.openWatcher())
        """)
        assert(errors.isEmpty)
    }

    "PathWatch runs watchers in concurrent child fibers" in {
        FileSystem.inMemory.map { fileSystem =>
            val roots = Chunk(Path("isolated-watch-one"), Path("isolated-watch-two"))
            FileSystem.let(fileSystem) {
                Scope.run {
                    Path.runWatch {
                        Path.run {
                            Kyo.foreachDiscard(roots)(_.mkDir).andThen {
                                Async.foreach(roots, 2) { root =>
                                    val file = root / "created.txt"
                                    for
                                        watcher <- root.openWatcher()
                                        _       <- file.write("created")
                                        events  <- watcher.events.take(1).run
                                    yield events
                                    end for
                                }.map { events =>
                                    assert(events == roots.map(root => Chunk(PathChange.Created(root / "created.txt"))))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "interrupting a PathWatch child releases its watchers" in {
        FileSystem.inMemory.map { fileSystem =>
            val root = Path("interrupted-watch")
            fileSystem.mkDir(root).andThen {
                AtomicRef.init[Maybe[Path.Watcher]](Absent).map { acquired =>
                    Latch.init(1).map { ready =>
                        Latch.init(1).map { hold =>
                            Scope.run {
                                for
                                    fiber <- Fiber.initUnscoped {
                                        Scope.run {
                                            Path.runWatchWith(fileSystem) {
                                                for
                                                    watcher <- root.openWatcher()
                                                    _       <- acquired.set(Present(watcher))
                                                    _       <- ready.release
                                                    _       <- hold.await
                                                yield ()
                                            }
                                        }
                                    }
                                    _           <- ready.await
                                    interrupted <- fiber.interrupt
                                    watcher     <- acquired.get.map(_.get)
                                    events      <- Scope.run(watcher.events.run)
                                yield assert(interrupted && events.isEmpty)
                            }
                        }
                    }
                }
            }
        }
    }

    "discarded-write watching observes staged upper mutations" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { fileSystem =>
                val root = Path("discarded-watch-root")
                val file = root / "staged.txt"
                FileSystem.let(fileSystem) {
                    Scope.run {
                        Path.runWatch {
                            Path.run {
                                root.mkDir.andThen {
                                    Path.discardWrites {
                                        for
                                            watcher <- root.openWatcher()
                                            _       <- file.write("staged")
                                            _       <- clock.advance(Duration.Zero, 10.millis)
                                            _       <- clock.advance(10.millis, 10.millis)
                                            events  <- Scope.run(watcher.events.take(1).run)
                                        yield assert(events == Chunk(PathChange.Created(file)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "staged watch rows compile through nested scopes without losing residual effects" in {
        val errors = typeCheckErrors("""
            given Frame = Frame.internal
            val root = Path("nested-staged-watch")
            val program: Path.Watcher < (PathWrite & PathWatch & Async & Scope & Abort[CommitConflict]) =
                Path.discardWrites {
                    Path.commitWritesOnSuccess {
                        Path.stageWrites(root.openWatcher()).map(_._1)
                    }
                }
        """)
        assert(errors.isEmpty, errors.mkString("\n"))
    }

    "discarded-write watching observes lower-origin mutations in the effective view" in {
        Clock.withTimeControl { clock =>
            FileSystem.inMemory.map { fileSystem =>
                val root = Path("discarded-lower-watch-root")
                val file = root / "lower.txt"
                FileSystem.let(fileSystem) {
                    fileSystem.mkDir(root).andThen {
                        Scope.run {
                            Path.runWatch {
                                Path.run {
                                    Path.discardWrites {
                                        for
                                            watcher <- root.openWatcher()
                                            _       <- fileSystem.write(file, "lower", Path.WriteOptions())
                                            _       <- clock.advance(Duration.Zero, 10.millis)
                                            _       <- clock.advance(10.millis, 10.millis)
                                            events  <- Scope.run(watcher.events.take(1).run)
                                        yield assert(events == Chunk(PathChange.Created(file)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
end PathWatchTest
