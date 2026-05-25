package kyo.internal.reflect.snapshot

import kyo.*
import kyo.internal.reflect.query.FileSource

/** FNV-1a 64-bit digest computation for classpath cache invalidation.
  *
  * Uses FNV-1a 64-bit rather than SHA-256 (supervisor override: sufficient for cache invalidation, zero external dependencies, ~30 LOC of
  * pure Scala, identical on all platforms).
  *
  * `compute`: hashes sorted (path, mtime, size) tuples -- fast, metadata-only. `computeParanoid`: hashes sorted (path, content) tuples --
  * slower, detects content changes that leave mtime+size unchanged.
  *
  * Per-platform mtime source:
  *   - JVM: `java.nio.file.Files.getLastModifiedTime(path).toMillis` (via JvmFileSource)
  *   - Native: POSIX `stat()` FFI (via NativeFileSource)
  *   - JS-Node: `fs.statSync(path).mtimeMs` (via JsFileSource)
  *   - JS-browser: `openCached` returns `Abort.fail(ReflectError.FileNotFound("browser: use fromPickles"))` before any digest computation
  */
object DigestComputer:

    /** FNV-1a 64-bit offset basis. Stored as a signed Long (the unsigned value is 14695981039346656037). */
    private val fnv1aOffset: Long = -3750763034362895579L

    /** FNV-1a 64-bit prime. */
    private val fnv1aPrime: Long = 1099511628211L

    /** Update FNV-1a 64-bit hash with a byte array. */
    private def fnv1aUpdate(h: Long, data: Array[Byte]): Long =
        var hash = h
        var i    = 0
        while i < data.length do
            hash ^= (data(i) & 0xff).toLong
            hash *= fnv1aPrime
            i += 1
        end while
        hash
    end fnv1aUpdate

    /** Update FNV-1a 64-bit hash with a single Long value (little-endian). */
    private def fnv1aUpdateLong(h: Long, v: Long): Long =
        var hash = h
        hash ^= (v & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 8) & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 16) & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 24) & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 32) & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 40) & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 48) & 0xff).toLong
        hash *= fnv1aPrime
        hash ^= ((v >> 56) & 0xff).toLong
        hash *= fnv1aPrime
        hash
    end fnv1aUpdateLong

    /** Compute a 64-bit digest of sorted (path, mtime, size) tuples from the given roots.
      *
      * Deterministic: same input files and metadata always produce the same 8-byte result. The digest is returned as an 8-byte
      * little-endian array.
      */
    def compute(roots: Seq[String], source: FileSource)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
        collectStats(roots, source).map: stats =>
            val sorted = stats.sortBy(_._1)
            var hash   = fnv1aOffset
            for (path, mtime, size) <- sorted do
                hash = fnv1aUpdate(hash, SnapshotFormat.encodeString(path))
                hash = fnv1aUpdateLong(hash, mtime)
                hash = fnv1aUpdateLong(hash, size)
            end for
            longToBytes(hash)

    /** Compute a 64-bit digest of sorted (path, content) tuples from the given roots.
      *
      * Slower than `compute` but detects content changes that leave mtime+size unchanged (e.g., in-place content edit with `touch -t`).
      */
    def computeParanoid(roots: Seq[String], source: FileSource)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
        collectFiles(roots, source).flatMap: files =>
            val sorted = files.sortBy(identity)
            Kyo.foreach(sorted): path =>
                source.read(path).map: bytes =>
                    (path, bytes)
            .map: pairs =>
                var hash = fnv1aOffset
                for (path, bytes) <- pairs do
                    hash = fnv1aUpdate(hash, SnapshotFormat.encodeString(path))
                    hash = fnv1aUpdate(hash, bytes)
                longToBytes(hash)

    /** Collect (path, mtime, size) for all .tasty files reachable from roots. */
    private def collectStats(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Seq[(String, Long, Long)] < (Sync & Abort[ReflectError]) =
        collectFiles(roots, source).flatMap: files =>
            Kyo.foreach(files): path =>
                source.stat(path).map: st =>
                    (path, st.mtimeMs, st.size)

    /** Collect all .tasty file paths from roots. */
    private def collectFiles(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Seq[String] < (Sync & Abort[ReflectError]) =
        Kyo.foreach(roots): root =>
            source.exists(root).flatMap: ex =>
                if !ex then Seq.empty[String]
                else source.list(root, ".tasty").map(_.toSeq)
        .map(_.flatten.toSeq)

    /** Convert a Long to an 8-byte little-endian array. */
    def longToBytes(v: Long): Array[Byte] =
        val buf = new Array[Byte](8)
        SnapshotFormat.writeInt64LE(buf, 0, v)
        buf
    end longToBytes

    /** Convert an 8-byte little-endian array back to a Long. */
    def bytesToLong(bytes: Array[Byte]): Long =
        SnapshotFormat.readInt64LE(bytes, 0)

    /** Convert a digest byte array to a hex string (used in snapshot filenames). */
    def toHexString(digest: Array[Byte]): String =
        val sb = new StringBuilder(digest.length * 2)
        for b <- digest do
            sb.append(String.format("%02x", b & 0xff))
        sb.toString
    end toHexString

end DigestComputer
