package kyo.internal.framing

import kyo.*

private[kyo] object FramerImpl:

    def parseLineDelimited(
        stream: Stream[Chunk[Byte], Async & Abort[Closed]]
    )(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        // AtomicRef for stateful leftover buffer across mapChunk calls; Sync effect threaded via mapChunk S2
        Stream.unwrap {
            AtomicRef.init[Chunk[Byte]](Chunk.empty).map { bufRef =>
                stream.mapChunk[Chunk[Byte], Chunk[Byte], Sync] { chunks =>
                    Sync.defer {
                        // AtomicRef.Unsafe.get/set inside Sync.defer for leftover buffer; single-fiber stream consumption
                        val current        = bufRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                        val combined       = current ++ chunks.foldLeft(Chunk.empty[Byte])(_ ++ _)
                        val (frames, left) = splitOnLf(combined)
                        bufRef.unsafe.set(left)(using AllowUnsafe.embrace.danger)
                        frames.toSeq
                    }
                }
            }
        }

    def parseContentLength(
        stream: Stream[Chunk[Byte], Async & Abort[Closed]]
    )(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        // AtomicRef for stateful leftover buffer across mapChunk calls; Sync effect threaded via mapChunk S2
        Stream.unwrap {
            AtomicRef.init[Chunk[Byte]](Chunk.empty).map { bufRef =>
                stream.mapChunk[Chunk[Byte], Chunk[Byte], Sync] { chunks =>
                    Sync.defer {
                        // AtomicRef.Unsafe.get/set inside Sync.defer for leftover buffer; single-fiber stream consumption
                        val current        = bufRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                        val combined       = current ++ chunks.foldLeft(Chunk.empty[Byte])(_ ++ _)
                        val (frames, left) = splitContentLength(combined)
                        bufRef.unsafe.set(left)(using AllowUnsafe.embrace.danger)
                        frames.toSeq
                    }
                }
            }
        }

    private def splitOnLf(buf: Chunk[Byte]): (Chunk[Chunk[Byte]], Chunk[Byte]) =
        val builder = scala.collection.mutable.ArrayBuffer.empty[Chunk[Byte]]
        var start   = 0
        var i       = 0
        val arr     = buf.toArray
        while i < arr.length do
            if arr(i) == '\n'.toByte then
                val end   = if i > 0 && arr(i - 1) == '\r'.toByte then i - 1 else i
                val frame = Chunk.from(arr.slice(start, end))
                if frame.nonEmpty then builder += frame
                start = i + 1
            end if
            i += 1
        end while
        val leftover = Chunk.from(arr.slice(start, arr.length))
        (Chunk.from(builder.toSeq), leftover)
    end splitOnLf

    private def splitContentLength(buf: Chunk[Byte]): (Chunk[Chunk[Byte]], Chunk[Byte]) =
        val builder = scala.collection.mutable.ArrayBuffer.empty[Chunk[Byte]]
        var rest    = buf
        var done    = false
        while !done do
            parseOneContentLengthFrame(rest) match
                case Maybe.Absent => done = true
                case Maybe.Present((frame, r)) =>
                    builder += frame
                    rest = r
        end while
        (Chunk.from(builder.toSeq), rest)
    end splitContentLength

    private def parseOneContentLengthFrame(buf: Chunk[Byte]): Maybe[(Chunk[Byte], Chunk[Byte])] =
        val arr  = buf.toArray
        val sep1 = indexOf(arr, "\r\n\r\n".getBytes("UTF-8"))
        val sep2 = indexOf(arr, "\n\n".getBytes("UTF-8"))
        val sepIdx = (sep1, sep2) match
            case (-1, -1) => -1
            case (a, -1)  => a
            case (-1, b)  => b
            case (a, b)   => Math.min(a, b)
        if sepIdx == -1 then Maybe.Absent
        else
            val sepLen  = if sepIdx == sep1 then 4 else 2
            val headers = new String(arr.slice(0, sepIdx), "UTF-8")
            val len     = parseContentLengthHeader(headers).getOrElse(-1)
            if len < 0 then Maybe.Absent
            else
                val bodyStart = sepIdx + sepLen
                val bodyEnd   = bodyStart + len
                if arr.length < bodyEnd then Maybe.Absent
                else
                    val frame = Chunk.from(arr.slice(bodyStart, bodyEnd))
                    val rest  = Chunk.from(arr.slice(bodyEnd, arr.length))
                    Maybe.Present((frame, rest))
                end if
            end if
        end if
    end parseOneContentLengthFrame

    private def parseContentLengthHeader(headers: String): Maybe[Int] =
        val lines             = headers.split("\r?\n").iterator
        var found: Maybe[Int] = Maybe.Absent
        while lines.hasNext && found.isEmpty do
            val line  = lines.next()
            val colon = line.indexOf(':')
            if colon > 0 then
                val key   = line.substring(0, colon).trim
                val value = line.substring(colon + 1).trim
                if key.equalsIgnoreCase("Content-Length") then
                    scala.util.Try(value.toInt).toOption match
                        // scala.Option arm; interop with stdlib Try.toOption
                        case Some(n) if n >= 0 => found = Maybe.Present(n)
                        // scala.Option arm; interop with stdlib Try.toOption
                        case _ => ()
                end if
            end if
        end while
        found
    end parseContentLengthHeader

    private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
        var i     = 0
        val limit = haystack.length - needle.length
        while i <= limit do
            var j  = 0
            var ok = true
            while j < needle.length && ok do
                if haystack(i + j) != needle(j) then ok = false
                j += 1
            if ok then return i
            i += 1
        end while
        -1
    end indexOf

end FramerImpl
