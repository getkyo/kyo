package kyo

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

// The overlay's staged-journal operation record. Unrelated to the conceptual Path.WriteOp
// write-group partition of Path.Op (a private[kyo] read/write op family); despite sharing a
// base name these are different types defined in different contexts.
private[kyo] enum WriteOp:
    case WriteFile(path: Chunk[String], bytes: Span[Byte], stat: Path.PathStat)
    case WriteDirectory(path: Chunk[String], opaque: Boolean)
    case Remove(path: Chunk[String])
    // Move and Copy carry the source entry captured at stage time (`resolved`), so replay is
    // source-independent: Move replays as remove(from) then write(resolved) at to; Copy writes
    // resolved at to. No diff or partial-entry format.
    case Move(from: Chunk[String], to: Chunk[String], resolved: Path.Entry)
    case Copy(from: Chunk[String], to: Chunk[String], resolved: Path.Entry)
end WriteOp

private[kyo] object OverlayService:

    /** Upper-layer entry variants. `Entry` holds a staged file or directory; `Whiteout` marks a
      * deleted path; `OpaqueDir` marks a directory that hides all lower children.
      */
    enum Upper derives CanEqual:
        case Entry(body: Path.Entry)
        case Whiteout
        case OpaqueDir(stat: Path.PathStat)
    end Upper

    /** The overlay's mutable state: the upper map of staged entries, the append-only journal of
      * staged write operations (consumed by commit), and the read-set of lower observations
      * (stamps recorded the first time a lower path is read through the overlay).
      */
    final case class OverlayState(
        upper: Map[Chunk[String], Upper],
        journal: Chunk[WriteOp],
        readSet: Map[Chunk[String], Path.Stamp]
    )

    object OverlayState:
        val empty: OverlayState = OverlayState(Map.empty, Chunk.empty, Map.empty)

    def init[S](lower: Path.Service[S])(using Frame): Path.Service.Overlay[S] < (Sync & Scope) =
        Scope.acquireRelease(AtomicRef.init(OverlayState.empty)) { ref =>
            // Unsafe: resets staged state on scope exit (auto-rollback); no Sync dispatch needed.
            Sync.Unsafe.defer { discard(ref.unsafe.updateAndGet(_ => OverlayState.empty)) }
        }.map(ref => new OverlayService(lower, ref))

end OverlayService

/** Copy-on-write overlay service. Reads check the upper layer first; writes stage in the upper
  * layer and append to the journal without touching lower. The journal is replayed onto lower on
  * commit. The read-set records a Path.Stamp for each lower path on its first observation; commit
  * validates these stamps against the live lower before replaying.
  *
  * The four structural components are: lower (the constructor field), upper (Map in OverlayState),
  * journal (Chunk[WriteOp] in OverlayState), and readSet (Map[Chunk[String], Path.Stamp] in
  * OverlayState). All state changes go through the CAS modify loop so concurrent access is safe.
  *
  * Scope-managed: the enclosing Scope bounds its lifetime; on scope exit the staged state is
  * reset (auto-rollback). disposition is ManualCommit.
  */
