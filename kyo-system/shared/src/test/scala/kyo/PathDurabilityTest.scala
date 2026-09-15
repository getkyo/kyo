package kyo

class PathDurabilityTest extends kyo.test.Test[Any]:

    private[kyo] def supportsDirectorySync: Boolean = !kyo.internal.Platform.isWindows

    private[kyo] def hostFileSystem(prefix: String)(using
        Frame
    ): (FileSystem.Write[Sync], Path) < (Sync & Scope & Abort[FileSystemException]) =
        Scope.acquireRelease(FileSystem.host.tempDir(prefix))(h => Sync.Unsafe.defer(h.remove())).map(handle =>
            (FileSystem.host, handle.path)
        )

    private def replaceOnHost(
        fs: FileSystem.Write[Sync],
        target: Path,
        bytes: Span[Byte],
        directorySyncSupported: Boolean = supportsDirectorySync
    )(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Sync & Abort[FileSystemException]) =
        Abort.run[FileSystemException](fs.durableReplace(target, bytes)).map { result =>
            fs.readBytes(target).map { actual =>
                assert(actual.is(bytes))
                if !directorySyncSupported then
                    val parent = target.parent.getOrElse(Path())
                    result match
                        case Result.Failure(FileAccessDeniedException(path))                             => assert(path == parent)
                        case Result.Failure(FileIOException(path, FileSystemOperation.SyncDirectory, _)) => assert(path == parent)
                        case other => fail(s"Expected unsupported directory synchronization, got $other")
                    end match
                else assert(result == Result.unit, s"Durable replacement result: $result")
                end if
                ()
            }
        }

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
        interruptAfterOpen: Maybe[Throwable] = Absent,
        interruptAfterTransfer: Maybe[Throwable] = Absent,
        cleaned: Maybe[Latch] = Absent
    ) extends FileSystem.Write[Sync]:
        export base.{
            durableReplace as _,
            move as _,
            openWriteChannelUnscoped as _,
            remove as _,
            siblingTemporary as _,
            syncDirectory as _,
            tempFileHandle as _,
            *
        }
        private val recorded                     = scala.collection.mutable.ArrayBuffer.empty[Event]
        def events: Chunk[Event]                 = Chunk.from(recorded)
        private def fails(step: String): Boolean = failOn.contains(step)
        private def failure(path: Path, operation: FileSystemOperation)(using Frame): FileIOException =
            FileIOException(path, operation, new java.io.IOException(s"injected $operation failure"))

        override private[kyo] def openWriteChannelUnscoped(
            path: Path,
            open: FileSystem.WriteOpen,
            onAcquire: Path.ChannelCloseHandle => Unit
        )(using
            Frame
        ): (Path.WriteChannel[Sync], () => Unit < Sync, Path.ChannelCloseHandle) <
            (Sync & Abort[FileWriteException | FileStructureException]) =
            Sync.defer {
                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                def recordClose(): Unit =
                    if closed.compareAndSet(false, true) then recorded += Event.Close(path)
                def wrapClose(underlying: Path.ChannelCloseHandle): Path.ChannelCloseHandle =
                    new Path.ChannelCloseHandle:
                        def close()(using AllowUnsafe): Unit =
                            underlying.close()
                            recordClose()
                recorded += Event.Temporary(path)
                recorded += Event.Open(path, open)
                (if fails("open") then Abort.fail(failure(path, FileSystemOperation.Channel))
                 else base.openWriteChannelUnscoped(path, open, close => onAcquire(wrapClose(close))))
                    .map { (channel, release, closeHandle) =>
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
                        interruptAfterOpen.foreach { error =>
                            // Unsafe: force cancellation precisely at the resource ownership handoff.
                            discard(kyo.kernel.internal.Safepoint.get.getInterceptor()
                                .asInstanceOf[kyo.scheduler.IOPromise[Any, Any]].interrupt(Result.Panic(error)))
                        }
                        (wrapped, () => release().andThen(Sync.defer(recordClose())), wrappedClose)
                    }
            }

        override private[kyo] def tempFileHandle(temporary: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(temporary)
            new Path.TempFileHandle:
                def path: Path = temporary
                def remove()(using AllowUnsafe): Unit =
                    recorded += Event.Remove(temporary)
                    handle.remove()
                    // Unsafe: signal completion only after the raw cleanup has removed the file.
                    cleaned.foreach(latch => Sync.Unsafe.evalOrThrow(latch.release))
                end remove
            end new
        end tempFileHandle

        override private[kyo] def siblingTemporary(target: Path, onAcquire: Path.TempFileHandle => Unit)(using
            Frame
        ): Path.TempFileHandle < (Sync & Abort[FileWriteException | FileStructureException]) =
            FileSystem.siblingTemporary[Any](
                this,
                target,
                handle =>
                    onAcquire(handle)
                    interruptAfterTransfer.foreach { error =>
                        // Unsafe: force cancellation after scope ownership transfers but before returning.
                        discard(kyo.kernel.internal.Safepoint.get.getInterceptor()
                            .asInstanceOf[kyo.scheduler.IOPromise[Any, Any]].interrupt(Result.Panic(error)))
                    }
            )

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
            Sync.defer(recorded += Event.SyncDirectory(path)).andThen {
                if fails("sync-directory") then Abort.fail(failure(path, FileSystemOperation.SyncDirectory))
                else ()
            }

        override def remove(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException | FileStructureException]) =
            Sync.defer(recorded += Event.Remove(path)).andThen(base.remove(path))
    end Recording

    final private class Interrupting(
        base: FileSystem.Write[Async],
        ready: Latch,
        hold: Latch,
        temporary: AtomicRef[Maybe[Path]],
        closes: AtomicInt,
        cleaned: Latch,
        removals: AtomicInt
    ) extends FileSystem.Write[Async]:
        export base.{durableReplace as _, openWriteChannelUnscoped as _, tempFileHandle as _, *}

        override private[kyo] def openWriteChannelUnscoped(
            path: Path,
            open: FileSystem.WriteOpen,
            onAcquire: Path.ChannelCloseHandle => Unit
        )(using
            Frame
        ): (Path.WriteChannel[Async], () => Unit < Async, Path.ChannelCloseHandle) <
            (Async & Abort[FileWriteException | FileStructureException]) =
            Sync.defer {
                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                def recordCloseUnsafe()(using AllowUnsafe): Unit =
                    if closed.compareAndSet(false, true) then Sync.Unsafe.evalOrThrow(closes.incrementAndGet.unit)
                def recordClose: Unit < Sync =
                    Sync.defer(closed.compareAndSet(false, true)).map { first =>
                        if first then closes.incrementAndGet.unit else ()
                    }
                def capture(underlying: Path.ChannelCloseHandle): Unit =
                    onAcquire(new Path.ChannelCloseHandle:
                        def close()(using AllowUnsafe): Unit =
                            underlying.close()
                            recordCloseUnsafe())
                temporary.set(Present(path)).andThen(base.openWriteChannelUnscoped(path, open, capture)).map {
                    (channel, release, closeHandle) =>
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
            }

        override private[kyo] def tempFileHandle(path: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(path)
            new Path.TempFileHandle:
                def path: Path = handle.path
                def remove()(using AllowUnsafe): Unit =
                    handle.remove()
                    Sync.Unsafe.evalOrThrow(removals.incrementAndGet.unit.andThen(cleaned.release))
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
        cleaned: Latch,
        removals: AtomicInt
    ) extends FileSystem.Write[Async]:
        export base.{openWriteChannelUnscoped as _, siblingTemporary as _, tempFileHandle as _, *}

        override private[kyo] def openWriteChannelUnscoped(
            path: Path,
            open: FileSystem.WriteOpen,
            onAcquire: Path.ChannelCloseHandle => Unit
        )(using
            Frame
        ): (Path.WriteChannel[Async], () => Unit < Async, Path.ChannelCloseHandle) <
            (Async & Abort[FileWriteException | FileStructureException]) =
            temporary.set(Present(path)).andThen(base.openWriteChannelUnscoped(path, open, onAcquire)).map { (channel, release, close) =>
                (channel, () => ready.release.andThen(hold.await).andThen(release()), close)
            }

        override private[kyo] def tempFileHandle(path: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(path)
            new Path.TempFileHandle:
                def path: Path = handle.path
                def remove()(using AllowUnsafe): Unit =
                    handle.remove()
                    Sync.Unsafe.evalOrThrow(removals.incrementAndGet.unit.andThen(cleaned.release))
            end new
        end tempFileHandle

        override private[kyo] def siblingTemporary(target: Path, onAcquire: Path.TempFileHandle => Unit)(using
            Frame
        ): Path.TempFileHandle < (Async & Abort[FileWriteException | FileStructureException]) =
            FileSystem.siblingTemporary[Async](this, target, onAcquire)
    end ReleaseInterrupting

    final private class ClosePanicking(
        base: FileSystem.Write[Async],
        ready: Latch,
        hold: Latch,
        temporary: AtomicRef[Maybe[Path]],
        closes: AtomicInt,
        cleaned: Latch,
        removals: AtomicInt,
        closeFailure: Throwable
    ) extends FileSystem.Write[Async]:
        export base.{durableReplace as _, openWriteChannelUnscoped as _, tempFileHandle as _, *}

        override private[kyo] def openWriteChannelUnscoped(
            path: Path,
            open: FileSystem.WriteOpen,
            onAcquire: Path.ChannelCloseHandle => Unit
        )(using
            Frame
        ): (Path.WriteChannel[Async], () => Unit < Async, Path.ChannelCloseHandle) <
            (Async & Abort[FileWriteException | FileStructureException]) =
            def wrapClose(rawClose: Path.ChannelCloseHandle): Path.ChannelCloseHandle =
                new Path.ChannelCloseHandle:
                    def close()(using AllowUnsafe): Unit =
                        rawClose.close()
                        Sync.Unsafe.evalOrThrow(closes.incrementAndGet.unit)
                        throw closeFailure
                    end close
            temporary.set(Present(path)).andThen(base.openWriteChannelUnscoped(path, open, close => onAcquire(wrapClose(close))))
                .map { (channel, release, rawClose) =>
                    val wrapped = new Path.WriteChannel[Async]:
                        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (Async & Abort[FileWriteException]) =
                            ready.release.andThen(hold.await).andThen(channel.writeAt(position, bytes))
                        def sync(metadata: Boolean)(using Frame): Unit < (Async & Abort[FileWriteException]) = channel.sync(metadata)
                        def truncate(size: Long)(using Frame): Unit < (Async & Abort[FileWriteException])    = channel.truncate(size)
                    val close = wrapClose(rawClose)
                    (wrapped, release, close)
                }
        end openWriteChannelUnscoped

        override private[kyo] def tempFileHandle(path: Path)(using Frame): Path.TempFileHandle =
            val handle = base.tempFileHandle(path)
            new Path.TempFileHandle:
                def path: Path = handle.path
                def remove()(using AllowUnsafe): Unit =
                    handle.remove()
                    Sync.Unsafe.evalOrThrow(removals.incrementAndGet.unit.andThen(cleaned.release))
            end new
        end tempFileHandle

        override def durableReplace(target: Path, bytes: Span[Byte])(using
            Frame
        ): Unit < (Async & Abort[FileReadException | FileWriteException | FileStructureException]) =
            FileSystem.durableReplace[Async](this, target, bytes)
    end ClosePanicking

    "durable replacement writes and persists bytes" in {
        for
            (fs, root) <- hostFileSystem("kyo-durable-persist")
            target = root / "durability" / "target.bin"
            bytes  = Span.from(Array[Byte](1, 2, 3, 4))
            _      <- replaceOnHost(fs, target, bytes)
            actual <- fs.readBytes(target)
        yield assert(actual.toArrayUnsafe.sameElements(bytes.toArrayUnsafe))
    }

    "durable replacement performs the persistence protocol in order" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-ordered")
            _            <- base.mkDir(root / "durability")
            fs     = new Recording(base)
            target = root / "durability" / "ordered.bin"
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
                Event.SyncDirectory(root / "durability")
            )
        )
    }

    "durable replacement persists each newly created parent directory entry" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-parents")
            fs     = new Recording(base)
            target = root / "new-parent" / "new-child" / "target.bin"
            bytes  = Span(1.toByte, 2.toByte, 3.toByte)
            _      <- fs.durableReplace(target, bytes)
            actual <- base.readBytes(target)
            directories = fs.events.collect { case Event.SyncDirectory(path) => path }
        yield
            assert(actual.is(bytes))
            assert(directories == Chunk(root / "new-parent" / "new-child", root / "new-parent", root))
    }

    "durability operations accept a target near the filename length limit" in {
        for
            (fs, root) <- hostFileSystem("kyo-durable-long-name")
            target = root / ("a" * 250)
            bytes  = Span(1.toByte, 2.toByte)
            _         <- replaceOnHost(fs, target, bytes)
            actual    <- fs.readBytes(target)
            temporary <- Scope.run(Path.runWith(fs)(target.siblingTemporary))
            remains   <- fs.exists(temporary)
        yield
            assert(actual.is(bytes))
            assert(temporary.parent == target.parent)
            assert(!remains)
    }

    Chunk("missing", "missing/child").foreach { suffix =>
        s"directory synchronization preserves a missing path failure for $suffix" in {
            for
                (fs, root) <- hostFileSystem("kyo-durable-sync-missing")
                path = root / suffix
                result <- Abort.run(fs.syncDirectory(path))
            yield assert(result == Result.fail(FileNotFoundException(path)), s"Directory synchronization result: $result")
        }
    }

    "sibling temporary accepts a target near the filename length limit" in {
        for
            (fs, root) <- hostFileSystem("kyo-durable-sibling-long-name")
            target = root / ("a" * 250)
            temporary <- Scope.run(Path.runWith(fs)(target.siblingTemporary))
            remains   <- fs.exists(temporary)
        yield
            assert(temporary.parent == target.parent)
            assert(!remains)
    }

    "directory synchronization rejects a regular file" in {
        for
            (fs, root) <- hostFileSystem("kyo-durable-sync-file")
            path = root / "file"
            _      <- fs.mkFile(path)
            result <- Abort.run[FileSystemException](fs.syncDirectory(path))
        yield assert(result == Result.fail(FileNotADirectoryException(path)))
    }

    Chunk("write", "sync-file", "move").foreach { failedStep =>
        s"failure at $failedStep stops later steps and removes the temporary" in {
            for
                (base, root) <- hostFileSystem(s"kyo-durable-fail-$failedStep")
                fs     = new Recording(base, Present(failedStep))
                target = root / "durability" / s"$failedStep.bin"
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
            (base, root) <- hostFileSystem("kyo-durable-sync-dir-fail")
            fs     = new Recording(base, Present("sync-directory"))
            target = root / "durability" / "uncertain.bin"
            _ <- base.writeBytes(target, Span.from(Array[Byte](9)), Path.WriteOptions())
            result <- Abort.run[FileReadException | FileWriteException | FileStructureException](
                fs.durableReplace(target, Span.from(Array[Byte](1, 2)))
            )
            actual <- base.readBytes(target)
        yield assert(result.isFailure && actual.toArrayUnsafe.sameElements(Array[Byte](1, 2)))
    }

    "interruption at acquisition still closes and removes the durable temporary" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-acquire-interrupt")
            done         <- Latch.init(1)
            primary = new RuntimeException("acquisition interrupted")
            fs      = new Recording(base, interruptAfterOpen = Present(primary), cleaned = Present(done))
            target  = root / "target.bin"
            fiber <- Fiber.initUnscoped(fs.durableReplace(target, Span(1.toByte)))
            // An outer Sync.ensure can run before the resource finalizer on interruption.
            // Wait for removal itself before inspecting the directory and recorded events.
            _       <- done.await
            result  <- fiber.poll
            entries <- base.list(root)
        yield
            assert(result.contains(Result.Panic(primary)))
            assert(entries.isEmpty)
            assert(fs.events.count { case Event.Close(_) => true; case _ => false } == 1)
            assert(fs.events.count { case Event.Remove(_) => true; case _ => false } == 1)
    }

    "interruption during sibling ownership transfer still removes the temporary" in {
        for
            (base, root) <- hostFileSystem("kyo-sibling-transfer-interrupt")
            done         <- Latch.init(1)
            primary = new RuntimeException("transfer interrupted")
            fs      = new Recording(base, interruptAfterTransfer = Present(primary))
            fiber <- Fiber.initUnscoped {
                Scope.run {
                    Scope.ensure(done.release).andThen(Path.runWith(fs)((root / "target.bin").siblingTemporary))
                }
            }
            _       <- done.await
            result  <- fiber.poll
            entries <- base.list(root)
        yield
            assert(result.contains(Result.Panic(primary)))
            assert(entries.isEmpty)
            assert(fs.events.count { case Event.Remove(_) => true; case _ => false } == 1)
    }

    "sibling acquisition after scope closure removes the late temporary" in {
        for
            (base, root) <- hostFileSystem("kyo-sibling-late-acquire")
            ready        <- Latch.init(1)
            hold         <- Latch.init(1)
            temporary    <- AtomicRef.init[Maybe[Path]](Absent)
            cleaned      <- Latch.init(1)
            removals     <- AtomicInt.init(0)
            fs = new ReleaseInterrupting(base.asInstanceOf[FileSystem.Write[Async]], ready, hold, temporary, cleaned, removals)
            fiber <- Scope.run {
                Fiber.initUnscoped(Path.runWith(fs)((root / "target.bin").siblingTemporary)).map { fiber =>
                    ready.await.andThen(fiber)
                }
            }
            _      <- hold.release
            path   <- fiber.get
            exists <- base.exists(path)
            count  <- removals.get
        yield
            assert(!exists)
            assert(count == 1)
    }

    "durable temporary names never expose an absent target before replacement" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-reserved-name")
            ready        <- Latch.init(1)
            hold         <- Latch.init(1)
            temporary    <- AtomicRef.init[Maybe[Path]](Absent)
            closes       <- AtomicInt.init(0)
            cleaned      <- Latch.init(1)
            removals     <- AtomicInt.init(0)
            fs     = new Interrupting(base.asInstanceOf[FileSystem.Write[Async]], ready, hold, temporary, closes, cleaned, removals)
            target = root / ".kyo-durable-0"
            fiber   <- Fiber.initUnscoped(fs.durableReplace(target, Span(1.toByte)))
            _       <- ready.await
            visible <- base.exists(target)
            _       <- fiber.interrupt
            _       <- cleaned.await
        yield assert(!visible)
    }

    "sibling temporary names never create the target itself" in {
        for
            (fs, root) <- hostFileSystem("kyo-sibling-reserved-name")
            target = root / ".kyo-temporary-0"
            observed <- Scope.run {
                Path.runWith(fs)(target.siblingTemporary).map { temporary =>
                    fs.exists(target).map(visible => (temporary, visible))
                }
            }
        yield
            assert(observed._1 != target)
            assert(!observed._2)
    }

    Chunk("child", "child/grandchild").foreach { suffix =>
        s"directory synchronization preserves a non-directory ancestor failure for $suffix" in {
            for
                (fs, root) <- hostFileSystem("kyo-durable-sync-ancestor-file")
                file = root / "file"
                path = file / suffix
                _      <- fs.mkFile(file)
                result <- Abort.run[FileSystemException](fs.syncDirectory(path))
            yield assert(result == Result.fail(FileNotADirectoryException(path)), s"Directory synchronization result: $result")
        }
    }

    "interruption closes the channel and removes the temporary exactly once" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-interrupt")
            ready        <- Latch.init(1)
            hold         <- Latch.init(1)
            temporary    <- AtomicRef.init[Maybe[Path]](Absent)
            closes       <- AtomicInt.init(0)
            removals     <- AtomicInt.init(0)
            cleaned      <- Latch.init(1)
            // Safe effect widening for the test double; FileSystem is invariant because its channels consume S.
            asyncBase = base.asInstanceOf[FileSystem.Write[Async]]
            fs        = new Interrupting(asyncBase, ready, hold, temporary, closes, cleaned, removals)
            target    = root / "durability" / "interrupted.bin"
            fiber <- Fiber.initUnscoped {
                fs.durableReplace(target, Span.from(Array[Byte](1, 2, 3)))
            }
            _           <- ready.await
            interrupted <- fiber.interrupt
            _           <- cleaned.await
            temp        <- temporary.get.map(_.get)
            exists      <- base.exists(temp)
            closeCount  <- closes.get
            removeCount <- removals.get
        yield assert(interrupted && !exists && closeCount == 1 && removeCount == 1)
    }

    "interruption while closing a sibling temporary still removes it exactly once" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-release-interrupt")
            ready        <- Latch.init(1)
            hold         <- Latch.init(1)
            temporary    <- AtomicRef.init[Maybe[Path]](Absent)
            removals     <- AtomicInt.init(0)
            cleaned      <- Latch.init(1)
            asyncBase = base.asInstanceOf[FileSystem.Write[Async]]
            fs        = new ReleaseInterrupting(asyncBase, ready, hold, temporary, cleaned, removals)
            fiber <- Fiber.initUnscoped {
                Scope.run(Path.runWith(fs)((root / "durability" / "release-interrupted.bin").siblingTemporary))
            }
            _           <- ready.await
            interrupted <- fiber.interrupt
            _           <- cleaned.await
            temp        <- temporary.get.map(_.get)
            exists      <- base.exists(temp)
            removeCount <- removals.get
        yield assert(interrupted && !exists && removeCount == 1)
    }

    "a close panic still removes the durable temporary and preserves the interruption panic" in {
        for
            (base, root) <- hostFileSystem("kyo-durable-close-panic")
            ready        <- Latch.init(1)
            hold         <- Latch.init(1)
            temporary    <- AtomicRef.init[Maybe[Path]](Absent)
            closes       <- AtomicInt.init(0)
            removals     <- AtomicInt.init(0)
            cleaned      <- Latch.init(1)
            primary    = new RuntimeException("primary interruption")
            closeError = new RuntimeException("close failure")
            asyncBase  = base.asInstanceOf[FileSystem.Write[Async]]
            fs         = new ClosePanicking(asyncBase, ready, hold, temporary, closes, cleaned, removals, closeError)
            fiber <- Fiber.initUnscoped {
                fs.durableReplace(root / "durability" / "close-panic.bin", Span.from(Array[Byte](1)))
            }
            _           <- ready.await
            interrupted <- fiber.interrupt(Result.Panic(primary))
            _           <- cleaned.await
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
            (fs, root) <- hostFileSystem("kyo-durable-sibling-scoped")
            observed <- Scope.run {
                Path.runWith(fs) {
                    val target = root / "durability" / "scoped.bin"
                    target.siblingTemporary.map { temporary =>
                        temporary.exists.map(exists => (target.parent, temporary.parent, temporary, exists))
                    }
                }
            }
            (targetParent, temporaryParent, temporary, existedInside) = observed
            existsAfter <- fs.exists(temporary)
        yield assert(targetParent == temporaryParent && existedInside && !existsAfter)
    }

    "host durable replacement synchronizes the current directory for a relative target" in {
        Random.nextStringAlphanumeric(24).map { id =>
            val target = Path(s".kyo-durable-relative-$id.bin")
            val bytes  = Span.from(Array[Byte](7, 8, 9))
            // Unsafe: removes only this test's uniquely named relative file before and after the assertion.
            val cleanup = Sync.Unsafe.defer(discard(target.unsafe.remove()))
            cleanup.andThen {
                Sync.ensure(cleanup) {
                    replaceOnHost(FileSystem.host, target, bytes, directorySyncSupported = !kyo.internal.Platform.isWindows)
                        .andThen(FileSystem.host.readBytes(target))
                        .map(actual => assert(actual.is(bytes)))
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
