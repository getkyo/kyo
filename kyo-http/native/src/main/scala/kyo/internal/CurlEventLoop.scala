package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.internal.CurlBindings
import kyo.internal.CurlBindings.*
import scala.annotation.tailrec
import scala.collection.mutable.HashMap
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Dedicated event loop thread running curl_multi_socket_action for the http backend.
  *
  * Kyo threads enqueue transfers via requestQueue + self-pipe wakeup. The event loop thread polls, drives curl, and completes promises with
  * raw response data.
  */
final private[kyo] class CurlEventLoop(daemon: Boolean):
    import AllowUnsafe.embrace.danger

    private given Frame = Frame.internal

    private val multi: CURLM = curl_multi_init()

    // Transfer management
    private[kyo] val transfers = new ConcurrentHashMap[Long, CurlTransferState]()
    private val requestQueue   = new ConcurrentLinkedQueue[java.lang.Long]()

    // Socket tracking (event loop thread only)
    private val socketMap = new HashMap[Int, Short]()

    // Poll timeout from timer callback
    @volatile private var timeoutMs: Int = 1000

    // Self-pipe for wakeup
    private val (pipeReadFd: Int, pipeWriteFd: Int) =
        val fds = new Array[Int](2)
        Zone {
            val pipeFds = stackalloc[CInt](2)
            if posix_pipe(pipeFds) != 0 then
                throw new RuntimeException("Failed to create self-pipe")
            fds(0) = pipeFds(0)
            fds(1) = pipeFds(1)
        }
        (fds(0), fds(1))
    end val

    // Running flag
    @volatile private var running: Boolean = true

    // Register this event loop for static callback routing
    private val loopId: Long = CurlEventLoop.nextId.getAndIncrement()
    discard(CurlEventLoop.loops.put(loopId, this))

    // Set up multi callbacks
    locally {
        val loopPtr = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(loopId))
        discard(curl_multi_setopt(multi, CURLMOPT_SOCKETFUNCTION, CurlEventLoop.socketCallback))
        discard(curl_multi_setopt(multi, CURLMOPT_SOCKETDATA, loopPtr))
        discard(curl_multi_setopt(multi, CURLMOPT_TIMERFUNCTION, CurlEventLoop.timerCallback))
        discard(curl_multi_setopt(multi, CURLMOPT_TIMERDATA, loopPtr))
    }

    // Start the event loop thread
    private val thread = new Thread(() => eventLoop(), "kyo-http-curl-event-loop")
    thread.setDaemon(daemon)
    thread.start()

    /** Enqueue a transfer for processing by the event loop. */
    def enqueue(transferId: Long, state: CurlTransferState): Unit =
        discard(transfers.put(transferId, state))
        discard(requestQueue.add(java.lang.Long.valueOf(transferId)))
        wakeUp()
    end enqueue

    /** Shut down the event loop. */
    def shutdown(): Unit =
        running = false
        wakeUp()
        thread.join(5000)
        discard(CurlEventLoop.loops.remove(loopId))

        // Fail any remaining in-flight transfers
        val iter = transfers.entrySet().iterator()
        while iter.hasNext do
            val entry = iter.next()
            val state = entry.getValue
            failTransfer(state, HttpError.ConnectionError("Event loop shut down", new RuntimeException("Event loop shut down")))
            iter.remove()
        end while

        discard(curl_multi_cleanup(multi))
        discard(posix_close(pipeReadFd))
        discard(posix_close(pipeWriteFd))
    end shutdown

    private[kyo] def wakeUp(): Unit =
        Zone {
            val buf = stackalloc[Byte](1)
            !buf = 1.toByte
            discard(posix_write(pipeWriteFd, buf, 1.toCSize))
        }
    end wakeUp

    private def eventLoop(): Unit =
        Zone {
            val runningHandles = stackalloc[CInt](1)
            val maxPollFds     = 1024
            val fds            = stackalloc[PollFd](maxPollFds)

            discard(curl_multi_socket_action(multi, CURL_SOCKET_TIMEOUT, 0, runningHandles))

            while running do
                drainRequestQueue()
                checkPausedTransfers()

                val numSockets = socketMap.size
                val totalFds   = Math.min(numSockets + 1, maxPollFds)

                fds(0)._1 = pipeReadFd
                fds(0)._2 = POLLIN
                fds(0)._3 = 0

                var idx = 1
                socketMap.foreach { (fd, events) =>
                    fds(idx)._1 = fd
                    fds(idx)._2 = events
                    fds(idx)._3 = 0
                    idx += 1
                }

                val pollResult = posix_poll(fds, totalFds.toCSize, timeoutMs)

                if pollResult > 0 then
                    if (fds(0)._3 & POLLIN) != 0 then
                        drainPipe()

                    idx = 1
                    socketMap.foreach { (fd, _) =>
                        val revents = fds(idx)._3
                        if revents != 0 then
                            var evBitmask = 0
                            if (revents & POLLIN) != 0 then evBitmask |= CURL_POLL_IN
                            if (revents & POLLOUT) != 0 then evBitmask |= CURL_POLL_OUT
                            discard(curl_multi_socket_action(multi, fd, evBitmask, runningHandles))
                        end if
                        idx += 1
                    }
                else if pollResult == 0 then
                    discard(curl_multi_socket_action(multi, CURL_SOCKET_TIMEOUT, 0, runningHandles))
                end if

                processCompletedTransfers()
            end while
        }
    end eventLoop

    private def drainRequestQueue(): Unit =
        var boxedId: java.lang.Long = requestQueue.poll()
        while boxedId != null do
            val id    = boxedId.longValue
            val state = transfers.get(id)
            if state != null then
                val handle = state.easyHandle
                val idPtr  = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(id))
                discard(curl_easy_setopt(handle, CURLOPT_PRIVATE, idPtr))
                discard(curl_multi_add_handle(multi, handle))
            end if
            boxedId = requestQueue.poll()
        end while
    end drainRequestQueue

    private def checkPausedTransfers(): Unit =
        val iter = transfers.entrySet().iterator()
        while iter.hasNext do
            val entry = iter.next()
            val state = entry.getValue

            // Response write pause (streaming response)
            state match
                case st: CurlStreamingTransferState if st.isPaused =>
                    st.bodyChannel.full() match
                        case Result.Success(false) =>
                            st.isPaused = false
                            discard(curl_easy_pause(st.easyHandle, CURLPAUSE_CONT))
                        case _ => ()
                case _ => ()
            end match

            // Request read pause (streaming request body)
            val rs = state.readState
            if rs != null && rs.isPaused then
                rs.channel.empty() match
                    case Result.Success(false) =>
                        rs.isPaused = false
                        discard(curl_easy_pause(state.easyHandle, CURLPAUSE_CONT))
                    case _ => ()
            end if
        end while
    end checkPausedTransfers

    private def processCompletedTransfers(): Unit =
        Zone {
            val msgsLeft = stackalloc[CInt](1)
            var msg      = curl_multi_info_read(multi, msgsLeft)
            while msg != null do
                if msg._1 == CURLMSG_DONE then
                    val easyHandle = msg._2
                    val curlResult = msg._3

                    val privatePtr = stackalloc[Ptr[Byte]](1)
                    discard(curl_easy_getinfo(easyHandle, CURLINFO_PRIVATE, privatePtr))
                    val transferId = Intrinsics.castRawPtrToLong(toRawPtr(!privatePtr))

                    val state = transfers.remove(transferId)
                    discard(curl_multi_remove_handle(multi, easyHandle))

                    if state != null then
                        if curlResult == 0 then
                            completeTransfer(state)
                        else
                            val error = curlResultToError(curlResult, state)
                            failTransfer(state, error)
                        end if
                        cleanupTransfer(state)
                    end if
                end if
                msg = curl_multi_info_read(multi, msgsLeft)
            end while
        }
    end processCompletedTransfers

    private def completeTransfer(state: CurlTransferState): Unit =
        state match
            case bs: CurlBufferedTransferState =>
                val headers = parseHeaders(bs.responseHeaders.toString)
                val body    = bs.responseBody.toByteArray
                val result  = CurlBufferedResult(bs.statusCode, headers, Span.fromUnsafe(body))
                discard(bs.promise.complete(Result.succeed(result)))

            case ss: CurlStreamingTransferState =>
                if !ss.headersCompleted then
                    val headers = parseHeaders(ss.responseHeaders.toString)
                    ss.headersCompleted = true
                    discard(ss.headerPromise.complete(Result.succeed(CurlStreamingHeaders(ss.statusCode, headers))))
                end if
                // Signal end of stream with Absent sentinel
                discard(ss.bodyChannel.putFiber(Absent))
    end completeTransfer

    private def failTransfer(state: CurlTransferState, error: HttpError): Unit =
        state match
            case bs: CurlBufferedTransferState =>
                discard(bs.promise.complete(Result.fail(error)))
            case ss: CurlStreamingTransferState =>
                discard(ss.headerPromise.complete(Result.fail(error)))
                discard(ss.bodyChannel.close())
    end failTransfer

    private def cleanupTransfer(state: CurlTransferState): Unit =
        if state.readState != null then
            discard(state.readState.channel.close())
        if state.headerList != null then
            curl_slist_free_all(state.headerList)
        curl_easy_cleanup(state.easyHandle)
    end cleanupTransfer

    private def curlResultToError(code: CInt, state: CurlTransferState): HttpError =
        val (host, port) = state match
            case bs: CurlBufferedTransferState  => (bs.host, bs.port)
            case ss: CurlStreamingTransferState => (ss.host, ss.port)
        CurlEventLoop.curlResultToError(code, host, port)
    end curlResultToError

    /** Parse raw header lines into HttpHeaders (flat interleaved Chunk). */
    private def parseHeaders(raw: String): HttpHeaders =
        val builder = ChunkBuilder.init[String]
        val lines   = raw.split("\r\n")
        var i       = 0
        while i < lines.length do
            val line     = lines(i)
            val colonIdx = line.indexOf(':')
            if colonIdx > 0 then
                discard(builder += line.substring(0, colonIdx).trim)
                discard(builder += line.substring(colonIdx + 1).trim)
            end if
            i += 1
        end while
        HttpHeaders.fromChunk(builder.result())
    end parseHeaders

    private def drainPipe(): Unit =
        Zone {
            val buf = stackalloc[Byte](64)
            discard(posix_read(pipeReadFd, buf, 64.toCSize))
        }
    end drainPipe

    // ── Callback handling (called by curl on event loop thread) ──────

    private[kyo] def onSocket(easyHandle: CURL, sockfd: CInt, what: CInt): Unit =
        what match
            case CURL_POLL_REMOVE =>
                discard(socketMap.remove(sockfd))
            case CURL_POLL_IN =>
                discard(socketMap.put(sockfd, POLLIN))
            case CURL_POLL_OUT =>
                discard(socketMap.put(sockfd, POLLOUT))
            case CURL_POLL_INOUT =>
                discard(socketMap.put(sockfd, (POLLIN | POLLOUT).toShort))
            case _ => ()
        end match
    end onSocket

    private[kyo] def onTimer(timeoutMsNew: Long): Unit =
        timeoutMs = if timeoutMsNew < 0 then 1000 else timeoutMsNew.toInt
    end onTimer

    private[kyo] def onWrite(transferId: Long, data: Ptr[Byte], size: CSize): CSize =
        val state = transfers.get(transferId)
        if state == null then size
        else
            val intSize = size.toInt
            state match
                case bs: CurlBufferedTransferState =>
                    bs.responseBody.write(copyFromPointer(data, intSize))
                    size

                case ss: CurlStreamingTransferState =>
                    if !ss.headersCompleted then
                        val headers = parseHeaders(ss.responseHeaders.toString)
                        ss.headersCompleted = true
                        discard(ss.headerPromise.complete(Result.succeed(CurlStreamingHeaders(ss.statusCode, headers))))
                    end if

                    ss.bodyChannel.offer(Present(Span.fromUnsafe(copyFromPointer(data, intSize)))) match
                        case Result.Success(true) => size
                        case Result.Success(false) =>
                            ss.isPaused = true
                            CURL_WRITEFUNC_PAUSE
                        case _ =>
                            size
                    end match
            end match
        end if
    end onWrite

    private[kyo] def onRead(transferId: Long, buffer: Ptr[Byte], maxSize: CSize): CSize =
        val state = transfers.get(transferId)
        if state == null then 0.toCSize // EOF
        else
            val rs = state.readState
            if rs == null then 0.toCSize // no streaming body
            else
                val max = maxSize.toInt
                // If we have leftover bytes from a previous chunk, serve those first
                if rs.currentChunk != null then
                    val remaining = rs.currentChunk.length - rs.offset
                    val toCopy    = Math.min(remaining, max)
                    var i         = 0
                    while i < toCopy do
                        buffer(i) = rs.currentChunk(rs.offset + i)
                        i += 1
                    rs.offset += toCopy
                    if rs.offset >= rs.currentChunk.length then
                        rs.currentChunk = null
                        rs.offset = 0
                    toCopy.toCSize
                else
                    // Poll channel for next chunk
                    // channel type: Channel.Unsafe[Maybe[Span[Byte]]]
                    // poll() returns Result[Closed, Maybe[Maybe[Span[Byte]]]]
                    // Present(Present(span)) = data, Present(Absent) = EOF sentinel, Absent = channel empty
                    rs.channel.poll() match
                        case Result.Success(Present(Present(span))) =>
                            val bytes = span.toArrayUnsafe
                            if bytes.length <= max then
                                var i = 0
                                while i < bytes.length do
                                    buffer(i) = bytes(i)
                                    i += 1
                                bytes.length.toCSize
                            else
                                // Partial: copy max, buffer the rest
                                var i = 0
                                while i < max do
                                    buffer(i) = bytes(i)
                                    i += 1
                                rs.currentChunk = bytes
                                rs.offset = max
                                max.toCSize
                            end if
                        case Result.Success(Present(Absent)) =>
                            0.toCSize // EOF sentinel
                        case Result.Success(Absent) =>
                            // Channel empty, no data yet — pause
                            rs.isPaused = true
                            CURL_READFUNC_PAUSE
                        case _ =>
                            0.toCSize // Channel closed or error — EOF
                    end match
                end if
            end if
        end if
    end onRead

    private[kyo] def onHeader(transferId: Long, data: Ptr[Byte], size: CSize): CSize =
        val state = transfers.get(transferId)
        if state == null then size
        else
            val intSize = size.toInt
            val line    = new String(copyFromPointer(data, intSize), "UTF-8")

            if line.startsWith("HTTP/") then
                val spaceIdx = line.indexOf(' ')
                if spaceIdx > 0 then
                    val codeStr = line.substring(spaceIdx + 1, Math.min(spaceIdx + 4, line.length))
                    try state.setStatusCode(codeStr.trim.toInt)
                    catch case _: NumberFormatException => ()
                end if
            else
                discard(state.responseHeaders.append(line))
            end if
            size
        end if
    end onHeader

    /** Copy `size` bytes from a native pointer into a new Array[Byte]. */
    private def copyFromPointer(data: Ptr[Byte], size: Int): Array[Byte] =
        val bytes = new Array[Byte](size)
        var i     = 0
        while i < size do
            bytes(i) = data(i)
            i += 1
        bytes
    end copyFromPointer

