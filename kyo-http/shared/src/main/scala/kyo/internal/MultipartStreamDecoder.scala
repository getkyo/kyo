package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import scala.annotation.tailrec

/** Stateful multipart/form-data decoder for streaming byte chunks.
  *
  * Accumulates bytes across chunk boundaries and yields complete `HttpRequest.Part` objects. Each call to `decode` may return zero or more
  * parts depending on how much data has arrived.
  *
  * State machine: PREAMBLE -> HEADERS -> CONTENT -> (loop back to HEADERS or DONE)
  */
final private[kyo] class MultipartStreamDecoder private (boundary: String):

    private val boundaryBytes    = ("--" + boundary).getBytes(StandardCharsets.UTF_8)
    private val crlfBytes        = "\r\n".getBytes(StandardCharsets.UTF_8)
    private val headerEndBytes   = "\r\n\r\n".getBytes(StandardCharsets.UTF_8)
    private val closingDashBytes = "--".getBytes(StandardCharsets.UTF_8)

    private var buffer: Array[Byte] = Array.empty
    private var done: Boolean       = false

    /** Decode a chunk of bytes, returning any complete parts found. */
    def decode(bytes: Span[Byte])(using AllowUnsafe): Seq[HttpRequest.Part] =
        if done then Seq.empty
        else
            val incoming = bytes.toArrayUnsafe
            if incoming.isEmpty then Seq.empty
            else
                val newBuf = new Array[Byte](buffer.length + incoming.length)
                java.lang.System.arraycopy(buffer, 0, newBuf, 0, buffer.length)
                java.lang.System.arraycopy(incoming, 0, newBuf, buffer.length, incoming.length)
                buffer = newBuf

                @tailrec def collectParts(acc: List[HttpRequest.Part]): Seq[HttpRequest.Part] =
                    tryParsePart() match
                        case Present(part) => collectParts(part :: acc)
                        case Absent        => acc.reverse

                collectParts(Nil)
            end if
    end decode

    /** Try to parse one complete part from the buffer. Returns Absent if not enough data yet. */
    private def tryParsePart(): Maybe[HttpRequest.Part] =
        val boundaryPos = MultipartUtil.indexOf(buffer, boundaryBytes, 0)
        if boundaryPos < 0 then Absent
        else
            val afterBoundary = boundaryPos + boundaryBytes.length
            // Check if this is the closing boundary (--)
            if afterBoundary + closingDashBytes.length <= buffer.length
                && buffer(afterBoundary) == '-' && buffer(afterBoundary + 1) == '-'
            then
                done = true
                Absent
            else if afterBoundary + crlfBytes.length > buffer.length then Absent
            else if buffer(afterBoundary) != '\r' || buffer(afterBoundary + 1) != '\n' then
                // Not a valid boundary line, skip past it
                buffer = sliceFrom(buffer, afterBoundary)
                Absent
            else
                val headerStart = afterBoundary + crlfBytes.length
                val headerEnd   = MultipartUtil.indexOf(buffer, headerEndBytes, headerStart)
                if headerEnd < 0 then Absent
                else
                    val headerSection                     = new String(buffer, headerStart, headerEnd - headerStart, StandardCharsets.UTF_8)
                    val (name, filename, partContentType) = parsePartHeaders(headerSection)
                    val contentStart                      = headerEnd + headerEndBytes.length

                    val crlfBoundary = new Array[Byte](crlfBytes.length + boundaryBytes.length)
                    java.lang.System.arraycopy(crlfBytes, 0, crlfBoundary, 0, crlfBytes.length)
                    java.lang.System.arraycopy(boundaryBytes, 0, crlfBoundary, crlfBytes.length, boundaryBytes.length)

                    val nextBoundary = MultipartUtil.indexOf(buffer, crlfBoundary, contentStart)
                    if nextBoundary < 0 then Absent
                    else
                        val contentLen = nextBoundary - contentStart
                        val content =
                            if contentLen > 0 then
                                val arr = new Array[Byte](contentLen)
                                java.lang.System.arraycopy(buffer, contentStart, arr, 0, contentLen)
                                arr
                            else
                                Array.empty[Byte]

                        buffer = sliceFrom(buffer, nextBoundary + crlfBytes.length)

                        if name.nonEmpty then
                            Present(HttpRequest.Part(name, filename, partContentType, content))
                        else
                            tryParsePart()
                        end if
                    end if
                end if
            end if
        end if
    end tryParsePart

    private def parsePartHeaders(headerSection: String): (String, Maybe[String], Maybe[String]) =
        val lines = headerSection.split("\r\n")
        @tailrec def loop(
            i: Int,
            name: String,
            filename: Maybe[String],
            contentType: Maybe[String]
        ): (String, Maybe[String], Maybe[String]) =
            if i >= lines.length then (name, filename, contentType)
            else
                val line     = lines(i)
                val colonIdx = line.indexOf(':')
                if colonIdx > 0 then
                    val headerName  = line.substring(0, colonIdx).trim.toLowerCase
                    val headerValue = line.substring(colonIdx + 1).trim
                    if headerName == "content-disposition" then
                        val n = MultipartUtil.extractDispositionParam(headerValue, "name").getOrElse(name)
                        val f = MultipartUtil.extractDispositionParam(headerValue, "filename") match
                            case Present(v) => Present(v)
                            case Absent     => filename
                        loop(i + 1, n, f, contentType)
                    else if headerName == "content-type" then
                        loop(i + 1, name, filename, Present(headerValue))
                    else
                        loop(i + 1, name, filename, contentType)
                    end if
                else
                    loop(i + 1, name, filename, contentType)
                end if
        loop(0, "", Absent, Absent)
    end parsePartHeaders

    private def sliceFrom(arr: Array[Byte], from: Int): Array[Byte] =
        if from >= arr.length then Array.empty
        else
            val len    = arr.length - from
            val result = new Array[Byte](len)
            java.lang.System.arraycopy(arr, from, result, 0, len)
            result
    end sliceFrom

end MultipartStreamDecoder

private[kyo] object MultipartStreamDecoder:
    def init(boundary: String)(using AllowUnsafe): MultipartStreamDecoder =
        new MultipartStreamDecoder(boundary)
end MultipartStreamDecoder
