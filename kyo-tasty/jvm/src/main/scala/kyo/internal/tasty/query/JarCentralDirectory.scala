package kyo.internal.tasty.query

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.*
import scala.collection.mutable

/** Direct ZIP central-directory reader for JAR files.
  *
  * Reads entry names by parsing the ZIP central directory (CEN) directly via RandomAccessFile. Does NOT call JarFile.entries() or
  * ZipFile.entries(), avoiding per-entry JarFile$JarFileEntry object allocation.
  *
  * ZIP format overview:
  *   - The file ends with an End-Of-Central-Directory (EOCD) record, signature 0x06054b50.
  *   - The EOCD record contains the offset and size of the central directory.
  *   - Each central directory entry (CEN record) has signature 0x02014b50 and contains the entry name, flags, compression method, sizes,
  *     and local file header offset.
  *   - Zip64 extension: if a JAR has more than 65535 entries or exceeds 4 GB, a Zip64 EOCD locator (signature 0x07064b50) precedes the
  *     EOCD, and a Zip64 EOCD record (signature 0x06064b50) provides the true entry count and CEN offset.
  *   - Entry name encoding: general-purpose-bit-11 set means UTF-8; otherwise CP437.
  *
  * Multi-disk JARs are not supported (they are also unsupported by the standard JVM JarFile).
  */
