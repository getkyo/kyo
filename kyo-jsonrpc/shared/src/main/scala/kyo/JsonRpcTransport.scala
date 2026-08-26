package kyo

import kyo.Stream
import kyo.net.NetPlatform

/** Envelope-level message channel between two JSON-RPC peers.
  *
  * Implement this trait to connect an existing I/O layer to the endpoint. The lifecycle
  * methods are:
  *  - `send`: transmit an outbound [[JsonRpcEnvelope]] to the peer.
  *  - `incoming`: a stream of inbound [[JsonRpcEnvelope]] values received from the peer.
  *  - `close`: tear down the underlying connection.
  *
  * Pre-built factories in the companion cover the most common cases:
  *  - [[JsonRpcTransport.inMemory]]: paired in-memory channels for testing.
  *  - [[JsonRpcTransport.fromWire]]: wraps a [[JsonRpcWireTransport]] + [[JsonRpcFramer]] + a `Schema[JsonRpcEnvelope]`.
  *  - [[JsonRpcTransport.stdio]]: line-delimited stdin/stdout transport for CLI servers.
  *  - [[JsonRpcTransport.contentLengthStdio]]: Content-Length-framed stdio transport for LSP, DAP,
  *    BSP, and other header-framed JSON-RPC protocols.
  *  - [[JsonRpcTransport.unixDomain]]: Unix-domain-socket transport.
  *
  * @see [[JsonRpcHandler]]
  */
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end JsonRpcTransport