end CurlEventLoop

private[kyo] object CurlEventLoop:
    import CurlBindings.*
    import scala.scalanative.runtime.Intrinsics

    private[kyo] val nextId = new AtomicLong(0)
    private[kyo] val loops  = new ConcurrentHashMap[Long, CurlEventLoop]()

    /** Map a curl result code to the appropriate HttpError. */
    private[kyo] def curlResultToError(code: Int, host: String, port: Int)(using Frame): HttpError =
        code match
            case 6 | 7 =>
                HttpError.ConnectionError(
                    s"Connection failed to $host:$port (curl error $code)",
                    new RuntimeException(s"curl error $code")
                )
            case 28 => HttpError.TimeoutError(Duration.Zero) // curl doesn't tell us the configured timeout
            case 35 | 51 | 53 | 54 | 58 | 59 | 60 =>
                HttpError.ConnectionError(
                    s"SSL error connecting to $host:$port (curl error $code)",
                    new RuntimeException(s"curl SSL error $code")
                )
            case _ => HttpError.ConnectionError(s"curl error $code for $host:$port", new RuntimeException(s"curl error $code"))
        end match
    end curlResultToError

    // ── Static C callbacks ─────────────────────────────────────────────

    private def ptrToLong(p: Ptr[Byte]): Long =
        Intrinsics.castRawPtrToLong(toRawPtr(p))

    private[kyo] val socketCallback: CFuncPtr5[CURL, CInt, CInt, Ptr[Byte], Ptr[Byte], CInt] =
        CFuncPtr5.fromScalaFunction { (easy: CURL, sockfd: CInt, what: CInt, userp: Ptr[Byte], socketp: Ptr[Byte]) =>
            val loopId = ptrToLong(userp)
            val loop   = loops.get(loopId)
            if loop != null then loop.onSocket(easy, sockfd, what)
            0
        }

    private[kyo] val timerCallback: CFuncPtr3[CURLM, Long, Ptr[Byte], CInt] =
        CFuncPtr3.fromScalaFunction { (multi: CURLM, timeoutMs: Long, userp: Ptr[Byte]) =>
            val loopId = ptrToLong(userp)
            val loop   = loops.get(loopId)
            if loop != null then loop.onTimer(timeoutMs)
            0
        }

    private[kyo] val readCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
        CFuncPtr4.fromScalaFunction { (buffer: Ptr[Byte], size: CSize, nmemb: CSize, userdata: Ptr[Byte]) =>
            val maxSize    = size * nmemb
            val transferId = ptrToLong(userdata)
            val iter       = loops.values().iterator()
            var result     = 0.toCSize // EOF by default
            var found      = false
            while iter.hasNext && !found do
                val loop = iter.next()
                if loop.transfers.containsKey(transferId) then
                    result = loop.onRead(transferId, buffer, maxSize)
                    found = true
            end while
            result
        }

    private[kyo] val writeCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
        CFuncPtr4.fromScalaFunction { (data: Ptr[Byte], size: CSize, nmemb: CSize, userdata: Ptr[Byte]) =>
            val totalSize  = size * nmemb
            val transferId = ptrToLong(userdata)
            val iter       = loops.values().iterator()
            var result     = totalSize
            var found      = false
            while iter.hasNext && !found do
                val loop = iter.next()
                if loop.transfers.containsKey(transferId) then
                    result = loop.onWrite(transferId, data, totalSize)
                    found = true
            end while
            result
        }

    private[kyo] val headerCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
        CFuncPtr4.fromScalaFunction { (data: Ptr[Byte], size: CSize, nmemb: CSize, userdata: Ptr[Byte]) =>
            val totalSize  = size * nmemb
            val transferId = ptrToLong(userdata)
            val iter       = loops.values().iterator()
            var result     = totalSize
            var found      = false
            while iter.hasNext && !found do
                val loop = iter.next()
                if loop.transfers.containsKey(transferId) then
                    result = loop.onHeader(transferId, data, totalSize)
                    found = true
            end while
            result
        }

end CurlEventLoop
