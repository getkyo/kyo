package kyo.internal.dolt

import kyo.*

/** Every statement [[kyo.DoltFileSystem]] sends, and the translation of what comes back.
  *
  * Two invariants hold file-wide: every path reaches a statement bound rather than interpolated, so a file named with
  * a quote stays a filename; and every `SqlException` is translated into the filesystem's own exception hierarchy
  * rather than passed through to a caller who only asked to read a file.
  */
final private[kyo] class DoltVfsStore(client: Dolt):

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
    /** The hex an engine's `unhex` reads back, for the one piece of a spliced write that is not already stored. */
    private def hexOf(bytes: Span[Byte]): String =
        val digits = "0123456789ABCDEF"
        val out    = new StringBuilder(bytes.size * 2)
        var i      = 0
        while i < bytes.size do
            val b = bytes(i) & 0xff
            discard(out.append(digits.charAt(b >>> 4)))
            discard(out.append(digits.charAt(b & 0x0f)))
            i += 1
        end while
        out.toString
    end hexOf

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
        execute(path, FileSystemOperation.Remove, "DELETE FROM vfs_node WHERE path = ?", Chunk(text(key(path)))).unit

    // --- Content ---

    /** Every byte of a file.
      *
      * Sized from the node's recorded length rather than from the stored value: [[truncate]] can extend a file
      * without storing the zeros it grew by, so the tail of the array is the gap reading back as zeros.
      */
    def readAll(path: Path, sizeBytes: Long)(using Frame): Span[Byte] < (Async & Abort[FileIOException]) =
        if sizeBytes <= 0L then Span.empty[Byte]
        else
            query(
                path,
                FileSystemOperation.Read,
                "SELECT bytes FROM vfs_node WHERE path = ?",
                Chunk(text(key(path)))
            ).map { rows =>
                if rows.isEmpty then Span.empty[Byte]
                else
                    lifted(path, FileSystemOperation.Read)(rows.head.decode[Span[Byte]]("bytes")).map { stored =>
                        if stored.size.toLong == sizeBytes then stored
                        else
                            val out = new Array[Byte](sizeBytes.toInt)
                            copyBytes(stored, 0, out, 0, math.min(stored.size.toLong, sizeBytes).toInt)
                            Span.fromUnsafe(out)
                    }
            }

    /** Reads up to `length` bytes at `position`, slicing in the engine so only the slice crosses the wire.
      *
      * The slice is taken over `hex(bytes)` rather than over the bytes, for the reason recorded on
      * [[DoltFileSystemSchema]]: this engine's byte-slicing functions either refuse a value that is not valid
      * UTF-8 or silently replace the offending bytes. Hex offsets are one-based and two characters per byte.
      */
    def readRange(path: Path, sizeBytes: Long, position: Long, length: Int)(using
        Frame
    ): Span[Byte] < (Async & Abort[FileIOException]) =
        val available = math.max(0L, math.min(length.toLong, sizeBytes - position))
        if available <= 0L then Span.empty[Byte]
        else
            query(
                path,
                FileSystemOperation.Channel,
                "SELECT unhex(substr(coalesce(hex(bytes), ''), ?, ?)) AS slice FROM vfs_node WHERE path = ?",
                Chunk(number(position * 2L + 1L), number(available * 2L), text(key(path)))
            ).map { rows =>
                if rows.isEmpty then Span.empty[Byte]
                else
                    lifted(path, FileSystemOperation.Channel)(rows.head.decode[Span[Byte]]("slice")).map { got =>
                        // Short when the read reaches past what is stored, which is the gap truncate left.
                        if got.size.toLong == available then got
                        else
                            val out = new Array[Byte](available.toInt)
                            copyBytes(got, 0, out, 0, math.min(got.size.toLong, available).toInt)
                            Span.fromUnsafe(out)
                    }
            }
        end if
    end readRange

    /** Replaces the whole content of a file. */
    def writeAll(path: Path, bytes: Span[Byte], modifiedMs: Long)(using Frame): Unit < (Async & Abort[FileIOException]) =
        execute(
            path,
            FileSystemOperation.Write,
            "UPDATE vfs_node SET bytes = ?, size_bytes = ?, modified_ms = ? WHERE path = ?",
            Chunk(blob(bytes), number(bytes.size.toLong), number(modifiedMs), text(key(path)))
        ).unit

    /** Writes `bytes` at `position`, zero-filling any gap beyond the current end.
      *
      * Spliced in the engine over the hex text: the head up to the write, the new bytes as hex, then whatever
      * followed them. A write starting past the end carries the gap as leading `00` pairs in that middle piece,
      * so one statement covers both cases and the value never crosses the wire.
      */
    def writeAt(path: Path, sizeBytes: Long, position: Long, bytes: Span[Byte], modifiedMs: Long)(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        if bytes.isEmpty then touch(path, math.max(sizeBytes, position), modifiedMs)
        else
            val gap        = math.max(0L, position - sizeBytes)
            val headBytes  = math.min(position, sizeBytes)
            val middle     = ("00" * gap.toInt) + hexOf(bytes)
            val tailAtByte = position + bytes.size
            execute(
                path,
                FileSystemOperation.Write,
                "UPDATE vfs_node SET bytes = unhex(concat(" +
                    "substr(coalesce(hex(bytes), ''), 1, ?), ?, substr(coalesce(hex(bytes), ''), ?)" +
                    ")), size_bytes = ?, modified_ms = ? WHERE path = ?",
                Chunk(
                    number(headBytes * 2L),
                    text(middle),
                    number(tailAtByte * 2L + 1L),
                    number(math.max(sizeBytes, tailAtByte)),
                    number(modifiedMs),
                    text(key(path))
                )
            ).unit
        end if
    end writeAt

    /** Shortens or extends a file to `size`.
      *
      * Shortening cuts the stored value; extending records the new length and stores nothing, so the bytes it
      * grew by read back as zeros without being written.
      */
    def truncate(path: Path, sizeBytes: Long, size: Long, modifiedMs: Long)(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        if size >= sizeBytes then touch(path, size, modifiedMs)
        else
            execute(
                path,
                FileSystemOperation.Write,
                "UPDATE vfs_node SET bytes = unhex(substr(coalesce(hex(bytes), ''), 1, ?)), " +
                    "size_bytes = ?, modified_ms = ? WHERE path = ?",
                Chunk(number(size * 2L), number(size), number(modifiedMs), text(key(path)))
            ).unit

    /** Moves every node at or beneath `from` to sit beneath `to`, keeping their content with them. */
    def relocate(from: Path, to: Path, nodes: Chunk[DoltVfsNode], modifiedMs: Long)(using
        Frame
    ): Unit < (Async & Abort[FileIOException]) =
        Kyo.foreachDiscard(nodes) { node =>
            val moved = rebase(node.path, from, to)
            // Re-keyed rather than deleted and re-inserted, because the content is a column on this row now and
            // putNode, which deletes first, would drop it.
            execute(
                node.path,
                FileSystemOperation.Move,
                "UPDATE vfs_node SET path = ?, parent = ?, modified_ms = ? WHERE path = ?",
                Chunk(
                    text(key(moved)),
                    text(moved.parent.fold("")(key)),
                    number(if node.path == from then modifiedMs else node.modifiedMs),
                    text(key(node.path))
                )
            )
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
