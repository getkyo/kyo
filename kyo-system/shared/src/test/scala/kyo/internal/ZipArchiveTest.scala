package kyo.internal

import kyo.AllowUnsafe.embrace.danger
import kyo.Chunk
import kyo.discard

/** Tests for [[ZipArchive]], the pure `Array[Byte]` zip central-directory reader and STORED-only
  * writer.
  */
class ZipArchiveTest extends kyo.test.Test[Any]:

    private def entryBytes(s: String): Array[Byte] = s.getBytes("UTF-8")

    // A zip archive containing one DEFLATED (method 8) entry "greeting.txt", built once via
    // java.util.zip.ZipOutputStream on the JVM at fixture-authoring time (mirroring
    // PortableInflateTest.scala's own checked-in-fixture convention). The known original content is
    // "The quick brown fox jumps over the lazy dog. ".repeat(30).
    private val deflatedFixtureContent: Array[Byte] = "The quick brown fox jumps over the lazy dog. ".repeat(30).getBytes("UTF-8")
    private val deflatedFixtureZip: Array[Byte] = Array(
        80,
        75,
        3,
        4,
        20,
        0,
        8,
        8,
        8,
        0,
        153.toByte,
        9,
        242.toByte,
        92,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        12,
        0,
        0,
        0,
        103,
        114,
        101,
        101,
        116,
        105,
        110,
        103,
        46,
        116,
        120,
        116,
        11,
        201.toByte,
        72,
        85,
        40,
        44,
        205.toByte,
        76,
        206.toByte,
        86,
        72,
        42,
        202.toByte,
        47,
        207.toByte,
        83,
        72,
        203.toByte,
        175.toByte,
        80,
        200.toByte,
        42,
        205.toByte,
        45,
        40,
        86,
        200.toByte,
        47,
        75,
        45,
        82,
        40,
        1,
        74,
        231.toByte,
        36,
        86,
        85,
        42,
        164.toByte,
        228.toByte,
        167.toByte,
        235.toByte,
        41,
        132.toByte,
        140.toByte,
        42,
        30,
        85,
        60,
        170.toByte,
        120,
        84,
        241.toByte,
        168.toByte,
        98,
        84,
        197.toByte,
        0,
        80,
        75,
        7,
        8,
        43,
        241.toByte,
        31,
        103,
        59,
        0,
        0,
        0,
        70,
        5,
        0,
        0,
        80,
        75,
        1,
        2,
        20,
        0,
        20,
        0,
        8,
        8,
        8,
        0,
        153.toByte,
        9,
        242.toByte,
        92,
        43,
        241.toByte,
        31,
        103,
        59,
        0,
        0,
        0,
        70,
        5,
        0,
        0,
        12,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        103,
        114,
        101,
        101,
        116,
        105,
        110,
        103,
        46,
        116,
        120,
        116,
        80,
        75,
        5,
        6,
        0,
        0,
        0,
        0,
        1,
        0,
        1,
        0,
        58,
        0,
        0,
        0,
        117,
        0,
        0,
        0,
        0,
        0
    )

    // ZipArchive.write's output for entries = [("a.txt", false, "hello"), ("dir/b.txt", false,
    // "world")], captured once at test-authoring time as the pinned canonical byte sequence (the
    // fixed 1980-01-01 MS-DOS write timestamp and deterministic UTF-8 encoding make write's output
    // fully reproducible, so a byte-for-byte drift here is a real format regression).
    private val goldenTwoEntryArchive: Array[Byte] = Array(
        80,
        75,
        3,
        4,
        20,
        0,
        0,
        8,
        0,
        0,
        0,
        0,
        33,
        0,
        134.toByte,
        166.toByte,
        16,
        54,
        5,
        0,
        0,
        0,
        5,
        0,
        0,
        0,
        5,
        0,
        0,
        0,
        97,
        46,
        116,
        120,
        116,
        104,
        101,
        108,
        108,
        111,
        80,
        75,
        3,
        4,
        20,
        0,
        0,
        8,
        0,
        0,
        0,
        0,
        33,
        0,
        67,
        17,
        119,
        58,
        5,
        0,
        0,
        0,
        5,
        0,
        0,
        0,
        9,
        0,
        0,
        0,
        100,
        105,
        114,
        47,
        98,
        46,
        116,
        120,
        116,
        119,
        111,
        114,
        108,
        100,
        80,
        75,
        1,
        2,
        20,
        0,
        20,
        0,
        0,
        8,
        0,
        0,
        0,
        0,
        33,
        0,
        134.toByte,
        166.toByte,
        16,
        54,
        5,
        0,
        0,
        0,
        5,
        0,
        0,
        0,
        5,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        97,
        46,
        116,
        120,
        116,
        80,
        75,
        1,
        2,
        20,
        0,
        20,
        0,
        0,
        8,
        0,
        0,
        0,
        0,
        33,
        0,
        67,
        17,
        119,
        58,
        5,
        0,
        0,
        0,
        5,
        0,
        0,
        0,
        9,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        40,
        0,
        0,
        0,
        100,
        105,
        114,
        47,
        98,
        46,
        116,
        120,
        116,
        80,
        75,
        5,
        6,
        0,
        0,
        0,
        0,
        2,
        0,
        2,
        0,
        106,
        0,
        0,
        0,
        84,
        0,
        0,
        0,
        0,
        0
    )

    "parse recovers CEN entries and readEntry returns byte-identical content for a STORED entry" in {
        val bytes   = ZipArchive.write(Chunk(("a.txt", false, entryBytes("hello"))))
        val entries = ZipArchive.parse(bytes)
        assert(entries.size == 1)
        assert(entries.head.name == "a.txt")
        assert(entries.head.method == ZipArchive.MethodStored)
        assert(ZipArchive.readEntry(bytes, entries.head).toSeq == entryBytes("hello").toSeq)
    }

    "parse and readEntry decode a foreign DEFLATED (method 8) entry byte-identical, on all four platforms" in {
        val entries = ZipArchive.parse(deflatedFixtureZip)
        assert(entries.size == 1)
        assert(entries.head.method == ZipArchive.MethodDeflated)
        val recovered = ZipArchive.readEntry(deflatedFixtureZip, entries.head)
        assert(recovered.toSeq == deflatedFixtureContent.toSeq)
    }

    "readEntry raises ZipFormatException on a CRC-32 mismatch (corrupted entry)" in {
        val bytes   = ZipArchive.write(Chunk(("a.txt", false, entryBytes("hello"))))
        val entries = ZipArchive.parse(bytes)
        val entry   = entries.head
        // The CEN CRC-32 field sits at offset 16 within the 46-byte fixed CEN record; locate the
        // CEN start via the LFH name length (30 + nameBytes.length is the local-header size) and
        // flip one CRC byte before re-parsing.
        val lfhNameLen = "a.txt".length
        val cenStart   = 30 + lfhNameLen + entry.compSize
        val corrupted  = bytes.clone()
        corrupted(cenStart + 16) = (corrupted(cenStart + 16) ^ 0xff.toByte).toByte
        val reparsed = ZipArchive.parse(corrupted)
        try
            ZipArchive.readEntry(corrupted, reparsed.head)
            fail("expected ZipFormatException on CRC-32 mismatch")
        catch
            case ex: ZipArchive.ZipFormatException =>
                assert(ex.getMessage.toLowerCase.contains("crc"))
        end try
    }

    "readEntry raises ZipFormatException, not a raw index panic, on an out-of-range or overflow-inducing local-file-header offset" in {
        val bytes   = ZipArchive.write(Chunk(("a.txt", false, entryBytes("hello"))))
        val entries = ZipArchive.parse(bytes)
        // Includes Int.MaxValue-range values whose naive `lfh + 30` bounds arithmetic would
        // overflow to a negative Int and slip past the guard.
        val badOffsets = Seq(999999999, Int.MaxValue, Int.MaxValue - 10, -1)
        badOffsets.foreach { off =>
            val badEntry = entries.head.copy(lfhOffset = off)
            try
                discard(ZipArchive.readEntry(bytes, badEntry))
                fail(s"expected ZipFormatException on local-file-header offset $off")
            catch
                case ex: ZipArchive.ZipFormatException =>
                    assert(ex.getMessage.toLowerCase.contains("out of range"))
            end try
        }
        succeed
    }

    "readEntry raises ZipFormatException, not an OOM panic, on an overflow-inducing compressed size" in {
        val bytes   = ZipArchive.write(Chunk(("a.txt", false, entryBytes("hello"))))
        val entries = ZipArchive.parse(bytes)
        // compSize = Int.MaxValue: `start + compSize` overflows in Int, so the range check must
        // be done in Long or the slice/inflate attempts a hostile allocation.
        val badEntry = entries.head.copy(compSize = Int.MaxValue)
        try
            discard(ZipArchive.readEntry(bytes, badEntry))
            fail("expected ZipFormatException on an overflow-inducing compressed size")
        catch
            case ex: ZipArchive.ZipFormatException =>
                assert(ex.getMessage.toLowerCase.contains("out of bounds"))
        end try
    }

    "write then parse round-trips directory and nested-file entries with prefix-derived structure" in {
        val entries = Chunk(("a", true, Array.emptyByteArray), ("a/b.txt", false, entryBytes("x")))
        val bytes   = ZipArchive.write(entries)
        val parsed  = ZipArchive.parse(bytes)
        assert(parsed.size == 2)
        val dir  = parsed.find(_.name == "a").getOrElse(fail("missing entry \"a\""))
        val file = parsed.find(_.name == "a/b.txt").getOrElse(fail("missing entry \"a/b.txt\""))
        assert(dir.isDirectory)
        assert(!file.isDirectory)
        assert(ZipArchive.readEntry(bytes, file).toSeq == entryBytes("x").toSeq)
    }

    "a real java.util.zip.ZipFile reads a STORED archive produced by ZipArchive.write (JVM-only cross-tool leaf)".onlyJvm in {
        val entries = Chunk(
            ("a.txt", false, entryBytes("first")),
            ("b.txt", false, entryBytes("second")),
            ("adir", true, Array.emptyByteArray)
        )
        val bytes = ZipArchive.write(entries)
        val tmp   = java.io.File.createTempFile("kyo-ziparchive-crosstool", ".zip")
        try
            val fos = new java.io.FileOutputStream(tmp)
            try fos.write(bytes)
            finally fos.close()
            val zf = new java.util.zip.ZipFile(tmp)
            try
                val a = zf.getEntry("a.txt")
                val b = zf.getEntry("b.txt")
                val d = zf.getEntry("adir/")
                assert(a != null && b != null && d != null)
                val aBytes = zf.getInputStream(a).readAllBytes()
                val bBytes = zf.getInputStream(b).readAllBytes()
                assert(aBytes.toSeq == entryBytes("first").toSeq)
                assert(bBytes.toSeq == entryBytes("second").toSeq)
                assert(d.isDirectory)
                val crc32 = new java.util.zip.CRC32()
                crc32.update(entryBytes("first"))
                assert(a.getCrc == crc32.getValue)
            finally zf.close()
            end try
        finally discard(tmp.delete())
        end try
    }

    "write's output for a fixed entries set is byte-identical to a checked-in golden fixture, on all four platforms" in {
        val entries = Chunk(("a.txt", false, entryBytes("hello")), ("dir/b.txt", false, entryBytes("world")))
        val bytes   = ZipArchive.write(entries)
        assert(bytes.toSeq == goldenTwoEntryArchive.toSeq)
    }

end ZipArchiveTest
