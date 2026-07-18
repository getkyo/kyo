package kyo.internal

import kyo.AllowUnsafe
import kyo.Chunk

/** Pure `Array[Byte]` zip central-directory reader and STORED-only writer, ported from the
  * CEN-parsing shape in
  * `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala`. No
  * `RandomAccessFile`, no `ByteBuffer`, no `java.util.HashMap`, no `Charset.forName`: entry names
  * are decoded UTF-8 when general-purpose bit 11 is set (every archive [[write]] produces sets it),
  * or via an embedded 128-entry CP437 high-table for a foreign archive that does not. Zip64
  * extensions are not supported: every archive this codebase produces or consumes is far below the
  * 4GB / 65535-entry threshold that requires them.
  */
private[kyo] object ZipArchive:

    /** One archive entry's identity and metadata, extracted from its central directory (CEN)
      * record. `lfhOffset` locates the local file header, whose own name/extra length is
      * authoritative for the entry's data start (never assumed to match the CEN's).
      */
    final case class Entry(
        name: String,
        isDirectory: Boolean,
        method: Int,
        compSize: Int,
        uncompSize: Int,
        crc32: Long,
        lfhOffset: Int,
        lastModifiedMs: Long
    )

    /** Thrown internally on a malformed archive (bad signature, truncated record, CRC-32 mismatch,
      * unsupported compression method); caught by the adjacent `FileSystem` backend and re-lifted
      * into a typed `FileIOException`. Bypasses `KyoException` (no `Frame`/public-API crossing at
      * this pure parsing layer) and uses `enableSuppression=false, writableStackTrace=false`.
      */
    final class ZipFormatException(msg: String) extends RuntimeException(msg, null, false, false)

    val MethodStored: Int   = 0
    val MethodDeflated: Int = 8

    private val SigEocd = 0x06054b50
    private val SigCen  = 0x02014b50
    private val SigLfh  = 0x04034b50

    private val EocdFixedSize = 22
    private val EocdMaxScan   = 65557 // 22 (EOCD fixed) + 65535 (max comment length)

    private val GpFlagUtf8 = 0x0800

    // Fixed MS-DOS date/time written to every entry this codec produces (1980-01-01 00:00:00, the
    // classic zip "no timestamp" default): keeps [[write]] fully deterministic, so the byte-identical
    // cross-platform parity a materialized archive must hold never depends on wall-clock skew between
    // platforms or test runs.
    private val WriteDosTime = 0
    private val WriteDosDate = 0x21

    // --- little-endian byte-order helpers ---
    private def u16(b: Array[Byte], off: Int): Int = (b(off) & 0xff) | ((b(off + 1) & 0xff) << 8)
    private def i32(b: Array[Byte], off: Int): Int =
        (b(off) & 0xff) | ((b(off + 1) & 0xff) << 8) | ((b(off + 2) & 0xff) << 16) | ((b(off + 3) & 0xff) << 24)
    private def u32(b: Array[Byte], off: Int): Long = i32(b, off).toLong & 0xffffffffL

    private def w16(buf: scala.collection.mutable.ArrayBuffer[Byte], v: Int): Unit =
        buf += (v & 0xff).toByte
        buf += ((v >>> 8) & 0xff).toByte
    private def w32(buf: scala.collection.mutable.ArrayBuffer[Byte], v: Long): Unit =
        buf += (v & 0xff).toByte
        buf += ((v >>> 8) & 0xff).toByte
        buf += ((v >>> 16) & 0xff).toByte
        buf += ((v >>> 24) & 0xff).toByte
    end w32

    // CP437 high half (byte 0x80-0xFF -> Unicode codepoint), used only to decode a foreign entry
    // name whose general-purpose bit 11 (UTF-8) is unset. Bytes 0x00-0x7F are ASCII in both CP437
    // and UTF-8, so no table entry is needed for them.
    private val cp437High: Array[Char] = Array(
        'Ç', 'ü', 'é', 'â', 'ä', 'à', 'å', 'ç',
        'ê', 'ë', 'è', 'ï', 'î', 'ì', 'Ä', 'Å',
        'É', 'æ', 'Æ', 'ô', 'ö', 'ò', 'û', 'ù',
        'ÿ', 'Ö', 'Ü', '¢', '£', '¥', '₧', 'ƒ',
        'á', 'í', 'ó', 'ú', 'ñ', 'Ñ', 'ª', 'º',
        '¿', '⌐', '¬', '½', '¼', '¡', '«', '»',
        '░', '▒', '▓', '│', '┤', '╡', '╢', '╖',
        '╕', '╣', '║', '╗', '╝', '╜', '╛', '┐',
        '└', '┴', '┬', '├', '─', '┼', '╞', '╟',
        '╚', '╔', '╩', '╦', '╠', '═', '╬', '╧',
        '╨', '╤', '╥', '╙', '╘', '╒', '╓', '╫',
        '╪', '┘', '┌', '█', '▄', '▌', '▐', '▀',
        'α', 'ß', 'Γ', 'π', 'Σ', 'σ', 'µ', 'τ',
        'Φ', 'Θ', 'Ω', 'δ', '∞', 'φ', 'ε', '∩',
        '≡', '±', '≥', '≤', '⌠', '⌡', '÷', '≈',
        '°', '∙', '·', '√', 'ⁿ', '²', '■', ' '
    )

    private def decodeName(bytes: Array[Byte], off: Int, len: Int, utf8: Boolean): String =
        if utf8 then new String(bytes, off, len, java.nio.charset.StandardCharsets.UTF_8)
        else
            val chars = new Array[Char](len)
            var i     = 0
            while i < len do
                val b = bytes(off + i) & 0xff
                chars(i) = if b < 0x80 then b.toChar else cp437High(b - 0x80)
                i += 1
            end while
            new String(chars)

    // MS-DOS date/time -> epoch millis, pure integer arithmetic (Howard Hinnant's civil-from-days,
    // proleptic Gregorian). No java.util.Calendar/Date: only used to expose a foreign archive's real
    // entry mtime through Path.PathStat; every entry this codec WRITES carries the fixed WriteDosTime
    // / WriteDosDate constants above, never a computed one.
    private def civilFromDays(z0: Long): (Long, Long, Long) =
        val z   = z0 + 719468L
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y   = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp  = (5L * doy + 2L) / 153L
        val d   = doy - (153L * mp + 2L) / 5L + 1L
        val m   = if mp < 10L then mp + 3L else mp - 9L
        (y + (if m <= 2L then 1L else 0L), m, d)
    end civilFromDays

    private def fromDosDateTime(dosTime: Int, dosDate: Int): Long =
        val second = (dosTime & 0x1f) * 2
        val minute = (dosTime >> 5) & 0x3f
        val hour   = (dosTime >> 11) & 0x1f
        val day    = dosDate & 0x1f
        val month  = (dosDate >> 5) & 0x0f
        val year   = ((dosDate >> 9) & 0x7f) + 1980
        val days   = daysFromCivil(year.toLong, month.toLong, day.toLong)
        (days * 86400L + hour * 3600L + minute * 60L + second) * 1000L
    end fromDosDateTime

    private def daysFromCivil(y0: Long, m: Long, d: Long): Long =
        val y   = if m <= 2 then y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400L
        val doy = (153L * (if m > 2 then m - 3 else m + 9) + 2L) / 5L + d - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        era * 146097L + doe - 719468L
    end daysFromCivil

    /** Scans `bytes` from the end for the EOCD record (signature 0x06054b50), returning its offset. */
    private def findEocd(bytes: Array[Byte]): Int =
        val fileLen   = bytes.length
        val scanLen   = math.min(EocdMaxScan, fileLen)
        val scanStart = fileLen - scanLen
        var i         = fileLen - EocdFixedSize
        var found     = -1
        while found < 0 && i >= scanStart do
            if i32(bytes, i) == SigEocd then found = i
            i -= 1
        if found >= 0 then found
        else throw new ZipFormatException("EOCD record not found (not a valid zip archive)")
    end findEocd

    /** Parses `bytes`' central directory into a `Chunk[Entry]`. A directory entry is identified by a
      * trailing `/` in its raw CEN name (stripped from the returned `Entry.name`).
      */
    def parse(bytes: Array[Byte]): Chunk[Entry] =
        if bytes.length == 0 then Chunk.empty
        else
            val eocdOffset = findEocd(bytes)
            val cenSize    = u32(bytes, eocdOffset + 12)
            val cenOffsetU = u32(bytes, eocdOffset + 16)
            if cenOffsetU < 0 || cenOffsetU > bytes.length then
                throw new ZipFormatException(s"CEN offset $cenOffsetU out of range [0, ${bytes.length})")
            val cenStart = cenOffsetU.toInt
            val cenEnd   = math.min(bytes.length, cenStart + cenSize.toInt)
            val results  = scala.collection.mutable.ArrayBuffer.empty[Entry]
            var pos      = cenStart
            var scanning = true
            while scanning && pos + 46 <= cenEnd do
                if i32(bytes, pos) != SigCen then scanning = false
                else
                    val gpFlag     = u16(bytes, pos + 8)
                    val method     = u16(bytes, pos + 10)
                    val modTime    = u16(bytes, pos + 12)
                    val modDate    = u16(bytes, pos + 14)
                    val crc        = u32(bytes, pos + 16)
                    val compSize   = u32(bytes, pos + 20)
                    val uncompSize = u32(bytes, pos + 24)
                    val nameLen    = u16(bytes, pos + 28)
                    val extraLen   = u16(bytes, pos + 30)
                    val commentLen = u16(bytes, pos + 32)
                    val lfhOffset  = u32(bytes, pos + 42)
                    val recordSize = 46 + nameLen + extraLen + commentLen
                    if pos + recordSize > cenEnd then scanning = false
                    else
                        val utf8    = (gpFlag & GpFlagUtf8) != 0
                        val rawName = decodeName(bytes, pos + 46, nameLen, utf8)
                        val isDir   = rawName.endsWith("/")
                        val name    = if isDir then rawName.stripSuffix("/") else rawName
                        results += Entry(
                            name,
                            isDir,
                            method,
                            compSize.toInt,
                            uncompSize.toInt,
                            crc,
                            lfhOffset.toInt,
                            fromDosDateTime(modTime, modDate)
                        )
                        pos += recordSize
                    end if
                end if
            end while
            Chunk.from(results.toIndexedSeq)

    /** Locates entry data start within `bytes` by reading `entry`'s own local file header (LFH). */
    private def dataStart(bytes: Array[Byte], entry: Entry): Int =
        val lfh = entry.lfhOffset
        // Bounds arithmetic is done in Long so a hostile lfhOffset near Int.MaxValue cannot
        // overflow the check into a negative Int and slip past it.
        if lfh < 0 || lfh.toLong + 30L > bytes.length then
            throw new ZipFormatException(
                s"local file header offset $lfh out of range [0, ${bytes.length}) for entry ${entry.name}"
            )
        end if
        if i32(bytes, lfh) != SigLfh then
            throw new ZipFormatException(s"local file header signature mismatch at offset $lfh for entry ${entry.name}")
        val nameLen  = u16(bytes, lfh + 26)
        val extraLen = u16(bytes, lfh + 28)
        val start    = lfh.toLong + 30L + nameLen + extraLen
        if start > bytes.length then
            throw new ZipFormatException(s"entry data start $start out of range for entry ${entry.name}")
        start.toInt
    end dataStart

    /** Reads and, if needed, inflates `entry`'s full content from `bytes`. STORED (method 0) is a
      * direct slice; DEFLATED (method 8) drives [[ZipInflate.inflateRaw]] over the raw deflate
      * stream. The recovered bytes are validated against the entry's own CRC-32 (never Adler-32:
      * zip's method-8 streams are raw RFC 1951, not RFC 1950 ZLIB-framed).
      */
    def readEntry(bytes: Array[Byte], entry: Entry)(using AllowUnsafe): Array[Byte] =
        val start = dataStart(bytes, entry)
        if entry.compSize < 0 || start.toLong + entry.compSize.toLong > bytes.length then
            throw new ZipFormatException(
                s"entry data range [$start, ${start.toLong + entry.compSize.toLong}) out of bounds [0, ${bytes.length}) for entry ${entry.name}"
            )
        end if
        val raw =
            if entry.method == MethodStored then
                java.util.Arrays.copyOfRange(bytes, start, start + entry.compSize)
            else if entry.method == MethodDeflated then
                try ZipInflate.inflateRaw(bytes, start.toLong * 8L, start + entry.compSize)
                catch
                    case e: ZipInflate.InflateException =>
                        throw new ZipFormatException(s"deflate decode failed for entry ${entry.name}: ${e.getMessage}")
                    case _: IndexOutOfBoundsException =>
                        throw new ZipFormatException(s"deflate stream overran archive bounds for entry ${entry.name}")
            else
                throw new ZipFormatException(s"unsupported compression method ${entry.method} for entry ${entry.name}")
        val actualCrc = FileSystemCrc32.of(raw).toLong & 0xffffffffL
        if actualCrc != entry.crc32 then
            throw new ZipFormatException(s"CRC-32 mismatch for entry ${entry.name}: expected ${entry.crc32}, got $actualCrc")
        raw
    end readEntry

    /** Serializes `entries` (name, isDirectory, content) into a STORED-only (uncompressed, zip
      * method 0) archive: local file headers plus data, followed by the central directory and EOCD.
      * Entry names are written UTF-8 with general-purpose bit 11 set, so any conformant reader
      * (including this codec's own [[parse]]) decodes them without CP437 ambiguity.
      */
    def write(entries: Chunk[(String, Boolean, Array[Byte])]): Array[Byte] =
        val local = new scala.collection.mutable.ArrayBuffer[Byte]()
        val cen   = new scala.collection.mutable.ArrayBuffer[Byte]()
        var count = 0
        entries.foreach { case (name, isDir, content) =>
            val entryName = if isDir && !name.endsWith("/") then name + "/" else name
            val nameBytes = entryName.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            val data      = if isDir then Array.emptyByteArray else content
            val crc       = FileSystemCrc32.of(data).toLong & 0xffffffffL
            val lfhOffset = local.length

            w32(local, SigLfh.toLong)
            w16(local, 20) // version needed to extract
            w16(local, GpFlagUtf8)
            w16(local, MethodStored)
            w16(local, WriteDosTime)
            w16(local, WriteDosDate)
            w32(local, crc)
            w32(local, data.length.toLong)
            w32(local, data.length.toLong)
            w16(local, nameBytes.length)
            w16(local, 0) // extra field length
            local ++= nameBytes
            local ++= data

            w32(cen, SigCen.toLong)
            w16(cen, 20) // version made by
            w16(cen, 20) // version needed to extract
            w16(cen, GpFlagUtf8)
            w16(cen, MethodStored)
            w16(cen, WriteDosTime)
            w16(cen, WriteDosDate)
            w32(cen, crc)
            w32(cen, data.length.toLong)
            w32(cen, data.length.toLong)
            w16(cen, nameBytes.length)
            w16(cen, 0)                                 // extra field length
            w16(cen, 0)                                 // comment length
            w16(cen, 0)                                 // disk number start
            w16(cen, 0)                                 // internal attributes
            w32(cen, if isDir then 0x10L << 16 else 0L) // external attributes: MS-DOS directory bit
            w32(cen, lfhOffset.toLong)
            cen ++= nameBytes

            count += 1
        }
        val cenOffset = local.length
        val out       = new scala.collection.mutable.ArrayBuffer[Byte](local.length + cen.length + EocdFixedSize)
        out ++= local
        out ++= cen
        w32(out, SigEocd.toLong)
        w16(out, 0)     // disk number
        w16(out, 0)     // disk with CEN start
        w16(out, count) // entries on this disk
        w16(out, count) // total entries
        w32(out, cen.length.toLong)
        w32(out, cenOffset.toLong)
        w16(out, 0) // comment length
        out.toArray
    end write

end ZipArchive
