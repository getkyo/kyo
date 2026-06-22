package kyo.internal.transport

import java.io.InputStream
import java.io.OutputStream
import kyo.*

/** JVM wire transport that carries raw bytes between an `InputStream` and an
  * `OutputStream`.
  *
  * This transport is intentionally byte-transparent: it reads available bytes from `in`
  * in chunks and writes bytes to `out` as-is. Frame parsing (e.g. Content-Length header
  * extraction) is delegated to the `JsonRpcFramer` layer above, consistent with how
  * `UdsWireTransport` delegates to `JsonRpcFramer.lineDelimited` or
  * `JsonRpcFramer.contentLength`.
  *
  * Used by `JsonRpcTransport.contentLengthStdio` to back the Content-Length-framed stdio
  * transport for LSP, DAP, BSP, and other header-framed JSON-RPC protocols.
  */
final private[kyo] class ContentLengthStdioWireTransport(
    in: InputStream,
    out: OutputStream
) extends JsonRpcWireTransport:

    private val bufSize = 4096

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer {
            out.write(bytes.toArray)
            out.flush()
        }

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        // chunkSize = 1 so each read flushes a single element downstream immediately. With the default chunkSize,
        // Stream.unfold batches multiple emissions before pushing to subscribers, and a held-open stdin (the LSP-spec
        // session lifecycle) never produces enough data to fill a batch: the framer parses nothing, the handshake
        // stalls, and the host TERM-kills the server.
        Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]]((), chunkSize = 1) { _ =>
            Sync.defer {
                val buf = new Array[Byte](bufSize)
                val n   = in.read(buf)
                if n < 0 then Maybe.Absent
                else
                    val arr = buf.slice(0, n)
                    Maybe.Present((Chunk.from(arr), ()))
                end if
            }
        }

    def close(using Frame): Unit < Async =
        Sync.defer {
            try in.close()
            catch case _: Exception => ()
            try out.close()
            catch case _: Exception => ()
        }

end ContentLengthStdioWireTransport
