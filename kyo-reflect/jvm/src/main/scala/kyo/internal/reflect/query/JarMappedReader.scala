package kyo.internal.reflect.query

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/** Memory-mapped JAR reader. One MappedByteBuffer and a parsed CEN index per JAR file.
  *
  * Concurrency: Multiple fibers/threads can call `readEntry` against the same JarMappedReader concurrently. Each call duplicates the
  * underlying buffer (~30 bytes, cheap) so each caller has an independent position cursor. Each call creates its own Inflater instance;
  * Inflater is single-threaded and stateful, so it must NOT be shared or pooled without careful synchronization.
  *
  * Lifecycle: Created by `JarMappedReader.open`. The MappedByteBuffer is backed by an OS memory mapping that remains valid after the
  * RandomAccessFile/FileChannel is closed. The mapping is released when the buffer is GC'd (or when the JVM exits). No explicit unmap is
  * performed; attempting to unmap a MappedByteBuffer via sun.misc.Cleaner is unsafe on Java 9+ and is deliberately avoided here.
  */
final private[kyo] class JarMappedReader(
    val jarPath: String,
    private val mbb: MappedByteBuffer,
    private val entries: java.util.HashMap[String, JarCentralDirectory.JarEntry]
):

    /** Read and decompress the named entry from the memory-mapped JAR.
      *
      * Thread-safe: duplicates the buffer to obtain an independent position. Creates a fresh Inflater per call.
      *
      * @throws java.io.FileNotFoundException
      *   if the entry name is not found in the JAR's central directory
      * @throws java.io.IOException
      *   on bad local-file-header signature, unsupported compression method, or truncated deflate stream
      */
    def readEntry(entryName: String): Array[Byte] =
        val entry = entries.get(entryName)
        if entry == null then
            // Unsafe: null is Java-interop result from HashMap.get; documented as such
            throw new java.io.FileNotFoundException(s"$jarPath!/$entryName: entry not found in jar")
        end if
        val buf = mbb.duplicate()
        buf.order(ByteOrder.LITTLE_ENDIAN)

        // Local file header (LFH) layout:
        //   sig(4) + version(2) + gpFlag(2) + method(2) + modTime(2) + modDate(2)
        //   + crc(4) + compSize(4) + uncompSize(4) + nameLen(2) + extraLen(2)
        //   = 30 bytes fixed, followed by name(nameLen) + extra(extraLen) + data
        //
        // We must read nameLen + extraLen from the LFH to locate the actual data start because
        // the LFH extra field length can differ from the CEN extra field length.
        buf.position(entry.lfhOffset.toInt)
        val sig = buf.getInt()
        if sig != 0x04034b50 then
            throw new java.io.IOException(
                s"$jarPath!/$entryName: bad local file header signature 0x${sig.toHexString}"
            )
        end if

        // Skip version(2) + gpFlag(2) + method(2) + modTime(2) + modDate(2) + crc(4) + compSize(4) + uncompSize(4) = 22 bytes
        // We are at offset +4 after reading sig; skip 22 more to reach nameLen at +26.
        buf.position(entry.lfhOffset.toInt + 26)
        val nameLen  = buf.getShort() & 0xffff
        val extraLen = buf.getShort() & 0xffff

        val dataOffset = entry.lfhOffset.toInt + 30 + nameLen + extraLen
        val compSize   = entry.compSize.toInt
        val uncompSize = entry.uncompSize.toInt

        val compBytes = new Array[Byte](compSize)
        buf.position(dataOffset)
        buf.get(compBytes)

        entry.method match
            case 0 =>
                // STORED: data is verbatim
                compBytes
            case 8 =>
                // DEFLATED: nowrap=true because ZIP uses raw deflate (no zlib header/trailer)
                val inflater = new Inflater(true)
                try
                    inflater.setInput(compBytes)
                    val out   = new Array[Byte](uncompSize)
                    var total = 0
                    while total < uncompSize && !inflater.finished() do
                        val n = inflater.inflate(out, total, uncompSize - total)
                        if n == 0 then
                            if inflater.needsInput() || inflater.needsDictionary() then
                                throw new java.io.IOException(
                                    s"$jarPath!/$entryName: truncated deflate stream (inflated $total of $uncompSize bytes)"
                                )
                        end if
                        total += n
                    end while
                    out
                finally inflater.end()
                end try
            case other =>
                throw new java.io.IOException(
                    s"$jarPath!/$entryName: unsupported compression method $other"
                )
        end match
    end readEntry

end JarMappedReader

object JarMappedReader:

    /** Open a JAR, memory-map it, parse its central directory, and return a JarMappedReader.
      *
      * The RandomAccessFile and FileChannel are closed after mapping; the MappedByteBuffer remains valid because OS memory mappings outlive
      * the file descriptor.
      *
      * @throws java.io.IOException
      *   on any I/O error, malformed ZIP/JAR, or multi-disk JAR
      */
    def open(jarPath: String): JarMappedReader =
        val raf = new RandomAccessFile(jarPath, "r")
        try
            val channel = raf.getChannel
            val size    = channel.size()
            if size == 0 then throw new java.io.IOException(s"$jarPath: empty file")
            val mbb    = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size)
            val entMap = JarCentralDirectory.parseAllEntries(jarPath, mbb)
            new JarMappedReader(jarPath, mbb, entMap)
        finally
            // Closing RAF also closes the channel; the MappedByteBuffer remains valid.
            raf.close()
        end try
    end open

end JarMappedReader