final private[kyo] class OverlayService[S](lower: Path.Service[S], state: AtomicRef[OverlayService.OverlayState])(using Frame)
    extends Path.Service.Overlay[S]:
    import OverlayService.*

    val disposition: Path.Disposition = Path.Disposition.ManualCommit

    // CAS modify loop for operations that may fail with FileException.
    private def modify[A](op: OverlayState => Result[FileException, (OverlayState, A)]): A < (Sync & Abort[FileException]) =
        Loop(()) { _ =>
            state.get.map { cur =>
                Abort.get(op(cur)).map { (next, v) =>
                    state.compareAndSet(cur, next).map {
                        case true  => Loop.done(v)
                        case false => Loop.continue(())
                    }
                }
            }
        }

    // Reads the current snapshot and presents it as `S & Abort[FileException]` so callers can
    // sequence it with lower calls without leaking an extra Sync into their effect row.
    // Safe because S = Sync at the only instantiation site (OverlayService.init).
    private def stateGet: OverlayState < (S & Abort[FileException]) =
        state.get.asInstanceOf[OverlayState < (S & Abort[FileException])]

    // Snapshot access: reads state then runs f; the cast keeps the declared effect row clean.
    private def withState[A](f: OverlayState => A < (S & Abort[FileException])): A < (S & Abort[FileException]) =
        stateGet.map(f)

    // Pure-state modify: never fails. Declared as `< (S & Abort[FileException])` so stamp
    // helpers and write methods share the same effect row as lower calls; the asInstanceOf is safe
    // because S = Sync at the only instantiation site.
    private def modifyPure(op: OverlayState => OverlayState): Unit < (S & Abort[FileException]) =
        (Loop(()) { _ =>
            state.get.map { cur =>
                state.compareAndSet(cur, op(cur)).map {
                    case true  => Loop.done(())
                    case false => Loop.continue(())
                }
            }
        }: Unit < Sync).asInstanceOf[Unit < (S & Abort[FileException])]

    // Record a lower observation in the read-set for a file. Idempotent: existing stamps are kept.
    private def stampFile(parts: Chunk[String], stat: Path.PathStat): Unit < (S & Abort[FileException]) =
        modifyPure { s =>
            if s.readSet.contains(parts) then s
            else
                val stamp = Path.Stamp(Path.Stamp.Kind.File, Present(stat.sizeBytes.bytes), Present(stat.lastModifiedMs), Absent)
                s.copy(readSet = s.readSet.updated(parts, stamp))
        }

    private def stampDir(parts: Chunk[String], stat: Path.PathStat): Unit < (S & Abort[FileException]) =
        modifyPure { s =>
            if s.readSet.contains(parts) then s
            else
                val stamp = Path.Stamp(Path.Stamp.Kind.Directory, Absent, Present(stat.lastModifiedMs), Absent)
                s.copy(readSet = s.readSet.updated(parts, stamp))
        }

    private def stampAbsent(parts: Chunk[String]): Unit < (S & Abort[FileException]) =
        modifyPure { s =>
            if s.readSet.contains(parts) then s
            else s.copy(readSet = s.readSet.updated(parts, Path.Stamp(Path.Stamp.Kind.Absent, Absent, Absent, Absent)))
        }

    // Stamp a lower observation using stat + isRegularFile to determine the kind.
    private def stampLower(parts: Chunk[String]): Unit < (S & Abort[FileException]) =
        val path = pathFrom(parts)
        lower.exists(path).map { found =>
            if !found then stampAbsent(parts)
            else
                lower.stat(path).map { stat =>
                    lower.isRegularFile(path).map { isFile =>
                        if isFile then stampFile(parts, stat) else stampDir(parts, stat)
                    }
                }
        }
    end stampLower

    // Reconstruct a Path from its parts (parallel to Path.parts decomposition).
    private def pathFrom(parts: Chunk[String]): Path =
        if parts.isEmpty then Path("")
        else parts.tail.foldLeft(Path(parts.head))((acc, seg) => acc / seg)

    // --- Inspection ---

    def exists(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout) => false
                case Some(_)              => true
                case None =>
                    lower.exists(path).map { found =>
                        if !found then stampAbsent(path.parts).andThen(false)
                        else
                            lower.stat(path).map { stat =>
                                lower.isRegularFile(path).map { isFile =>
                                    (if isFile then stampFile(path.parts, stat) else stampDir(path.parts, stat)).andThen(true)
                                }
                            }
                    }
        }

    def exists(path: Path, followLinks: Boolean): Boolean < (S & Abort[FileException]) = exists(path)

    def isDirectory(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout)                       => false
                case Some(Upper.OpaqueDir(_))                   => true
                case Some(Upper.Entry(Path.Entry.Directory(_))) => true
                case Some(Upper.Entry(Path.Entry.File(_, _)))   => false
                case None =>
                    lower.isDirectory(path).map { isDir =>
                        lower.stat(path).map { stat =>
                            (if isDir then stampDir(path.parts, stat) else stampFile(path.parts, stat)).andThen(isDir)
                        }
                    }
        }

    def isRegularFile(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout)                       => false
                case Some(Upper.OpaqueDir(_))                   => false
                case Some(Upper.Entry(Path.Entry.Directory(_))) => false
                case Some(Upper.Entry(Path.Entry.File(_, _)))   => true
                case None =>
                    lower.isRegularFile(path).map { isFile =>
                        lower.stat(path).map { stat =>
                            (if isFile then stampFile(path.parts, stat) else stampDir(path.parts, stat)).andThen(isFile)
                        }
                    }
        }

    def isSymbolicLink(path: Path): Boolean < (S & Abort[FileException]) = false

    def realPath(path: Path): Path < (S & Abort[FileException]) = path

    // --- Reads ---

    def read(path: Path): String < (S & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, StandardCharsets.UTF_8))

    def read(path: Path, charset: Charset): String < (S & Abort[FileException]) =
        readBytes(path).map(b => new String(b.toArrayUnsafe, charset))

    def readBytes(path: Path): Span[Byte] < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(bytes, _))) => bytes
                case Some(_)                                      => Abort.fail(FileNotFoundException(path))
                case None =>
                    lower.readBytes(path).map { bytes =>
                        lower.stat(path).map { stat =>
                            stampFile(path.parts, stat).andThen(bytes)
                        }
                    }
        }

    def readLines(path: Path): Chunk[String] < (S & Abort[FileException]) =
        read(path).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))

    def readLines(path: Path, charset: Charset): Chunk[String] < (S & Abort[FileException]) =
        read(path, charset).map(c => Chunk.from(c.split("\n", -1).toIndexedSeq))

    def size(path: Path): Long < (S & Abort[FileException]) = readBytes(path).map(_.size.toLong)

    def stat(path: Path): Path.PathStat < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(_, ps)))   => ps
                case Some(Upper.Entry(Path.Entry.Directory(ps))) => ps
                case Some(Upper.OpaqueDir(ps))                   => ps
                case Some(Upper.Whiteout)                        => Abort.fail(FileNotFoundException(path))
                case None =>
                    lower.stat(path).map { ps =>
                        lower.isRegularFile(path).map { isFile =>
                            (if isFile then stampFile(path.parts, ps) else stampDir(path.parts, ps)).andThen(ps)
                        }
                    }
        }

    // --- Read handles ---

    def openRead(path: Path): Path.ReadHandle < (S & Abort[FileException]) =
        readBytes(path).map(bytes => InMemoryHandles.read(bytes))

    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (S & Abort[FileException]) =
        read(path, charset).map(text => InMemoryHandles.lines(text))

    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (S & Abort[FileException]) =
        walkCollect(path, maxDepth).map { paths =>
            new Path.WalkHandle:
                private val it                             = paths.iterator
                def next()(using AllowUnsafe): Maybe[Path] = if it.hasNext then Maybe(it.next()) else Maybe.empty
                def close()(using AllowUnsafe): Unit       = ()
        }

    // Preorder traversal through the overlay view for openWalk.
    private def walkCollect(path: Path, maxDepth: Int): Chunk[Path] < (S & Abort[FileException]) =
        if maxDepth <= 0 then Chunk.empty
        else
            list(path).map { children =>
                children.foldLeft[Chunk[Path] < (S & Abort[FileException])](Chunk.empty) { (accKyo, child) =>
                    accKyo.map { acc =>
                        isDirectory(child).map { isDir =>
                            if isDir then walkCollect(child, maxDepth - 1).map(sub => acc.appended(child).appendedAll(sub))
                            else acc.appended(child)
                        }
                    }
                }
            }

    // --- List ---

    def list(path: Path): Chunk[Path] < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout)                     => Abort.fail(FileNotFoundException(path))
                case Some(Upper.Entry(Path.Entry.File(_, _))) => Abort.fail(FileNotADirectoryException(path))
                case maybeOpaque =>
                    val isOpaque = maybeOpaque.exists {
                        case Upper.OpaqueDir(_) => true
                        case _                  => false
                    }

                    // Collect the set of segments already covered in upper for this directory.
                    val upperSegs: Set[String] = s.upper.keysIterator.collect {
                        case parts if parts.size == path.parts.size + 1 && parts.startsWith(path.parts) =>
                            parts.last
                    }.toSet

                    // Collect visible upper children (non-Whiteout).
                    val upperVisible: List[Path] = s.upper.collect {
                        case (parts, v) if parts.size == path.parts.size + 1 && parts.startsWith(path.parts) && v != Upper.Whiteout =>
                            pathFrom(parts)
                    }.toList

                    val lowerKyo: Chunk[Path] < (S & Abort[FileException]) =
                        if isOpaque then Chunk.empty
                        else
                            lower.exists(path).map { exists =>
                                if !exists then Chunk.empty[Path]
                                else
                                    lower.stat(path).map { stat =>
                                        stampDir(path.parts, stat).andThen {
                                            lower.list(path).map { lp =>
                                                // Drop lower children that have any upper entry (Entry, Whiteout, or OpaqueDir).
                                                lp.filter(p => !upperSegs.contains(p.parts.last))
                                            }
                                        }
                                    }
                            }

                    lowerKyo.map { lowerPaths =>
                        val combined = (lowerPaths.toSeq ++ upperVisible).distinctBy(_.parts).sortBy(_.parts.last)
                        Chunk.from(combined)
                    }
        }

    def list(path: Path, glob: String): Chunk[Path] < (S & Abort[FileException]) =
        list(path).map(_.filter(p => p.name.exists(InMemoryHandles.matchesGlob(_, glob))))

    // --- Writes ---

    def write(path: Path, value: String, createFolders: Boolean): Unit < (S & Abort[FileException]) =
        writeBytes(path, Span.from(value.getBytes(StandardCharsets.UTF_8)), createFolders)

    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        val stat = Path.PathStat(0L, value.size.toLong)
        modifyPure { s =>
            s.copy(
                upper = s.upper.updated(path.parts, Upper.Entry(Path.Entry.File(value, stat))),
                journal = s.journal.appended(WriteOp.WriteFile(path.parts, value, stat))
            )
        }
    end writeBytes

    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        write(path, value.mkString("", "\n", "\n"), createFolders)

    def append(path: Path, value: String, createFolders: Boolean): Unit < (S & Abort[FileException]) =
        appendBytes(path, Span.from(value.getBytes(StandardCharsets.UTF_8)), createFolders)

    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(existing, _))) =>
                    // Already in upper: concatenate without consulting lower, no stamp needed.
                    val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                    val stat   = Path.PathStat(0L, merged.size.toLong)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(merged, stat))),
                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, merged, stat))
                        )
                    }
                case _ =>
                    // Not in upper: read lower (stamp on first observation), then stage.
                    lower.exists(path).map { found =>
                        val readLower: Span[Byte] < (S & Abort[FileException]) =
                            if !found then stampAbsent(path.parts).andThen(Span.empty[Byte])
                            else
                                lower.readBytes(path).map { existing =>
                                    lower.stat(path).map { stat => stampFile(path.parts, stat).andThen(existing) }
                                }
                        readLower.map { existing =>
                            val merged = Span.fromUnsafe(existing.toArrayUnsafe ++ value.toArrayUnsafe)
                            val stat   = Path.PathStat(0L, merged.size.toLong)
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(merged, stat))),
                                    journal = cur.journal.appended(WriteOp.WriteFile(path.parts, merged, stat))
                                )
                            }
                        }
                    }
        }

    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (S & Abort[FileException]) =
        append(path, value.mkString("", "\n", "\n"), createFolders)

    def truncate(path: Path, size: Long): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(bytes, _))) =>
                    val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(size.toInt))
                    val stat = Path.PathStat(0L, kept.size.toLong)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(kept, stat))),
                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, kept, stat))
                        )
                    }
                case Some(_) => Abort.fail(FileNotFoundException(path))
                case None =>
                    lower.readBytes(path).map { bytes =>
                        lower.stat(path).map { lStat =>
                            stampFile(path.parts, lStat).andThen {
                                val kept = Span.fromUnsafe(bytes.toArrayUnsafe.take(size.toInt))
                                val stat = Path.PathStat(0L, kept.size.toLong)
                                modifyPure { cur =>
                                    cur.copy(
                                        upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(kept, stat))),
                                        journal = cur.journal.appended(WriteOp.WriteFile(path.parts, kept, stat))
                                    )
                                }
                            }
                        }
                    }
        }

    def setLastModified(path: Path, epochMs: Long): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(Path.Entry.File(bytes, stat))) =>
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(bytes, ns))),
                            journal = cur.journal.appended(WriteOp.WriteFile(path.parts, bytes, ns))
                        )
                    }
                case Some(Upper.Entry(Path.Entry.Directory(stat))) =>
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.Directory(ns))),
                            journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = false))
                        )
                    }
                case Some(Upper.OpaqueDir(stat)) =>
                    val ns = stat.copy(lastModifiedMs = epochMs)
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.OpaqueDir(ns)),
                            journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = true))
                        )
                    }
                case Some(Upper.Whiteout) => Abort.fail(FileNotFoundException(path))
                case None =>
                    lower.stat(path).map { stat =>
                        lower.isRegularFile(path).map { isFile =>
                            (if isFile then stampFile(path.parts, stat) else stampDir(path.parts, stat)).andThen {
                                if isFile then
                                    lower.readBytes(path).map { bytes =>
                                        val ns = stat.copy(lastModifiedMs = epochMs)
                                        modifyPure { cur =>
                                            cur.copy(
                                                upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(bytes, ns))),
                                                journal = cur.journal.appended(WriteOp.WriteFile(path.parts, bytes, ns))
                                            )
                                        }
                                    }
                                else
                                    val ns = stat.copy(lastModifiedMs = epochMs)
                                    modifyPure { cur =>
                                        cur.copy(
                                            upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.Directory(ns))),
                                            journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = false))
                                        )
                                    }
                            }
                        }
                    }
        }

    def mkDir(path: Path): Unit < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.OpaqueDir(_))                   => () // already opaque dir
                case Some(Upper.Entry(Path.Entry.Directory(_))) => () // already a dir in upper
                case _                                          =>
                    // If lower has a directory at this path, create OpaqueDir (hides lower children).
                    // If lower has a file or absent, create a regular directory entry.
                    lower.exists(path).map { exists =>
                        if !exists then
                            stampAbsent(path.parts).andThen {
                                val st = Path.PathStat(0L, 0L)
                                modifyPure { cur =>
                                    cur.copy(
                                        upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.Directory(st))),
                                        journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = false))
                                    )
                                }
                            }
                        else
                            lower.stat(path).map { stat =>
                                lower.isDirectory(path).map { isDir =>
                                    (if isDir then stampDir(path.parts, stat) else stampFile(path.parts, stat)).andThen {
                                        // An existing lower dir (or file) gets OpaqueDir, hiding its children.
                                        val st = if isDir then stat else Path.PathStat(0L, 0L)
                                        modifyPure { cur =>
                                            cur.copy(
                                                upper = cur.upper.updated(path.parts, Upper.OpaqueDir(st)),
                                                journal = cur.journal.appended(WriteOp.WriteDirectory(path.parts, opaque = true))
                                            )
                                        }
                                    }
                                }
                            }
                    }
        }

    def mkFile(path: Path): Unit < (S & Abort[FileException]) = writeBytes(path, Span.empty[Byte], createFolders = true)

    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (S & Abort[FileException]) =
        resolveEntry(from).map { resolved =>
            withState { s =>
                val targetExists: Boolean =
                    s.upper.get(to.parts) match
                        case Some(Upper.Whiteout) => false
                        case Some(_)              => true
                        case None                 => false // lower checked separately below
                if targetExists && !replaceExisting then
                    Abort.fail(FileAlreadyExistsException(to))
                else
                    lower.exists(to).map { lowerTargetExists =>
                        if lowerTargetExists && !replaceExisting then
                            Abort.fail(FileAlreadyExistsException(to))
                        else
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(from.parts, Upper.Whiteout).updated(to.parts, Upper.Entry(resolved)),
                                    journal = cur.journal.appended(WriteOp.Move(from.parts, to.parts, resolved))
                                )
                            }
                    }
                end if
            }
        }

    def copy(
        from: Path,
        to: Path,
        followLinks: Boolean,
        replaceExisting: Boolean,
        copyAttributes: Boolean,
        createFolders: Boolean
    ): Unit < (S & Abort[FileException]) =
        resolveEntry(from).map { resolved =>
            withState { s =>
                val targetExists: Boolean =
                    s.upper.get(to.parts) match
                        case Some(Upper.Whiteout) => false
                        case Some(_)              => true
                        case None                 => false
                if targetExists && !replaceExisting then
                    Abort.fail(FileAlreadyExistsException(to))
                else
                    lower.exists(to).map { lowerTargetExists =>
                        if lowerTargetExists && !replaceExisting then
                            Abort.fail(FileAlreadyExistsException(to))
                        else
                            modifyPure { cur =>
                                cur.copy(
                                    upper = cur.upper.updated(to.parts, Upper.Entry(resolved)),
                                    journal = cur.journal.appended(WriteOp.Copy(from.parts, to.parts, resolved))
                                )
                            }
                    }
                end if
            }
        }

    // Resolve a source path to a Path.Entry, checking upper first then lower.
    // Records a stamp when reading from lower. Fails if source is Whiteout or absent.
    private def resolveEntry(path: Path): Path.Entry < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Entry(e))        => e
                case Some(Upper.OpaqueDir(stat)) => Path.Entry.Directory(stat): Path.Entry
                case Some(Upper.Whiteout)        => Abort.fail(FileNotFoundException(path))
                case None =>
                    lower.exists(path).map { found =>
                        if !found then Abort.fail(FileNotFoundException(path))
                        else
                            lower.isRegularFile(path).map { isFile =>
                                if isFile then
                                    lower.readBytes(path).map { bytes =>
                                        lower.stat(path).map { stat =>
                                            stampFile(path.parts, stat).andThen {
                                                Path.Entry.File(bytes, stat): Path.Entry
                                            }
                                        }
                                    }
                                else
                                    lower.stat(path).map { stat =>
                                        stampDir(path.parts, stat).andThen {
                                            Path.Entry.Directory(stat): Path.Entry
                                        }
                                    }
                            }
                    }
        }

    def remove(path: Path): Boolean < (S & Abort[FileException]) =
        withState { s =>
            s.upper.get(path.parts) match
                case Some(Upper.Whiteout) => false
                case Some(_) =>
                    modifyPure { cur =>
                        cur.copy(
                            upper = cur.upper.updated(path.parts, Upper.Whiteout),
                            journal = cur.journal.appended(WriteOp.Remove(path.parts))
                        )
                    }.andThen(true)
                case None =>
                    lower.exists(path).map { found =>
                        if !found then stampAbsent(path.parts).andThen(false)
                        else
                            stampLower(path.parts).andThen {
                                modifyPure { cur =>
                                    cur.copy(
                                        upper = cur.upper.updated(path.parts, Upper.Whiteout),
                                        journal = cur.journal.appended(WriteOp.Remove(path.parts))
                                    )
                                }.andThen(true)
                            }
                    }
        }

    def removeExisting(path: Path): Unit < (S & Abort[FileException]) =
        remove(path).map(existed => if existed then () else Abort.fail(FileNotFoundException(path)))

    def removeAll(path: Path): Unit < (S & Abort[FileException]) =
        remove(path).unit

    // --- Write handle ---

    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (S & Abort[FileException]) =
        withState { s =>
            val upperSeed: Maybe[Span[Byte]] =
                if append then
                    s.upper.get(path.parts) match
                        case Some(Upper.Entry(Path.Entry.File(bytes, _))) => Present(bytes)
                        case _                                            => Absent
                else Present(Span.empty[Byte])

            upperSeed match
                case Present(seed) => mkWriteHandle(path, seed)
                case Absent        =>
                    // append mode with no upper entry: seed from lower
                    lower.exists(path).map { found =>
                        if !found then stampAbsent(path.parts).andThen(mkWriteHandle(path, Span.empty[Byte]))
                        else
                            lower.readBytes(path).map { bytes =>
                                lower.stat(path).map { stat =>
                                    stampFile(path.parts, stat).andThen(mkWriteHandle(path, bytes))
                                }
                            }
                    }
            end match
        }

    private def mkWriteHandle(path: Path, seed: Span[Byte]): Path.WriteHandle =
        new Path.WriteHandle:
            private var acc = seed.toArrayUnsafe
            def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
                acc = acc ++ chunk.toArray
                Result.succeed(())
            def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit] =
                acc = acc ++ s.getBytes(charset)
                Result.succeed(())
            def finish()(using AllowUnsafe): Unit =
                val bytes = Span.fromUnsafe(acc)
                val stat  = Path.PathStat(0L, bytes.size.toLong)
                // Unsafe: commits buffered bytes into the overlay upper layer at finish()
                discard(state.unsafe.updateAndGet { cur =>
                    cur.copy(
                        upper = cur.upper.updated(path.parts, Upper.Entry(Path.Entry.File(bytes, stat))),
                        journal = cur.journal.appended(WriteOp.WriteFile(path.parts, bytes, stat))
                    )
                })
            end finish
            def close()(using AllowUnsafe): Unit = () // bytes dropped if finish() not called

    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (S & Abort[FileException]) =
        // Unsafe: pumps the write handle's internal buffer; no overlay state involved.
        // asInstanceOf: Sync.Unsafe.defer gives `< (Sync & Abort)` but S = Sync at the
        // only instantiation site so this is a no-op cast at runtime.
        Sync.Unsafe.defer(Abort.get[FileException](handle.writeBytes(chunk))).asInstanceOf[Unit < (S & Abort[FileException])]

    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (S & Abort[FileException]) =
        // Unsafe: same as writeChunk.
        Sync.Unsafe.defer(Abort.get[FileException](handle.writeString(value, charset))).asInstanceOf[Unit < (S & Abort[FileException])]

    // --- Temp dir ---

    def tempDir(prefix: String): Path.TempDirHandle < (S & Abort[FileException]) =
        val id   = java.lang.System.identityHashCode(prefix).toHexString
        val dir  = Path(prefix + "-overlay-" + id)
        val stat = Path.PathStat(0L, 0L)
        modifyPure { cur =>
            cur.copy(
                upper = cur.upper.updated(dir.parts, Upper.Entry(Path.Entry.Directory(stat))),
                journal = cur.journal.appended(WriteOp.WriteDirectory(dir.parts, opaque = false))
            )
        }.andThen {
            new Path.TempDirHandle:
                def path: Path = dir
                // Unsafe: removes the upper entry; never touches the host filesystem.
                def remove()(using AllowUnsafe): Unit =
                    discard(state.unsafe.updateAndGet(cur => cur.copy(upper = cur.upper.removed(dir.parts))))
        }
    end tempDir

    // --- Commit / rollback ---

    def rollback(using Frame): Unit < S =
        // modifyPure's CAS loop never triggers Abort[FileException]; Abort.run discharges the
        // phantom Abort row, leaving Unit < S as declared.
        Abort.run[FileException](modifyPure(_ => OverlayState.empty)).map(_ => ())

    // Validate the read-set: for each stamped path, re-stat lower and compare.
    private def validate(s: OverlayState): Chunk[Conflict] < (S & Abort[FileException]) =
        s.readSet.toIndexedSeq.foldLeft[Chunk[Conflict] < (S & Abort[FileException])](Chunk.empty) {
            case (accKyo, (parts, stamp)) =>
                accKyo.map { acc =>
                    val path = pathFrom(parts)
                    lower.exists(path).map { found =>
                        if !found then
                            if stamp.entryType == Path.Stamp.Kind.Absent then acc
                            else
                                val conflict =
                                    Conflict(path, Present(stamp), s.upper.get(parts).fold[Maybe[Path.Entry]](Absent)(upperToEntry), Absent)
                                acc.appended(conflict)
                        else
                            lower.stat(path).map { liveStat =>
                                lower.isRegularFile(path).map { isFile =>
                                    val liveStamp =
                                        if isFile then
                                            Path.Stamp(
                                                Path.Stamp.Kind.File,
                                                Present(liveStat.sizeBytes.bytes),
                                                Present(liveStat.lastModifiedMs),
                                                Absent
                                            )
                                        else Path.Stamp(Path.Stamp.Kind.Directory, Absent, Present(liveStat.lastModifiedMs), Absent)
                                    if stamp.entryType == liveStamp.entryType && stamp.lastModifiedMs == liveStamp.lastModifiedMs && stamp.size == liveStamp.size
                                    then acc
                                    else
                                        val liveEntry: Maybe[Path.Entry] =
                                            if isFile then Absent else Present(Path.Entry.Directory(liveStat))
                                        val conflict = Conflict(
                                            path,
                                            Present(stamp),
                                            s.upper.get(parts).fold[Maybe[Path.Entry]](Absent)(upperToEntry),
                                            liveEntry
                                        )
                                        acc.appended(conflict)
                                    end if
                                }
                            }
                    }
                }
        }

    private def upperToEntry(u: Upper): Maybe[Path.Entry] =
        u match
            case Upper.Entry(e)      => Present(e)
            case Upper.OpaqueDir(st) => Present(Path.Entry.Directory(st))
            case Upper.Whiteout      => Absent

    // Direct journal replay onto lower.
    private def applyJournal(journal: Chunk[WriteOp]): Unit < (S & Abort[FileException]) =
        journal.foldLeft[Unit < (S & Abort[FileException])](()) { (accKyo, op) =>
            accKyo.andThen {
                op match
                    case WriteOp.WriteFile(parts, bytes, _) =>
                        lower.writeBytes(pathFrom(parts), bytes, createFolders = true)
                    case WriteOp.WriteDirectory(parts, _) =>
                        lower.mkDir(pathFrom(parts))
                    case WriteOp.Remove(parts) =>
                        lower.removeAll(pathFrom(parts))
                    case WriteOp.Move(fromP, toP, resolved) =>
                        lower.removeAll(pathFrom(fromP)).andThen(applyEntry(pathFrom(toP), resolved))
                    case WriteOp.Copy(_, toP, resolved) =>
                        applyEntry(pathFrom(toP), resolved)
            }
        }

    private def applyEntry(path: Path, entry: Path.Entry): Unit < (S & Abort[FileException]) =
        entry match
            case Path.Entry.File(bytes, _) => lower.writeBytes(path, bytes, createFolders = true)
            case Path.Entry.Directory(_)   => lower.mkDir(path)

    def commit(using Frame): Unit < (S & Abort[FileException] & Abort[CommitConflict]) =
        stateGet.map { s =>
            validate(s).map { conflicts =>
                if conflicts.isEmpty then
                    applyJournal(s.journal).andThen(modifyPure(_ => OverlayState.empty))
                else
                    Abort.fail(CommitConflict(conflicts))
            }
        }

    def commitOverwrite(using Frame): Unit < (S & Abort[FileException]) =
        stateGet.map { s =>
            applyJournal(s.journal).andThen(modifyPure(_ => OverlayState.empty))
        }

    def commitWith[S2](resolve: Conflict => Resolution < S2)(using Frame): Unit < (S & Abort[FileException] & S2) =
        stateGet.map { s =>
            validate(s).map { conflicts =>
                // Collect one Resolution per conflicted path, then rebuild upper and journal
                // so the replay reflects every resolution (not just the original staged ops).
                conflicts.foldLeft[Map[Chunk[String], Resolution] < (S & Abort[FileException] & S2)](Map.empty) { (accKyo, conflict) =>
                    accKyo.map { resolutions =>
                        resolve(conflict).map { resolution =>
                            resolutions.updated(conflict.path.parts, resolution)
                        }
                    }
                }.map { resolutions =>
                    // Pure fold: compute replacement upper and journal from the resolution map.
                    val (newUpper, replacedJournal) =
                        resolutions.foldLeft((s.upper, s.journal)) { case ((upper, journal), (parts, resolution)) =>
                            resolution match
                                case Resolution.KeepOurs =>
                                    (upper, journal)
                                case Resolution.KeepTheirs =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _)       => to != parts
                                    }
                                    (upper.removed(parts), stripped)
                                case Resolution.Write(entry) =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _)       => to != parts
                                    }
                                    val newOp = entry match
                                        case Path.Entry.File(bytes, stat) => WriteOp.WriteFile(parts, bytes, stat)
                                        case Path.Entry.Directory(_)      => WriteOp.WriteDirectory(parts, opaque = false)
                                    (upper.updated(parts, Upper.Entry(entry)), stripped.appended(newOp))
                                case Resolution.Remove =>
                                    val stripped = journal.filter {
                                        case WriteOp.WriteFile(p, _, _)   => p != parts
                                        case WriteOp.WriteDirectory(p, _) => p != parts
                                        case WriteOp.Remove(p)            => p != parts
                                        case WriteOp.Move(from, to, _)    => from != parts && to != parts
                                        case WriteOp.Copy(_, to, _)       => to != parts
                                    }
                                    (upper.updated(parts, Upper.Whiteout), stripped.appended(WriteOp.Remove(parts)))
                        }
                    modifyPure(_.copy(upper = newUpper, journal = replacedJournal)).andThen {
                        applyJournal(replacedJournal).andThen(modifyPure(_ => OverlayState.empty))
                    }
                }
            }
        }

end OverlayService
