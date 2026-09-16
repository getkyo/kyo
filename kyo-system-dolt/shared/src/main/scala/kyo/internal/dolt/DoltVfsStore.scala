package kyo.internal.dolt

import kyo.*

/** Every statement [[kyo.DoltFileSystem]] sends, and the translation of what comes back.
  *
  * Two invariants hold file-wide: every path reaches a statement bound rather than interpolated, so a file named with
  * a quote stays a filename; and every `SqlException` is translated into the filesystem's own exception hierarchy
  * rather than passed through to a caller who only asked to read a file.
  */
final private[kyo] class DoltVfsStore(client: DoltClient):

    import DoltVfsStore.*
    import kyo.DoltFileSystemSchema as Schema

    // --- Binding and failure translation ---

    private def text(value: String): Sql.BoundValue[String] =
        Sql.BoundValue(value, summon[SqlSchema.Column[String]], "TEXT")

    private def number(value: Long): Sql.BoundValue[Long] =
        Sql.BoundValue(value, summon[SqlSchema.Column[Long]], "BIGINT")

    private def blob(value: Span[Byte]): Sql.BoundValue[Span[Byte]] =
        Sql.BoundValue(value, summon[SqlSchema.Column[Span[Byte]]], "BLOB")

    /** Runs a statement, translating an engine failure into a `FileIOException` carrying the `SqlException` as cause. */
    private def run[A](path: Path, operation: FileSystemOperation)(
        body: A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[FileIOException]) =
        Abort.recover[SqlException]((e: SqlException) => Abort.fail(FileIOException(path, operation, e)))(body)

    private def query(path: Path, operation: FileSystemOperation, sql: String, args: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): Chunk[SqlRow] < (Async & Abort[FileIOException]) =
        run(path, operation)(client.routedWith(client.config)(_.extendedQuery(sql, args)))

    private def execute(path: Path, operation: FileSystemOperation, sql: String, args: Chunk[Sql.BoundValue[?]])(using
        Frame
    ): Long < (Async & Abort[FileIOException]) =
        run(path, operation)(client.routedWith(client.config)(_.extendedExecute(sql, args)))

    private def lifted[A](path: Path, operation: FileSystemOperation)(
        decode: A < Abort[SqlDecodeException]
    )(using Frame): A < Abort[FileIOException] =
        Abort.recover[SqlDecodeException](
            (e: SqlDecodeException) => Abort.fail(FileIOException(path, operation, e)),
            t => Abort.error(Result.Panic(t))
        )(decode)

    /** Copies a run of bytes out of a `Span` into an array. Through the backing array rather than `copyToArray`, which
      * can only copy from a span's start, and `java.lang.System` in full because `kyo.System` shadows it here.
      */
    private def copyBytes(src: Span[Byte], srcPos: Int, dst: Array[Byte], dstPos: Int, length: Int): Unit =
        if length > 0 then java.lang.System.arraycopy(src.toArrayUnsafe, srcPos, dst, dstPos, length)

    // --- Schema ---

    /** Brings the tables up, idempotently. */
    def install(using Frame): Unit < (Async & Abort[FileIOException]) =
        Kyo.foreachDiscard(Schema.create)(statement => execute(Path(), FileSystemOperation.Create, statement, Chunk.empty).unit)

    // --- Node reads ---

    def node(path: Path)(using Frame): Maybe[DoltVfsNode] < (Async & Abort[FileIOException]) =
        query(
            path,
            FileSystemOperation.Inspect,
            "SELECT path, kind, target, size_bytes, modified_ms FROM vfs_node WHERE path = ?",
            Chunk(text(key(path)))
        ).map { rows =>
            if rows.isEmpty then Absent
            else lifted(path, FileSystemOperation.Inspect)(readNode(rows.head)).map(Present(_))
        }

    /** The children of a directory, as full paths. Ordered by path because neither engine promises an order otherwise,
      * and a listing that reorders between runs makes every test over it flaky.
      */
    def children(path: Path)(using Frame): Chunk[DoltVfsNode] < (Async & Abort[FileIOException]) =
        query(
            path,
            FileSystemOperation.List,
            "SELECT path, kind, target, size_bytes, modified_ms FROM vfs_node WHERE parent = ? ORDER BY path",
            Chunk(text(key(path)))
        ).map(rows => Kyo.foreach(rows)(row => lifted(path, FileSystemOperation.List)(readNode(row))))

    /** Every node at or beneath `path`, ordered so a parent always precedes its children. The prefix match is on the
      * stored key plus a separator, without which it would also match a sibling whose name starts with the same
      * characters.
      */
    def descendants(path: Path)(using Frame): Chunk[DoltVfsNode] < (Async & Abort[FileIOException]) =
        val self   = key(path)
        val prefix = if self.isEmpty then "" else self + Separator
        query(
            path,
            FileSystemOperation.Walk,
            "SELECT path, kind, target, size_bytes, modified_ms FROM vfs_node WHERE path = ? OR path LIKE ? ORDER BY path",
            Chunk(text(self), text(escapeLike(prefix) + "%"))
        ).map(rows => Kyo.foreach(rows)(row => lifted(path, FileSystemOperation.Walk)(readNode(row))))
    end descendants

    private def readNode(row: SqlRow)(using Frame): DoltVfsNode < Abort[SqlDecodeException] =
        for
            stored   <- row.decode[String]("path")
            kind     <- row.decode[String]("kind")
            target   <- row.decode[Maybe[String]]("target")
            size     <- row.decode[Long]("size_bytes")
            modified <- row.decode[Long]("modified_ms")
        yield DoltVfsNode(path(stored), kind, target.filter(_.nonEmpty), size, modified)

    // --- Node writes ---

    /** Creates or replaces a node row. A delete followed by an insert rather than an upsert, because the upsert
      * syntaxes diverge: `ON DUPLICATE KEY UPDATE` on the server, `ON CONFLICT DO UPDATE` on the embedded engine.
      */
    def putNode(node: DoltVfsNode)(using Frame): Unit < (Async & Abort[FileIOException]) =
        execute(
            node.path,
            FileSystemOperation.Write,
            "DELETE FROM vfs_node WHERE path = ?",
            Chunk(text(key(node.path)))
        ).andThen {
            execute(
                node.path,
                FileSystemOperation.Write,
                "INSERT INTO vfs_node (path, parent, kind, target, size_bytes, modified_ms) VALUES (?, ?, ?, ?, ?, ?)",
                Chunk(
                    text(key(node.path)),
                    text(node.path.parent.fold("")(key)),
                    text(node.kind),
                    text(node.target.getOrElse("")),
                    number(node.sizeBytes),
                    number(node.modifiedMs)
                )
            )
        }.unit

    def touch(path: Path, sizeBytes: Long, modifiedMs: Long)(using Frame): Unit < (Async & Abort[FileIOException]) =
        execute(
            path,
            FileSystemOperation.Write,
            "UPDATE vfs_node SET size_bytes = ?, modified_ms = ? WHERE path = ?",
            Chunk(number(sizeBytes), number(modifiedMs), text(key(path)))
        ).unit

    def removeNode(path: Path)(using Frame): Unit < (Async & Abort[FileIOException]) =
        execute(path, FileSystemOperation.Remove, "DELETE FROM vfs_node WHERE path = ?", Chunk(text(key(path)))).andThen {
            execute(path, FileSystemOperation.Remove, "DELETE FROM vfs_block WHERE path = ?", Chunk(text(key(path))))
        }.unit

    // --- Content ---

    /** The whole content of a file, assembled from its blocks. Sized from the node's recorded length, so a partially
      * filled last block comes back at its true size rather than rounded up to a block boundary.
      */
    def readAll(path: Path, sizeBytes: Long)(using Frame): Span[Byte] < (Async & Abort[FileIOException]) =
        if sizeBytes <= 0L then Span.empty[Byte]
        else
            query(
                path,
                FileSystemOperation.Read,
                "SELECT idx, bytes FROM vfs_block WHERE path = ? ORDER BY idx",
                Chunk(text(key(path)))
            ).map { rows =>
                Kyo.foreach(rows)(row =>
                    lifted(path, FileSystemOperation.Read) {
                        for
                            idx   <- row.decode[Int]("idx")
                            bytes <- row.decode[Span[Byte]]("bytes")
                        yield (idx, bytes)
                    }
                ).map { blocks =>
                    val out = new Array[Byte](sizeBytes.toInt)
                    blocks.foreach { (idx, bytes) =>
                        val at = idx.toLong * Schema.BlockBytes
                        val n  = math.min(bytes.size.toLong, sizeBytes - at).toInt
                        if n > 0 then copyBytes(bytes, 0, out, at.toInt, n)
                    }
                    Span.fromUnsafe(out)
                }
            }

    /** Reads up to `length` bytes at `position`, touching only the blocks that span the request. A read past the
      * recorded end returns fewer bytes than asked, which is the short read the channel contract allows.
      */
    def readRange(path: Path, sizeBytes: Long, position: Long, length: Int)(using
        Frame
    ): Span[Byte] < (Async & Abort[FileIOException]) =
        val available = math.max(0L, math.min(length.toLong, sizeBytes - position))
        if available <= 0L then Span.empty[Byte]
        else
            val first = position / Schema.BlockBytes
            val last  = (position + available - 1) / Schema.BlockBytes
            query(
                path,
                FileSystemOperation.Channel,
                "SELECT idx, bytes FROM vfs_block WHERE path = ? AND idx BETWEEN ? AND ? ORDER BY idx",
                Chunk(text(key(path)), number(first), number(last))
            ).map { rows =>
                Kyo.foreach(rows)(row =>
                    lifted(path, FileSystemOperation.Channel) {
                        for
                            idx   <- row.decode[Int]("idx")
                            bytes <- row.decode[Span[Byte]]("bytes")
                        yield (idx, bytes)
                    }
                ).map { blocks =>
                    val out = new Array[Byte](available.toInt)
                    blocks.foreach { (idx, bytes) =>
                        val blockStart = idx.toLong * Schema.BlockBytes
                        val from       = math.max(position, blockStart)
                        val to         = math.min(position + available, blockStart + bytes.size)
                        if to > from then
                            copyBytes(bytes, (from - blockStart).toInt, out, (from - position).toInt, (to - from).toInt)
                    }
                    Span.fromUnsafe(out)
                }
            }
        end if
    end readRange

    /** Replaces the whole content of a file, leaving no block from a longer previous version behind. */
    def writeAll(path: Path, bytes: Span[Byte], modifiedMs: Long)(using Frame): Unit < (Async & Abort[FileIOException]) =
        execute(path, FileSystemOperation.Write, "DELETE FROM vfs_block WHERE path = ?", Chunk(text(key(path)))).andThen {
            writeBlocks(path, 0L, bytes)
        }.andThen(touch(path, bytes.size.toLong, modifiedMs))

    /** Writes `bytes` at `position`, zero-filling any gap beyond the current end, so writing at offset 3 of an empty
      * file leaves three zero bytes before it rather than whatever the block happened to hold.
      */
    def writeAt(path: Path, sizeBytes: Long, position: Long, bytes: Span[Byte], modifiedMs: Long)(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        writeBlocks(path, position, bytes).andThen {
            touch(path, math.max(sizeBytes, position + bytes.size), modifiedMs)
        }

    private def writeBlocks(path: Path, position: Long, bytes: Span[Byte])(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        if bytes.isEmpty then ()
        else
            val first  = position / Schema.BlockBytes
            val last   = (position + bytes.size - 1) / Schema.BlockBytes
            val blocks = Chunk.from(first to last)
            Kyo.foreachDiscard(blocks) { idx =>
                val blockStart = idx * Schema.BlockBytes
                val from       = math.max(position, blockStart)
                val to         = math.min(position + bytes.size, blockStart + Schema.BlockBytes)
                // Read the existing block back before patching, so a write covering only part of it keeps the rest.
                val existing =
                    if from == blockStart && to == blockStart + Schema.BlockBytes then Kyo.lift(Span.empty[Byte])
                    else
                        query(
                            path,
                            FileSystemOperation.Write,
                            "SELECT bytes FROM vfs_block WHERE path = ? AND idx = ?",
                            Chunk(text(key(path)), number(idx))
                        ).map { rows =>
                            if rows.isEmpty then Span.empty[Byte]
                            else lifted(path, FileSystemOperation.Write)(rows.head.decode[Span[Byte]]("bytes"))
                        }
                existing.map { current =>
                    val size  = math.max(current.size, (to - blockStart).toInt)
                    val patch = new Array[Byte](size)
                    copyBytes(current, 0, patch, 0, current.size)
                    copyBytes(bytes, (from - position).toInt, patch, (from - blockStart).toInt, (to - from).toInt)
                    execute(
                        path,
                        FileSystemOperation.Write,
                        "DELETE FROM vfs_block WHERE path = ? AND idx = ?",
                        Chunk(text(key(path)), number(idx))
                    ).andThen {
                        execute(
                            path,
                            FileSystemOperation.Write,
                            "INSERT INTO vfs_block (path, idx, bytes) VALUES (?, ?, ?)",
                            Chunk(text(key(path)), number(idx), blob(Span.fromUnsafe(patch)))
                        )
                    }.unit
                }
            }
        end if
    end writeBlocks

    /** Shortens or extends a file to `size`, dropping whole blocks past the new end and trimming the one that spans it. */
    def truncate(path: Path, sizeBytes: Long, size: Long, modifiedMs: Long)(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        val keep = if size <= 0L then -1L else (size - 1) / Schema.BlockBytes
        execute(
            path,
            FileSystemOperation.Write,
            "DELETE FROM vfs_block WHERE path = ? AND idx > ?",
            Chunk(text(key(path)), number(keep))
        ).andThen {
            // Extending is recorded on the node alone: the gap reads back as zeros because no block covers it.
            if size >= sizeBytes then touch(path, size, modifiedMs)
            else
                val boundary = size % Schema.BlockBytes
                if boundary == 0L then touch(path, size, modifiedMs)
                else
                    query(
                        path,
                        FileSystemOperation.Write,
                        "SELECT bytes FROM vfs_block WHERE path = ? AND idx = ?",
                        Chunk(text(key(path)), number(keep))
                    ).map { rows =>
                        if rows.isEmpty then touch(path, size, modifiedMs)
                        else
                            lifted(path, FileSystemOperation.Write)(rows.head.decode[Span[Byte]]("bytes")).map { current =>
                                val trimmed = new Array[Byte](math.min(current.size.toLong, boundary).toInt)
                                copyBytes(current, 0, trimmed, 0, trimmed.length)
                                execute(
                                    path,
                                    FileSystemOperation.Write,
                                    "DELETE FROM vfs_block WHERE path = ? AND idx = ?",
                                    Chunk(text(key(path)), number(keep))
                                ).andThen {
                                    execute(
                                        path,
                                        FileSystemOperation.Write,
                                        "INSERT INTO vfs_block (path, idx, bytes) VALUES (?, ?, ?)",
                                        Chunk(text(key(path)), number(keep), blob(Span.fromUnsafe(trimmed)))
                                    )
                                }.andThen(touch(path, size, modifiedMs))
                            }
                    }
                end if
            end if
        }
    end truncate

    /** Moves every node at or beneath `from` to sit beneath `to`, keeping their content with them. */
    def relocate(from: Path, to: Path, nodes: Chunk[DoltVfsNode], modifiedMs: Long)(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        Kyo.foreachDiscard(nodes) { node =>
            val moved = rebase(node.path, from, to)
            execute(
                node.path,
                FileSystemOperation.Move,
                "UPDATE vfs_block SET path = ? WHERE path = ?",
                Chunk(text(key(moved)), text(key(node.path)))
            ).andThen {
                execute(node.path, FileSystemOperation.Move, "DELETE FROM vfs_node WHERE path = ?", Chunk(text(key(node.path))))
            }.andThen {
                putNode(node.copy(path = moved, modifiedMs = if node.path == from then modifiedMs else node.modifiedMs))
            }
        }

end DoltVfsStore

private[kyo] object DoltVfsStore:

    /** The separator between stored path components. A stored key is the path's own components joined, never a host
      * path string, so a tree reads back identically whichever platform wrote it.
      */
    val Separator: String = "/"

    def key(path: Path): String = path.parts.mkString(Separator)

    def path(key: String): Path =
        if key.isEmpty then Path() else Path(Chunk.from(key.split(Separator).toSeq).filter(_.nonEmpty)*)

    /** Re-roots `path` from under `from` to under `to`. */
    def rebase(path: Path, from: Path, to: Path): Path =
        if path == from then to
        else Path(to.parts.concat(path.parts.drop(from.parts.size))*)

    /** Escapes the characters `LIKE` treats as wildcards, without which a directory called `a_b` also matches `axb`
      * and a walk silently returns a sibling's files. Backslash is the default escape character on both engines.
      */
    def escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

end DoltVfsStore

/** One row of `vfs_node`, as the filesystem sees it. */
final private[kyo] case class DoltVfsNode(
    path: Path,
    kind: String,
    target: Maybe[String],
    sizeBytes: Long,
    modifiedMs: Long
) derives CanEqual:
    def isDirectory: Boolean = kind == DoltFileSystemSchema.Kind.Directory
    def isFile: Boolean      = kind == DoltFileSystemSchema.Kind.File
    def isSymlink: Boolean   = kind == DoltFileSystemSchema.Kind.Symlink
end DoltVfsNode
