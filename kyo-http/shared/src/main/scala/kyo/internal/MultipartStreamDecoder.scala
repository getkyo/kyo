package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** Stateful multipart/form-data decoder for streaming byte chunks.
  *
  * Accumulates bytes across chunk boundaries and yields complete `HttpRequest.Part` objects. Each call to `decode` may return zero or more
  * parts depending on how much data has arrived.
  *
  * State machine: PREAMBLE -> HEADERS -> CONTENT -> (loop back to HEADERS or DONE)
  */
final private[kyo] class MultipartStreamDecoder(boundary: String):

    private val boundaryBytes    = ("--" + boundary).getBytes(StandardCharsets.UTF_8)
    private val crlfBytes        = "\r\n".getBytes(StandardCharsets.UTF_8)
    private val headerEndBytes   = "\r\n\r\n".getBytes(StandardCharsets.UTF_8)
    private val closingDashBytes = "--".getBytes(StandardCharsets.UTF_8)

    private var buffer: Array[Byte] = Array.empty
    private var done: Boolean       = false

    /** Decode a chunk of bytes, returning any complete parts found. */
    def decode(bytes: Span[Byte]): Seq[HttpRequest.Part] =
        if done then return Seq.empty

        // Append new bytes to buffer
        val incoming = bytes.toArrayUnsafe
        if incoming.isEmpty then return Seq.empty

        val newBuf = new Array[Byte](buffer.length + incoming.length)
        java.lang.System.arraycopy(buffer, 0, newBuf, 0, buffer.length)
        java.lang.System.arraycopy(incoming, 0, newBuf, buffer.length, incoming.length)
        buffer = newBuf

        val parts = Seq.newBuilder[HttpRequest.Part]

        var continue = true
        while continue do
            tryParsePart() match
                case Present(part) => parts += part
                case Absent        => continue = false
        end while

        parts.result()
    end decode

    /** Try to parse one complete part from the buffer. Returns Absent if not enough data yet. */
    private def tryParsePart(): Maybe[HttpRequest.Part] =
        // Find boundary
        val boundaryPos = MultipartUtil.indexOf(buffer, boundaryBytes, 0)
        if boundaryPos < 0 then return Absent

        val afterBoundary = boundaryPos + boundaryBytes.length

        // Check if this is the closing boundary (--)
        if afterBoundary + closingDashBytes.length <= buffer.length then
            if buffer(afterBoundary) == '-' && buffer(afterBoundary + 1) == '-' then
                done = true
                return Absent
        end if

        // Need \r\n after boundary
        if afterBoundary + crlfBytes.length > buffer.length then return Absent
        if buffer(afterBoundary) != '\r' || buffer(afterBoundary + 1) != '\n' then
            // Not a valid boundary line, skip past it
            buffer = sliceFrom(buffer, afterBoundary)
            return Absent
        end if

        val headerStart = afterBoundary + crlfBytes.length

        // Find header end (\r\n\r\n)
        val headerEnd = MultipartUtil.indexOf(buffer, headerEndBytes, headerStart)
        if headerEnd < 0 then return Absent

        // Parse headers
        val headerSection                  = new String(buffer, headerStart, headerEnd - headerStart, StandardCharsets.UTF_8)
        var name: String                   = ""
        var filename: Maybe[String]        = Absent
        var partContentType: Maybe[String] = Absent

        headerSection.split("\r\n").foreach { line =>
            val colonIdx = line.indexOf(':')
            if colonIdx > 0 then
                val headerName  = line.substring(0, colonIdx).trim.toLowerCase
                val headerValue = line.substring(colonIdx + 1).trim
                if headerName == "content-disposition" then
                    MultipartUtil.extractDispositionParam(headerValue, "name").foreach(n => name = n)
                    filename = MultipartUtil.extractDispositionParam(headerValue, "filename")
                else if headerName == "content-type" then
                    partContentType = Present(headerValue)
                end if
            end if
        }

        val contentStart = headerEnd + headerEndBytes.length

        // Content runs until \r\n--boundary
        val crlfBoundary = new Array[Byte](crlfBytes.length + boundaryBytes.length)
        java.lang.System.arraycopy(crlfBytes, 0, crlfBoundary, 0, crlfBytes.length)
        java.lang.System.arraycopy(boundaryBytes, 0, crlfBoundary, crlfBytes.length, boundaryBytes.length)

        val nextBoundary = MultipartUtil.indexOf(buffer, crlfBoundary, contentStart)
        if nextBoundary < 0 then return Absent

        // Extract content
        val contentLen = nextBoundary - contentStart
        val content =
            if contentLen > 0 then
                val arr = new Array[Byte](contentLen)
                java.lang.System.arraycopy(buffer, contentStart, arr, 0, contentLen)
                arr
            else
                Array.empty[Byte]

        // Advance buffer past the \r\n before the next boundary marker
        buffer = sliceFrom(buffer, nextBoundary + crlfBytes.length)

        if name.nonEmpty then
            Present(HttpRequest.Part(name, filename, partContentType, content))
        else
            // Skip parts without a name but continue parsing
            tryParsePart()
        end if
    end tryParsePart

    private def sliceFrom(arr: Array[Byte], from: Int): Array[Byte] =
        if from >= arr.length then Array.empty
        else
            val len    = arr.length - from
            val result = new Array[Byte](len)
            java.lang.System.arraycopy(arr, from, result, 0, len)
            result
    end sliceFrom

end MultipartStreamDecoder
