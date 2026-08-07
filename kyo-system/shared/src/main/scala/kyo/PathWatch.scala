package kyo

import kyo.kernel.ArrowEffect

/** Capability required to acquire filesystem watchers.
  *
  * A program suspends this capability through [[Path.openWatcher]].
  * [[Path.runWatchWith]] interprets it with an explicit watch backend,
  * while [[Path.runWatch]] uses the backend selected in [[Local]].
  * Watchers are scope managed and emit their changes asynchronously.
  *
  * This capability is separate from [[PathRead]] and [[PathWrite]] so
  * backends can expose observation independently of reading and writing.
  */
sealed trait PathWatch extends ArrowEffect[[A] =>> Path.WatchOp[A], Id]

/** Selects how deeply a watcher observes a directory.
  *
  * [[Immediate]] observes only direct children of the watched root.
  * [[Recursive]] also observes descendants at every depth.
  * The watched root itself is not passed through the glob filter.
  * Its disappearance is instead reported as [[PathChange.Invalidated]].
  *
  * Depth is applied before glob matching, and paths emitted by either
  * mode retain their complete backend path.
  */
enum WatchDepth derives CanEqual:
    case Immediate
    case Recursive
end WatchDepth

/** Selects the case policy used when matching a watch glob.
  *
  * [[FileSystemDefault]] delegates to the backend's native policy.
  * [[Sensitive]] compares every glob component case sensitively.
  * [[Insensitive]] compares every glob component without case.
  *
  * This setting changes only event selection. It does not rewrite
  * the paths contained in emitted [[PathChange]] values.
  */
enum MatchCase derives CanEqual:
    case FileSystemDefault
    case Sensitive
    case Insensitive
end MatchCase

/** A normalized filesystem change emitted by a [[Path.Watcher]].
  *
  * Creation, modification, and removal events identify one path.
  * A move identifies both its former and current paths. Moves crossing
  * a watch filter boundary are normalized to removal or creation.
  * [[Overflow]] reports that bounded event delivery lost detail, while
  * [[Invalidated]] reports that the watched root is no longer usable.
  *
  * Paths use the namespace of the filesystem that acquired the watcher.
  */
enum PathChange derives CanEqual:
    case Created(path: Path)
    case Modified(path: Path)
    case Removed(path: Path)
    case Moved(from: Path, to: Path)
    case Overflow(root: Path)
    case Invalidated(root: Path)
end PathChange

/** Configures filesystem watching and event selection.
  *
  * `depth` controls traversal below the watched root. `glob` is matched
  * against paths relative to that root using `caseSensitivity`.
  * `capacity` bounds pending changes and must be positive. When it is
  * exceeded, queued detail is replaced by [[PathChange.Overflow]].
  * `followLinks` controls whether linked directories and their targets
  * participate in observation.
  *
  * The defaults observe direct children, match all names using the
  * backend's case policy, retain 256 pending changes, and do not follow links.
  */
final case class WatchOptions(
    depth: WatchDepth = WatchDepth.Immediate,
    glob: Glob = Glob.all,
    caseSensitivity: MatchCase = MatchCase.FileSystemDefault,
    capacity: Int = 256,
    followLinks: Boolean = false
) derives CanEqual

