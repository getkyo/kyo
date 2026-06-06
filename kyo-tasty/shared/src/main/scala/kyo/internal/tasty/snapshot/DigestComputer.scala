package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.query.FileSource

/** xxh64-based custom 64-bit content hash for classpath cache invalidation.
  *
  * Replaces the prior FNV-1a 64-bit mtime+size computation with an xxh64-custom walk over the JAR central directory (CEN) CRC32
  * values. This makes the digest stable across machine boundaries and mtime-only copies (INV-003).
  *
  * Algorithm note: the implementation uses xxh64 prime constants (0x9E3779B185EBCA87, etc.) with a bespoke per-entry mix loop.
  * This is NOT the full xxh3 streaming algorithm; it is a custom 64-bit hash built from xxh64 primes. Future improvement: replace
  * with a conforming xxh3-64 implementation for interoperability. Tracked as a future improvement.
  *
  * `compute`: hashes jar roots via CEN-CRC walk (JVM) or path-hash fallback (JS/Native); directory and jrt:/ roots use per-file
  * mtime+size as before. `computeParanoid`: hashes sorted (path, content) tuples.
  *
  * Per-platform jar digest source:
  *   - JVM: xxh64-custom hash over sorted (name, CRC32) tuples from the JAR central directory (via JarCentralDirectory.read)
  *   - Native: path-based xxh64-custom fallback (RandomAccessFile unavailable in the shared snapshot path; not content-addressed)
  *   - JS-Node: path-based xxh64-custom fallback (same reason; not content-addressed for in-place jar mutation)
  *   - JS-browser: openCached returns Abort.fail(TastyError.FileNotFound("browser: use fromPickles")) before any digest computation
  */
