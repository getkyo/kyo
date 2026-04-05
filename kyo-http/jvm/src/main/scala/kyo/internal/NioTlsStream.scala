package kyo.internal

import java.nio.ByteBuffer
import javax.net.ssl.*
import kyo.*

/** TLS-wrapped NIO stream using javax.net.ssl.SSLEngine.
  *
  * Architecture: Application plaintext ↔ SSLEngine.wrap/unwrap ↔ encrypted network bytes ↔ underlying NioStream
  *
  * Non-blocking handshake: checks SSLEngine.getHandshakeStatus() and performs NEED_WRAP (encrypt+send), NEED_UNWRAP (receive+decrypt), or
  * NEED_TASK (run cert validation inline in fiber) operations.
  *
  * Buffer management:
  *   - netInBuf: encrypted bytes read from TCP, input to unwrap
  *   - netOutBuf: encrypted bytes produced by wrap, written to TCP
  *   - BUFFER_OVERFLOW → enlarge output buffer and retry
  *   - BUFFER_UNDERFLOW → read more from TCP
  */
class NioTlsStream(
    underlying: RawStream,
    engine: SSLEngine
) extends RawStream:

    given CanEqual[SSLEngineResult.Status, SSLEngineResult.Status]                   = CanEqual.derived
    given CanEqual[SSLEngineResult.HandshakeStatus, SSLEngineResult.HandshakeStatus] = CanEqual.derived

    private val session = engine.getSession
    // Network-side buffers for encrypted data
    private var netInBuf =
        val b = ByteBuffer.allocate(session.getPacketBufferSize)
        discard(b.flip()) // start in read mode with 0 bytes
        b
    end netInBuf
    private var netOutBuf = ByteBuffer.allocate(session.getPacketBufferSize)

    // Tracks whether handshake() has been called. Allows lazy server-side handshake on first read/write.
    private var handshakeDone: Boolean = false

    // Leftover decrypted bytes from previous unwrap
    private var appReadBuf: ByteBuffer = ByteBuffer.allocate(0)

    /** Perform TLS handshake. Must be called before read/write, or will be called lazily on first use. */
    def handshake()(using Frame): Unit < Async =
        Sync.defer {
            handshakeDone = true
            engine.beginHandshake()
            handshakeLoop()
        }
    end handshake

    private def handshakeLoop()(using Frame): Unit < Async =
        Sync.defer { engine.getHandshakeStatus }.map {
            case SSLEngineResult.HandshakeStatus.FINISHED |
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING =>
                Kyo.unit

            case SSLEngineResult.HandshakeStatus.NEED_WRAP =>
                doWrap(ByteBuffer.allocate(0)).andThen(handshakeLoop())

            case SSLEngineResult.HandshakeStatus.NEED_UNWRAP =>
                doUnwrap().andThen(handshakeLoop())

            case SSLEngineResult.HandshakeStatus.NEED_TASK =>
                Sync.defer {
                    var task = engine.getDelegatedTask
                    while task != null do
                        task.run()
                        task = engine.getDelegatedTask
                }.andThen(handshakeLoop())
        }

    /** Wrap appData into netOutBuf and flush to underlying stream. */
    private def doWrap(appData: ByteBuffer)(using Frame): Unit < Async =
        Sync.defer {
            discard(netOutBuf.clear())
            val result = engine.wrap(appData, netOutBuf)
            discard(netOutBuf.flip())
            result
        }.map { result =>
            result.getStatus match
                case SSLEngineResult.Status.OK | SSLEngineResult.Status.CLOSED =>
                    Sync.defer {
                        if netOutBuf.hasRemaining then
                            val arr = new Array[Byte](netOutBuf.remaining)
                            discard(netOutBuf.get(arr))
                            val writeResult = underlying.write(Span.fromUnsafe(arr))
                            writeResult.andThen(
                                if appData.hasRemaining then doWrap(appData)
                                else Kyo.unit
                            )
                        else if appData.hasRemaining then doWrap(appData)
                        else Kyo.unit
                    }
                case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                    Sync.defer {
                        netOutBuf = ByteBuffer.allocate(netOutBuf.capacity * 2)
                        doWrap(appData)
                    }
                case status =>
                    Abort.panic(new SSLException(s"Unexpected wrap status: $status"))
        }

    /** Append bytes to netInBuf, enlarging if necessary. Must be called inside Sync.defer. */
    private def feedNetIn(data: Array[Byte], offset: Int, len: Int): Unit =
        discard(netInBuf.compact())
        if netInBuf.remaining < len then
            val newBuf = ByteBuffer.allocate((netInBuf.position + len) * 2)
            discard(netInBuf.flip())
            discard(newBuf.put(netInBuf))
            netInBuf = newBuf
        end if
        discard(netInBuf.put(data, offset, len))
        discard(netInBuf.flip())
    end feedNetIn

    /** Try to unwrap from netInBuf. If BUFFER_UNDERFLOW, read more from TCP and retry. */
    private def doUnwrap()(using Frame): Unit < Async =
        Sync.defer {
            val appBuf = ByteBuffer.allocate(session.getApplicationBufferSize)
            val result = engine.unwrap(netInBuf, appBuf)
            result
        }.map { result =>
            result.getStatus match
                case SSLEngineResult.Status.OK | SSLEngineResult.Status.CLOSED =>
                    Kyo.unit
                case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                    val tcpBuf = new Array[Byte](session.getPacketBufferSize)
                    underlying.read(tcpBuf).map { n =>
                        if n < 0 then Abort.panic(new SSLException("Connection closed during TLS handshake"))
                        else if n == 0 then doUnwrap() // spurious wakeup
                        else
                            Sync.defer {
                                feedNetIn(tcpBuf, 0, n)
                                doUnwrap()
                            }
                    }
                case SSLEngineResult.Status.BUFFER_OVERFLOW =>
                    Kyo.unit
                case status =>
                    Abort.panic(new SSLException(s"Unexpected unwrap status during handshake: $status"))
        }
    end doUnwrap

    // ── RawStream implementation ──────────────────

    def read(buf: Array[Byte])(using Frame): Int < Async =
        if !handshakeDone then handshake().andThen(readApp(buf))
        else readApp(buf)

    private def readApp(buf: Array[Byte])(using Frame): Int < Async =
        Sync.defer {
            if appReadBuf.hasRemaining then
                // Drain leftover decrypted bytes
                val count = math.min(buf.length, appReadBuf.remaining)
                discard(appReadBuf.get(buf, 0, count))
                count: Int < Async
            else if netInBuf.hasRemaining then
                // Try unwrapping from buffered netInBuf
                val appBuf = ByteBuffer.allocate(math.max(buf.length, session.getApplicationBufferSize))
                val result = engine.unwrap(netInBuf, appBuf)
                handleUnwrapResult(result, appBuf, buf)
            else
                readAndUnwrapApp(buf)
        }

    private def readAndUnwrapApp(buf: Array[Byte])(using Frame): Int < Async =
        val tcpBuf = new Array[Byte](session.getPacketBufferSize)
        underlying.read(tcpBuf).map { n =>
            if n < 0 then -1
            else if n == 0 then readApp(buf) // spurious wakeup
            else
                Sync.defer {
                    feedNetIn(tcpBuf, 0, n)
                    val appBuf = ByteBuffer.allocate(math.max(buf.length, session.getApplicationBufferSize))
                    val result = engine.unwrap(netInBuf, appBuf)
                    (result, appBuf)
                }.map { (result, appBuf) =>
                    handleUnwrapResult(result, appBuf, buf)
                }
            end if
        }
    end readAndUnwrapApp

    private def handleUnwrapResult(result: SSLEngineResult, appBuf: ByteBuffer, buf: Array[Byte])(using Frame): Int < Async =
        result.getStatus match
            case SSLEngineResult.Status.OK =>
                Sync.defer {
                    discard(appBuf.flip())
                    if appBuf.hasRemaining then
                        val count = math.min(buf.length, appBuf.remaining)
                        discard(appBuf.get(buf, 0, count))
                        // Save leftover decrypted bytes
                        if appBuf.hasRemaining then
                            appReadBuf = ByteBuffer.allocate(appBuf.remaining)
                            discard(appReadBuf.put(appBuf))
                            discard(appReadBuf.flip())
                        end if
                        Present(count)
                    else Absent
                    end if
                }.map { maybe =>
                    maybe match
                        case Present(count) => count
                        case _              => readApp(buf)
                }
            case SSLEngineResult.Status.BUFFER_UNDERFLOW =>
                readAndUnwrapApp(buf)
            case SSLEngineResult.Status.CLOSED =>
                -1
            case status =>
                Abort.panic(new SSLException(s"Unexpected unwrap status: $status"))

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else if !handshakeDone then handshake().andThen(doWrap(ByteBuffer.wrap(data.toArrayUnsafe)))
        else doWrap(ByteBuffer.wrap(data.toArrayUnsafe))

end NioTlsStream
