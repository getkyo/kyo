package kyo

import kyo.Path.Change as PathChange
import kyo.Path.MatchCase
import kyo.Path.WatchDepth
import kyo.Path.WatchOptions
import scala.compiletime.testing.typeCheckErrors

class PathWatchTest extends FileSystemWatchTestSuite:

    /** Counts the scans the polling watcher performs, so its pacing can be asserted rather than
      * assumed.
      */
    final private class CountingRead(
        delegate: FileSystem.Write[Sync],
        scans: AtomicInt
    ) extends FileSystem.Read[Sync], FileSystem.Watch:
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
    ) extends FileSystem.Read[Sync], FileSystem.Watch:
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

    /** One flag arms both faults, deliberately.
      *
      * The observation fault and the existence-check fault were separate flags, set as two effects
      * with the watcher already open. A poll landing between them saw the pair half-applied: `stat`
      * failing while `exists` still succeeded takes a different recovery path than the one the test
      * asserts on. They are only ever armed together, so one flag removes the intermediate state
      * rather than relying on nothing observing it.
      */
    final private class RecoveryFaultRead(
        delegate: FileSystem.Write[Sync],
        child: Path,
        faults: AtomicBoolean,
        panicCheck: java.util.concurrent.atomic.AtomicBoolean,
        panic: Throwable
    ) extends FileSystem.Read[Sync], FileSystem.Watch:
        export delegate.{exists as _, stat as _, *}

        override def stat(path: Path)(using Frame): Path.PathStat < (Sync & Abort[FileReadException]) =
            if panicCheck.get() && path == child then
                Abort.fail(FileIOException(path, FileSystemOperation.Inspect, new java.io.IOException("observation failed")))
            else
                faults.get.map {
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
                    faults.get.map {
                        case true  => Abort.fail(FileIOException(path, FileSystemOperation.Exists, new java.io.IOException("check failed")))
                        case false => delegate.exists(path, followLinks)
                    }

        def openWatcher(path: Path, options: WatchOptions)(using
            Frame
        ): Path.Watcher < (Sync & Async & Scope & Abort[FileWatchException]) =
            PathWatch.polling(this, path, options)
    end RecoveryFaultRead

    protected def withFileSystem(
        use: (FileSystem.Write[Sync] & FileSystem.Watch, Path) => Unit <
            (Async & Sync & Scope & Abort[FileSystemException])
    )(using Frame): Unit < (Async & Sync & Scope & Abort[FileSystemException]) =
        val fileSystem = FileSystem.host
        Scope.acquireRelease(fileSystem.tempDir("kyo-path-watch-test"))(handle => Sync.Unsafe.defer(handle.remove())).map { handle =>
            use(fileSystem, handle.path)
        }
    end withFileSystem

    private def hostRoot(prefix: String)(using Frame): Path < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(handle => Sync.Unsafe.defer(handle.remove())).map(_.path)

    private def glob(value: String): Glob =
        Glob.parse(value) match
            case Result.Success(glob) => glob
            case Result.Failure(error) =>
                throw new AssertionError(s"invalid test glob at ${error.offset}: ${error.reason}")
            case Result.Panic(error) => throw error

    "watcher emits Created after registration" in {
        hostRoot("kyo-path-watch-created").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "watched"
            val file       = root / "created.txt"
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
        hostRoot("kyo-path-watch-walk-race").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "walk-race-root"
            val created    = root / "during-walk.txt"
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

    "watcher emits Modified for an existing file" in {
        hostRoot("kyo-path-watch-modified").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "modified-root"
            val file       = root / "modified.txt"
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

    "watcher emits Removed for a removed child" in {
        hostRoot("kyo-path-watch-removed").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "removed-root"
            val file       = root / "removed.txt"
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

    "watcher emits Moved for a rename within the selected view" in {
        hostRoot("kyo-path-watch-moved").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "moved-root"
            val from       = root / "from.txt"
            val to         = root / "to.txt"
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

    "watcher emits Invalidated when its root is removed" in {
        hostRoot("kyo-path-watch-invalidated").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "invalidated-root"
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

    "invalidation is terminal and cannot resume after root recreation" in {
        // On the clock-driven poll loop, removeAll and the recreation below both land between two
        // scans unless the removal's own scan is forced through first: the advance after removeAll
        // is what lets the loop observe the root missing and close the stream before recreation ever
        // happens, rather than folding straight from "existed" to "exists again" and never noticing.
        Clock.withTimeControl { clock =>
            hostRoot("kyo-path-watch-terminal").map { dir =>
                val fileSystem = FileSystem.host
                val root       = dir / "terminal-root"
                val later      = root / "later.txt"
                Scope.run {
                    fileSystem.mkDir(root).andThen {
                        fileSystem.openWatcher(root, WatchOptions()).map { watcher =>
                            Fiber.initUnscoped(Scope.run(watcher.events.run)).map { fiber =>
                                fileSystem.removeAll(root).andThen {
                                    clock.advance(10.millis).andThen {
                                        fileSystem.mkDir(root).andThen {
                                            fileSystem.write(later, "later", Path.WriteOptions()).andThen {
                                                clock.advance(10.millis).andThen {
                                                    fiber.get.map(events => assert(events == Chunk(PathChange.Invalidated(root))))
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
    }

    "watcher does not invent moves for equal files or directories" in {
        hostRoot("kyo-path-watch-identity").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "identity-root"
            val oldFile    = root / "old.txt"
            val newFile    = root / "new.txt"
            val oldDir     = root / "old-dir"
            val newDir     = root / "new-dir"
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

    "watcher invalidates when its root is moved away" in {
        hostRoot("kyo-path-watch-moved-away").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "moved-watch-root"
            Scope.run {
                fileSystem.mkDir(root).andThen {
                    fileSystem.openWatcher(root, WatchOptions(capacity = 1)).map { watcher =>
                        fileSystem.move(root, dir / "moved-watch-target", Path.MoveOptions()).andThen {
                            watcher.events.take(1).run.map(events => assert(events == Chunk(PathChange.Invalidated(root))))
                        }
                    }
                }
            }
        }
    }

    "watcher invalidates when an ancestor is moved away" in {
        hostRoot("kyo-path-watch-ancestor-moved").map { dir =>
            val fileSystem = FileSystem.host
            val ancestor   = dir / "moved-watch-ancestor"
            val root       = ancestor / "root"
            Scope.run {
                fileSystem.mkDir(root).andThen {
                    fileSystem.openWatcher(root, WatchOptions(capacity = 1)).map { watcher =>
                        fileSystem.move(ancestor, dir / "moved-ancestor-target", Path.MoveOptions()).andThen {
                            watcher.events.take(1).run.map(events => assert(events == Chunk(PathChange.Invalidated(root))))
                        }
                    }
                }
            }
        }
    }

    "multi-event traces are stably path sorted" in {
        hostRoot("kyo-path-watch-sorted").map { tempRoot =>
            val fileSystem = FileSystem.host
            val root       = tempRoot / "sorted-watch-root"
            val dir        = root / "dir"
            val a          = dir / "a.txt"
            val b          = dir / "b.txt"
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

    "watcher emits Overflow when its bounded queue loses changes" in {
        // Both writes land before the scan, so the one poll pass that follows sees both new files at
        // once and folds them into a single Overflow. The polling loop rearms its sleeper only after
        // that scan publishes the whole batch, so waiting for the rearm proves the Overflow is queued
        // before the read begins.
        Clock.withTimeControl { clock =>
            hostRoot("kyo-path-watch-overflow").map { dir =>
                val fileSystem = FileSystem.host
                val root       = dir / "overflow-root"
                Scope.run {
                    Path.runWatchWith(fileSystem) {
                        Path.runWith(fileSystem) {
                            for
                                _       <- root.mkDir
                                watcher <- root.openWatcher(WatchOptions(capacity = 1))
                                _       <- (root / "first").write("first")
                                _       <- (root / "second").write("second")
                                _       <- clock.advance(10.millis)
                                _       <- clock.awaitPendingSleepers(1)
                                events  <- watcher.events.take(1).run
                            yield assert(events == Chunk(PathChange.Overflow(root)))
                        }
                    }
                }
            }
        }
    }

    "watch capacity is an exact logical bound" in {
        // Same reasoning as the Overflow case above: waiting for the polling loop to rearm proves the
        // scan has published the complete batch before the read begins.
        Clock.withTimeControl { clock =>
            hostRoot("kyo-path-watch-capacity").map { dir =>
                val fileSystem = FileSystem.host
                val root       = dir / "capacity-root"
                Scope.run {
                    Path.runWatchWith(fileSystem) {
                        Path.runWith(fileSystem) {
                            for
                                _       <- root.mkDir
                                watcher <- root.openWatcher(WatchOptions(capacity = 3))
                                _       <- Kyo.foreachDiscard(0 until 4)(index => (root / s"$index.txt").write(index.toString))
                                _       <- clock.advance(10.millis)
                                _       <- clock.awaitPendingSleepers(1)
                                events  <- watcher.events.take(1).run
                            yield assert(events == Chunk(PathChange.Overflow(root)))
                        }
                    }
                }
            }
        }
    }

    "immediate depth excludes descendants below direct children" in {
        hostRoot("kyo-path-watch-immediate").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "immediate-root"
            val nested     = root / "nested"
            val direct     = root / "direct.txt"
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
        hostRoot("kyo-path-watch-recursive").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "recursive-root"
            val nested     = root / "nested"
            val file       = nested / "selected.txt"
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
        hostRoot("kyo-path-watch-glob").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "glob-root"
            val ignored    = root / "other" / "FILE.TXT"
            val selected   = root / "selected" / "FILE.TXT"
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
        // Which file the glob selects depends on the host volume's own case policy, so the write
        // order and the expected event both branch on it rather than assuming Sensitive: on an
        // insensitive volume "IGNORED.TXT" matches "*.txt" too, so writing it first would make it
        // the first captured event instead of "selected.txt".
        hostRoot("kyo-path-watch-default-case").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "default-case-root"
            val upper      = root / "IGNORED.TXT"
            val lower      = root / "selected.txt"
            Scope.run {
                Path.runWatchWith(fileSystem) {
                    Path.runWith(fileSystem) {
                        for
                            _               <- root.mkDir
                            watcher         <- root.openWatcher(WatchOptions(glob = glob("*.txt")))
                            caseSensitivity <- fileSystem.defaultCaseSensitivity
                            _ <- caseSensitivity match
                                case Glob.CaseSensitivity.Sensitive   => upper.write("ignored").andThen(lower.write("selected"))
                                case Glob.CaseSensitivity.Insensitive => upper.write("selected")
                            expected = caseSensitivity match
                                case Glob.CaseSensitivity.Sensitive   => lower
                                case Glob.CaseSensitivity.Insensitive => upper
                            events <- watcher.events.take(1).run
                        yield assert(events == Chunk(PathChange.Created(expected)))
                    }
                }
            }
        }
    }

    "glob-filtered moves normalize entering and leaving the selected view" in {
        hostRoot("kyo-path-watch-move-filter").map { dir =>
            val fileSystem   = FileSystem.host
            val root         = dir / "move-filter-root"
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
        hostRoot("kyo-path-watch-scope").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "scope-root"
            Path.runWith(fileSystem)(root.mkDir).andThen {
                Scope.run(fileSystem.openWatcher(root, WatchOptions())).map { watcher =>
                    Scope.run(watcher.events.run).map(events => assert(events.isEmpty))
                }
            }
        }
    }

    "PathWatch runner uses the Local-selected watch backend" in {
        hostRoot("kyo-path-watch-local").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "local-watch-root"
            val file       = root / "created.txt"
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
            hostRoot("kyo-path-watch-fault").map { dir =>
                val delegate = FileSystem.host
                AtomicBoolean.init(false).map { failList =>
                    AtomicBoolean.init(true).map { rootIsDirectory =>
                        val root = dir / "fault-watch-root"
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
            hostRoot("kyo-path-watch-root-file").map { dir =>
                val delegate = FileSystem.host
                AtomicBoolean.init(false).map { failList =>
                    AtomicBoolean.init(true).map { rootIsDirectory =>
                        val root = dir / "file-watch-root"
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
            hostRoot("kyo-path-watch-terminal-polling").map { dir =>
                val delegate = FileSystem.host
                AtomicBoolean.init(false).map { failList =>
                    AtomicBoolean.init(true).map { rootIsDirectory =>
                        val root = dir / "terminal-polling-root"
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
        hostRoot("kyo-path-watch-pacing").map { dir =>
            val delegate = FileSystem.host
            val root     = dir / "poll-pacing-root"
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
            hostRoot("kyo-path-watch-recovery-failure").map { dir =>
                val delegate = FileSystem.host
                val root     = dir / "recovery-failure-root"
                val child    = root / "child.txt"
                AtomicBoolean.init(false).map { faults =>
                    val panicCheck = new java.util.concurrent.atomic.AtomicBoolean(false)
                    Kyo.unit.map { _ =>
                        Kyo.unit.map { _ =>
                            val fs = new RecoveryFaultRead(delegate, child, faults, panicCheck, new RuntimeException)
                            delegate.write(child, "value", Path.WriteOptions()).andThen {
                                fs.openWatcher(root, WatchOptions()).map { watcher =>
                                    // Armed in one step, after the watcher's clean initial snapshot.
                                    faults.set(true).andThen {
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
        hostRoot("kyo-path-watch-recovery-panic").map { dir =>
            val delegate   = FileSystem.host
            val root       = dir / "recovery-panic-root"
            val child      = root / "child.txt"
            val marker     = new RuntimeException("check panic")
            val panicCheck = new java.util.concurrent.atomic.AtomicBoolean(true)
            AtomicBoolean.init(false).map { faults =>
                Kyo.unit.map { _ =>
                    val fs = new RecoveryFaultRead(delegate, child, faults, panicCheck, marker)
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
        hostRoot("kyo-path-watch-coherent-local").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "coherent-local-watch"
            val file       = root / "created.txt"
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
        hostRoot("kyo-path-watch-concurrent-fibers").map { dir =>
            val fileSystem = FileSystem.host
            val roots      = Chunk(dir / "isolated-watch-one", dir / "isolated-watch-two")
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
        hostRoot("kyo-path-watch-interrupted").map { dir =>
            val fileSystem = FileSystem.host
            val root       = dir / "interrupted-watch"
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
end PathWatchTest
