package kyo.internal.reflect.tasty

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.binary.Utf8
import kyo.internal.reflect.binary.Varint

/** TASTy file header reader.
  *
  * Header layout (verbatim from TastyHeaderUnpickler.readFullHeader):
  *   1. 4 magic bytes: 0x5C 0xA1 0xAB 0x1F
  *   2. majorVersion (Nat, LEB128)
  *   3. fileMinor (Nat, LEB128)
  *   4. fileExperimental (Nat, LEB128)
  *   5. tooling version: length (Nat) then length raw UTF-8 bytes
  *   6. UUID: 16 raw bytes as two big-endian uncompressed Longs
  *
  * Version compatibility follows the verbatim dotty TastyFormat.isVersionCompatible rule:
  *   fileMajor == compilerMajor &&
  *     (  fileMinor == compilerMinor && fileExperimental == compilerExperimental
  *     || fileMinor <  compilerMinor && fileExperimental == 0
  *     )
  *
  * A file with experimental != 0 and fileMinor != compilerMinor is NOT readable.
  * A file with fileMinor > compilerMinor is forward-incompatible and is NOT readable.
  */
object TastyHeader:

    /** Parsed TASTy header fields. */
    final case class Data(
        major: Int,
        minor: Int,
        experimental: Int,
        toolingVersion: String,
        uuid: String
    )

    private def truncated(using Frame): Data < Abort[ReflectError] =
        Abort.fail(ReflectError.MalformedSection("header", "unexpected end of TASTy header"))

    /** Read the TASTy header from `view`, returning `Data` or a `ReflectError`.
      *
      * Uses explicit bounds checks via `view.remaining` before each read to avoid exception-based control flow.
      *
      * `Abort.fail(ReflectError.CorruptedFile(...))` if magic bytes mismatch. `Abort.fail(ReflectError.UnsupportedVersion(...))` if the
      * version is not compatible. `Abort.fail(ReflectError.MalformedSection(...))` if the header is truncated.
      */
    def read(view: ByteView)(using Frame): Data < Abort[ReflectError] =
        // Step 1: check 4 magic bytes via explicit remaining guard.
        if view.remaining < TastyFormat.MagicBytes.length then truncated
        else checkMagic(view, 0)

    private def checkMagic(view: ByteView, i: Int)(using Frame): Data < Abort[ReflectError] =
        if i >= TastyFormat.MagicBytes.length then readVersions(view)
        else
            val expected = TastyFormat.MagicBytes(i)
            val actual   = view.readByte() & 0xff
            if actual != expected then
                Abort.fail(
                    ReflectError.CorruptedFile(
                        path = "<byte view>",
                        at = i.toLong,
                        reason = s"magic byte $i expected 0x${expected.toHexString} but got 0x${actual.toHexString}"
                    )
                )
            else checkMagic(view, i + 1)
            end if
        end if
    end checkMagic

    private def readVersions(view: ByteView)(using Frame): Data < Abort[ReflectError] =
        // Step 2: version triple (3 Nats); each Nat is at least 1 byte.
        if view.remaining < 3 then truncated
        else
            val fileMajor        = Varint.readNat(view)
            val fileMinor        = Varint.readNat(view)
            val fileExperimental = Varint.readNat(view)

            // Step 3: check version compatibility using verbatim dotty formula.
            val supported = Reflect.supportedTastyVersion
            val compatible = TastyFormat.isVersionCompatible(
                fileMajor,
                fileMinor,
                fileExperimental,
                supported.major,
                supported.minor,
                supported.experimental
            )
            if !compatible then
                Abort.fail(
                    ReflectError.UnsupportedVersion(
                        found = Reflect.Version(fileMajor, fileMinor, fileExperimental),
                        supported = supported
                    )
                )
            else readTooling(view, fileMajor, fileMinor, fileExperimental)
            end if
        end if
    end readVersions

    private def readTooling(view: ByteView, fileMajor: Int, fileMinor: Int, fileExperimental: Int)(using
        Frame
    ): Data < Abort[ReflectError] =
        // Step 4: tooling version string (length Nat + length bytes).
        if view.remaining < 1 then truncated
        else
            val toolingLength = Varint.readNat(view)
            if view.remaining < toolingLength then truncated
            else
                val toolingBytes = new Array[Byte](toolingLength)
                var j            = 0
                while j < toolingLength do
                    toolingBytes(j) = view.readByte()
                    j += 1
                val toolingVersion = Utf8.decode(toolingBytes, 0, toolingLength)
                readUuid(view, fileMajor, fileMinor, fileExperimental, toolingVersion)
            end if
        end if
    end readTooling

    private def readUuid(
        view: ByteView,
        fileMajor: Int,
        fileMinor: Int,
        fileExperimental: Int,
        toolingVersion: String
    )(using Frame): Data < Abort[ReflectError] =
        // Step 5: UUID as two big-endian uncompressed Longs (16 bytes total).
        if view.remaining < 16 then truncated
        else
            val msb  = readUncompressedLong(view)
            val lsb  = readUncompressedLong(view)
            val uuid = f"${msb}%016x${lsb}%016x"
            Data(fileMajor, fileMinor, fileExperimental, toolingVersion, uuid)
        end if
    end readUuid

    /** Read 8 bytes big-endian as a Long (verbatim from TastyReader.readUncompressedLong). */
    private def readUncompressedLong(view: ByteView): Long =
        var x = 0L
        var k = 0
        while k < 8 do
            x = (x << 8) | (view.readByte() & 0xffL)
            k += 1
        x
    end readUncompressedLong

end TastyHeader
