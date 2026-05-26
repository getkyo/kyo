package kyo.internal.reflect.query

import java.io.RandomAccessFile
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
      * Effect row: Sync (file I/O) and Abort[ReflectError] (format errors, multi-disk jars, non-JAR files).
      */
    def list(jarPath: String, suffixes: Chunk[String])(using Frame): Chunk[(String, String)] < (Sync & Abort[ReflectError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else
            Sync.defer:
                var raf: RandomAccessFile = null
                try
                    raf = new RandomAccessFile(jarPath, "r")
                    listEntries(jarPath, raf, suffixes)
                catch
                    case err: ReflectErrorWrapper => Abort.fail(err.error)
                    case ex: java.io.IOException =>
                        Abort.fail(ReflectError.MalformedSection("jar", s"$jarPath: ${ex.getMessage}"))
                finally
                    if raf != null then
                        try raf.close()
                        catch case _: java.io.IOException => ()
                end try

    /** Thrown internally to propagate ReflectError through Java APIs that require checked exceptions. */
    final private class ReflectErrorWrapper(val error: ReflectError) extends Exception

    /** Core CEN parsing logic. Assumes raf is open and positioned at start. */
    private def listEntries(
        jarPath: String,
        raf: RandomAccessFile,
        suffixes: Chunk[String]
    ): Chunk[(String, String)] =
        val fileLen = raf.length()
        if fileLen == 0 then throw new ReflectErrorWrapper(ReflectError.MalformedSection("jar", s"$jarPath: empty file"))

        // Locate EOCD record by scanning from the end
        val (eocdOffset, eocdBuf) = findEocd(jarPath, raf, fileLen)

        // Check for Zip64: Zip64 EOCD locator is immediately before the EOCD record
        val (totalEntries, cenOffset) = readCenLocation(jarPath, raf, fileLen, eocdOffset, eocdBuf)

        // Validate CEN offset is within file bounds
        if cenOffset < 0 || cenOffset >= fileLen then
            throw new ReflectErrorWrapper(
                ReflectError.MalformedSection("jar", s"$jarPath: CEN offset $cenOffset out of range [0, $fileLen)")
            )
        end if

        // Read entire central directory into memory
        val cenSize = (eocdOffset - cenOffset).toInt.max(0)
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
        // Scan up to EOCD_MAX_SCAN bytes from the end
        val scanLen = EOCD_MAX_SCAN.min(fileLen.toInt)
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

        throw new ReflectErrorWrapper(
            ReflectError.MalformedSection("jar", s"$jarPath: EOCD record not found (not a valid ZIP/JAR file)")
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
            throw new ReflectErrorWrapper(
                ReflectError.MalformedSection("jar", s"$jarPath: multi-disk jars unsupported")
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
                    throw new ReflectErrorWrapper(
                        ReflectError.MalformedSection("jar", s"$jarPath: multi-disk jars unsupported")
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
                            throw new ReflectErrorWrapper(
                                ReflectError.MalformedSection("jar", s"$jarPath: multi-disk jars unsupported")
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
                val gpFlag     = readUInt16LE(cenBuf, pos + 8)
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

    // --- Byte-order reading helpers (little-endian, unsigned) ---

    /** Read a 4-byte little-endian int (signed, for signature comparison). */
    private def readInt32LE(buf: Array[Byte], off: Int): Int =
        (buf(off) & 0xff) |
            ((buf(off + 1) & 0xff) << 8) |
            ((buf(off + 2) & 0xff) << 16) |
            ((buf(off + 3) & 0xff) << 24)

    /** Read a 2-byte little-endian unsigned value as Int. */
    private def readUInt16LE(buf: Array[Byte], off: Int): Int =
        (buf(off) & 0xff) | ((buf(off + 1) & 0xff) << 8)

    /** Read a 4-byte little-endian unsigned value as Long. */
    private def readUInt32LE(buf: Array[Byte], off: Int): Long =
        ((buf(off) & 0xffL)) |
            ((buf(off + 1) & 0xffL) << 8) |
            ((buf(off + 2) & 0xffL) << 16) |
            ((buf(off + 3) & 0xffL) << 24)

    /** Read an 8-byte little-endian unsigned value as Long (top bit treated as sign by JVM). */
    private def readUInt64LE(buf: Array[Byte], off: Int): Long =
        (buf(off) & 0xffL) |
            ((buf(off + 1) & 0xffL) << 8) |
            ((buf(off + 2) & 0xffL) << 16) |
            ((buf(off + 3) & 0xffL) << 24) |
            ((buf(off + 4) & 0xffL) << 32) |
            ((buf(off + 5) & 0xffL) << 40) |
            ((buf(off + 6) & 0xffL) << 48) |
            ((buf(off + 7) & 0xffL) << 56)

end JarCentralDirectory
