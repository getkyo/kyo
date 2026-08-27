package kyo.internal

/** Tests for [[FileSystemCrc32]], the table-driven CRC32 implementation shared by
  * [[OverlayFileSystem]]'s intent-log framing and [[ZipArchive]]'s per-entry checksum.
  */
class FileSystemCrc32Test extends kyo.test.Test[Any]:

    "of matches the canonical CRC-32/ISO-HDLC check value for the ASCII string \"123456789\"" in {
        val bytes = "123456789".getBytes("UTF-8")
        val crc   = FileSystemCrc32.of(bytes) & 0xffffffffL
        assert(crc == 0xcbf43926L, f"expected 0xcbf43926 but got 0x$crc%08x")
    }

    "of(bytes, off, len) over a sub-range matches java.util.zip.CRC32 fed the identical sub-range (JVM-only cross-check)".onlyJvm in {
        val bytes = Array.tabulate(20)(i => (i * 7 + 3).toByte)
        val ours  = FileSystemCrc32.of(bytes, 5, 6) & 0xffffffffL
        val jdk   = new java.util.zip.CRC32()
        jdk.update(bytes, 5, 6)
        assert(ours == jdk.getValue)
    }

end FileSystemCrc32Test