object DigestComputer:

    /** Minimal descriptor of a JAR central-directory entry used for content-addressed digest computation.
      *
      * Cross-platform: this type lives in shared/ so that digestForJar is callable on all platforms, even when JarCentralDirectory (JVM-only)
      * is absent. Tests construct JarDigestEntry instances directly for cross-platform digest correctness tests (INV-006).
      *
      * @param name
      *   entry name as decoded from the CEN (UTF-8 or CP437)
      * @param crc32
      *   CRC-32 checksum of uncompressed entry data (from CEN record offset +16); stored as unsigned Long
      */
    final case class JarDigestEntry(name: String, crc32: Long)

    // xxh64 prime constants (from the xxHash specification; hex to avoid signed-Long overflow in decimal literals).
    // Note: PKWARE APPNOTE.TXT 4.3.12 defines the CEN+16 CRC32 field layout used elsewhere; these prime values
    // are from the xxHash algorithm by Yann Collet, not from PKWARE.
    private val xxh3Prime641: Long = 0x9e3779b185ebca87L
    private val xxh3Prime642: Long = 0xc2b2ae3d27d4eb4fL
    private val xxh3Prime643: Long = 0x165667b19e3779f9L
    private val xxh3Prime644: Long = 0x85ebca77c2b2ae63L

    private def xxh3Mix(acc: Long, input: Long): Long =
        val mixed = acc + input * xxh3Prime642
        java.lang.Long.rotateLeft(mixed, 27) * xxh3Prime641 + xxh3Prime644

    private def xxh3Avalanche(h: Long): Long =
        val a = (h ^ (h >>> 37)) * xxh3Prime643
        a ^ (a >>> 32)

    /** Compute an xxh64-custom 64-bit content-addressed digest of a Chunk of JAR central-directory entries.
      *
      * Entries are sorted by name before mixing so that the digest is invariant under the order in which the JAR writer emitted the CEN
      * records (including fat-jar duplicate names). Each entry contributes its UTF-8 name bytes and its CRC32 field; mtime and disk path are
      * excluded entirely.
      *
      * Cross-platform: takes JarDigestEntry (shared) not JarEntry (JVM-only). JVM code converts via JarEntry.name/crc32 before calling.
      */
    def digestForJar(centralDirectoryEntries: Chunk[JarDigestEntry]): Long =
        val sorted    = centralDirectoryEntries.sortBy(_.name)
        var acc: Long = 0L
        var ei        = 0
        while ei < sorted.length do
            val e         = sorted(ei)
            val nameBytes = e.name.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            acc = xxh3Mix(acc, nameBytes.length.toLong)
            var i = 0
            while i < nameBytes.length do
                acc = xxh3Mix(acc, nameBytes(i).toLong & 0xffL)
                i += 1
            end while
            acc = xxh3Mix(acc, e.crc32)
            ei += 1
        end while
        xxh3Avalanche(acc)
    end digestForJar

    /** Compute the content-addressed digest for a single root.
      *
      * Dispatches to the platform-specific PlatformDigest.digestForJarRoot for jar roots (JVM: CEN walk; JS/Native: path-hash fallback).
      * For non-jar roots this method returns 0L; callers that need non-jar digests should use compute(roots, source) instead.
      *
      * Called by BundledSnapshotProbe to verify the embedded per-jar snapshot digest (INV-003).
      */
    def digestForRoot(root: String): Long =
        if root.toLowerCase.endsWith(".jar") then
            PlatformDigest.digestForJarRoot(root)
        else
            // Non-jar roots: not content-addressed at this call site; use compute(roots, source) for those.
            0L

    /** Path-hash fallback for platforms that cannot walk the JAR CEN.
      *
      * Called by JS/Native PlatformDigest.digestForJarRoot. Mixes the UTF-8 jar path bytes using the xxh64-custom hash to produce a
      * deterministic value. Not content-addressed: in-place jar mutation (same path, different bytes) will NOT change the digest.
      * Tests on JS/Native should verify determinism (same input twice), not cross-machine or content stability.
      */
    private[kyo] def digestForJarFallback(jarPath: String): Long =
        val pathBytes = jarPath.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        var acc: Long = 0L
        acc = xxh3Mix(acc, pathBytes.length.toLong)
        var i = 0
        while i < pathBytes.length do
            acc = xxh3Mix(acc, pathBytes(i).toLong & 0xffL)
            i += 1
        end while
        xxh3Avalanche(acc)
    end digestForJarFallback

    /** Compute a 64-bit digest of sorted (path, digestValue, 0L) tuples from the given roots.
      *
      * For jar roots on JVM, hashes the JAR CEN entries (name, CRC32) via PlatformDigest.digestForJarRoot (content-addressed; INV-003). For
      * jar roots on JS/Native, falls back to a path-only hash (not content-addressed for in-place jar mutation). For `jrt:/` roots and
      * directory roots, retains the per-file enumeration via `source.list` and `source.stat` (mtime + size).
      *
      * Deterministic: same input files and metadata always produce the same 8-byte result. The digest is returned as an 8-byte little-endian
      * array.
      */
    def compute(roots: Seq[String], source: FileSource)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        collectAllStats(roots, source).map: stats =>
            val sorted = stats.sortBy(_._1)
            var acc    = 0L
            for (path, mtime, size) <- sorted do
                val pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                acc = xxh3Mix(acc, pathBytes.length.toLong)
                var i = 0
                while i < pathBytes.length do
                    acc = xxh3Mix(acc, pathBytes(i).toLong & 0xffL)
                    i += 1
                end while
                acc = xxh3Mix(acc, mtime)
                acc = xxh3Mix(acc, size)
            end for
            longToBytes(xxh3Avalanche(acc))

    /** Compute a 64-bit digest of sorted (path, content) tuples from the given roots.
      *
      * For jar roots, hashes `(jarPath, jarBytes)` by reading the entire jar file's raw bytes. For `jrt:/` and directory roots, retains the
      * existing per-file content hashing.
      *
      * Slower than `compute` but detects content changes that leave mtime+size unchanged (e.g., in-place content edit with `touch -t`).
      */
    def computeParanoid(roots: Seq[String], source: FileSource)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        collectAllFiles(roots, source).flatMap: files =>
            val sorted = files.sortBy(identity)
            Kyo.foreach(sorted): path =>
                source.read(path).map: bytes =>
                    (path, bytes)
            .map: pairs =>
                var acc = 0L
                for (path, bytes) <- pairs do
                    val pathBytes = path.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    acc = xxh3Mix(acc, pathBytes.length.toLong)
                    var i = 0
                    while i < pathBytes.length do
                        acc = xxh3Mix(acc, pathBytes(i).toLong & 0xffL)
                        i += 1
                    end while
                    acc = xxh3Mix(acc, bytes.length.toLong)
                    var j = 0
                    while j < bytes.length do
                        acc = xxh3Mix(acc, bytes(j).toLong & 0xffL)
                        j += 1
                    end while
                end for
                longToBytes(xxh3Avalanche(acc))

    /** Collect (path, digestLong, 0L) for all roots, branching by root type.
      *
      * Jar roots on JVM contribute one triple (jarPath, cenDigest, 0L) via PlatformDigest.digestForJarRoot (content-addressed).
      * Jar roots on JS/Native contribute (jarPath, pathHash, 0L) via the path-only fallback (not content-addressed for in-place mutation).
      * jrt:/ roots and directory roots contribute one triple per .tasty file via source.stat. All triples are collected into one flat
      * sequence; the caller sorts globally before hashing.
      */
    private def collectAllStats(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Seq[(String, Long, Long)] < (Sync & Abort[TastyError]) =
        Kyo.foreach(roots): root =>
            if root.startsWith("jrt:/") then
                collectStats(Seq(root), source)
            else if root.toLowerCase.endsWith(".jar") then
                // IOException from PlatformDigest.digestForJarRoot (JVM: missing/corrupt jar) is
                // converted to TastyError.MalformedSection to prevent silent 0L digest (BLOCKER-1 fix).
                Sync.defer:
                    try
                        val jarDigest = PlatformDigest.digestForJarRoot(root)
                        Seq((root, jarDigest, 0L))
                    catch
                        case e: java.io.IOException =>
                            Abort.fail(TastyError.MalformedSection("jar-digest", s"$root: ${e.getMessage}", 0L))
            else
                collectStats(Seq(root), source)
        .map(_.flatten.toSeq)

    /** Collect all file paths for `computeParanoid`, branching by root type.
      *
      * Jar roots contribute the jar path itself (to be read as raw bytes). `jrt:/` roots and directory roots contribute per-file `.tasty`
      * paths from `source.list`.
      */
    private def collectAllFiles(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Seq[String] < (Sync & Abort[TastyError]) =
        Kyo.foreach(roots): root =>
            if root.startsWith("jrt:/") then
                collectFiles(Seq(root), source)
            else if root.toLowerCase.endsWith(".jar") then
                Seq(root)
            else
                collectFiles(Seq(root), source)
        .map(_.flatten.toSeq)

    /** Collect (path, mtime, size) for all .tasty files reachable from the given roots (directory or jrt:/ roots only). */
    private def collectStats(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Seq[(String, Long, Long)] < (Sync & Abort[TastyError]) =
        collectFiles(roots, source).flatMap: files =>
            Kyo.foreach(files): path =>
                source.stat(path).map: st =>
                    (path, st.mtimeMs, st.size)

    /** Collect all .tasty file paths from directory or jrt:/ roots. */
    private def collectFiles(
        roots: Seq[String],
        source: FileSource
    )(using Frame): Seq[String] < (Sync & Abort[TastyError]) =
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
