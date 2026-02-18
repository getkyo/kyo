package kyo.internal

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import scala.collection.mutable.HashMap
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Transfer state for a single curl easy handle. */
sealed private[kyo] trait TransferState:
    def easyHandle: CurlBindings.CURL
    def headerList: Ptr[Byte]
    def setHeaderList(p: Ptr[Byte]): Unit
    def responseHeaders: StringBuilder
    def statusCode: Int
    def setStatusCode(code: Int): Unit
end TransferState

final private[kyo] class BufferedTransferState(
    val promise: Promise.Unsafe[HttpResponse[HttpBody.Bytes], Abort[HttpError]],
    val easyHandle: CurlBindings.CURL,
    val host: String,
    val port: Int
) extends TransferState:
    val responseBody: ByteArrayOutputStream = new ByteArrayOutputStream()
    val responseHeaders: StringBuilder      = new StringBuilder()
    private var _statusCode: Int            = 0
    private var _headerList: Ptr[Byte]      = null

    def statusCode: Int                   = _statusCode
    def setStatusCode(code: Int): Unit    = _statusCode = code
    def headerList: Ptr[Byte]             = _headerList
    def setHeaderList(p: Ptr[Byte]): Unit = _headerList = p
end BufferedTransferState

final private[kyo] class StreamingTransferState(
    val headerPromise: Promise.Unsafe[StreamingHeaders, Abort[HttpError]],
    val bodyChannel: Channel.Unsafe[Span[Byte]],
    val easyHandle: CurlBindings.CURL,
    val host: String,
    val port: Int
) extends TransferState:
    val responseHeaders: StringBuilder = new StringBuilder()
    private var _statusCode: Int       = 0
    private var _headerList: Ptr[Byte] = null
    var headersCompleted: Boolean      = false
    @volatile var isPaused: Boolean    = false

    def statusCode: Int                   = _statusCode
    def setStatusCode(code: Int): Unit    = _statusCode = code
    def headerList: Ptr[Byte]             = _headerList
    def setHeaderList(p: Ptr[Byte]): Unit = _headerList = p
end StreamingTransferState

/** Dedicated event loop thread running curl_multi_socket_action.
  *
  * Architecture mirrors Netty event loop -> Promise bridge:
  *   - Kyo threads enqueue transfers via requestQueue + self-pipe wakeup
  *   - Event loop thread polls, drives curl, and completes promises
  */
