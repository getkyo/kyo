package kyo.internal.email.net

import kyo.*
import kyo.EmailTransportException.Kind
import kyo.net.*

/** One connection to a mail server, read as lines ending in LF (a preceding CR removed) and as counted runs of octets, the two framings
  * IMAP and SMTP use. Opening it and upgrading it to TLS fail with an [[EmailConnectException]]; every read and write fails with an
  * [[EmailTransportException]]. Both name the operation passed in, the host and the port.
  *
  * The kyo-net connection is replaced in place by `startTls`, and octets read past a line stay pending for the next read, so both are held
  * in atomic cells. The protocol sessions above call it from one fiber at a time (they hold a mutex per command), so the cells are for
  * visibility across the fibers that take turns, not for concurrent use.
  */
final private[kyo] class LineConnection private (
    val host: String,
    val port: Int,
    current: AtomicRef[Connection],
    pending: AtomicRef[Span[Byte]]
):

    /** The next line, without its line end. More than `limit` octets without a line end is `Protocol`, holding the first `limit` of them:
      * the session redacts that text before it cuts it to what a failure shows, so the cut cannot split a credential it holds.
      */
    def readLine(method: String, limit: Int)(using Frame): Span[Byte] < (Async & Abort[EmailTransportException]) =
        pending.get.map { buffered =>
            buffered.indexOf('\n'.toByte) match
                case Present(lf) =>
                    val end = if lf > 0 && buffered(lf - 1) == '\r'.toByte then lf - 1 else lf
                    if end > limit then Abort.fail(failure(method, Kind.Protocol(LineConnection.shown(buffered.slice(0, limit)))))
                    else pending.set(buffered.slice(lf + 1, buffered.size)).andThen(buffered.slice(0, end))
                case Absent =>
                    if buffered.size > limit then Abort.fail(failure(method, Kind.Protocol(LineConnection.shown(buffered.slice(0, limit)))))
                    else
                        // The pieces are joined once, when one holds a line end or the line passes `limit`: joining at every piece would
                        // copy a long line once per chunk.
                        Loop(Chunk(buffered), buffered.size) { (pieces, size) =>
                            take(method).map { more =>
                                if more.indexOf('\n'.toByte).isEmpty && size + more.size <= limit then
                                    Loop.continue(pieces.append(more), size + more.size)
                                else pending.set(Span.concat(pieces.append(more).toSeq*)).andThen(Loop.done(()))
                            }
                        }.andThen(readLine(method, limit))
        }

    /** Exactly `count` octets, as an IMAP literal announces them. */
    def readExactly(method: String, count: Int)(using Frame): Span[Byte] < (Async & Abort[EmailTransportException]) =
        pending.get.map { buffered =>
            if buffered.size >= count then pending.set(buffered.slice(count, buffered.size)).andThen(buffered.slice(0, count))
            else
                Loop(Chunk(buffered), buffered.size) { (pieces, size) =>
                    if size >= count then Loop.done(Span.concat(pieces.toSeq*))
                    else take(method).map(more => Loop.continue(pieces.append(more), size + more.size))
                }.map { whole =>
                    pending.set(whole.slice(count, whole.size)).andThen(whole.slice(0, count))
                }
        }

    def write(method: String, octets: Span[Byte])(using Frame): Unit < (Async & Abort[EmailTransportException]) =
        current.get.map { conn =>
            Abort.run[Closed](conn.outbound.safe.put(octets)).map {
                case Result.Success(_) => ()
                case Result.Failure(_) => Abort.fail(failure(method, Kind.ConnectionClosed(Absent)))
                case Result.Panic(t)   => Abort.panic(t)
            }
        }

    /** Upgrades the connection to TLS in place (STARTTLS). Octets the server sent before the handshake are discarded: RFC 9051 section
      * 6.2.1 and RFC 3207 section 4.2 have a client ignore everything received before TLS, which is what keeps a man in the middle from
      * injecting responses into the protected session.
      */
    def startTls(method: String, tls: NetTlsConfig, timeout: Duration)(using Frame): Unit < (Async & Abort[EmailConnectException]) =
        // An upgraded connection has no host name of its own, so the one the connection was opened to is the SNI name and the name the
        // certificate is verified against, unless the config names another.
        val named = if tls.sniHostname.isEmpty then tls.copy(sniHostname = Present(host)) else tls
        current.get.map { conn =>
            pending.set(Span.empty[Byte]).andThen {
                LineConnection.await(method, host, port, timeout) {
                    // Unsafe: kyo-net's upgrade is unsafe-tier; its fiber is bridged to the safe tier here and awaited under the deadline.
                    Sync.Unsafe.defer(NetPlatform.transport.upgradeToTls(conn, named, NetConfig.DefaultChannelCapacity).safe)
                }(current.set)
            }
        }
    end startTls

    def close(using Frame): Unit < Sync =
        // Unsafe: closing a kyo-net connection is unsafe-tier; it is idempotent, so a second close costs nothing.
        current.get.map(conn => Sync.Unsafe.defer(conn.close()))

    private def take(method: String)(using Frame): Span[Byte] < (Async & Abort[EmailTransportException]) =
        current.get.map { conn =>
            Abort.run[Closed](conn.inbound.safe.take).map {
                case Result.Success(octets) => octets
                case Result.Failure(_)      => Abort.fail(failure(method, Kind.ConnectionClosed(Absent)))
                case Result.Panic(t)        => Abort.panic(t)
            }
        }

    private def failure(method: String, kind: Kind)(using Frame): EmailTransportException =
        EmailTransportException(method, kind, host, port, Absent)

