package kyo

/** JVM-only extension methods for [[JsonRpcTransport]].
  *
  * These methods are defined in the JVM source root and are unavailable on Scala.js and
  * Scala Native, where `java.io.InputStream` / `java.io.OutputStream`-based blocking I/O
  * is not available.
  */
extension (companion: JsonRpcTransport.type)

    /** Content-Length-framed stdio transport for JSON-RPC.
      *
      * Reads `Content-Length: N\r\n\r\n<N bytes>` frames from `in` and writes matching
      * frames to `out`. Used by LSP, DAP, BSP, and other JSON-RPC protocols that carry
      * each message payload in a Content-Length-prefixed header block. Headers other than
      * Content-Length (e.g. Content-Type) are silently skipped on the read side. The
      * write side always emits strict CRLF terminators as required by the LSP base
      * protocol specification.
      *
      * JVM-only: uses blocking `java.io.InputStream` reads and `java.io.OutputStream`
      * writes. Callers must ensure the streams are not shared with other readers or
      * writers during transport operation.
      *
      * Typical usage with LSP, DAP, or BSP servers over process stdio:
      * {{{
      * JsonRpcTransport.contentLengthStdio(System.in, System.out)
      * }}}
      *
      * @param in     input byte stream to read inbound frames from (e.g. `System.in`)
      * @param out    output byte stream to write outbound frames to (e.g. `System.out`)
      * @param framer framing strategy; defaults to [[JsonRpcFramer.contentLength]]
      * @param codec  envelope serialization; defaults to [[JsonRpcCodec.Strict2_0]]
      */
    def contentLengthStdio(
        in: java.io.InputStream,
        out: java.io.OutputStream,
        framer: JsonRpcFramer = JsonRpcFramer.contentLength,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.ContentLengthStdioWireTransport(in, out)).map { wire =>
            companion.fromWire(wire, framer, codec)
        }

end extension
