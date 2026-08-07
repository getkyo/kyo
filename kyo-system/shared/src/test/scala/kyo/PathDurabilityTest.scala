package kyo

class PathDurabilityTest extends kyo.test.Test[Any]:

    private enum Event derives CanEqual:
        case Temporary(path: Path)
        case Open(path: Path, mode: FileSystem.WriteOpen)
        case Write(path: Path, position: Long, bytes: Span[Byte])
        case SyncFile(path: Path, metadata: Boolean)
        case Close(path: Path)
        case Move(from: Path, to: Path, options: Path.MoveOptions)
        case SyncDirectory(path: Path)
        case Remove(path: Path)
    end Event

    final private class Recording(
        base: FileSystem.Write[Sync],
        failOn: Maybe[String] = Absent,
        failRootSyncAt: Maybe[Int] = Absent
    ) extends FileSystem.Write[Sync]:
        export base.{durableReplace as _, move as _, openWriteChannelUnscoped as _, remove as _, syncDirectory as _, tempFileHandle as _, *}
        private val recorded                     = scala.collection.mutable.ArrayBuffer.empty[Event]
        def events: Chunk[Event]                 = Chunk.from(recorded)
        private def fails(step: String): Boolean = failOn.contains(step)
        private def failure(path: Path, operation: FileSystemOperation)(using Frame): FileIOException =
            FileIOException(path, operation, new java.io.IOException(s"injected $operation failure"))

        override private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
            (Sync & Abort[FileWriteException | FileStructureException]) =
            Sync.defer {
                recorded += Event.Temporary(path)
                recorded += Event.Open(path, open)
            }.andThen(
                if fails("open") then Abort.fail(failure(path, FileSystemOperation.Channel))
                else base.openWriteChannelUnscoped(path, open)
            ).map { (channel, release, closeHandle) =>
                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                def recordClose(): Unit =
                    if closed.compareAndSet(false, true) then recorded += Event.Close(path)
                val wrapped = new Path.WriteChannel[Sync]:
                    def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Sync & Abort[FileWriteException]) =
                        Sync.defer(recorded += Event.Write(path, position, bytes)).andThen(
                            if fails("write") then Abort.fail(failure(path, FileSystemOperation.Write))
                            else channel.writeAt(position, bytes)
                        )
                    def sync(metadata: Boolean)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
                        Sync.defer(recorded += Event.SyncFile(path, metadata)).andThen(
                            if fails("sync-file") then Abort.fail(failure(path, FileSystemOperation.Write))
                            else channel.sync(metadata)
                        )
                    def truncate(size: Long)(using Frame): Unit < (Sync & Abort[FileWriteException]) = channel.truncate(size)
                val wrappedClose = new Path.ChannelCloseHandle:
                    def close()(using AllowUnsafe): Unit =
                        closeHandle.close()
                        recordClose()
                (wrapped, () => release().andThen(Sync.defer(recordClose())), wrappedClose)
            }

        override private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(temporary)
            new Path.TempFileHandle:
                def path: Path = temporary
                def remove()(using AllowUnsafe): Unit =
                    recorded += Event.Remove(temporary)
                    handle.remove()
            end new
        end tempFileHandle

        override def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            FileSystem.durableReplace[Any](this, target, bytes)

        override def move(from: Path, to: Path, options: Path.MoveOptions)(using
            Frame
        ): Unit < (Sync & Abort[FileReadException | FileWriteException | FileStructureException]) =
            Sync.defer(recorded += Event.Move(from, to, options)).andThen(
                if fails("move") then Abort.fail(failure(from, FileSystemOperation.Move))
                else base.move(from, to, options)
            )

        override def syncDirectory(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
            Sync.defer {
                recorded += Event.SyncDirectory(path)
                recorded.count {
                    case Event.SyncDirectory(recordedPath) => recordedPath == Path()
                    case _                                 => false
                }
            }.map { rootSync =>
                if fails("sync-directory") || (path == Path() && failRootSyncAt.contains(rootSync)) then
                    Abort.fail(failure(path, FileSystemOperation.SyncDirectory))
                else base.syncDirectory(path)
            }
        end syncDirectory

        override def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException | FileStructureException]) =
            Sync.defer(recorded += Event.Remove(path)).andThen(base.remove(path))
    end Recording

    final private class Interrupting(
        base: FileSystem.Write[Async],
        ready: Latch,
        hold: Latch,
        temporary: AtomicRef[Maybe[Path]],
        closes: AtomicInt,
        removals: AtomicInt
    ) extends FileSystem.Write[Async]:
        export base.{durableReplace as _, openWriteChannelUnscoped as _, tempFileHandle as _, *}

        override private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (Path.WriteChannel[Async], () => Unit < Async, Path.ChannelCloseHandle) <
            (Async & Abort[FileWriteException | FileStructureException]) =
            temporary.set(Present(path)).andThen(base.openWriteChannelUnscoped(path, open)).map { (channel, release, closeHandle) =>
                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                def recordCloseUnsafe()(using AllowUnsafe): Unit =
                    if closed.compareAndSet(false, true) then Sync.Unsafe.evalOrThrow(closes.incrementAndGet.unit)
                def recordClose: Unit < Sync =
                    Sync.defer(closed.compareAndSet(false, true)).map { first =>
                        if first then closes.incrementAndGet.unit else ()
                    }
                val wrapped = new Path.WriteChannel[Async]:
                    def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Async & Abort[FileWriteException]) =
                        ready.release.andThen(hold.await).andThen(channel.writeAt(position, bytes))
                    def sync(metadata: Boolean)(using Frame): Unit < (Async & Abort[FileWriteException]) = channel.sync(metadata)
                    def truncate(size: Long)(using Frame): Unit < (Async & Abort[FileWriteException])    = channel.truncate(size)
                val wrappedClose = new Path.ChannelCloseHandle:
                    def close()(using AllowUnsafe): Unit =
                        closeHandle.close()
                        recordCloseUnsafe()
                (wrapped, () => release().andThen(recordClose), wrappedClose)
            }

        override private[kyo] def tempFileHandle(path: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(path)
            new Path.TempFileHandle:
                def path: Path = handle.path
                def remove()(using AllowUnsafe): Unit =
                    Sync.Unsafe.evalOrThrow(removals.incrementAndGet.unit)
                    handle.remove()
            end new
        end tempFileHandle

        override def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
            FileSystem.durableReplace[Async](this, target, bytes)
    end Interrupting

    final private class ReleaseInterrupting(
        base: FileSystem.Write[Async],
        ready: Latch,
        hold: Latch,
        temporary: AtomicRef[Maybe[Path]],
        removals: AtomicInt
    ) extends FileSystem.Write[Async]:
        export base.{openWriteChannelUnscoped as _, siblingTemporary as _, tempFileHandle as _, *}

        override private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (Path.WriteChannel[Async], () => Unit < Async, Path.ChannelCloseHandle) <
            (Async & Abort[FileWriteException | FileStructureException]) =
            temporary.set(Present(path)).andThen(base.openWriteChannelUnscoped(path, open)).map { (channel, release, close) =>
                (channel, () => ready.release.andThen(hold.await).andThen(release()), close)
            }

        override private[kyo] def tempFileHandle(path: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(path)
            new Path.TempFileHandle:
                def path: Path = handle.path
                def remove()(using AllowUnsafe): Unit =
                    Sync.Unsafe.evalOrThrow(removals.incrementAndGet.unit)
                    handle.remove()
            end new
        end tempFileHandle

        override def siblingTemporary(target: Path)(using
            Frame
        ): Path.TempFileHandle < (Async & Abort[FileWriteException | FileStructureException]) =
            FileSystem.siblingTemporary[Async](this, target)
    end ReleaseInterrupting

    final private class ClosePanicking(
        base: FileSystem.Write[Async],
        ready: Latch,
        hold: Latch,
        temporary: AtomicRef[Maybe[Path]],
        closes: AtomicInt,
        removals: AtomicInt,
        closeFailure: Throwable
    ) extends FileSystem.Write[Async]:
        export base.{durableReplace as _, openWriteChannelUnscoped as _, tempFileHandle as _, *}

        override private[kyo] def openWriteChannelUnscoped(path: Path, open: FileSystem.WriteOpen)(using
            Frame
        ): (Path.WriteChannel[Async], () => Unit < Async, Path.ChannelCloseHandle) <
            (Async & Abort[FileWriteException | FileStructureException]) =
            temporary.set(Present(path)).andThen(base.openWriteChannelUnscoped(path, open)).map { (channel, release, _) =>
                val wrapped = new Path.WriteChannel[Async]:
                    def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Async & Abort[FileWriteException]) =
                        ready.release.andThen(hold.await).andThen(channel.writeAt(position, bytes))
                    def sync(metadata: Boolean)(using Frame): Unit < (Async & Abort[FileWriteException]) = channel.sync(metadata)
                    def truncate(size: Long)(using Frame): Unit < (Async & Abort[FileWriteException])    = channel.truncate(size)
                val close = new Path.ChannelCloseHandle:
                    def close()(using AllowUnsafe): Unit =
                        Sync.Unsafe.evalOrThrow(closes.incrementAndGet.unit)
                        throw closeFailure
                (wrapped, release, close)
            }

        override private[kyo] def tempFileHandle(path: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(path)
            new Path.TempFileHandle:
                def path: Path = handle.path
                def remove()(using AllowUnsafe): Unit =
                    Sync.Unsafe.evalOrThrow(removals.incrementAndGet.unit)
                    handle.remove()
            end new
        end tempFileHandle

        override def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
            FileSystem.durableReplace[Async](this, target, bytes)
    end ClosePanicking

    "durable replacement writes and persists bytes" in {
        for
            fs <- FileSystem.inMemory
            target = Path("durability", "target.bin")
            bytes  = Span.from(Array[Byte](1, 2, 3, 4))
            _      <- fs.durableReplace(target, bytes)
            actual <- fs.readBytes(target)
        yield assert(actual.toArrayUnsafe.sameElements(bytes.toArrayUnsafe))
    }

    "durable replacement performs the persistence protocol in order" in {
        for
            base <- FileSystem.inMemory
            fs     = new Recording(base)
            target = Path("durability", "ordered.bin")
            bytes  = Span.from(Array[Byte](5, 6, 7))
            _ <- fs.durableReplace(target, bytes)
            events = fs.events
            temp   = events(0).asInstanceOf[Event.Temporary].path
        yield assert(
            events == Chunk(
                Event.Temporary(temp),
                Event.Open(temp, FileSystem.WriteOpen.CreateNew),
                Event.Write(temp, 0L, bytes),
                Event.SyncFile(temp, metadata = true),
                Event.Close(temp),
                Event.Move(
                    temp,
                    target,
                    Path.MoveOptions(
                        replace = Path.Replace.Existing,
                        atomicity = Path.Atomicity.Required,
                        createFolders = false
                    )
                ),
                Event.SyncDirectory(Path("durability"))
            )
        )
    }

    List("write", "sync-file", "move").foreach { failedStep =>
        s"failure at $failedStep stops later steps and removes the temporary" in {
            for
                base <- FileSystem.inMemory
                fs     = new Recording(base, Present(failedStep))
                target = Path("durability", s"$failedStep.bin")
                _ <- base.writeBytes(target, Span.from(Array[Byte](9)), Path.WriteOptions())
                result <- Abort.run[FileReadException | FileWriteException | FileStructureException](
                    fs.durableReplace(target, Span.from(Array[Byte](1, 2)))
                )
                actual <- base.readBytes(target)
                temp = fs.events.collectFirst { case Event.Temporary(path) => path }.get
                exists <- base.exists(temp)
                moved  = fs.events.exists { case Event.Move(_, _, _) => true; case _ => false }
                synced = fs.events.exists { case Event.SyncDirectory(_) => true; case _ => false }
            yield assert(
                result.isFailure &&
                    actual.toArrayUnsafe.sameElements(Array[Byte](9)) &&
                    !exists &&
                    fs.events.last == Event.Remove(temp) &&
                    (failedStep == "move" || !moved) &&
                    !synced
            )
        }
    }

    "directory synchronization failure reports failure after replacement" in {
        for
            base <- FileSystem.inMemory
            fs     = new Recording(base, Present("sync-directory"))
            target = Path("durability", "uncertain.bin")
            _ <- base.writeBytes(target, Span.from(Array[Byte](9)), Path.WriteOptions())
            result <- Abort.run[FileReadException | FileWriteException | FileStructureException](
                fs.durableReplace(target, Span.from(Array[Byte](1, 2)))
            )
            actual <- base.readBytes(target)
        yield assert(result.isFailure && actual.toArrayUnsafe.sameElements(Array[Byte](1, 2)))
    }

    "interruption closes the channel and removes the temporary exactly once" in {
        for
            base      <- FileSystem.inMemory
            ready     <- Latch.init(1)
            hold      <- Latch.init(1)
            temporary <- AtomicRef.init[Maybe[Path]](Absent)
            closes    <- AtomicInt.init(0)
            removals  <- AtomicInt.init(0)
            // Safe effect widening for the test double; FileSystem is invariant because its channels consume S.
            asyncBase = base.asInstanceOf[FileSystem.Write[Async]]
            fs        = new Interrupting(asyncBase, ready, hold, temporary, closes, removals)
            target    = Path("durability", "interrupted.bin")
            fiber <- Fiber.initUnscoped {
                fs.durableReplace(target, Span.from(Array[Byte](1, 2, 3)))
            }
            _           <- ready.await
            interrupted <- fiber.interrupt
            _           <- assertEventually(closes.get.map(_ == 1))
            _           <- assertEventually(removals.get.map(_ == 1))
            temp        <- temporary.get.map(_.get)
            exists      <- base.exists(temp)
            closeCount  <- closes.get
            removeCount <- removals.get
        yield assert(interrupted && !exists && closeCount == 1 && removeCount == 1)
    }

    "interruption while closing a sibling temporary still removes it exactly once" in {
        for
            base      <- FileSystem.inMemory
            ready     <- Latch.init(1)
            hold      <- Latch.init(1)
            temporary <- AtomicRef.init[Maybe[Path]](Absent)
            removals  <- AtomicInt.init(0)
            asyncBase = base.asInstanceOf[FileSystem.Write[Async]]
            fs        = new ReleaseInterrupting(asyncBase, ready, hold, temporary, removals)
            fiber <- Fiber.initUnscoped {
                Scope.run(Path.runWith(fs)(Path("durability", "release-interrupted.bin").siblingTemporary))
            }
            _           <- ready.await
            interrupted <- fiber.interrupt
            _           <- assertEventually(removals.get.map(_ == 1))
            temp        <- temporary.get.map(_.get)
            exists      <- base.exists(temp)
            removeCount <- removals.get
        yield assert(interrupted && !exists && removeCount == 1)
    }

    "a close panic still removes the durable temporary and preserves the interruption panic" in {
        for
            base      <- FileSystem.inMemory
            ready     <- Latch.init(1)
            hold      <- Latch.init(1)
            temporary <- AtomicRef.init[Maybe[Path]](Absent)
            closes    <- AtomicInt.init(0)
            removals  <- AtomicInt.init(0)
            primary    = new RuntimeException("primary interruption")
            closeError = new RuntimeException("close failure")
            asyncBase  = base.asInstanceOf[FileSystem.Write[Async]]
            fs         = new ClosePanicking(asyncBase, ready, hold, temporary, closes, removals, closeError)
            fiber <- Fiber.initUnscoped {
                fs.durableReplace(Path("durability", "close-panic.bin"), Span.from(Array[Byte](1)))
            }
            _           <- ready.await
            interrupted <- fiber.interrupt(Result.Panic(primary))
            _           <- assertEventually(closes.get.map(_ == 1))
            result      <- fiber.poll.map(_.get)
            closeCount  <- closes.get
            removeCount <- removals.get
            temp        <- temporary.get.map(_.get)
            exists      <- base.exists(temp)
        yield
            val preserved = result match
                case Result.Panic(error) => error eq primary
                case _                   => false
            assert(interrupted && closeCount == 1 && removeCount == 1 && !exists && preserved)
    }

    "sibling temporary is created beside the target and removed at scope exit" in {
        for
            fs <- FileSystem.inMemory
            observed <- Scope.run {
                Path.runWith(fs) {
                    val target = Path("durability", "scoped.bin")
                    target.siblingTemporary.map { temporary =>
                        temporary.exists.map(exists => (target.parent, temporary.parent, temporary, exists))
                    }
                }
            }
            (targetParent, temporaryParent, temporary, existedInside) = observed
            existsAfter <- fs.exists(temporary)
        yield assert(targetParent == temporaryParent && existedInside && !existsAfter)
    }

    "overlay sibling temporary cleanup cannot be resurrected by commit" in {
        Scope.run {
            for
                lower   <- FileSystem.inMemory
                overlay <- FileSystem.overlay(lower)
                temporary <- Scope.run {
                    Path.runWith(overlay)(Path("durability", "overlay-target.bin").siblingTemporary)
                }
                _      <- overlay.commitWith(_ => FileSystem.Resolution.KeepOurs)
                exists <- lower.exists(temporary)
            yield assert(!exists)
        }
    }

    "overlay commit synchronizes the current directory after replacing a relative target" in {
        Scope.run {
            for
                base <- FileSystem.inMemory
                lower = new Recording(base, failRootSyncAt = Present(2))
                overlay <- FileSystem.overlay(lower)
                target = Path("overlay-relative-target.bin")
                bytes  = Span.from(Array[Byte](4, 5, 6))
                _      <- overlay.writeBytes(target, bytes, Path.WriteOptions())
                result <- Abort.run[FileSystemException | CommitConflict](overlay.commitWith(_ => FileSystem.Resolution.KeepOurs))
                actual <- base.readBytes(target)
                rootSyncs = lower.events.collect { case Event.SyncDirectory(path) if path == Path() => path }
            yield assert(
                result.isFailure &&
                    rootSyncs.size == 2 &&
                    actual.toArrayUnsafe.sameElements(bytes.toArrayUnsafe),
                s"result=$result rootSyncs=$rootSyncs events=${lower.events}"
            )
        }
    }

    "host durable replacement synchronizes the current directory for a relative target" in {
        Sync.defer(java.lang.System.nanoTime()).map { id =>
            val target = Path(s".kyo-durable-relative-$id.bin")
            val bytes  = Span.from(Array[Byte](7, 8, 9))
            // Unsafe: removes only this test's uniquely named relative file before and after the assertion.
            val cleanup = Sync.Unsafe.defer(discard(target.unsafe.remove()))
            cleanup.andThen {
                Sync.ensure(cleanup) {
                    Abort.run[FileReadException | FileWriteException | FileStructureException](
                        FileSystem.host.durableReplace(target, bytes)
                    ).map {
                        case Result.Success(_)     => assert(true)
                        case Result.Failure(error) => assert(false, s"relative durable replacement failed: $error")
                    }
                }
            }
        }
    }

    "Path durability operations remain capability suspended" in {
        typeCheck("def replace(path: kyo.Path, bytes: kyo.Span[Byte])(using kyo.Frame): Unit < kyo.PathWrite = path.durableReplace(bytes)")
        typeCheck("def sync(path: kyo.Path)(using kyo.Frame): Unit < kyo.PathWrite = path.syncDirectory")
        typeCheck(
            "def temporary(path: kyo.Path)(using kyo.Frame): kyo.Path < (kyo.PathWrite & kyo.Scope & kyo.Sync) = path.siblingTemporary"
        )
    }

    "FileSystem durability rows are precise" in {
        typeCheck(
            "def sync[S](fs: kyo.FileSystem.Write[S], path: kyo.Path)(using kyo.Frame): Unit < (S & kyo.Abort[kyo.FileWriteException]) = fs.syncDirectory(path)"
        )
        typeCheck(
            "def replace[S](fs: kyo.FileSystem.Write[S], path: kyo.Path, bytes: kyo.Span[Byte])(using kyo.Frame): Unit < (S & kyo.Abort[kyo.FileReadException | kyo.FileWriteException | kyo.FileStructureException]) = fs.durableReplace(path, bytes)"
        )
    }
end PathDurabilityTest