object JsonRpcTransport:

    /** Pair of cross-wired in-memory transports for tests.
      *
      * Returns (a, b) where a.send -> b.incoming and b.send -> a.incoming.
      * close on either end terminates both incoming streams.
      */
    def inMemory(capacity: Int)(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync =
        for
            // Unsafe: Channel.initUnscoped is the kyo idiom for fields that must be
            // released by the owning component's close() rather than Scope finalizers.
            aToB <- Channel.initUnscoped[JsonRpcEnvelope](capacity)
            bToA <- Channel.initUnscoped[JsonRpcEnvelope](capacity)
        yield
            // type-widening from internal subtype to public supertype required for the returned tuple element type
            val a: JsonRpcTransport = new internal.transport.InMemoryTransport(out = aToB, in = bToA)
            // type-widening from internal subtype to public supertype required for the returned tuple element type
            val b: JsonRpcTransport = new internal.transport.InMemoryTransport(out = bToA, in = aToB)
            (a, b)

    def inMemory(using Frame): (JsonRpcTransport, JsonRpcTransport) < Sync = inMemory(64)

    /** Lifts a byte-stream transport plus framer plus envelope codec into the envelope-level
      * `JsonRpcTransport` seam. Inbound bytes pass through `framer.parse` and `codec.decode`;
      * outbound envelopes pass through `codec.encode` and `framer.frame`.
      */
    def fromWire(
        wire: JsonRpcWireTransport,
        framer: JsonRpcFramer,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.WireTransportAdapter(wire, framer, codec))

    /** Line-delimited stdio transport for CLI-style RPC servers. Reads `Console.readLine`
      * and writes `Console.printLine`. EOF on stdin closes `incoming`. One envelope per line.
      */
    def stdio(
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer(new internal.transport.StdioWireTransport).map { wire =>
            fromWire(wire, framer, codec)
        }

    /** [[stdio]] with the process's own streams protected for the duration of `f`.
      *
      * On this transport stdin and stdout ARE the protocol channel, so an application write to either
      * corrupts it. `Console.printLine` is the idiomatic way to print in kyo and does exactly that: one
      * line of application output lands between two envelopes and the peer sees a parse error or drops
      * the connection, which is the worst place to debug it from, since the server side looks fine.
      *
      * Inside `f`, `Console` is rebound so `print` and `printLine` go to stderr along with `printErr`,
      * and `readLine` fails rather than consuming bytes the protocol is waiting for. Handlers therefore
      * print safely by construction and no author has to know the rule. Nothing else changes: the
      * transport is the one [[stdio]] returns, and the binding is dynamically scoped, so the dispatch
      * loop and every handler forked inside `f` inherit it.
      *
      * {{{
      * JsonRpcTransport.stdioWith() { transport =>
      *     McpServer.init(transport, handlers*).andThen(Async.never)
      * }
      * }}}
      *
      * Prefer this over [[stdio]] for any server that runs on the process's own streams.
      *
      * @param framer byte-stream framing strategy; defaults to [[JsonRpcFramer.lineDelimited]]
      * @param codec  envelope serialisation; defaults to the strict `Schema[JsonRpcEnvelope]`
      * @param f      the body, run with the transport and with `Console` diverted away from the channel
      */
    def stdioWith[A, S](
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(f: JsonRpcTransport => A < S)(using Frame): A < (S & Async & Scope) =
        stdio(framer, codec).map { transport =>
            Console.use(ambient => Console.let(divertedConsole(ambient))(f(transport)))
        }

    /** The ambient console with its stdout side diverted to stderr and its input closed.
      *
      * Derived from the ambient console rather than from `Console.live`, so an enclosing binding (a test
      * capturing output, a custom sink) still sees the writes, on the side they were diverted to.
      */
    private def divertedConsole(ambient: Console): Console =
        val under = ambient.unsafe
        Console(
            new Console.Unsafe:
                def readLine()(using AllowUnsafe): Result[java.io.IOException, String] =
                    Result.fail(new java.io.IOException(
                        "stdin is the JSON-RPC protocol channel on this transport; reading it would consume protocol bytes"
                    ))
                def print(s: String)(using AllowUnsafe): Unit        = under.printErr(s)
                def printErr(s: String)(using AllowUnsafe): Unit     = under.printErr(s)
                def printLine(s: String)(using AllowUnsafe): Unit    = under.printLineErr(s)
                def printLineErr(s: String)(using AllowUnsafe): Unit = under.printLineErr(s)
                def checkErrors(using AllowUnsafe): Boolean          = under.checkErrors
                def flush()(using AllowUnsafe): Unit                 = under.flush()
        )
    end divertedConsole

    /** Unix domain socket transport, served over the platform kyo-net transport.
      *
      * Binds a Unix-domain listener on `sockPath` and serves a single client: the first accepted connection becomes the
      * wire, and a [[Scope]] cleanup closes the connection and listener and removes the socket file. Works on every
      * platform kyo-net targets: JVM (the posix io_uring/epoll/kqueue backend, or the NIO floor), Native (posix), and
      * JS/Wasm (Node's `net` module, so it requires a Node.js runtime; a browser has no sockets).
      *
      * @param sockPath path to the socket file (must not already exist)
      * @param framer   byte-stream framing strategy; defaults to [[JsonRpcFramer.lineDelimited]]
      * @param codec    envelope serialisation; defaults to the strict `Schema[JsonRpcEnvelope]`
      */
    def unixDomain(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        internal.transport.UdsBackend.connect(sockPath, framer, codec)

    /** Content-Length-framed stdio transport for JSON-RPC (LSP, DAP, BSP).
      *
      * Reads `Content-Length: N\r\n\r\n<N bytes>` frames from process stdin and writes matching frames to process stdout,
      * over the platform kyo-net transport's stdio connection (fds 0/1 on JVM and Native, `process.stdin`/`process.stdout`
      * on Node). Headers other than Content-Length are skipped on the read side; the write side emits strict CRLF as the LSP
      * base protocol requires.
      *
      * Stdio is process-global: one stdio transport per process. A second `contentLengthStdio()` (or a
      * [[JsonRpcTransport.stdio]] byte-stream claim) in the same process aborts [[kyo.net.NetStdioAlreadyOpenException]].
      *
      * To frame Content-Length messages over an arbitrary byte-stream pair (for example a spawned subprocess's pipes)
      * rather than process stdio, implement the [[JsonRpcWireTransport]] seam and pass it to [[fromWire]] with
      * [[JsonRpcFramer.contentLength]].
      *
      * @param framer framing strategy; defaults to [[JsonRpcFramer.contentLength]]
      * @param codec  envelope serialisation; defaults to the strict `Schema[JsonRpcEnvelope]`
      */
    def contentLengthStdio(
        framer: JsonRpcFramer = JsonRpcFramer.contentLength,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        // Unsafe: Transport.stdio() is unsafe-tier; bridged once here.
        Sync.Unsafe.defer {
            NetPlatform.transport.stdio().safe.get.map { conn =>
                val wire: JsonRpcWireTransport = internal.transport.ConnectionWireTransport(conn)
                Scope.ensure(wire.close).andThen {
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }

end JsonRpcTransport