end LineConnection

private[kyo] object LineConnection:

    /** How much of a line a `Protocol` failure shows, cut after the line is redacted. */
    inline val ShownOctets = 200

    /** Opens a connection to `host:port`, over TLS from the first byte when `tls` is present. `timeout` bounds the TCP connect and the TLS
      * handshake together; kyo-net's own deadlines are disabled, so the deadline runs on kyo's `Clock` and every expiry is
      * `ConnectTimeout`. A connection that opens after the caller failed or was interrupted is closed.
      */
    def open(method: String, host: String, port: Int, tls: Maybe[NetTlsConfig], timeout: Duration)(using
        Frame
    ): LineConnection < (Async & Abort[EmailConnectException]) =
        await(method, host, port, timeout) {
            // Unsafe: kyo-net's connect is unsafe-tier; its fiber is bridged to the safe tier here and awaited under the deadline.
            Sync.Unsafe.defer {
                tls match
                    case Present(config) =>
                        NetPlatform.transport.connectTls(
                            host,
                            port,
                            config.copy(handshakeTimeout = Duration.Infinity),
                            Duration.Infinity
                        ).safe
                    case Absent => NetPlatform.transport.connect(host, port, Duration.Infinity).safe
            }
        }(wrap(_, host, port))

    /** An already open connection, such as one a test server accepted, read and written the same way. */
    def wrap(conn: Connection, host: String, port: Int)(using Frame): LineConnection < Sync =
        for
            current <- AtomicRef.init(conn)
            pending <- AtomicRef.init(Span.empty[Byte])
        yield new LineConnection(host, port, current, pending)

    // Awaits the connection `launch` starts under `timeout`, and runs `use` on it. On any other outcome than `use`'s value, the fiber is
    // interrupted and a connection it produced is closed, so neither a deadline nor an interrupt leaves a socket open. `ensureMap` takes
    // the fiber and `use` runs inside the ensured region, so no preemption point lies between the connection existing and its release
    // being owned: `map` would drop a pending interrupt's obligation there (kyo-kernel `Pending.ensureMap`).
    private def await[A](method: String, host: String, port: Int, timeout: Duration)(
        launch: => Fiber[Connection, Abort[NetException]] < Sync
    )(use: Connection => A < Sync)(using Frame): A < (Async & Abort[EmailConnectException]) =
        launch.ensureMap { fiber =>
            Sync.ensure[A, EmailConnectException, Async] { (error: Maybe[Result.Error[Any]]) =>
                if error.isEmpty then ()
                else
                    fiber.interrupt.andThen(fiber.onComplete {
                        // Unsafe: a connection nobody will read is closed as soon as it exists; close is idempotent.
                        case Result.Success(conn) => conn.map(c => Sync.Unsafe.defer(c.close()))
                        case _                    => ()
                    })
            } {
                Abort.run[Timeout](Async.timeout(timeout)(Abort.run[NetException](fiber.get))).map {
                    case Result.Success(Result.Success(conn))  => use(conn)
                    case Result.Success(Result.Failure(cause)) => Abort.fail(fromNet(method, host, port, cause))
                    case Result.Success(Result.Panic(t))       => Abort.panic(t)
                    case Result.Failure(_)                     =>
                        Abort.fail(EmailConnectException(
                            method,
                            EmailConnectException.Kind.ConnectTimeout,
                            host,
                            port,
                            Present(timeout)
                        )(Absent))
                    case Result.Panic(t) => Abort.panic(t)
                }
            }
        }

    /** The one mapping from kyo-net's sealed hierarchy; the cause is kept for the four kinds that have one. A leaf a client TCP connection
      * cannot produce is a bug panic naming it, never a declared kind.
      */
    def fromNet(method: String, host: String, port: Int, cause: NetException)(using Frame): EmailConnectException =
        import EmailConnectException.Kind
        def leaf(kind: Kind, keep: Boolean = false) =
            EmailConnectException(method, kind, host, port, Absent)(if keep then Present(cause) else Absent)
        def unreachable = bug(s"kyo-net failure a client TCP connection cannot produce: ${cause.getClass.getSimpleName}")
        cause match
            case _: NetConnectException        => leaf(Kind.Connect, keep = true)
            case _: NetDnsResolutionException  => leaf(Kind.Dns, keep = true)
            case e: NetConnectTimeoutException =>
                EmailConnectException(method, Kind.ConnectTimeout, host, port, Present(e.timeout))(Present(cause))
            case _: NetTlsHandshakeException        => leaf(Kind.Tls, keep = true)
            case e: NetTlsHandshakeTimeoutException =>
                EmailConnectException(method, Kind.ConnectTimeout, host, port, Present(e.timeout))(Present(cause))
            // The peer closing before the connection or its TLS handshake is complete is a connection that could not be opened.
            case _: NetConnectionClosedException       => leaf(Kind.Connect, keep = true)
            case _: NetTlsProviderUnavailableException => leaf(Kind.TlsSetup)
            case _: NetTlsConfigException              => leaf(Kind.TlsSetup)
            case _: NetBackendUnavailableException     => leaf(Kind.BackendUnavailable)
            // The module never sets a socket buffer size or a grace window, and its connections are TCP, which kyo-net can always upgrade.
            case _: NetUnixConnectException | _: NetUnixConnectTimeoutException | _: NetBindException |
                _: NetSocketOptionUnsupportedException | _: NetStdioUnsupportedException | _: NetStdioAlreadyOpenException |
                _: NetNotUpgradableException | _: NetAlreadyDetachedException | _: NetConfigException => unreachable
            // kyo-net's driver-internal leaves are `private[net]`, so each is matched through its sealed parent after every named sibling:
            // `NetConnectionIoException` reaches a caller only as the cause inside a public leaf, and `NetDriverUnsupportedException` only
            // as a panic.
            case _: NetConnectionException | _: NetCapabilityException => unreachable
        end match
    end fromNet

    /** Octets as text for a failure's field: ASCII kept, every other octet as `\xNN`, so nothing undecodable reaches a message. */
    def shown(octets: Span[Byte]): String =
        val out = new java.lang.StringBuilder(octets.size)
        octets.foreach { b =>
            val c = b & 0xff
            if c >= 0x20 && c < 0x7f then discard(out.append(c.toChar)) else discard(out.append(f"\\x$c%02x"))
        }
        out.toString
    end shown

end LineConnection
