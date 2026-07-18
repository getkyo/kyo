package kyo.internal

/** Table-driven CRC32 with the reflected IEEE 802.3 polynomial (0xEDB88320). Produces bit-identical
  * output to `java.util.zip.CRC32` for any input. Promoted from the inline copy that lived in
  * `OverlayFileSystem.scala`'s `WriteOpLog` (staged-journal record framing); this shared copy now
  * also backs [[ZipArchive]]'s per-entry checksum. No `java.util.zip`; works on JVM, JS, Native.
  *
  * Named `FileSystemCrc32` rather than `CRC32` to avoid colliding with the already-shipped, same-API
  * `kyo.internal.CRC32` in `kyo-eventlog` (a distinct module that depends on `kyo-system`, so both
  * classes would otherwise land on the same compile classpath under one fully-qualified name).
  *
  * API mirrors `kyo-eventlog/shared/src/main/scala/kyo/internal/CRC32.scala`'s call pattern: create
  * an instance, call `update(bytes, off, len)` one or more times, then read `value` as an unsigned
  * 32-bit Long. The `of` helpers cover the common one-shot cases.
  */
final private[kyo] class FileSystemCrc32:
    private var crc: Int = 0xffffffff

    def update(bytes: Array[Byte], off: Int, len: Int): Unit =
        var i   = off
        val end = off + len
        while i < end do
            crc = (crc >>> 8) ^ FileSystemCrc32.Table((crc ^ bytes(i)) & 0xff)
            i += 1
    end update

    def update(bytes: Array[Byte]): Unit = update(bytes, 0, bytes.length)

    /** The accumulated CRC32 value, as an unsigned 32-bit integer in a Long. */
    def value: Long = (~crc) & 0xffffffffL

    def reset(): Unit = crc = 0xffffffff

end FileSystemCrc32

private[kyo] object FileSystemCrc32:

    /** Precomputed 256-entry lookup table, reflected IEEE 802.3 polynomial 0xEDB88320. */
    val Table: Array[Int] =
        val t = new Array[Int](256)
        var i = 0
        while i < 256 do
            var c = i
            var j = 0
            while j < 8 do
                if (c & 1) != 0 then c = 0xedb88320 ^ (c >>> 1)
                else c = c >>> 1
                j += 1
            end while
            t(i) = c
            i += 1
        end while
        t
    end Table

    /** One-shot CRC32 of `bytes`. */
    def of(bytes: Array[Byte]): Int =
        val c = new FileSystemCrc32()
        c.update(bytes)
        c.value.toInt
    end of

    /** One-shot CRC32 of `bytes(off until off + len)`, avoiding a defensive copy for a sub-range. */
    def of(bytes: Array[Byte], off: Int, len: Int): Int =
        val c = new FileSystemCrc32()
        c.update(bytes, off, len)
        c.value.toInt
    end of

end FileSystemCrc32
