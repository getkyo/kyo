package kyo.internal

import kyo.Maybe
import scala.scalajs.js

/** Synchronous line reader over a Node file descriptor.
  *
  * Node has no synchronous stdin: `process.stdin` is an async stream, so nothing on it can answer a call whose signature returns a value.
  * `fs.readSync(fd, ...)` is the synchronous read Node does expose, and this is the line framing on top of it.
  *
  * Reads arrive in [[ChunkSize]] blocks and the bytes past the line just returned are kept in [[pending]], the same shape as the
  * `BufferedReader` behind `scala.Console.in` on the JVM. The buffer is deliberately a `var` on a per-descriptor instance: a reader owns the
  * read position of one descriptor, and a line boundary rarely falls on a read boundary, so the leftover has to outlive the call that read
  * it. Nothing outside the instance can observe it, and Node is single-threaded, so there is no sharing to guard.
  *
  * A line ends at `\n`, which is not included; a trailing `\r` is dropped so a CRLF stream reads the same as an LF one. The bytes are decoded
  * as UTF-8 only once a full line is in hand, so a multi-byte character split across two reads still decodes correctly.
  */
final private[kyo] class NodeLineReader(fd: Int):

    import NodeLineReader.*

    private var pending: js.Dynamic = emptyBuffer
    private var atEof: Boolean      = false

    /** Read the next line, or [[kyo.Absent]] at end of input.
      *
      * A final line with no terminating newline is returned; the call after it reports the end. `Absent` therefore means "nothing left",
      * never "an empty line", which reads back as `Present("")`.
      *
      * @throws scala.scalajs.js.JavaScriptException
      *   if the underlying `fs.readSync` fails for any reason other than end of input.
      */
    def readLine(): Maybe[String] =
        var line = Maybe.empty[String]
        var done = false
        while !done do
            val newline = pending.applyDynamic("indexOf")(Newline).asInstanceOf[Int]
            if newline >= 0 then
                line = Maybe(decode(pending.applyDynamic("subarray")(0, newline)))
                pending = pending.applyDynamic("subarray")(newline + 1)
                done = true
            else if atEof then
                // No newline and nothing more to read: what is left is the last line, if there is anything left at all.
                if length(pending) > 0 then
                    line = Maybe(decode(pending))
                    pending = emptyBuffer
                done = true
            else fill()
            end if
        end while
        line
    end readLine

    /** Read one more block into `pending`, or record end of input. */
    private def fill(): Unit =
        val chunk = buffer.applyDynamic("allocUnsafe")(ChunkSize)
        val n     = read(chunk)
        if n <= 0 then atEof = true
        else pending = buffer.applyDynamic("concat")(js.Array(pending, chunk.applyDynamic("subarray")(0, n)))
    end fill

    /** One `fs.readSync`, returning the byte count or 0 at end of input.
      *
      * `EAGAIN` means the descriptor is a non-blocking TTY with nothing typed yet, so the read is retried rather than reported: the caller
      * asked for a line and is entitled to wait for one, which is what the JVM's blocking `System.in` read does. `EOF` is how a TTY reports
      * end of input on some hosts, where a pipe reports it as a zero-length read.
      */
    private def read(chunk: js.Dynamic): Int =
        var result = -1
        while result < 0 do
            try result = CoreNodeFs.readSync(fd, chunk, 0, ChunkSize, null)
            catch
                case js.JavaScriptException(error) =>
                    errorCode(error) match
                        case "EAGAIN" => ()
                        case "EOF"    => result = 0
                        case _        => throw js.JavaScriptException(error)
        end while
        result
    end read

end NodeLineReader

private[kyo] object NodeLineReader:

    /** The process's standard input. Built lazily: constructing it touches no descriptor, but a program that never reads a line should not
      * pay for the buffer either.
      */
    lazy val stdin: NodeLineReader = new NodeLineReader(0)

    /** Bytes per `fs.readSync`. Large enough that a line-oriented protocol costs one syscall per message rather than one per byte. */
    private inline val ChunkSize = 8192

    private inline val Newline = 0x0a
    private inline val Return  = 0x0d

    private def buffer: js.Dynamic = js.Dynamic.global.Buffer

    private def emptyBuffer: js.Dynamic = buffer.applyDynamic("alloc")(0)

    private def length(b: js.Dynamic): Int = b.selectDynamic("length").asInstanceOf[Int]

    /** Decode a line's bytes as UTF-8, dropping one trailing `\r` so CRLF input reads the same as LF input. */
    private def decode(bytes: js.Dynamic): String =
        val size = length(bytes)
        val end =
            if size > 0 && bytes.applyDynamic("readUInt8")(size - 1).asInstanceOf[Int] == Return then size - 1
            else size
        bytes.applyDynamic("toString")("utf8", 0, end).asInstanceOf[String]
    end decode

    /** The `code` property Node puts on a system error, or the empty string when the failure carries none. */
    private def errorCode(error: Any): String =
        val code = error.asInstanceOf[js.Dynamic].selectDynamic("code")
        if js.typeOf(code) == "string" then code.asInstanceOf[String] else ""

end NodeLineReader
