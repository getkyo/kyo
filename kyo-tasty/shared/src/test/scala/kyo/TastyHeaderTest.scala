package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.TastyHeader

/** Tests for TastyHeader.read.
  *
  * Nat encoding reminder (TASTy big-endian base-128, stop-bit on last byte):
  *   value v < 128: single byte = (v & 0x7f) | 0x80
  *   e.g. 28 -> 0x9C, 8 -> 0x88, 0 -> 0x80, 7 -> 0x87, 9 -> 0x89
  *
  * Version compatibility (verbatim dotty rule):
  *   fileMajor == compilerMajor &&
  *     (  fileMinor == compilerMinor && fileExperimental == compilerExperimental
  *     || fileMinor <  compilerMinor && fileExperimental == 0
  *     )
  *   Supported: Version(28, 8, 0). So:
  *     minor=7, exp=0  -> OK  (7 < 8 && exp==0)
  *     minor=9, exp=0  -> FAIL (9 > 8, neither condition satisfied)
  *     minor=8, exp=1  -> FAIL (minor==8 but exp!=0)
  */
class TastyHeaderTest extends Test:

    // Fixed 16-byte UUID used across tests: all zeros
    private val zeroUuid: Array[Byte] = Array.fill(16)(0x00.toByte)

    // Build a minimal header byte array from component parts
    private def headerBytes(
        magic: Array[Byte],
        major: Int,
        minor: Int,
        experimental: Int,
        toolingBytes: Array[Byte],
        uuid: Array[Byte]
    ): Array[Byte] =
        def encodeNat(v: Int): Array[Byte] =
            if v < 128 then
                Array((v | 0x80).toByte)
            else
                // Multi-byte encoding: continuation bytes have 0x80 CLEAR, last has 0x80 SET
                val buf    = new scala.collection.mutable.ArrayBuffer[Byte]()
                var x      = v
                var groups = scala.collection.mutable.ArrayBuffer[Int]()
                while x != 0 do
                    groups += (x & 0x7f)
                    x = x >>> 7
                // Write high groups first (continuation, 0x80 CLEAR)
                val gs = groups.reverse
                for i <- 0 until gs.length - 1 do
                    buf += gs(i).toByte
                // Last group is the terminating byte (0x80 SET)
                buf += (gs.last | 0x80).toByte
                buf.toArray
        val toolingLen = encodeNat(toolingBytes.length)
        magic ++ encodeNat(major) ++ encodeNat(minor) ++ encodeNat(experimental) ++
            toolingLen ++ toolingBytes ++ uuid
    end headerBytes

    private val validMagic = Array(0x5c.toByte, 0xa1.toByte, 0xab.toByte, 0x1f.toByte)
    private val wrongMagic = Array(0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)

    // Test 19: valid header 28.8.0 succeeds
    "reading correct magic + Version(28,8,0) succeeds and returns Data with those values" in run {
        val bytes = headerBytes(validMagic, 28, 8, 0, Array.empty, zeroUuid)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Success(data) =>
                    assert(data.major == 28)
                    assert(data.minor == 8)
                    assert(data.experimental == 0)
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 20: wrong magic produces CorruptedFile
    "reading wrong magic 0xDEADBEEF produces CorruptedFile error" in run {
        val bytes = headerBytes(wrongMagic, 28, 8, 0, Array.empty, zeroUuid)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.CorruptedFile(_, _, _)) =>
                    succeed
                case other =>
                    fail(s"Expected CorruptedFile but got: $other")
        }
    }

    // Test 21: major=99 produces UnsupportedVersion
    "reading major=99 produces UnsupportedVersion error" in run {
        val bytes = headerBytes(validMagic, 99, 8, 0, Array.empty, zeroUuid)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.UnsupportedVersion(found, supported)) =>
                    assert(found.major == 99)
                    assert(supported.major == 28)
                case other =>
                    fail(s"Expected UnsupportedVersion but got: $other")
        }
    }

    // Test 22: major=28, minor=7, experimental=0 succeeds (backward compatible: 7 < 8, exp==0)
    "reading minor=7 experimental=0 succeeds (stable backward compatible)" in run {
        val bytes = headerBytes(validMagic, 28, 7, 0, Array.empty, zeroUuid)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Success(data) =>
                    assert(data.major == 28)
                    assert(data.minor == 7)
                    assert(data.experimental == 0)
                case Result.Failure(e) =>
                    fail(s"Expected success for minor=7 but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Test 23: major=28, minor=9, experimental=0 produces UnsupportedVersion
    // (minor=9 > supportedMinor=8, so forward-incompatible per dotty rule)
    "reading minor=9 experimental=0 produces UnsupportedVersion (forward incompatible)" in run {
        val bytes = headerBytes(validMagic, 28, 9, 0, Array.empty, zeroUuid)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.UnsupportedVersion(found, _)) =>
                    assert(found.minor == 9)
                case other =>
                    fail(s"Expected UnsupportedVersion for minor=9 but got: $other")
        }
    }

    // Test 24: experimental=1 with supportedExperimental=0 produces UnsupportedVersion
    "reading experimental=1 when supportedExperimental=0 produces UnsupportedVersion" in run {
        val bytes = headerBytes(validMagic, 28, 8, 1, Array.empty, zeroUuid)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.UnsupportedVersion(found, _)) =>
                    assert(found.experimental == 1)
                case other =>
                    fail(s"Expected UnsupportedVersion for experimental=1 but got: $other")
        }
    }

    // Additional test: truncated header (only magic bytes) produces MalformedSection
    "reading truncated header produces MalformedSection error" in run {
        // Only 4 magic bytes, no version fields
        val bytes = validMagic
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Failure(TastyError.MalformedSection(_, _)) =>
                    succeed
                case other =>
                    fail(s"Expected MalformedSection for truncated header but got: $other")
        }
    }

    // Additional test: UUID is read as 16 bytes and formatted as hex string
    "uuid field is formatted correctly from 16 UUID bytes" in run {
        // UUID bytes: first 8 = 0x0102030405060708L, last 8 = 0x090A0B0C0D0E0F10L
        val uuidBytes = Array[Byte](
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
        )
        val bytes = headerBytes(validMagic, 28, 8, 0, Array.empty, uuidBytes)
        val view  = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Success(data) =>
                    assert(data.uuid == "0102030405060708090a0b0c0d0e0f10")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

    // Additional test: tooling version string is decoded correctly
    "tooling version string is decoded from UTF-8 bytes in header" in run {
        // "scalac" = [0x73, 0x63, 0x61, 0x6c, 0x61, 0x63]
        val tooling = Array[Byte](0x73, 0x63, 0x61, 0x6c, 0x61, 0x63)
        val bytes   = headerBytes(validMagic, 28, 8, 0, tooling, zeroUuid)
        val view    = ByteView(bytes)
        Abort.run[TastyError] {
            TastyHeader.read(view)
        }.map { result =>
            result match
                case Result.Success(data) =>
                    assert(data.toolingVersion == "scalac")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
        }
    }

end TastyHeaderTest