private[kyo] object PathWatch:

    private val pollInterval = 5.millis

    final private case class Snapshot(directory: Boolean, stat: Path.PathStat, contentHash: Int, identity: Maybe[String])
        derives CanEqual
    final private case class ScanPanic(error: Throwable)

    private def watchFailure(root: Path, error: FileSystemException)(using Frame): FileWatchException =
        error match
            case watch: FileWatchException => watch
            case other                     => FileIOException(root, FileSystemOperation.Watch, other)

    private def scan[S](service: FileSystem.Read[S], root: Path, options: WatchOptions)(using
        Frame
    ): Result[FileWatchException, Map[Path, Snapshot]] < S =
        def hash(bytes: Span[Byte]): Int =
            var value = 1
            var index = 0
            while index < bytes.size do
                value = 31 * value + bytes(index)
                index += 1
            value
        end hash

        def entry(path: Path): Snapshot < (S & Abort[FileReadException]) =
            service.isSymbolicLink(path).map { symbolic =>
                if symbolic && !options.followLinks then Snapshot(false, Path.PathStat(0L, 0L), 0, Absent)
                else
                    service.isDirectory(path).map { directory =>
                        service.stat(path).map { stat =>
                            service.stableIdentity(path).map { identity =>
                                if directory then Snapshot(true, stat, 0, identity)
                                else service.readBytes(path).map(bytes => Snapshot(false, stat, hash(bytes), identity))
                            }
                        }
                    }
            }

        def children(path: Path, recursive: Boolean, visited: Set[Path]): Map[Path, Snapshot] <
            (S & Abort[FileReadException | FileStructureException | ScanPanic]) =
            service.list(path).map { listed =>
                Kyo.foldLeft(listed)(Map.empty[Path, Snapshot]) { (acc, child) =>
                    val observe = entry(child).map { childSnapshot =>
                        val next = acc.updated(child, childSnapshot)
                        if recursive && childSnapshot.directory then
                            service.isSymbolicLink(child).map { symbolic =>
                                if symbolic && !options.followLinks then next
                                else if options.followLinks then
                                    service.realPath(child).map { real =>
                                        if visited.contains(real) then next
                                        else children(child, recursive, visited + real).map(next ++ _)
                                    }
                                else children(child, recursive, visited).map(next ++ _)
                            }
                        else next
                        end if
                    }
                    Abort.run[FileReadException | FileStructureException | ScanPanic](observe).map {
                        case Result.Success(next) => next
                        case Result.Failure(error) =>
                            Abort.run[FileReadException](service.exists(child, options.followLinks)).map {
                                case Result.Success(false)      => acc
                                case Result.Success(true)       => Abort.fail(error)
                                case Result.Failure(checkError) => Abort.fail(checkError)
                                case Result.Panic(checkError)   => Abort.fail(ScanPanic(checkError))
                            }
                        case Result.Panic(error) => Abort.fail(ScanPanic(error))
                    }
                }
            }

        val read = service.exists(root, options.followLinks).map {
            case false => Abort.fail[FileWatchException](FileWatchInvalidatedException(root))
            case true =>
                service.isDirectory(root).map {
                    case false =>
                        Abort.fail[FileReadException](
                            FileIOException(root, FileSystemOperation.Watch, new java.io.IOException("watched path is not a directory"))
                        )
                    case true =>
                        if options.followLinks then
                            service.realPath(root).map(real => children(root, options.depth == WatchDepth.Recursive, Set(real)))
                        else children(root, options.depth == WatchDepth.Recursive, Set.empty)
                }
        }
        Abort.run[FileReadException | FileStructureException | FileWatchException | ScanPanic](read).map {
            case Result.Success(snapshot)                   => Result.Success(snapshot)
            case Result.Failure(scanPanic: ScanPanic)       => Result.Panic(scanPanic.error)
            case Result.Failure(error: FileSystemException) => Result.Failure(watchFailure(root, error))
            case Result.Panic(error)                        => Result.Panic(error)
        }
    end scan

    private def changes(before: Map[Path, Snapshot], after: Map[Path, Snapshot]): Chunk[PathChange] =
        val removed = scala.collection.mutable.ArrayBuffer.from(before.keySet.diff(after.keySet).toSeq.sortBy(_.toString))
        val created = scala.collection.mutable.ArrayBuffer.from(after.keySet.diff(before.keySet).toSeq.sortBy(_.toString))
        val moved   = scala.collection.mutable.ArrayBuffer.empty[(Path, Path)]
        var old     = 0
        while old < removed.size do
            val from     = removed(old)
            val identity = before(from).identity
            val candidates = identity.fold(Seq.empty[(Path, Int)])(id =>
                created.zipWithIndex.filter((to, _) => after(to).identity.contains(id))
            )
            val oldMatches = identity.fold(0)(id => removed.count(candidate => before(candidate).identity.contains(id)))
            if identity.isDefined && candidates.size == 1 && oldMatches == 1 then
                val (to, newIndex) = candidates.head
                moved += ((from, to))
                val _ = created.remove(newIndex)
                val _ = removed.remove(old)
            else old += 1
            end if
        end while
        val modified = before.keySet
            .intersect(after.keySet)
            .toSeq
            .sortBy(_.toString)
            .filter(path => !before(path).directory && before(path) != after(path))
        Chunk.from(moved.map(PathChange.Moved.apply)) ++
            Chunk.from(removed.map(PathChange.Removed.apply)) ++
            Chunk.from(created.map(PathChange.Created.apply)) ++
            Chunk.from(modified.map(PathChange.Modified.apply))
    end changes

    private def relative(root: Path, path: Path): Maybe[Chunk[String]] =
        val rootParts = root.parts
        val parts     = path.parts
        if parts.size < rootParts.size || parts.take(rootParts.size) != rootParts then Absent
        else Present(parts.drop(rootParts.size))
    end relative

    private def matches(root: Path, options: WatchOptions, caseSensitivity: Glob.CaseSensitivity, path: Path): Boolean =
        relative(root, path).exists { parts =>
            parts.nonEmpty &&
            (options.depth == WatchDepth.Recursive || parts.size == 1) &&
            options.glob.matches(parts, caseSensitivity)
        }

    private def select(
        root: Path,
        options: WatchOptions,
        caseSensitivity: Glob.CaseSensitivity,
        change: PathChange
    ): Maybe[PathChange] =
        def selected(path: Path): Boolean = matches(root, options, caseSensitivity, path)
        change match
            case PathChange.Created(path)  => if selected(path) then Present(change) else Absent
            case PathChange.Modified(path) => if selected(path) then Present(change) else Absent
            case PathChange.Removed(path)  => if selected(path) then Present(change) else Absent
            case PathChange.Moved(from, to) =>
                (selected(from), selected(to)) match
                    case (true, true)  => Present(change)
                    case (true, false) => Present(PathChange.Removed(from))
                    case (false, true) => Present(PathChange.Created(to))
                    case _             => Absent
            case PathChange.Overflow(_)    => Present(PathChange.Overflow(root))
            case PathChange.Invalidated(_) => Present(PathChange.Invalidated(root))
        end match
    end select

    def polling[S, S2](service: FileSystem.Read[S & Sync], root: Path, options: WatchOptions)(using
        Frame,
        Isolate[S, Sync, S2]
    ): Path.Watcher < (S & Sync & Async & Scope & Abort[FileWatchException]) =
        if options.capacity <= 0 then
            Abort.fail(FileIOException(root, FileSystemOperation.Watch, new IllegalArgumentException("watch capacity must be positive")))
        else
            service.defaultCaseSensitivity.map { defaultCase =>
                val caseSensitivity = options.caseSensitivity match
                    case MatchCase.FileSystemDefault => defaultCase
                    case MatchCase.Sensitive         => Glob.CaseSensitivity.Sensitive
                    case MatchCase.Insensitive       => Glob.CaseSensitivity.Insensitive
                scan(service, root, options).map {
                    case Result.Failure(error) => Abort.fail(error)
                    case Result.Panic(error)   => Abort.panic[FileWatchException](error)
                    case Result.Success(initial) =>
                        Channel.init[Result[FileWatchException, PathChange]](options.capacity).map { channel =>
                            AtomicInt.init(0).map { pending =>
                                AtomicBoolean.init(false).map { overflowed =>
                                    def overflow: Unit < Sync =
                                        overflowed.compareAndSet(false, true).map {
                                            case false => ()
                                            case true =>
                                                Abort.run[Closed](channel.drain).andThen {
                                                    pending.set(1).andThen {
                                                        Abort.run[Closed](channel.offer(Result.Success(PathChange.Overflow(root)))).map {
                                                            case Result.Success(true) => ()
                                                            case _                    => pending.set(0).andThen(overflowed.set(false))
                                                        }
                                                    }
                                                }
                                        }
                                    def offer(event: PathChange): Unit < Sync =
                                        overflowed.get.map {
                                            case true => ()
                                            case false =>
                                                pending.incrementAndGet.map { count =>
                                                    if count > options.capacity then overflow
                                                    else
                                                        Abort.run[Closed](channel.offer(Result.Success(event))).map {
                                                            case Result.Success(true)  => ()
                                                            case Result.Success(false) => overflow
                                                            case _                     => pending.decrementAndGet.unit
                                                        }
                                                }
                                        }
                                    def publish(events: Chunk[PathChange]): Unit < Sync =
                                        val selected = events.flatMap(change =>
                                            select(root, options, caseSensitivity, change).fold(Chunk.empty[PathChange])(Chunk(_))
                                        )
                                        Kyo.foreachDiscard(selected)(offer)
                                    end publish
                                    def fail(error: FileWatchException): Unit < Sync =
                                        Abort.run[Closed](channel.drain).andThen {
                                            pending.set(1).andThen {
                                                Abort.run[Closed](channel.offer(Result.Failure(error))).unit
                                            }
                                        }
                                    def panic(error: Throwable): Unit < Sync =
                                        Abort.run[Closed](channel.drain).andThen {
                                            pending.set(1).andThen {
                                                Abort.run[Closed](channel.offer(Result.Panic(error))).unit
                                            }
                                        }
                                    def invalidate: Unit < Sync =
                                        Abort.run[Closed](channel.drain).andThen {
                                            pending.set(1).andThen {
                                                Abort.run[Closed](channel.offer(Result.Success(PathChange.Invalidated(root)))).unit
                                            }
                                        }
                                    def terminate(error: FileWatchException): Unit < (S & Sync) =
                                        Abort.run[FileReadException](service.exists(root, options.followLinks)).map {
                                            case Result.Success(false) => invalidate
                                            case Result.Success(true) =>
                                                Abort.run[FileReadException](service.isDirectory(root)).map {
                                                    case Result.Success(false)      => invalidate
                                                    case Result.Success(true)       => fail(error)
                                                    case Result.Failure(checkError) => fail(watchFailure(root, checkError))
                                                    case Result.Panic(checkError)   => Abort.panic(checkError)
                                                }
                                            case Result.Failure(checkError) => fail(watchFailure(root, checkError))
                                            case Result.Panic(checkError)   => Abort.panic(checkError)
                                        }
                                    def poll(previous: Map[Path, Snapshot], deferredRemoval: Boolean): Unit < (S & Async) =
                                        scan(service, root, options).map {
                                            case Result.Success(current) =>
                                                val detected = changes(previous, current)
                                                val removalOnly = detected.nonEmpty && detected.forall {
                                                    case PathChange.Removed(_) => true
                                                    case _                     => false
                                                }
                                                if removalOnly && !deferredRemoval then loop(previous, true)
                                                else publish(detected).andThen(loop(current, false))
                                            case Result.Failure(error) => terminate(error)
                                            case Result.Panic(error)   => panic(error)
                                        }
                                    // Async.sleep, not Clock.sleep: the latter hands back the timer Fiber rather than
                                    // suspending on it, so discarding it would leave this loop free-running.
                                    def loop(previous: Map[Path, Snapshot], deferredRemoval: Boolean): Unit < (S & Async) =
                                        Async.sleep(pollInterval).andThen(poll(previous, deferredRemoval))
                                    // The first interval is armed here, in the acquiring fiber, rather than inside the
                                    // poll fiber. Clock.sleep is the Fiber-returning form on purpose: registering the
                                    // timer before openWatcher returns is what makes the first tick deterministic. A
                                    // timer armed inside the poll fiber would race the caller, and under a controlled
                                    // Clock an advance that lands before the fiber reaches its first sleep would set a
                                    // deadline that never fires.
                                    Clock.sleep(pollInterval).map { firstTick =>
                                        Fiber.init(firstTick.get.andThen(poll(initial, false)))
                                    }.map { _ =>
                                        new Path.Watcher:
                                            def events: Stream[PathChange, Async & Scope & Abort[FileWatchException]] =
                                                Stream:
                                                    def pull: Unit < (Emit[Chunk[PathChange]] & Async & Abort[FileWatchException]) =
                                                        Abort.run[Closed](channel.take).map {
                                                            case Result.Failure(_)   => ()
                                                            case Result.Panic(error) => Abort.panic[FileWatchException](error)
                                                            case Result.Success(result) =>
                                                                pending.decrementAndGet.andThen {
                                                                    result match
                                                                        case Result.Success(event) =>
                                                                            event match
                                                                                case PathChange.Overflow(_) =>
                                                                                    overflowed.set(
                                                                                        false
                                                                                    ).andThen(Emit.value(Chunk(event))).andThen(pull)
                                                                                case PathChange.Invalidated(_) =>
                                                                                    channel.close.andThen(Emit.value(Chunk(event)))
                                                                                case _ => Emit.value(Chunk(event)).andThen(pull)
                                                                        case Result.Failure(error) =>
                                                                            channel.close.andThen(Abort.fail(error))
                                                                        case Result.Panic(error) =>
                                                                            channel.close.andThen(Abort.panic[FileWatchException](error))
                                                                }
                                                        }
                                                    pull
                                    }
                                }
                            }
                        }
                }
            }
    end polling
end PathWatch