private[kyo] object JarCentralDirectory:

    /** Full metadata for a single JAR entry, extracted from the central directory record.
      *
      * @param name
      *   entry name as decoded from the CEN (UTF-8 or CP437 depending on bit-11 of gpFlag)
      * @param lfhOffset
      *   byte offset of the local file header (LFH) from the start of the JAR; used to skip to data
      * @param compSize
      *   compressed size in bytes; already resolved from Zip64 extra field if the CEN field was 0xFFFFFFFF
      * @param uncompSize
      *   uncompressed size in bytes; already resolved from Zip64 extra field if the CEN field was 0xFFFFFFFF
      * @param method
      *   compression method: 0 = STORED, 8 = DEFLATED
      * @param crc32
      *   CRC-32 checksum of uncompressed entry data (CEN record offset +16 per PKWARE APPNOTE.TXT 4.3.12); stored as unsigned Long
      */
    final case class JarEntry(name: String, lfhOffset: Long, compSize: Long, uncompSize: Long, method: Short, crc32: Long)

    // ZIP signature constants
    private val SIG_EOCD: Int  = 0x06054b50
    private val SIG_ZIP64_LOC  = 0x07064b50
    private val SIG_ZIP64_EOCD = 0x06064b50
    private val SIG_CEN: Int   = 0x02014b50

    // EOCD record size (fixed part): signature(4) + diskNum(2) + startDisk(2) + entriesOnDisk(2)
    // + totalEntries(2) + cenSize(4) + cenOffset(4) + commentLen(2) = 22 bytes
    private val EOCD_FIXED_SIZE = 22

    // Maximum size to scan from the end for the EOCD record:
    // 22 (EOCD fixed) + 65535 (max comment length) = 65557
    private val EOCD_MAX_SCAN = 65557

    // Zip64 EOCD locator size: signature(4) + startDisk(4) + zip64EocdOffset(8) + totalDisks(4) = 20 bytes
    private val ZIP64_LOC_SIZE = 20

    // Zip64 EOCD record fixed part size: signature(4) + recordSize(8) + versionMade(2) + versionNeeded(2)
    // + diskNum(4) + startDisk(4) + entriesOnDisk(8) + totalEntries(8) + cenSize(8) + cenOffset(8) = 56 bytes
    private val ZIP64_EOCD_FIXED_SIZE = 56

    // General-purpose bit flag: bit 11 = UTF-8 entry names
    private val GP_FLAG_UTF8 = 0x0800

    // CP437 charset used for ZIP entry names when bit 11 is not set
    private val CP437: Charset = Charset.forName("IBM437")

    /** List all entries in a JAR file whose names end with any of the given suffixes.
      *
      * Returns a Chunk of (jarPath, entryName) pairs, where jarPath is the input path verbatim. The caller is responsible for composing the
      * canonical "jarPath!/entryName" string only when actually reading file content, avoiding an allocation per-entry at enumeration time.
      *
      * Effect row: Sync (file I/O) and Abort[TastyError] (format errors, multi-disk jars, non-JAR files).
      */
    def list(jarPath: String, suffixes: Chunk[String])(using Frame): Chunk[(String, String)] < (Sync & Abort[TastyError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else
            Sync.defer {
                var raf: RandomAccessFile = null
                try
                    raf = new RandomAccessFile(jarPath, "r")
                    listEntries(jarPath, raf, suffixes)
                catch
                    case err: TastyErrorWrapper => Abort.fail(err.error)
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.MalformedSection("jar", s"$jarPath: ${ex.getMessage}", 0L))
                finally
                    if raf != null then
                        try raf.close()
                        catch case _: java.io.IOException => ()
                end try
            }

    /** List all entries in a JAR file whose names end with any of the given suffixes, returning full entry metadata.
      *
      * Returns a Chunk of JarEntry values. The JarEntry carries the local-file-header offset, compressed/uncompressed sizes, and
      * compression method, enabling direct mmap-based reading without opening a JarFile.
      *
      * Effect row: Sync (file I/O) and Abort[TastyError] (format errors, multi-disk jars, non-JAR files).
      */
    def listFull(jarPath: String, suffixes: Chunk[String])(using Frame): Chunk[JarEntry] < (Sync & Abort[TastyError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else
            Sync.defer {
                var raf: RandomAccessFile = null
                try
                    raf = new RandomAccessFile(jarPath, "r")
                    listEntriesFull(jarPath, raf, suffixes)
                catch
                    case err: TastyErrorWrapper => Abort.fail(err.error)
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.MalformedSection("jar", s"$jarPath: ${ex.getMessage}", 0L))
                finally
                    if raf != null then
                        try raf.close()
                        catch case _: java.io.IOException => ()
                end try
            }

    /** Read all CEN entries from a JAR file, returning them as a Chunk of JarEntry.
      *
      * Used by DigestComputer to walk the central directory for content-addressed digest computation. Returns ALL entries (no
      * suffix filter); directories are excluded. Runs synchronously under AllowUnsafe; no Scope or Sync effect required.
      *
      * Propagates IOException on missing or corrupt jar files: the caller (PlatformDigest.digestForJarRoot on JVM) must handle it.
      * Swallowing IOException here would cause digestForJar(Chunk.empty) == 0L, producing silent false-positive cache hits.
      *
      * Unsafe: synchronous JAR CEN walk via RandomAccessFile; bounded to this call site; no Scope required.
      */
    private[kyo] def read(jarPath: String)(using AllowUnsafe): Chunk[JarEntry] =
        var raf: RandomAccessFile = null
        try
            raf = new RandomAccessFile(jarPath, "r")
            val fileLen = raf.length()
            if fileLen == 0 then
                throw new java.io.IOException(s"$jarPath: empty file")
            else
                val (eocdOffset, eocdBuf)     = findEocd(jarPath, raf, fileLen)
                val (totalEntries, cenOffset) = readCenLocation(jarPath, raf, fileLen, eocdOffset, eocdBuf)

                if cenOffset < 0 || cenOffset >= fileLen then
                    throw new java.io.IOException(s"$jarPath: CEN offset $cenOffset out of range [0, $fileLen)")
                else
                    val cenSizeLong = (eocdOffset - cenOffset).max(0L)
                    if cenSizeLong > Int.MaxValue then
                        throw new java.io.IOException(
                            s"$jarPath: central directory size $cenSizeLong exceeds 2GB"
                        )
                    else
                        val cenSize = cenSizeLong.toInt
                        val cenBuf  = new Array[Byte](cenSize)
                        raf.seek(cenOffset)
                        raf.readFully(cenBuf)
                        readAllCenEntries(jarPath, cenBuf, cenSize)
                    end if
                end if
            end if
        catch
            case e: TastyErrorWrapper => throw new java.io.IOException(e.error.toString, e)
        finally
            if raf != null then
                try raf.close()
                catch case _: java.io.IOException => ()
        end try
    end read

    /** Parse ALL central directory records from cenBuf into a Chunk (no suffix filter, no HashMap dedup).
      *
      * Used by `read` for the digest walk. Directories (names ending with '/') are skipped.
      */
    private def readAllCenEntries(jarPath: String, cenBuf: Array[Byte], cenSize: Int): Chunk[JarEntry] =
        val results = mutable.ArrayBuffer.empty[JarEntry]
        var pos     = 0

        while pos + 46 <= cenSize do
            val sig = readInt32LE(cenBuf, pos)
            if sig != SIG_CEN then
                pos = cenSize // break
            else
                val gpFlag = readUInt16LE(cenBuf, pos + 8)
                val method = readUInt16LE(cenBuf, pos + 10).toShort
                // CRC-32 at CEN record offset +16 per PKWARE APPNOTE.TXT 4.3.12
                val crc32      = readUInt32LE(cenBuf, pos + 16)
                var compSize   = readUInt32LE(cenBuf, pos + 20)
                var uncompSize = readUInt32LE(cenBuf, pos + 24)
                val nameLen    = readUInt16LE(cenBuf, pos + 28)
                val extraLen   = readUInt16LE(cenBuf, pos + 30)
                val commentLen = readUInt16LE(cenBuf, pos + 32)
                var lfhOffset  = readUInt32LE(cenBuf, pos + 42)
                val recordSize = 46 + nameLen + extraLen + commentLen

                if pos + recordSize > cenSize then
                    pos = cenSize // truncated record, stop
                else
                    // Resolve Zip64 extra field if sentinel values are present
                    val needsZip64 = compSize == 0xffffffffL || uncompSize == 0xffffffffL || lfhOffset == 0xffffffffL
                    if needsZip64 && extraLen >= 4 then
                        val extraStart = pos + 46 + nameLen
                        var ep         = extraStart
                        val extraEnd   = extraStart + extraLen
                        var done       = false
                        while ep + 4 <= extraEnd && !done do
                            val hdrId    = readUInt16LE(cenBuf, ep)
                            val dataSize = readUInt16LE(cenBuf, ep + 2)
                            if hdrId == 0x0001 then
                                var dp = ep + 4
                                if uncompSize == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    uncompSize = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                if compSize == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    compSize = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                if lfhOffset == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    lfhOffset = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                done = true
                            else
                                ep += 4 + dataSize
                            end if
                        end while
                    end if

                    // Decode entry name: bit 11 set means UTF-8, otherwise CP437
                    val charset = if (gpFlag & GP_FLAG_UTF8) != 0 then StandardCharsets.UTF_8 else CP437
                    val name    = new String(cenBuf, pos + 46, nameLen, charset)

                    // Skip directories
                    if !name.endsWith("/") then
                        results += JarEntry(name, lfhOffset, compSize, uncompSize, method, crc32)

                    pos += recordSize
                end if
            end if
        end while

        Chunk.from(results.toSeq)
    end readAllCenEntries

    /** Parse all entries in a JAR (regardless of suffix) into a HashMap keyed by entry name.
      *
      * Called by JarMappedReader.init to build its internal entry index. The MappedByteBuffer is used for EOCD and CEN parsing; the channel
      * must be seekable (which MappedByteBuffer is, via position()).
      *
      * Throws java.io.IOException (not TastyError) since this is a private utility called from JarMappedReader where IOException is already
      * the declared failure mode.
      */
    private[kyo] def parseAllEntries(jarPath: String, buffer: ByteBuffer): java.util.HashMap[String, JarEntry] =
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val fileLen = buffer.limit().toLong
        if fileLen == 0 then throw new java.io.IOException(s"$jarPath: empty file")

        val (eocdOffset, stdEntries, stdCenOffset, isZip64) = findEocdBuf(jarPath, buffer, fileLen)
        val (totalEntries, cenOffset) = readCenLocationBuf(jarPath, buffer, fileLen, eocdOffset, stdEntries, stdCenOffset)

        if cenOffset < 0 || cenOffset >= fileLen then
            throw new java.io.IOException(s"$jarPath: CEN offset $cenOffset out of range [0, $fileLen)")

        val cenSizeLong = (eocdOffset - cenOffset).max(0L)
        if cenSizeLong > Int.MaxValue then
            throw new java.io.IOException(
                s"$jarPath: central directory size $cenSizeLong exceeds 2GB; Zip64 required"
            )
        end if
        val cenSize = cenSizeLong.toInt
        val cenBuf  = new Array[Byte](cenSize)
        if cenOffset > Int.MaxValue then
            throw new java.io.IOException(
                s"$jarPath: cenOffset $cenOffset exceeds 2GB mmap range; Zip64 required"
            )
        end if
        buffer.position(cenOffset.toInt)
        buffer.get(cenBuf)

        parseCenRecordsAll(jarPath, cenBuf, cenSize)
    end parseAllEntries

    /** Thrown internally to propagate TastyError through Java APIs that require checked exceptions.
      *
      * Internal sentinel: caught by the adjacent JAR parser and re-lifted into `Abort.fail(error)` on the
      * `Abort[TastyError]` row. Deliberately bypasses `KyoException` (no public-API crossing) and uses
      * `enableSuppression=false, writableStackTrace=false` so the throw path skips stack-trace
      * materialisation (NoStackTrace flags).
      */
    final private class TastyErrorWrapper(val error: TastyError) extends Exception(null, null, false, false)

    /** Core CEN parsing logic. Assumes raf is open and positioned at start. */
    private def listEntries(
        jarPath: String,
        raf: RandomAccessFile,
        suffixes: Chunk[String]
    ): Chunk[(String, String)] =
        val fileLen = raf.length()
        if fileLen == 0 then throw new TastyErrorWrapper(TastyError.MalformedSection("jar", s"$jarPath: empty file", 0L))

        // Locate EOCD record by scanning from the end
        val (eocdOffset, eocdBuf) = findEocd(jarPath, raf, fileLen)

        // Check for Zip64: Zip64 EOCD locator is immediately before the EOCD record
        val (totalEntries, cenOffset) = readCenLocation(jarPath, raf, fileLen, eocdOffset, eocdBuf)

        // Validate CEN offset is within file bounds
        if cenOffset < 0 || cenOffset >= fileLen then
            throw new TastyErrorWrapper(
                TastyError.MalformedSection("jar", s"$jarPath: CEN offset $cenOffset out of range [0, $fileLen)", eocdOffset)
            )
        end if

        // Read entire central directory into memory
        val cenSizeLong0 = (eocdOffset - cenOffset).max(0L)
        if cenSizeLong0 > Int.MaxValue then
            throw new TastyErrorWrapper(
                TastyError.MalformedSection(
                    "jar",
                    s"$jarPath: central directory size $cenSizeLong0 exceeds 2GB; Zip64 required",
                    eocdOffset
                )
            )
        end if
        val cenSize = cenSizeLong0.toInt
        val cenBuf  = new Array[Byte](cenSize)
        raf.seek(cenOffset)
        raf.readFully(cenBuf)

        // Parse CEN records
        parseCenRecords(jarPath, cenBuf, cenSize, totalEntries, suffixes)
    end listEntries

    /** Scan from the end of the file for the EOCD record (signature 0x06054b50).
      *
      * Returns (eocdOffset, eocdBuf) where eocdBuf is the 22-byte fixed EOCD data.
      */
    private def findEocd(jarPath: String, raf: RandomAccessFile, fileLen: Long): (Long, Array[Byte]) =
        // Scan up to EOCD_MAX_SCAN bytes from the end. EOCD_MAX_SCAN is 65557, always < Int.MaxValue,
        // so the .min result is safe as Int without truncating fileLen first.
        val scanLen = EOCD_MAX_SCAN.toLong.min(fileLen).toInt
        val scanBuf = new Array[Byte](scanLen)
        raf.seek(fileLen - scanLen)
        raf.readFully(scanBuf)

        // Search backwards for EOCD signature
        var i = scanLen - EOCD_FIXED_SIZE
        while i >= 0 do
            if readInt32LE(scanBuf, i) == SIG_EOCD then
                val eocdOffset = fileLen - scanLen + i
                val eocdBuf    = new Array[Byte](EOCD_FIXED_SIZE)
                java.lang.System.arraycopy(scanBuf, i, eocdBuf, 0, EOCD_FIXED_SIZE)
                return (eocdOffset, eocdBuf)
            end if
            i -= 1
        end while

        throw new TastyErrorWrapper(
            TastyError.MalformedSection("jar", s"$jarPath: EOCD record not found (not a valid ZIP/JAR file)", fileLen)
        )
    end findEocd

    /** Read the total entry count and CEN offset, with Zip64 handling.
      *
      * Checks for a Zip64 EOCD locator immediately before the EOCD. If present and the Zip64 EOCD record is readable, use it. Otherwise
      * fall back to standard EOCD fields.
      */
    private def readCenLocation(
        jarPath: String,
        raf: RandomAccessFile,
        fileLen: Long,
        eocdOffset: Long,
        eocdBuf: Array[Byte]
    ): (Long, Long) =
        // Standard EOCD fields (may be 0xFFFF/0xFFFFFFFF sentinel for Zip64)
        val stdDiskNum   = readUInt16LE(eocdBuf, 4)
        val stdStartDisk = readUInt16LE(eocdBuf, 6)
        val stdEntries   = readUInt16LE(eocdBuf, 10).toLong
        val stdCenOffset = readUInt32LE(eocdBuf, 16)

        // Multi-disk check on standard EOCD
        if stdDiskNum != 0 || stdStartDisk != 0 then
            throw new TastyErrorWrapper(
                TastyError.MalformedSection("jar", s"$jarPath: multi-disk jars unsupported", eocdOffset)
            )
        end if

        // Check for Zip64 EOCD locator: it sits immediately before the EOCD record
        val locOffset = eocdOffset - ZIP64_LOC_SIZE
        if locOffset >= 0 then
            val locBuf = new Array[Byte](ZIP64_LOC_SIZE)
            raf.seek(locOffset)
            raf.readFully(locBuf)
            if readInt32LE(locBuf, 0) == SIG_ZIP64_LOC then
                // Zip64 locator found: parse it
                val locDiskCount = readUInt32LE(locBuf, 16)
                if locDiskCount > 1 then
                    throw new TastyErrorWrapper(
                        TastyError.MalformedSection("jar", s"$jarPath: multi-disk jars unsupported", locOffset)
                    )
                end if
                val zip64EocdOffset = readUInt64LE(locBuf, 8)
                if zip64EocdOffset >= 0 && zip64EocdOffset < fileLen then
                    val zip64Buf = new Array[Byte](ZIP64_EOCD_FIXED_SIZE)
                    raf.seek(zip64EocdOffset)
                    raf.readFully(zip64Buf)
                    if readInt32LE(zip64Buf, 0) == SIG_ZIP64_EOCD then
                        val zip64DiskNum   = readUInt32LE(zip64Buf, 16)
                        val zip64StartDisk = readUInt32LE(zip64Buf, 20)
                        if zip64DiskNum != 0 || zip64StartDisk != 0 then
                            throw new TastyErrorWrapper(
                                TastyError.MalformedSection("jar", s"$jarPath: multi-disk jars unsupported", zip64EocdOffset)
                            )
                        end if
                        val totalEntries = readUInt64LE(zip64Buf, 32)
                        val cenOffset    = readUInt64LE(zip64Buf, 48)
                        return (totalEntries, cenOffset)
                    end if
                end if
            end if
        end if

        // Fall back to standard EOCD
        (stdEntries, stdCenOffset)
    end readCenLocation

    /** Parse central directory records from cenBuf, collecting entries whose names match suffixes. */
    private def parseCenRecords(
        jarPath: String,
        cenBuf: Array[Byte],
        cenSize: Int,
        totalEntries: Long,
        suffixes: Chunk[String]
    ): Chunk[(String, String)] =
        val results = mutable.ArrayBuffer.empty[(String, String)]
        var pos     = 0
        var count   = 0L

        while pos + 46 <= cenSize do
            val sig = readInt32LE(cenBuf, pos)
            if sig != SIG_CEN then
                // Stop at first non-CEN record (e.g., end of central directory)
                pos = cenSize // break
            else
                val gpFlag = readUInt16LE(cenBuf, pos + 8)
                // CRC-32 at CEN record offset +16 per PKWARE APPNOTE.TXT 4.3.12
                val crc32      = readUInt32LE(cenBuf, pos + 16)
                val nameLen    = readUInt16LE(cenBuf, pos + 28)
                val extraLen   = readUInt16LE(cenBuf, pos + 30)
                val commentLen = readUInt16LE(cenBuf, pos + 32)
                val recordSize = 46 + nameLen + extraLen + commentLen

                if pos + recordSize > cenSize then
                    pos = cenSize // truncated record, stop
                else
                    // Decode entry name: bit 11 set means UTF-8, otherwise CP437
                    val charset = if (gpFlag & GP_FLAG_UTF8) != 0 then StandardCharsets.UTF_8 else CP437
                    val name    = new String(cenBuf, pos + 46, nameLen, charset)

                    // Skip directories (names ending with '/')
                    if !name.endsWith("/") then
                        var i = 0
                        while i < suffixes.length do
                            if name.endsWith(suffixes(i)) then
                                results += ((jarPath, name))
                                i = suffixes.length // break
                            else
                                i += 1
                        end while
                    end if

                    pos += recordSize
                    count += 1
                end if
            end if
        end while

        Chunk.from(results.toSeq)
    end parseCenRecords

    /** Core full-metadata CEN parsing logic. Assumes raf is open and positioned at start. */
    private def listEntriesFull(
        jarPath: String,
        raf: RandomAccessFile,
        suffixes: Chunk[String]
    ): Chunk[JarEntry] =
        val fileLen = raf.length()
        if fileLen == 0 then throw new TastyErrorWrapper(TastyError.MalformedSection("jar", s"$jarPath: empty file", 0L))

        val (eocdOffset, eocdBuf)     = findEocd(jarPath, raf, fileLen)
        val (totalEntries, cenOffset) = readCenLocation(jarPath, raf, fileLen, eocdOffset, eocdBuf)

        if cenOffset < 0 || cenOffset >= fileLen then
            throw new TastyErrorWrapper(
                TastyError.MalformedSection("jar", s"$jarPath: CEN offset $cenOffset out of range [0, $fileLen)", eocdOffset)
            )
        end if

        val cenSizeLong1 = (eocdOffset - cenOffset).max(0L)
        if cenSizeLong1 > Int.MaxValue then
            throw new TastyErrorWrapper(
                TastyError.MalformedSection(
                    "jar",
                    s"$jarPath: central directory size $cenSizeLong1 exceeds 2GB; Zip64 required",
                    eocdOffset
                )
            )
        end if
        val cenSize = cenSizeLong1.toInt
        val cenBuf  = new Array[Byte](cenSize)
        raf.seek(cenOffset)
        raf.readFully(cenBuf)

        parseCenRecordsFull(jarPath, cenBuf, cenSize, suffixes)
    end listEntriesFull

    /** Parse central directory records from cenBuf, returning full JarEntry metadata for entries matching suffixes. */
    private def parseCenRecordsFull(
        jarPath: String,
        cenBuf: Array[Byte],
        cenSize: Int,
        suffixes: Chunk[String]
    ): Chunk[JarEntry] =
        val results = mutable.ArrayBuffer.empty[JarEntry]
        var pos     = 0

        while pos + 46 <= cenSize do
            val sig = readInt32LE(cenBuf, pos)
            if sig != SIG_CEN then
                pos = cenSize // break
            else
                val gpFlag = readUInt16LE(cenBuf, pos + 8)
                val method = readUInt16LE(cenBuf, pos + 10).toShort
                // CRC-32 at CEN record offset +16 per PKWARE APPNOTE.TXT 4.3.12
                val crc32      = readUInt32LE(cenBuf, pos + 16)
                var compSize   = readUInt32LE(cenBuf, pos + 20)
                var uncompSize = readUInt32LE(cenBuf, pos + 24)
                val nameLen    = readUInt16LE(cenBuf, pos + 28)
                val extraLen   = readUInt16LE(cenBuf, pos + 30)
                val commentLen = readUInt16LE(cenBuf, pos + 32)
                var lfhOffset  = readUInt32LE(cenBuf, pos + 42)
                val recordSize = 46 + nameLen + extraLen + commentLen

                if pos + recordSize > cenSize then
                    pos = cenSize // truncated record, stop
                else
                    // Resolve Zip64 extra field if sentinel values are present
                    val needsZip64 = compSize == 0xffffffffL || uncompSize == 0xffffffffL || lfhOffset == 0xffffffffL
                    if needsZip64 && extraLen >= 4 then
                        val extraStart = pos + 46 + nameLen
                        var ep         = extraStart
                        val extraEnd   = extraStart + extraLen
                        var done       = false
                        while ep + 4 <= extraEnd && !done do
                            val hdrId    = readUInt16LE(cenBuf, ep)
                            val dataSize = readUInt16LE(cenBuf, ep + 2)
                            if hdrId == 0x0001 then
                                // Zip64 extended information extra field
                                var dp = ep + 4
                                if uncompSize == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    uncompSize = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                if compSize == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    compSize = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                if lfhOffset == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    lfhOffset = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                done = true
                            else
                                ep += 4 + dataSize
                            end if
                        end while
                    end if

                    // Decode entry name: bit 11 set means UTF-8, otherwise CP437
                    val charset = if (gpFlag & GP_FLAG_UTF8) != 0 then StandardCharsets.UTF_8 else CP437
                    val name    = new String(cenBuf, pos + 46, nameLen, charset)

                    // Skip directories (names ending with '/')
                    if !name.endsWith("/") then
                        var i = 0
                        while i < suffixes.length do
                            if name.endsWith(suffixes(i)) then
                                results += JarEntry(name, lfhOffset, compSize, uncompSize, method, crc32)
                                i = suffixes.length // break
                            else
                                i += 1
                        end while
                    end if

                    pos += recordSize
                end if
            end if
        end while

        Chunk.from(results.toSeq)
    end parseCenRecordsFull

    /** Parse ALL central directory records from cenBuf into a HashMap keyed by entry name.
      *
      * Used by JarMappedReader.init to build its entry index. Includes all entries (no suffix filter). Directories (names ending with '/')
      * are skipped.
      */
    private[kyo] def parseCenRecordsAll(
        jarPath: String,
        cenBuf: Array[Byte],
        cenSize: Int
    ): java.util.HashMap[String, JarEntry] =
        val results = new java.util.HashMap[String, JarEntry]()
        var pos     = 0

        while pos + 46 <= cenSize do
            val sig = readInt32LE(cenBuf, pos)
            if sig != SIG_CEN then
                pos = cenSize // break
            else
                val gpFlag = readUInt16LE(cenBuf, pos + 8)
                val method = readUInt16LE(cenBuf, pos + 10).toShort
                // CRC-32 at CEN record offset +16 per PKWARE APPNOTE.TXT 4.3.12
                val crc32      = readUInt32LE(cenBuf, pos + 16)
                var compSize   = readUInt32LE(cenBuf, pos + 20)
                var uncompSize = readUInt32LE(cenBuf, pos + 24)
                val nameLen    = readUInt16LE(cenBuf, pos + 28)
                val extraLen   = readUInt16LE(cenBuf, pos + 30)
                val commentLen = readUInt16LE(cenBuf, pos + 32)
                var lfhOffset  = readUInt32LE(cenBuf, pos + 42)
                val recordSize = 46 + nameLen + extraLen + commentLen

                if pos + recordSize > cenSize then
                    throw new java.io.IOException(
                        s"$jarPath: truncated CEN record at $pos: declared size $recordSize exceeds remaining ${cenSize - pos}"
                    )
                else
                    // Resolve Zip64 extra field if sentinel values are present
                    val needsZip64 = compSize == 0xffffffffL || uncompSize == 0xffffffffL || lfhOffset == 0xffffffffL
                    if needsZip64 && extraLen >= 4 then
                        val extraStart = pos + 46 + nameLen
                        var ep         = extraStart
                        val extraEnd   = extraStart + extraLen
                        var done       = false
                        while ep + 4 <= extraEnd && !done do
                            val hdrId    = readUInt16LE(cenBuf, ep)
                            val dataSize = readUInt16LE(cenBuf, ep + 2)
                            if hdrId == 0x0001 then
                                var dp = ep + 4
                                if uncompSize == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    uncompSize = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                if compSize == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    compSize = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                if lfhOffset == 0xffffffffL && dp + 8 <= ep + 4 + dataSize then
                                    lfhOffset = readUInt64LE(cenBuf, dp)
                                    dp += 8
                                end if
                                done = true
                            else
                                ep += 4 + dataSize
                            end if
                        end while
                    end if

                    // Decode entry name: bit 11 set means UTF-8, otherwise CP437
                    val charset = if (gpFlag & GP_FLAG_UTF8) != 0 then StandardCharsets.UTF_8 else CP437
                    val name    = new String(cenBuf, pos + 46, nameLen, charset)

                    // Skip directories
                    if !name.endsWith("/") then
                        results.put(name, JarEntry(name, lfhOffset, compSize, uncompSize, method, crc32)): Unit

                    pos += recordSize
                end if
            end if
        end while

        results
    end parseCenRecordsAll

    /** EOCD scan variant using a ByteBuffer (for parseAllEntries / mmap path).
      *
      * Returns (eocdOffset, stdEntries, stdCenOffset, isZip64) where isZip64 is a placeholder; the caller may ignore it.
      */
    private def findEocdBuf(
        jarPath: String,
        buffer: ByteBuffer,
        fileLen: Long
    ): (Long, Long, Long, Boolean) =
        // EOCD_MAX_SCAN is 65557, always < Int.MaxValue, so the result is safe as Int.
        val scanLen         = EOCD_MAX_SCAN.toLong.min(fileLen).toInt
        val scanBuf         = new Array[Byte](scanLen)
        val scanStartOffset = fileLen - scanLen
        if scanStartOffset > Int.MaxValue then
            throw new java.io.IOException(
                s"$jarPath: EOCD scan start offset $scanStartOffset exceeds 2GB mmap range; Zip64 required"
            )
        end if
        buffer.position(scanStartOffset.toInt)
        buffer.get(scanBuf)

        var i = scanLen - EOCD_FIXED_SIZE
        while i >= 0 do
            if readInt32LE(scanBuf, i) == SIG_EOCD then
                val eocdOffset = fileLen - scanLen + i
                val eocdBuf    = new Array[Byte](EOCD_FIXED_SIZE)
                java.lang.System.arraycopy(scanBuf, i, eocdBuf, 0, EOCD_FIXED_SIZE)
                val stdEntries   = readUInt16LE(eocdBuf, 10).toLong
                val stdCenOffset = readUInt32LE(eocdBuf, 16)
                return (eocdOffset, stdEntries, stdCenOffset, false)
            end if
            i -= 1
        end while

        throw new java.io.IOException(s"$jarPath: EOCD record not found (not a valid ZIP/JAR file)")
    end findEocdBuf

    /** CEN location reader variant using a ByteBuffer (for parseAllEntries / mmap path).
      *
      * Checks for Zip64 EOCD locator; falls back to standard EOCD fields. Throws IOException on multi-disk jars.
      */
    private def readCenLocationBuf(
        jarPath: String,
        buffer: ByteBuffer,
        fileLen: Long,
        eocdOffset: Long,
        stdEntries: Long,
        stdCenOffset: Long
    ): (Long, Long) =
        val locOffset = eocdOffset - ZIP64_LOC_SIZE
        if locOffset >= 0 then
            val locBuf = new Array[Byte](ZIP64_LOC_SIZE)
            if locOffset > Int.MaxValue then
                throw new java.io.IOException(
                    s"$jarPath: Zip64 locator offset $locOffset exceeds 2GB mmap range; Zip64 required"
                )
            end if
            buffer.position(locOffset.toInt)
            buffer.get(locBuf)
            if readInt32LE(locBuf, 0) == SIG_ZIP64_LOC then
                val locDiskCount = readUInt32LE(locBuf, 16)
                if locDiskCount > 1 then
                    throw new java.io.IOException(s"$jarPath: multi-disk jars unsupported")
                end if
                val zip64EocdOffset = readUInt64LE(locBuf, 8)
                if zip64EocdOffset >= 0 && zip64EocdOffset < fileLen then
                    val zip64Buf = new Array[Byte](ZIP64_EOCD_FIXED_SIZE)
                    if zip64EocdOffset > Int.MaxValue then
                        throw new java.io.IOException(
                            s"$jarPath: Zip64 EOCD offset $zip64EocdOffset exceeds 2GB mmap range; Zip64 required"
                        )
                    end if
                    buffer.position(zip64EocdOffset.toInt)
                    buffer.get(zip64Buf)
                    if readInt32LE(zip64Buf, 0) == SIG_ZIP64_EOCD then
                        val zip64DiskNum   = readUInt32LE(zip64Buf, 16)
                        val zip64StartDisk = readUInt32LE(zip64Buf, 20)
                        if zip64DiskNum != 0 || zip64StartDisk != 0 then
                            throw new java.io.IOException(s"$jarPath: multi-disk jars unsupported")
                        end if
                        val totalEntries = readUInt64LE(zip64Buf, 32)
                        val cenOffset    = readUInt64LE(zip64Buf, 48)
                        return (totalEntries, cenOffset)
                    end if
                end if
            end if
        end if
        (stdEntries, stdCenOffset)
    end readCenLocationBuf

    // --- Byte-order reading helpers (little-endian, unsigned) ---

    /** Read a 4-byte little-endian int (signed, for signature comparison). */
    private def readInt32LE(buffer: Array[Byte], off: Int): Int =
        (buffer(off) & 0xff) |
            ((buffer(off + 1) & 0xff) << 8) |
            ((buffer(off + 2) & 0xff) << 16) |
            ((buffer(off + 3) & 0xff) << 24)

    /** Read a 2-byte little-endian unsigned value as Int. */
    private def readUInt16LE(buffer: Array[Byte], off: Int): Int =
        (buffer(off) & 0xff) | ((buffer(off + 1) & 0xff) << 8)

    /** Read a 4-byte little-endian unsigned value as Long. */
    private def readUInt32LE(buffer: Array[Byte], off: Int): Long =
        ((buffer(off) & 0xffL)) |
            ((buffer(off + 1) & 0xffL) << 8) |
            ((buffer(off + 2) & 0xffL) << 16) |
            ((buffer(off + 3) & 0xffL) << 24)

    /** Read an 8-byte little-endian unsigned value as Long (top bit treated as sign by JVM). */
    private def readUInt64LE(buffer: Array[Byte], off: Int): Long =
        (buffer(off) & 0xffL) |
            ((buffer(off + 1) & 0xffL) << 8) |
            ((buffer(off + 2) & 0xffL) << 16) |
            ((buffer(off + 3) & 0xffL) << 24) |
            ((buffer(off + 4) & 0xffL) << 32) |
            ((buffer(off + 5) & 0xffL) << 40) |
            ((buffer(off + 6) & 0xffL) << 48) |
            ((buffer(off + 7) & 0xffL) << 56)

end JarCentralDirectory
