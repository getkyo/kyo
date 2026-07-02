package kyo.internal

import Image.ConsoleType
import kyo.*

/** An immutable image backed by raw bytes, with file I/O and terminal rendering helpers.
  *
  * `Image` is a thin wrapper around an immutable `Span[Byte]` carrying an encoded image payload (typically PNG or JPEG produced by
  * `Browser.screenshot`). It provides pure accessors for the underlying bytes and convenience effects for persisting the image or
  * displaying it inline in a supported terminal emulator.
  *
  * Construction is private; callers obtain an `Image` either from a browser screenshot effect or via [[Image.fromBinary]] /
  * [[Image.fromBase64]]. This guarantees the byte buffer is owned by the `Image` instance and never mutated after construction.
  *
  * Terminal rendering is best-effort: [[Image.renderToConsole]] returns `Absent` when the host terminal cannot be detected as one of the
  * supported emulators (iTerm2, Kitty).
  *
  * @see
  *   [[Image.fromBinary]] and [[Image.fromBase64]] for construction.
  * @see
  *   [[Image.ConsoleType]] for the supported terminal emulators.
  * @see
  *   [[kyo.Browser]] for the screenshot actions that produce `Image` values.
  * @see
  *   [[kyo.Path]] for the file-system targets used by the `writeFile*` methods.
  */
final case class Image private (data: Span[Byte]) derives CanEqual:

    /** Two `Image` values are equal when their byte payloads are byte-identical. The default case-class equality compares `data` via the
      * underlying `Array`'s reference identity (because `Span[Byte]` is an opaque alias over `Array[Byte]`), so we override `equals` /
      * `hashCode` to derive content-equality from [[Span.is]] and the bytewise XXH32 of the payload.
      */
    override def equals(other: Any): Boolean =
        other match
            case that: Image => data.is(that.data)
            case _           => false

    override def hashCode: Int =
        XXHash.hash32(data)

    /** Writes the image to a file in raw binary format. */
    def writeFileBinary(path: String)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        writeFileBinary(Path(path))

    /** Writes the image to a file in raw binary format. */
    def writeFileBinary(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        path.writeBytes(data)

    /** Writes the image to a file as a Base64-encoded text payload. */
    def writeFileBase64(path: String)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        writeFileBase64(Path(path))

    /** Writes the image to a file as a Base64-encoded text payload. */
    def writeFileBase64(path: Path)(using Frame): Unit < (Sync & Abort[FileWriteException]) =
        path.write(base64)

    /** The raw image bytes as an immutable [[Span]]. */
    def binary: Span[Byte] = data

    /** The image bytes encoded as a Base64 string.
      *
      * Uses the cross-platform [[kyo.Base64]] encoder so behaviour is identical on JVM, Scala.js and Scala Native.
      */
    def base64: String = Base64.encode(data)

    /** Renders the image inline for iTerm2 or Kitty terminals.
      *
      * Returns `Absent` when no supported terminal type can be detected. Width and height are expressed in terminal cells; passing zero
      * means "auto" along that axis. The terminal type is auto-detected via [[ConsoleType.get]] (environment-based).
      *
      * @param charsWidth
      *   Desired width in character cells, or `0` for automatic sizing.
      * @param charsHeight
      *   Desired height in character cells, or `0` for automatic sizing.
      */
    def renderToConsole(charsWidth: Int = 0, charsHeight: Int = 0)(using Frame): Maybe[String] < Sync =
        ConsoleType.get.map(_.map(renderWith(charsWidth, charsHeight, _)))
    end renderToConsole

    /** Pure rendering helper for an explicit terminal type. Exposed for unit testing without going through env-based detection. */
    private[kyo] def renderWith(charsWidth: Int, charsHeight: Int, consoleType: ConsoleType): String =
        val base64Image = base64
        consoleType match
            case ConsoleType.iterm =>
                val sizeSpec =
                    if charsWidth > 0 && charsHeight > 0 then
                        s"width=${charsWidth}ch;height=${charsHeight}ch;"
                    else if charsWidth > 0 then
                        s"width=${charsWidth}ch;"
                    else
                        ""
                s"]1337;File=inline=1;${sizeSpec}preserveAspectRatio=1:${base64Image}"
            case ConsoleType.kitty =>
                val sizeParams =
                    if charsWidth > 0 && charsHeight > 0 then
                        s"s=${charsWidth},v=${charsHeight},"
                    else if charsWidth > 0 then
                        s"s=${charsWidth},"
                    else
                        ""
                s"_Gf=100,${sizeParams}m=1;${base64Image}\\"
        end match
    end renderWith
end Image

/** Companion containing constructors and the [[Image.ConsoleType]] enumeration. */
object Image:

    /** Terminal emulators that can render inline images via escape-sequence protocols.
      *
      * @see
      *   [[ConsoleType.get]] for environment-based detection.
      * @see
      *   [[Image.renderToConsole]] for usage.
      */
    enum ConsoleType derives CanEqual:
        case iterm, kitty

    object ConsoleType:
        /** Detects the host terminal emulator via `TERM_PROGRAM` and `TERM` environment variables.
          *
          * Returns `Absent` when no supported terminal is detected.
          */
        def get(using Frame): Maybe[ConsoleType] < Sync =
            for
                termProgram <- System.env[String]("TERM_PROGRAM")
                term        <- System.env[String]("TERM")
            yield detect(Map(
                "TERM_PROGRAM" -> termProgram.getOrElse(""),
                "TERM"         -> term.getOrElse("")
            ))

        /** Pure detection logic over an arbitrary environment map.
          *
          * Extracted to enable unit-testing without mutating the real process environment.
          */
        private[kyo] def detect(env: Map[String, String]): Maybe[ConsoleType] =
            val termProgram = env.getOrElse("TERM_PROGRAM", "").toLowerCase
            val term        = env.getOrElse("TERM", "").toLowerCase
            if termProgram.contains("iterm") then
                Present(ConsoleType.iterm)
            else if termProgram.contains("kitty") || term.contains("kitty") then
                Present(ConsoleType.kitty)
            else
                Absent
            end if
        end detect

    end ConsoleType

    /** Wraps a raw byte array as an [[Image]]. The bytes are copied into an immutable [[Span]]. */
    def fromBinary(array: Array[Byte]): Image = Image(Span.from(array))

    /** Decodes a Base64 payload and wraps the resulting bytes as an [[Image]].
      *
      * Returns [[Result.Failure]] carrying an [[IllegalArgumentException]] when the input is not valid Base64. Callers that produce the
      * input from a CDP wire payload should translate the failure to a typed `Abort[BrowserDecodingException]` at the call site so the
      * malformed-wire path stays inside the typed-error channel.
      */
    def fromBase64(string: String): Result[IllegalArgumentException, Image] =
        Base64.decode(string).map(Image(_))
end Image
