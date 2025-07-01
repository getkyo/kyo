package kyo

import Image.ConsoleType
import java.util.Base64

/** Represents an image with utility methods for manipulation and display.
  *
  * This class provides functionality for:
  *   - Writing images to files in binary or base64 format
  *   - Converting between binary and base64 representations
  *   - Rendering images directly to compatible terminal emulators
  *
  * Images can be created from binary data or base64 strings using the companion object's factory methods.
  */
final case class Image private (data: Array[Byte]):

    /** Writes the image to a file in binary format.
      *
      * @param path
      *   The file path as a string
      * @return
      *   Unit wrapped in Sync effect
      */
    def writeFileBinary(path: String)(using Frame): Unit < Sync =
        writeFileBinary(Path(path))

    /** Writes the image to a file in binary format.
      *
      * @param path
      *   The file path as a Path object
      * @return
      *   Unit wrapped in Sync effect
      */
    def writeFileBinary(path: Path)(using Frame): Unit < Sync =
        path.writeBytes(binary.unsafeArray)

    /** Writes the image to a file in base64 format.
      *
      * @param path
      *   The file path as a string
      * @return
      *   Unit wrapped in Sync effect
      */
    def writeFileBase64(path: String)(using Frame): Unit < Sync =
        writeFileBase64(Path(path))

    /** Writes the image to a file in base64 format.
      *
      * @param path
      *   The file path as a Path object
      * @return
      *   Unit wrapped in Sync effect
      */
    def writeFileBase64(path: Path)(using Frame): Unit < Sync =
        path.write(base64)

    /** Converts the image data to an immutable array of bytes.
      *
      * @return
      *   The image data as an IArray[Byte]
      */
    def binary: IArray[Byte] = IArray.unsafeFromArray(data)

    /** Converts the image data to a base64 encoded string.
      *
      * @return
      *   The image data as a base64 encoded string
      */
    def base64: String = Base64.getEncoder().encodeToString(data)

    /** Attempts to render the image directly to the terminal.
      *
      * Supports iTerm2 and Kitty terminal emulators. The image will be displayed inline in the terminal output if the terminal supports it.
      *
      * @param charsWidth
      *   Optional width in character cells (default: 0, uses terminal default)
      * @param charsHeight
      *   Optional height in character cells (default: 0, uses terminal default)
      * @return
      *   A Maybe containing the terminal control sequence if rendering is supported, or Absent if the terminal doesn't support inline
      *   images
      */
    def renderToConsole(charsWidth: Int = 0, charsHeight: Int = 0, consoleType: Maybe[ConsoleType] = ConsoleType.get)(
        using Frame
    ): Maybe[String] =
        val base64Image = base64
        consoleType.map {
            case ConsoleType.iterm =>
                val sizeSpec =
                    if charsWidth > 0 && charsHeight > 0 then
                        s"width=${charsWidth}ch;height=${charsHeight}ch;"
                    else if charsWidth > 0 then
                        s"width=${charsWidth}ch;"
                    else
                        ""
                s"\u001b]1337;File=inline=1;${sizeSpec}preserveAspectRatio=1:${base64Image}\u0007"
            case ConsoleType.kitty =>
                val sizeParams =
                    if charsWidth > 0 && charsHeight > 0 then
                        s"s=${charsWidth},v=${charsHeight},"
                    else if charsWidth > 0 then
                        s"s=${charsWidth},"
                    else
                        ""
                s"\u001b_Gf=100,${sizeParams}m=1;${base64Image}\u001b\\"
        }
    end renderToConsole
end Image

object Image:

    enum ConsoleType derives CanEqual:
        case iterm, kitty

    object ConsoleType:
        def get: Maybe[ConsoleType] =
            val termProgram = sys.env.getOrElse("TERM_PROGRAM", "").toLowerCase
            val term        = sys.env.getOrElse("TERM", "").toLowerCase
            if termProgram.contains("iterm") then
                Present(ConsoleType.iterm)
            else if termProgram.contains("kitty") || term.contains("kitty") then
                Present(ConsoleType.kitty)
            else
                Absent
            end if
        end get

    end ConsoleType

    /** Creates an Image from binary data.
      *
      * @param array
      *   The binary data as a byte array
      * @return
      *   A new Image instance
      */
    def fromBinary(array: Array[Byte]): Image = Image(array)

    /** Creates an Image from a base64 encoded string.
      *
      * @param string
      *   The base64 encoded string
      * @return
      *   A new Image instance
      */
    def fromBase64(string: String): Image = Image(Base64.getDecoder().decode(string))
end Image
