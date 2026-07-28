package kyo.internal.http1

import kyo.*
import kyo.internal.util.*
import kyo.net.internal.util.GrowableByteBuffer
import scala.annotation.tailrec

/** Strips HTTP/1.1 chunked transfer encoding framing from raw bytes.
  *
  * State machine: ReadSize -> ReadData -> ReadDataCrlf -> (loop or ReadTrailer -> Done). Handles splits at any byte boundary: chunk sizes,
  * CRLF separators, and data payloads can all arrive split across multiple channel reads.
  *
  * Three entry points:
  *   - readBufferedUnsafe: callback-driven (for use inside unsafe parser callbacks)
  *   - readBuffered: Kyo-native, accumulates all decoded chunks into one Span[Byte]
  *   - readStreaming: Kyo-native, delivers decoded chunks to an output channel as they arrive
  *
  * The DecoderState class holds the mutable parse state and internal byte buffer. It is created fresh per request, not connection-scoped,  * because chunked bodies are not reused.
  */
private[kyo] object ChunkedBodyDecoder:

    private val CR: Byte = '\r'.toByte
    private val LF: Byte = '\n'.toByte

    /** Buffered mode (unsafe): accumulates all decoded chunks into one Span[Byte] via callbacks. Calls the completion callback with the
      * final result when done.
      */
    def readBufferedUnsafe(
        inbound: Channel.Unsafe[Span[Byte]],
        initialBytes: Span[Byte],
        maxBytes: Int,
        state: DecoderState = new DecoderState
    )(
        onResult: Result[Closed, Span[Byte]] => Unit,
        onTooLarge: Int => Unit,
        onInvalid: HttpMalformedBodyException => Unit
    )(using AllowUnsafe, Frame): Unit =
        val accumulator = new GrowableByteBuffer
        if !initialBytes.isEmpty then
            state.feedBytes(initialBytes)
        bufferedLoopUnsafe(inbound, accumulator, state, maxBytes, onResult, onTooLarge, onInvalid)
    end readBufferedUnsafe

    /** Main unsafe buffered decode loop. Processes buffer, accumulates data, reads more via callbacks. Caps the accumulated body at
      * `maxBytes`: a server streaming an unbounded chunked body would otherwise grow `accumulator` without limit (CWE-400). The cap is
      * checked at the top of each drain iteration; since per-chunk size is already overflow-bounded ([[parseChunkSizeLine]]) and the realistic
      * attack is many chunks, the accumulated total cannot grow past `maxBytes` plus at most one in-flight chunk before `onTooLarge` fires.
      */
    private def bufferedLoopUnsafe(
        inbound: Channel.Unsafe[Span[Byte]],
        accumulator: GrowableByteBuffer,
        state: DecoderState,
        maxBytes: Int,
        onResult: Result[Closed, Span[Byte]] => Unit,
        onTooLarge: Int => Unit,
        onInvalid: HttpMalformedBodyException => Unit
    )(using AllowUnsafe, Frame): Unit =
        val pending = accumulator.size + state.pendingSize
        if pending > maxBytes then onTooLarge(pending)
        else
            state.drain(accumulator) match
                case DrainResult.Done =>
                    onResult(Result.succeed(Span.fromUnsafe(accumulator.toByteArray)))
                case DrainResult.NeedMore =>
                    // Cross opaque boundary: Fiber.Unsafe[Span[Byte], Abort[Closed]] is IOPromise[Any, Span[Byte] < Abort[Closed]]
                    // at runtime, but Channel delivers plain Span[Byte] values, not effectful computations.
                    // Casting to IOPromise[Closed, Span[Byte]] lets onComplete see Result[Closed, Span[Byte]] directly.
                    inbound.takeFiber()
                        .asInstanceOf[kyo.scheduler.IOPromise[Closed, Span[Byte]]].onComplete { result =>
                            result match
                                case Result.Success(span) =>
                                    state.feedBytes(span)
                                    bufferedLoopUnsafe(inbound, accumulator, state, maxBytes, onResult, onTooLarge, onInvalid)
                                case Result.Failure(closed) =>
                                    onResult(Result.fail(closed))
                                case Result.Panic(t) =>
                                    onResult(Result.panic(t))
                        }
                case DrainResult.ChunkReady =>
                    // In buffered mode, data is already in accumulator. Continue draining.
                    bufferedLoopUnsafe(inbound, accumulator, state, maxBytes, onResult, onTooLarge, onInvalid)
                case DrainResult.Invalid(reason) =>
                    onInvalid(HttpMalformedBodyException(reason))
        end if
    end bufferedLoopUnsafe

    /** Buffered mode: accumulates all decoded chunks into one Span[Byte], bounded by `maxBytes`.
      *
      * A chunked body has no declared length, so decoding it into memory must be capped or it grows without limit
      * (CWE-400). When the accumulated decoded bytes exceed `maxBytes` the decode aborts `HttpPayloadTooLargeException`
      * so the caller can answer 413 rather than buffer an unbounded body.
      */
    def readBuffered(
        inbound: Channel.Unsafe[Span[Byte]],
        initialBytes: Span[Byte],
        maxBytes: Int,
        state: DecoderState = new DecoderState
    )(using Frame): Span[Byte] < (Async & Abort[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException]) =
        val accumulator = new GrowableByteBuffer
        if !initialBytes.isEmpty then
            state.feedBytes(initialBytes)
        bufferedLoop(inbound, accumulator, state, maxBytes)
    end readBuffered

    /** Streaming mode: delivers each decoded chunk to output, then closes output.
      *
      * The delivered body is unbounded by design (streaming trades unbounded total for bounded memory), but the control
      * plane is not: a chunk extension or trailer that never completes a line would buffer without limit (CWE-400).
      * `maxControlBytes` bounds the buffered-but-undelivered bytes (the size line and trailer, plus at most one read of
      * chunk data), aborting HttpPayloadTooLargeException when exceeded; the delivered stream itself stays uncapped.
      */
    def readStreaming(
        inbound: Channel.Unsafe[Span[Byte]],
        initialBytes: Span[Byte],
        output: Channel.Unsafe[Span[Byte]],
        maxControlBytes: Int,
        state: DecoderState = new DecoderState
    )(using Frame): Unit < (Async & Abort[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException]) =
        if !initialBytes.isEmpty then
            state.feedBytes(initialBytes)
        streamingLoop(inbound, output, state, maxControlBytes)
    end readStreaming

    /** Main buffered decode loop. Processes buffer, accumulates data, reads more if needed. Caps the accumulated body
      * at `maxBytes` (aborting HttpPayloadTooLargeException), for the same CWE-400 reason as bufferedLoopUnsafe.
      */
    private def bufferedLoop(
        inbound: Channel.Unsafe[Span[Byte]],
        accumulator: GrowableByteBuffer,
        state: DecoderState,
        maxBytes: Int
    )(using Frame): Span[Byte] < (Async & Abort[Closed | HttpPayloadTooLargeException | HttpMalformedBodyException]) =
        val pending = accumulator.size + state.pendingSize
        if pending > maxBytes then
            Abort.fail(HttpPayloadTooLargeException(pending, maxBytes))
        else
            state.drain(accumulator) match
                case DrainResult.Done =>
                    Span.fromUnsafe(accumulator.toByteArray)
                case DrainResult.NeedMore =>
                    inbound.safe.take.map { span =>
                        state.feedBytes(span)
                        bufferedLoop(inbound, accumulator, state, maxBytes)
                    }
                case DrainResult.ChunkReady =>
                    // In buffered mode, data is already in accumulator. Continue draining.
                    bufferedLoop(inbound, accumulator, state, maxBytes)
                case DrainResult.Invalid(reason) =>
                    Abort.fail(HttpMalformedBodyException(reason))
        end if
    end bufferedLoop

    /** Main streaming decode loop. Processes buffer, delivers complete chunks to output. */
    private def streamingLoop(
        inbound: Channel.Unsafe[Span[Byte]],
        output: Channel.Unsafe[Span[Byte]],
        state: DecoderState,
        maxControlBytes: Int
    )(using Frame): Unit < (Async & Abort[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException]) =
        val chunkBuf = new GrowableByteBuffer
        streamingDrainAndDeliver(inbound, output, state, chunkBuf, maxControlBytes)
    end streamingLoop

    /** Drains the state buffer, delivering complete chunks via the safe channel API. */
    private def streamingDrainAndDeliver(
        inbound: Channel.Unsafe[Span[Byte]],
        output: Channel.Unsafe[Span[Byte]],
        state: DecoderState,
        chunkBuf: GrowableByteBuffer,
        maxControlBytes: Int
    )(using Frame): Unit < (Async & Abort[Closed | HttpMalformedBodyException | HttpPayloadTooLargeException]) =
        state.drain(chunkBuf) match
            case DrainResult.Done =>
                // Don't close the channel here, the caller (UnsafeServerDispatch) manages lifecycle.
                // Channel.close drops buffered items, which would lose chunks the consumer hasn't read yet.
                ()
            case DrainResult.Invalid(reason) =>
                // Malformed framing mid-stream: fail rather than silently ending the delivered stream truncated.
                Abort.fail(HttpMalformedBodyException(reason))
            case DrainResult.ChunkReady =>
                // A complete chunk has been decoded, deliver it
                val decoded = Span.fromUnsafe(chunkBuf.toByteArray)
                chunkBuf.reset()
                output.safe.put(decoded).andThen(
                    streamingDrainAndDeliver(inbound, output, state, chunkBuf, maxControlBytes)
                )
            case DrainResult.NeedMore =>
                // Deliver any partial data accumulated so far for this chunk, then, before blocking for more input,
                // bound the undelivered control plane. At NeedMore the decoder has consumed all it can, so buf holds
                // only an incomplete size line or trailer (chunk data has already been drained into chunkBuf), and
                // state.pendingSize is that control plane alone. A control line that never completes would grow it
                // without limit across reads (CWE-400); the delivered body is not counted, so a large legitimate body
                // still streams.
                val flush =
                    if chunkBuf.size > 0 then
                        val decoded = Span.fromUnsafe(chunkBuf.toByteArray)
                        chunkBuf.reset()
                        output.safe.put(decoded)
                    else Kyo.unit
                flush.andThen {
                    if state.pendingSize > maxControlBytes then
                        Abort.fail(HttpPayloadTooLargeException(state.pendingSize, maxControlBytes))
                    else
                        inbound.safe.take.map { span =>
                            state.feedBytes(span)
                            streamingDrainAndDeliver(inbound, output, state, chunkBuf, maxControlBytes)
                        }
                }
    end streamingDrainAndDeliver

    /** Result of a drain operation. */
    private[http1] enum DrainResult derives CanEqual:
        case Done                    // Terminal chunk processed
        case NeedMore                // Need more bytes from inbound
        case ChunkReady              // A complete chunk's data has been written to the accumulator
        case Invalid(reason: String) // Malformed framing: the decode must fail, not truncate silently
    end DrainResult

    /** Mutable state machine for chunked decoding.
      *
      * Phases: ReadSize -> ReadData -> ReadDataCrlf -> (loop back to ReadSize or ReadTrailer -> Done)
      *
      * drain() processes the internal buffer until it either completes (Done), needs more input (NeedMore), or finishes decoding one
      * chunk's data (ChunkReady). The caller decides how to handle each result, buffered mode ignores ChunkReady and keeps draining,
      * streaming mode delivers the chunk to the output channel.
      *
      * Instances can be reset and reused across multiple responses on the same connection via reset().
      */
    private[http1] class DecoderState:
        // Internal buffer of unprocessed bytes
        private var buf: Array[Byte] = new Array[Byte](4096)
        private var readPos: Int     = 0
        private var writePos: Int    = 0

        // State machine phase
        private var phase: Int     = PhaseReadSize
        private var chunkSize: Int = 0 // size of current chunk being read
        private var dataRead: Int  = 0 // bytes of chunk data read so far

        // Accumulated bytes for chunk size line (handles line split across reads)
        private val sizeLine = new GrowableByteBuffer

        // Tracks whether we're mid-CRLF (saw CR, waiting for LF)
        private var sawCr: Boolean = false

        // Set alongside PhaseInvalid: why the framing was rejected, surfaced as HttpMalformedBodyException by the loops.
        private var invalidReason: String = ""

        /** Reset all parse state for reuse on a new chunked response. The internal byte buffer is retained to avoid reallocation. */
        def reset(): Unit =
            readPos = 0
            writePos = 0
            phase = PhaseReadSize
            chunkSize = 0
            dataRead = 0
            sizeLine.reset()
            sawCr = false
            invalidReason = ""
        end reset

        /** Transition to the terminal invalid phase with a reason; returns true so the drain loop re-checks the phase. */
        private def fail(reason: String): Boolean =
            phase = PhaseInvalid
            invalidReason = reason
            true
        end fail

        /** Bytes buffered but not yet turned into output: the raw buf tail plus the in-progress size line. A chunk
          * extension or a trailer section with no terminating line accumulates here without ever producing output, so
          * it must be counted against the cap or it grows unbounded (CWE-400). The decoded accumulator is counted
          * separately by the caller.
          */
        def pendingSize: Int = (writePos - readPos) + sizeLine.size

        /** Feed new bytes into the internal buffer. */
        def feedBytes(span: Span[Byte]): Unit =
            val arr = span.toArray
            ensureSpace(arr.length)
            java.lang.System.arraycopy(arr, 0, buf, writePos, arr.length)
            writePos += arr.length
        end feedBytes

        /** Process the internal buffer. Writes decoded chunk data to `accumulator`.
          *
          * Returns:
          *   - Done when terminal chunk + trailers are consumed
          *   - NeedMore when the buffer is exhausted mid-parse
          *   - ChunkReady when one chunk's data has been fully written to accumulator (for streaming delivery)
          */
        def drain(accumulator: GrowableByteBuffer): DrainResult =
            @tailrec def loop(): DrainResult =
                if phase == PhaseDone then DrainResult.Done
                else if phase == PhaseInvalid then DrainResult.Invalid(invalidReason)
                else
                    val available = writePos - readPos
                    if available <= 0 then
                        compact()
                        DrainResult.NeedMore
                    else
                        phase match
                            case PhaseReadSize =>
                                if processReadSize() then loop()
                                else
                                    compact(); DrainResult.NeedMore
                            case PhaseReadData =>
                                val transitioned = processReadData(accumulator)
                                if transitioned then
                                    // Full chunk data consumed, signal chunk ready
                                    DrainResult.ChunkReady
                                else
                                    // Partial data consumed, need more bytes
                                    compact()
                                    DrainResult.NeedMore
                                end if
                            case PhaseReadDataCrlf =>
                                if processReadDataCrlf() then loop()
                                else
                                    compact(); DrainResult.NeedMore
                            case PhaseReadTrailer =>
                                if processReadTrailer() then loop()
                                else
                                    compact(); DrainResult.NeedMore
                            case PhaseDone => DrainResult.Done
                    end if
                end if
            end loop
            loop()
        end drain

        /** Process the chunk size line. Returns true if phase transitioned. */
        private def processReadSize(): Boolean =
            val end = writePos
            @tailrec def findLf(i: Int): Int =
                if i >= end then -1
                else if buf(i) == LF then i
                else findLf(i + 1)
            val lfPos = findLf(readPos)
            if lfPos < 0 then
                // No complete line yet, buffer the bytes for next time
                sizeLine.writeBytes(buf, readPos, end - readPos)
                readPos = end
                false
            else
                // Copy bytes up to LF into sizeLine
                sizeLine.writeBytes(buf, readPos, lfPos - readPos)
                readPos = lfPos + 1

                // Validate CRLF: the byte before LF must be CR.
                // The CR is the last byte in sizeLine (since we copied up to but not including LF).
                val lineArr = sizeLine.toByteArray
                sizeLine.reset()
                val lineLen = lineArr.length
                if lineLen == 0 || lineArr(lineLen - 1) != CR then
                    // Bare LF line ending (no preceding CR): RFC 9112 section 2.2 forbids it; CVE-2025-22871.
                    fail("chunk size line ended with a bare LF")
                else if hasEmbeddedCr(lineArr, lineLen - 1) then
                    // A CR before the terminating CR. The chunk-ext is token / quoted-string (RFC 9112 section 7.1.1),
                    // neither of which admits a CR, so a quoted-string-aware upstream reads the line end at a different
                    // byte and the two disagree on where the chunk data begins (Jetty CVE-2026-2332 / Netty
                    // CVE-2026-33870 smuggling desync). findHexEnd stops at ';', so only a full-line scan catches a CR
                    // inside the extension.
                    fail("embedded CR in chunk size line")
                else
                    chunkSize = parseChunkSizeLine(lineArr)
                    dataRead = 0

                    if chunkSize == -1 then
                        fail("invalid chunk size")
                    else if chunkSize == 0 then
                        phase = PhaseReadTrailer
                        true
                    else
                        phase = PhaseReadData
                        true
                    end if
                end if
            end if
        end processReadSize

        /** True if a CR appears anywhere in lineArr strictly before `crPos` (the terminating CR's index). The line was
          * split on the first LF, so it holds no LF; only a bare CR can be embedded.
          */
        private def hasEmbeddedCr(lineArr: Array[Byte], crPos: Int): Boolean =
            @tailrec def scan(i: Int): Boolean =
                if i >= crPos then false
                else if lineArr(i) == CR then true
                else scan(i + 1)
            scan(0)
        end hasEmbeddedCr

        /** Process chunk data. Returns true if all data for this chunk has been consumed. */
        private def processReadData(accumulator: GrowableByteBuffer): Boolean =
            val remaining = chunkSize - dataRead
            val available = writePos - readPos
            val toCopy    = math.min(remaining, available)
            accumulator.writeBytes(buf, readPos, toCopy)
            readPos += toCopy
            dataRead += toCopy
            if dataRead >= chunkSize then
                phase = PhaseReadDataCrlf
                sawCr = false
                true
            else
                false
            end if
        end processReadData

        /** Process the CRLF after chunk data. Returns true if consumed. */
        private def processReadDataCrlf(): Boolean =
            val available = writePos - readPos
            if available < 1 then
                false
            else if sawCr then
                // Previously consumed CR, now looking for LF
                if buf(readPos) == LF then
                    readPos += 1
                    sawCr = false
                    phase = PhaseReadSize
                    true
                else
                    // CR not followed by LF -- framing error
                    fail("CR not followed by LF after chunk data")
                end if
            else if buf(readPos) == CR then
                readPos += 1
                if readPos < writePos && buf(readPos) == LF then
                    readPos += 1
                    phase = PhaseReadSize
                    true
                else if readPos >= writePos then
                    // CR consumed, need LF in next read
                    sawCr = true
                    false
                else
                    // CR followed by something other than LF -- framing error
                    fail("CR not followed by LF after chunk data")
                end if
            else
                // Neither CR nor LF -- framing error (missing CRLF after chunk data)
                fail("missing CRLF after chunk data")
            end if
        end processReadDataCrlf

        /** Process trailer headers after terminal chunk. Consumes until empty line. */
        private def processReadTrailer(): Boolean =
            // We need to find the end of trailers, which is an empty line.
            // After the terminal "0\r\n", we expect either:
            //   - "\r\n" immediately (no trailers)
            //   - "Header: value\r\n...\r\n" (trailers followed by empty line)
            // Scan for two consecutive line endings (LF LF, with optional CR before each)
            @tailrec def scan(i: Int, prevWasLf: Boolean): Int =
                if i >= writePos then -1
                else if buf(i) == LF then
                    if prevWasLf then i // Found empty line
                    else scan(i + 1, true)
                else if buf(i) == CR then
                    scan(i + 1, prevWasLf) // CR doesn't reset LF tracking
                else
                    scan(i + 1, false)
            end scan

            // Start with prevWasLf=true because the chunk size line "0\r\n" already ended with LF.
            // This way, the very first "\r\n" (no trailers case) is recognized as the empty line.
            val endPos = scan(readPos, true)
            if endPos < 0 then
                false // Need more data
            else
                readPos = endPos + 1
                phase = PhaseDone
                true
            end if
        end processReadTrailer

        /** Compact the internal buffer, shifting unread data to the front. */
        private def compact(): Unit =
            if readPos > 0 then
                val remaining = writePos - readPos
                if remaining > 0 then
                    java.lang.System.arraycopy(buf, readPos, buf, 0, remaining)
                writePos = remaining
                readPos = 0
            end if
        end compact

        /** Ensure space for `needed` additional bytes. */
        private def ensureSpace(needed: Int): Unit =
            val required = writePos + needed
            if required > buf.length then
                @tailrec def grow(newLen: Int): Int = if newLen < required then grow(newLen * 2) else newLen
                val newBuf                          = new Array[Byte](grow(buf.length))
                java.lang.System.arraycopy(buf, 0, newBuf, 0, writePos)
                buf = newBuf
            end if
        end ensureSpace
    end DecoderState

    // Phase constants
    private val PhaseReadSize: Int     = 0
    private val PhaseReadData: Int     = 1
    private val PhaseReadDataCrlf: Int = 2
    private val PhaseReadTrailer: Int  = 3
    private val PhaseDone: Int         = 4
    private val PhaseInvalid: Int      = 5

    /** Parse a chunk size line (may contain extensions after ';'). Strips trailing CR. */
    private def parseChunkSizeLine(lineBytes: Array[Byte]): Int =
        val len0 = lineBytes.length
        // Strip trailing CR
        val len = if len0 > 0 && lineBytes(len0 - 1) == CR then len0 - 1 else len0
        // Find semicolon for extensions
        @tailrec def findHexEnd(i: Int): Int = if i >= len || lineBytes(i) == ';'.toByte then i else findHexEnd(i + 1)
        val hexEnd                           = findHexEnd(0)
        // Parse hex digits, use Long to detect overflow, reject invalid chars
        @tailrec def parseHex(i: Int, acc: Long): Int =
            if i >= hexEnd then
                if acc > Int.MaxValue then -1 else acc.toInt
            else
                val b = lineBytes(i) & 0xff
                val digit =
                    if b >= '0' && b <= '9' then b - '0'
                    else if b >= 'a' && b <= 'f' then b - 'a' + 10
                    else if b >= 'A' && b <= 'F' then b - 'A' + 10
                    else -1
                if digit == -1 || acc > 0xfffffffL then -1 // invalid char or overflow
                else parseHex(i + 1, (acc << 4) | digit)
        if hexEnd == 0 then 0
        else parseHex(0, 0L)
    end parseChunkSizeLine

end ChunkedBodyDecoder