final private[kyo] class CurlEventLoop(daemon: Boolean):
    import AllowUnsafe.embrace.danger
    import CurlBindings.*
    private given Frame = Frame.internal

    private val multi: CURLM = curl_multi_init()

    // Transfer management
    private[internal] val transfers = new ConcurrentHashMap[Long, TransferState]()
    private val requestQueue        = new ConcurrentLinkedQueue[java.lang.Long]()

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
    private val thread = new Thread(() => eventLoop(), "kyo-curl-event-loop")
    thread.setDaemon(daemon)
    thread.start()

    /** Enqueue a transfer for processing by the event loop. */
    def enqueue(transferId: Long, state: TransferState): Unit =
        discard(transfers.put(transferId, state))
        discard(requestQueue.add(java.lang.Long.valueOf(transferId)))
        wakeUp()
    end enqueue

    /** Shut down the event loop. */
    def shutdown(): Unit =
        // Signal loop to stop and wake it up
        running = false
        wakeUp()
        thread.join(5000)
        discard(CurlEventLoop.loops.remove(loopId))

        // Fail any remaining in-flight transfers
        val iter = transfers.entrySet().iterator()
        while iter.hasNext do
            val entry = iter.next()
            val state = entry.getValue
            failTransfer(state, HttpError.ConnectionFailed("", 0, new RuntimeException("Event loop shut down")))
            iter.remove()
        end while

        // Clean up curl multi and self-pipe fds
        discard(curl_multi_cleanup(multi))
        discard(posix_close(pipeReadFd))
        discard(posix_close(pipeWriteFd))
    end shutdown

    private def wakeUp(): Unit =
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
                // New transfers arrive via requestQueue; add them to curl_multi before polling
                drainRequestQueue()
                // Streaming transfers may have been paused due to full channels — unpause if space freed
                checkPausedTransfers()

                // Build poll fd array: self-pipe (for wakeup) + tracked curl sockets
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

                // Blocks until I/O ready or timeout — drives curl_multi_socket_action below
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
                // Set the transferId as private data for later retrieval
                val idPtr = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(id))
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
            entry.getValue match
                case st: StreamingTransferState if st.isPaused =>
                    st.bodyChannel.full() match
                        case Result.Success(false) =>
                            st.isPaused = false
                            discard(curl_easy_pause(st.easyHandle, CURLPAUSE_CONT))
                        case _ => ()
                case _ => ()
            end match
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

                    // Retrieve transferId from CURLINFO_PRIVATE
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

    private def completeTransfer(state: TransferState): Unit =
        state match
            case bs: BufferedTransferState =>
                val status  = HttpStatus(bs.statusCode)
                val headers = parseHeaders(bs.responseHeaders.toString)
                val body    = bs.responseBody.toByteArray
                val resp    = HttpResponse.initBytes(status, headers, Span.fromUnsafe(body))
                discard(bs.promise.complete(Result.succeed(resp)))

            case ss: StreamingTransferState =>
                // Complete header promise if not already done
                if !ss.headersCompleted then
                    val status  = HttpStatus(ss.statusCode)
                    val headers = parseHeaders(ss.responseHeaders.toString)
                    ss.headersCompleted = true
                    discard(ss.headerPromise.complete(Result.succeed(StreamingHeaders(status, headers))))
                end if
                // Signal end of stream
                discard(ss.bodyChannel.closeAwaitEmpty())
    end completeTransfer

    private def failTransfer(state: TransferState, error: HttpError): Unit =
        state match
            case bs: BufferedTransferState =>
                discard(bs.promise.complete(Result.fail(error)))
            case ss: StreamingTransferState =>
                discard(ss.headerPromise.complete(Result.fail(error)))
                discard(ss.bodyChannel.closeAwaitEmpty())
    end failTransfer

    private def cleanupTransfer(state: TransferState): Unit =
        if state.headerList != null then
            curl_slist_free_all(state.headerList)
        curl_easy_cleanup(state.easyHandle)
    end cleanupTransfer

    private def curlResultToError(code: CInt, state: TransferState): HttpError =
        val (host, port) = state match
            case bs: BufferedTransferState  => (bs.host, bs.port)
            case ss: StreamingTransferState => (ss.host, ss.port)
        CurlEventLoop.curlResultToError(code, host, port)
    end curlResultToError

    private def parseHeaders(raw: String): HttpHeaders =
        RawHeaderParser.parseHeaders(raw)

    private def drainPipe(): Unit =
        Zone {
            val buf = stackalloc[Byte](64)
            discard(posix_read(pipeReadFd, buf, 64.toCSize))
        }
    end drainPipe

    // ── Callback handling (called by curl on event loop thread) ──────

    private[internal] def onSocket(easyHandle: CURL, sockfd: CInt, what: CInt): Unit =
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

    private[internal] def onTimer(timeoutMsNew: Long): Unit =
        timeoutMs = if timeoutMsNew < 0 then 1000 else timeoutMsNew.toInt
    end onTimer

    private[internal] def onWrite(transferId: Long, data: Ptr[Byte], size: CSize): CSize =
        val state = transfers.get(transferId)
        if state == null then size
        else
            val intSize = size.toInt
            state match
                case bs: BufferedTransferState =>
                    bs.responseBody.write(copyFromPointer(data, intSize))
                    size

                // Headers are only available after the first body data arrives from curl
                case ss: StreamingTransferState =>
                    if !ss.headersCompleted then
                        val status  = HttpStatus(ss.statusCode)
                        val headers = parseHeaders(ss.responseHeaders.toString)
                        ss.headersCompleted = true
                        discard(ss.headerPromise.complete(Result.succeed(StreamingHeaders(status, headers))))
                    end if

                    // Pause curl if channel is full (backpressure)
                    ss.bodyChannel.offer(Span.fromUnsafe(copyFromPointer(data, intSize))) match
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

    private[internal] def onHeader(transferId: Long, data: Ptr[Byte], size: CSize): CSize =
        val state = transfers.get(transferId)
        if state == null then size
        else
            val intSize = size.toInt
            val line    = new String(copyFromPointer(data, intSize), "UTF-8")

            // curl delivers status line ("HTTP/1.1 200 OK") and headers through the same callback
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

    private[internal] val nextId = new AtomicLong(0)
    private[internal] val loops  = new ConcurrentHashMap[Long, CurlEventLoop]()

    /** Map a curl result code to the appropriate HttpError. */
    private[kyo] def curlResultToError(code: Int, host: String, port: Int)(using Frame): HttpError =
        code match
            case 6 | 7 => HttpError.ConnectionFailed(host, port, new RuntimeException(s"curl error $code"))
            case 28    => HttpError.Timeout(s"curl timeout (error $code)")
            case 35 | 51 | 53 | 54 | 58 | 59 | 60 =>
                HttpError.SslError(s"curl SSL error $code", new RuntimeException(s"curl error $code"))
            case _ => HttpError.InvalidResponse(s"curl error $code")
        end match
    end curlResultToError

    // ── Static C callbacks ─────────────────────────────────────────────
    // These are CFuncPtrs that route to the correct CurlEventLoop instance via userp.

    private def ptrToLong(p: Ptr[Byte]): Long =
        Intrinsics.castRawPtrToLong(toRawPtr(p))

    private[internal] val socketCallback: CFuncPtr5[CURL, CInt, CInt, Ptr[Byte], Ptr[Byte], CInt] =
        CFuncPtr5.fromScalaFunction { (easy: CURL, sockfd: CInt, what: CInt, userp: Ptr[Byte], socketp: Ptr[Byte]) =>
            val loopId = ptrToLong(userp)
            val loop   = loops.get(loopId)
            if loop != null then loop.onSocket(easy, sockfd, what)
            0
        }

    private[internal] val timerCallback: CFuncPtr3[CURLM, Long, Ptr[Byte], CInt] =
        CFuncPtr3.fromScalaFunction { (multi: CURLM, timeoutMs: Long, userp: Ptr[Byte]) =>
            val loopId = ptrToLong(userp)
            val loop   = loops.get(loopId)
            if loop != null then loop.onTimer(timeoutMs)
            0
        }

    private[kyo] val writeCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
        CFuncPtr4.fromScalaFunction { (data: Ptr[Byte], size: CSize, nmemb: CSize, userdata: Ptr[Byte]) =>
            val totalSize  = size * nmemb
            val transferId = ptrToLong(userdata)
            // Find the loop that owns this transfer
            val iter   = loops.values().iterator()
            var result = totalSize
            var found  = false
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
