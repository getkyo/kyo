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
  *  - [[JsonRpcTransport.subprocess]]: a spawned child process's stdin and stdout, for talking to a stdio server.
  *
  * @see [[JsonRpcHandler]]
  */
trait JsonRpcTransport:
    /** Transmits `env` to the peer.
      *
      * Aborts `Closed` when the connection is gone, and [[JsonRpcError]] when the envelope cannot be
      * put on the wire at all: a [[JsonRpcMalformedMessage]], which only ever comes from decoding a
      * peer's garbage and so is unsendable by construction, or extras carrying a reserved key. Both
      * are failures of the value handed in rather than of the connection, and neither can be
      * retried, but both are reachable from runtime data, so they are reported rather than silently
      * dropped.
      */
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed | JsonRpcError])

    /** Sends `env`, naming the inbound request it belongs to when a handler sent it while answering one.
      *
      * A notification a handler emits, or a request it makes of the peer, belongs to the inbound request that handler is
      * answering. A transport that carries each request's exchange on a channel of its own, as MCP's Streamable HTTP does
      * with a response stream per request, delivers such a message on that request's channel; every other transport
      * ignores the relation, which is what this default does.
      */
    def send(env: JsonRpcEnvelope, relatedTo: Maybe[JsonRpcId])(using Frame): Unit < (Async & Abort[Closed | JsonRpcError]) =
        send(env)
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end JsonRpcTransport

object JsonRpcTransport:

    /** Pair of cross-wired in-memory transports for tests.
      *
      * Returns (a, b) where a.send -> b.incoming and b.send -> a.incoming.
      * close on either end ends both incoming streams: the closing end's at once, and the other end's once it has read
      * the envelopes already sent to it.
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
      *
      * The console it uses is the one ambient here, captured now and kept: the protocol channel has to stay the
      * channel whatever an enclosing scope later rebinds `Console` to, which is what lets [[stdioWith]] divert
      * application output without diverting the protocol along with it.
      */
    def stdio(
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Console.use { channel =>
            Sync.defer(new internal.transport.StdioWireTransport(channel)).map { wire =>
                fromWire(wire, framer, codec)
            }
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

    /** A transport to a JSON-RPC peer running as a child process, speaking over the child's stdin and stdout.
      *
      * The command is spawned with the three settings the protocol depends on forced: stdin piped, stdout piped, and stderr kept apart
      * from stdout. Every other setting (arguments, working directory, environment, where stderr goes) is taken from `command` as given.
      * Each call spawns a fresh child, so a caller that needs to start over calls it again with the same `Command`.
      *
      * Envelopes are framed with `framer` (one JSON object per line by default) and encoded with `codec`, exactly as [[fromWire]] does
      * for any byte stream. The child's stderr is drained from spawn, so a child that writes a lot of diagnostics never stalls on a full
      * pipe; [[Subprocess.stderr]] streams it, keeping the most recent output when nobody reads it for a while.
      *
      * When the child exits on its own, `incoming` ends, so a [[JsonRpcHandler]] over this transport fails its pending calls with
      * `Closed`, and a later `send` fails with `Closed`.
      *
      * Closing ends the child in steps: stdin is closed and the child gets `closeGracePeriod` to exit; if it is still running it is asked
      * to terminate (SIGTERM on Unix) and gets another `closeGracePeriod`; if it is still running it is killed. The waits are measured
      * against the ambient `Clock`. The enclosing `Scope` closes the transport when it ends.
      *
      * Works wherever kyo-system can spawn processes: JVM, Native, and JS/Wasm on Node.js.
      *
      * {{{
      * Scope.run {
      *     for
      *         transport <- JsonRpcTransport.subprocess(Command("my-language-server", "--stdio"))
      *         handler   <- JsonRpcHandler.init(transport)
      *         result    <- handler.call[Ping, Pong]("ping", Ping())
      *     yield result
      * }
      * }}}
      *
      * @param command          the child to spawn
      * @param framer           byte-stream framing strategy; defaults to [[JsonRpcFramer.lineDelimited]]
      * @param codec            envelope serialisation; defaults to the strict `Schema[JsonRpcEnvelope]`
      * @param closeGracePeriod how long each close step waits for the child to exit before escalating
      * @see [[subprocessUnscoped]] for a transport whose close the caller owns
      */
    def subprocess(
        command: Command,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]],
        closeGracePeriod: Duration = 2.seconds
    )(using Frame): Subprocess < (Sync & Scope & Abort[CommandException]) =
        Scope.acquireRelease(subprocessUnscoped(command, framer, codec, closeGracePeriod))(_.close)

    /** [[subprocess]] without registering the close with any `Scope`: the caller owns the child and must call `close`. */
    def subprocessUnscoped(
        command: Command,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]],
        closeGracePeriod: Duration = 2.seconds
    )(using Frame): Subprocess < (Sync & Abort[CommandException]) =
        internal.transport.SubprocessTransport.init(command, framer, codec, closeGracePeriod)

    /** A [[JsonRpcTransport]] to a child process, returned by [[subprocess]] and [[subprocessUnscoped]].
      *
      * `send` and `incoming` carry envelopes over the child's stdin and stdout; `close` ends the child, escalating from closing
      * stdin to a termination request to a kill, each step bounded by the grace period the transport was created with.
      *
      * On top of the transport operations it exposes the child itself, for its pid and exit status, and the child's stderr,
      * which is the usual place a stdio server reports what went wrong. Reading stderr is optional: it is drained whether or not
      * anyone reads it.
      *
      * @see [[JsonRpcTransport.subprocess]]
      */
    abstract class Subprocess extends JsonRpcTransport:

        /** The spawned child. Terminate it through `close`, which escalates, rather than through the process directly. */
        def process: Process

        /** The child's stderr, from spawn until the child closes it. It ends once the child's stderr is closed and what was buffered has
          * been read. At most the most recent 256 chunks are kept while nobody reads. Single consumer.
          */
        def stderr(using Frame): Stream[Byte, Async]
    end Subprocess

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
      * To frame Content-Length messages over a spawned child's pipes rather than process stdio, use [[subprocess]] with
      * [[JsonRpcFramer.contentLength]]; for any other byte-stream pair, implement the [[JsonRpcWireTransport]] seam and
      * pass it to [[fromWire]] with [[JsonRpcFramer.contentLength]].
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
