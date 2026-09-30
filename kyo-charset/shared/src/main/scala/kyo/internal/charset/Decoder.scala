package kyo.internal.charset

import kyo.*
import kyo.charset.Charset

/** Turns the bytes of one piece of text in one charset into a `String`, in a single call over the whole input.
  *
  * `run` writes into an [[Decoder.Output]], which records where the decoder first found the input ill-formed; `decode` ignores that
  * record and `decodeStrict` turns it into the failure. A decoder holds no state between calls, so one instance serves any number of
  * concurrent decodes. An encoding whose decoder is stateful (ISO-2022-JP, UTF-7) keeps that state inside one call, starting from the
  * encoding's initial state each time, which is what a MIME part, an encoded word or a buffered body is: a whole, independently
  * decodable unit.
  */
abstract private[kyo] class Decoder:

    def charset: Charset

    /** Decodes every byte of `bytes` into `out`, one `out.replacement` per ill-formed sequence where the encoding's algorithm says. */
    def run(bytes: Span[Byte], out: Decoder.Output): Unit

    final def decode(bytes: Span[Byte]): String =
        val out = new Decoder.Output(bytes.size)
        run(bytes, out)
        out.toString
    end decode

    final def decodeStrict(bytes: Span[Byte]): Result[Charset.Malformed, String] =
        val out = new Decoder.Output(bytes.size)
        run(bytes, out)
        if out.malformedAt < 0 then Result.succeed(out.toString) else Result.fail(Charset.Malformed(charset, out.malformedAt))
    end decodeStrict

end Decoder

private[kyo] object Decoder:

    inline val Replacement = '�'

    /** The text a decoder produces, and the offset of the first ill-formed sequence it met, or -1. */
    final class Output(capacity: Int):
        private val text     = new java.lang.StringBuilder(capacity)
        private var firstBad = -1

        def malformedAt: Int = firstBad

        def append(c: Char): Output =
            discard(text.append(c))
            this

        def append(s: String): Output =
            discard(text.append(s))
            this

        def appendCodePoint(codePoint: Int): Output =
            discard(text.appendCodePoint(codePoint))
            this

        /** Writes U+FFFD for an ill-formed sequence the decoder detected with its read position at `offset`. */
        def replacement(offset: Int): Unit =
            if firstBad < 0 then firstBad = offset
            discard(text.append(Replacement))

        override def toString: String = text.toString
    end Output

end Decoder
