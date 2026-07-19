# kyo-net

kyo-net is the low-level network transport under [kyo-http](../kyo-http/README.md): TCP, Unix-domain sockets, stdio, and TLS, over a completion-based I/O driver that runs on every Kyo platform (Linux io_uring/epoll, macOS/BSD kqueue, the JVM NIO floor, and Node.js on JS). It stops at the socket and the TLS engine; the byte-stream and protocol layers live in kyo-http.

Most applications should use kyo-http, not kyo-net directly. Reach for kyo-net when you are building a transport-level integration or a protocol that kyo-http does not cover, and you need raw sockets with the same backend selection, TLS, and lifecycle model kyo-http is built on.

## The unsafe surface

kyo-net is an unsafe-tier API. Every async operation returns a `Fiber.Unsafe`, every entry point requires an `AllowUnsafe`, and the caller owns the lifecycle of every connection and listener it opens. There is no safe-tier wrapper, because the module exists to be the unsafe floor others build on.

A `Fiber.Unsafe` is consumed one of two ways:

- compose it into an effectful program with `.safe.get`, which lifts it into `A < (Async & Abort[NetException])`. This is what a caller writing a normal Kyo program does, and it is the style this README uses.
- inside another unsafe-tier component, register `fiber.onComplete { ... }` or, for a fiber known to be complete, `done()` then `poll()`, never blocking a carrier.

The examples below import `AllowUnsafe.embrace.danger` to obtain the `AllowUnsafe` and then compose with `.safe.get`, so the whole expression is an ordinary `< Async` value you can run with `KyoApp` or `Sync.Unsafe.evalOrThrow`.

## Connecting

To reach a peer you go through the platform transport, not a socket constructor. `NetPlatform.transport` is the process-global instance, shared for the process lifetime; `connect` names a host and port and returns a fiber that completes with an open `Connection`.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val connected: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connect("example.com", 80).safe.get
```

`connectUnix` opens a Unix-domain socket connection by path, and `stdio` wraps the process's standard input and output as a connection:

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val viaUnix: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connectUnix("/tmp/app.sock").safe.get
```

For a connection that is encrypted from its first byte, use the `connect` overload that takes a `NetTlsConfig`; that and the STARTTLS-style in-place upgrade are covered under [TLS](#tls).

## Reading and writing

kyo-net has no `write` method: a `Connection` is a pair of byte channels, `inbound` and `outbound`. You send by putting a `Span[Byte]` on `outbound`, and you receive by taking from `inbound`. Both are `Channel.Unsafe[Span[Byte]]`, so compose them with `.safe`.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

def echo(conn: Connection): Maybe[Span[Byte]] < (Async & Abort[Closed]) =
    conn.outbound.safe.put(Span.fromUnsafe("ping".getBytes)).andThen {
        conn.inbound.safe.poll.map { reply =>
            conn.close()
            reply
        }
    }
```

`close()` releases the connection; `isOpen` reports whether it is still live. The caller closes every connection it opens.

When the peer closes, `inbound` completes. At that point `status` reports how the stream ended. For a TLS connection this separates an orderly close, where the peer sent its authenticated `close_notify` before the TCP FIN (`Status.CleanClose`), from a `Status.Truncated` end, where the connection dropped with a bare FIN and no `close_notify`.

> **Caution:** A `Truncated` close is the truncation-attack condition (RFC 8446 6.1). kyo-net does not reject it, because a large population of real HTTP/1.1 servers close this way after a complete length-framed message, but a length-aware caller that has not yet reached its expected message boundary must treat a `Truncated` end as a truncation, not a normal EOF.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

def receive(conn: Connection): Maybe[Chunk[Span[Byte]]] < (Async & Abort[Closed]) =
    conn.inbound.safe.drain.map { bytes =>
        conn.status match
            case Connection.Status.CleanClose => Present(bytes) // close_notify seen: the stream is complete
            case Connection.Status.Truncated  => Absent         // bare FIN, no close_notify: drop as a possible truncation
            case _                            => Present(bytes) // non-TLS or still-active: no truncation distinction
    }
```

## Listening

A server does not run its own accept loop: it binds with `listen` and hands over a per-connection handler that the transport invokes once for each accepted connection. The handler is `Connection => Unit`: it runs fire-and-forget on the accept carrier and returns immediately, so anything beyond a trivial setup spawns its own carrier with `Fiber.Unsafe.init`. A long-running synchronous handler stalls accepts.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val listening: Listener < (Async & Abort[NetException]) =
    NetPlatform.transport.listen("0.0.0.0", port = 0, backlog = 128) { conn =>
        // Per-accepted-connection work goes here; spawn a carrier for anything async.
        conn.close()
    }.safe.get
```

Binding to port `0` asks the OS for a free port; the `Listener` reports the address it actually bound to, so read `listener.port` after `listen` returns to learn it. `close()` on the listener stops accepting new connections and releases the bound socket, but does NOT close connections already accepted (those are owned by their handler), so a graceful shutdown closes the listener first, then drains the live connections.

A second `listen` overload takes a `NetTlsConfig` and terminates TLS for every accepted connection (see [TLS](#tls)), and `listenUnix` binds a Unix-domain server socket by path.

## TLS

A connection carries plaintext until you secure it, and there are two moments to do that. When the protocol is encrypted from its first byte, terminate TLS at the point of connect or accept. When the protocol speaks plaintext first and then negotiates the switch in band, upgrade the live connection in place. One `NetTlsConfig` drives both, on both the client and the server side.

When you control the endpoint and the protocol is TLS from the start (HTTPS, a TLS-only service), use `connect(tls)` on the client or `listen(tls)` on the server: the handshake completes before you see the first byte. When the protocol exchanges a plaintext preamble and then negotiates an upgrade (SMTP STARTTLS, an IMAP `STARTTLS`, a database `sslmode` handshake), use `upgradeToTls` on the already-open connection.

### Configuration

Which fields of `NetTlsConfig` you set depends on the role a connection plays, not on a client/server flag; the same type configures both:

- A verifying client needs a reference identity: set `sniHostname` (which both sends SNI and binds the verification name). `trustAll` disables verification for testing only: setting `trustAll = true` disables all certificate validation and makes the connection vulnerable to MITM attacks, so use it only in development or integration test environments where you control both endpoints.
- A server presents a certificate chain and key: `certChainPath` and `privateKeyPath`.
- Mutual TLS adds client-certificate verification on the server: set `clientAuth` to `Required` or `Optional`, and point `trustStorePath` (falling back to `caCertPath`) at the CA that signs the client certs.
- `minVersion` / `maxVersion` pin the TLS version window; the default floor is TLS 1.2.

`tlsProvider` pins the TLS implementation for a single connection by id (`"boringssl"`, `"openssl"`, `"jdk"`, or `"node"`), the per-connection form of `-Dkyo.net.tls`. A pinned provider that the serving transport cannot drive, or that is not available on the host, fails the connection closed rather than substituting a different engine.

```scala
import kyo.*
import kyo.net.*

val serverTls = NetTlsConfig(
    certChainPath = Present("/etc/tls/server.pem"),
    privateKeyPath = Present("/etc/tls/server.key"),
    clientAuth = NetTlsConfig.ClientAuth.Required,
    trustStorePath = Present("/etc/tls/client-ca.pem"),
    minVersion = NetTlsConfig.Version.TLS13
)
```

The TLS implementation is selected by the platform: the bundled BoringSSL is the primary, system OpenSSL is the Native fallback, and the JDK `SSLEngine` is the JVM floor. You configure TLS through `NetTlsConfig`; you do not choose an engine.

### TLS at connect and accept

When the protocol is encrypted from its first byte, `connectTls` terminates TLS as part of the connect, so the returned connection is already encrypted:

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val tls = NetTlsConfig(sniHostname = Present("example.com"))

val secured: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connectTls("example.com", 443, tls).safe.get
```

The matching `listenTls` completes the TLS handshake for every accepted connection before the handler runs, so the server side never sees a plaintext byte. `NetTlsConfig.handshakeTimeout` bounds that handshake, so a peer that stalls it is reaped rather than pinning the accepted connection.

### In-place upgrade (STARTTLS)

When the protocol exchanges plaintext before it negotiates encryption, `upgradeToTls` turns an already-open connection into a TLS one in place, after the pre-handshake bytes have been exchanged. It is supported on every platform. The returned fiber completes with the new TLS connection, or aborts a `NetException` when the connection cannot be upgraded or the handshake fails.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

def startTls(conn: Connection): Connection < (Async & Abort[NetException]) =
    val tls = NetTlsConfig(sniHostname = Present("example.com"))
    NetPlatform.transport.upgradeToTls(conn, tls, channelCapacity = 1024).safe.get
end startTls
```

## Configuring an operation

There is one transport for the whole process, and it takes no configuration. Settings belong to the connection or the operation, so they travel with the call: a caller wanting bigger buffers or a tighter deadline passes them to `connect` or `listen` and still shares the one I/O fabric rather than building a second one.

Each setting sits where it can act. `NetConfig` shapes the connection and socket an operation produces, `connectTimeout` is a parameter of the connect operations, and the TLS handshake deadline is a field of `NetTlsConfig`, so it reaches every operation that handshakes and none that do not.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

// Bigger channels and read buffers for a bulk-transfer connection.
val bulk = NetConfig(channelCapacity = 4096, readChunkSize = 65536)

def fetch(host: String, port: Int): Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connect(host, port, connectTimeout = 5.seconds, config = bulk).safe.get

def serve(tls: NetTlsConfig)(handler: Connection => Unit): Listener < (Async & Abort[NetException]) =
    NetPlatform.transport.listenTls(
        "0.0.0.0",
        8443,
        backlog = 128,
        tls.copy(handshakeTimeout = 10.seconds),
        bulk
    )(handler).safe.get
```

`NetPlatform.transport` is shared and must NOT be closed: closing it would take every co-tenant's connections down. Close your own connections and listeners instead, which is what actually reclaims their resources. `NetPlatform.ownedTransport()` hands back an isolated instance the caller owns and MUST `close()`, for a test asserting transport-level behavior or a component that must tear its own I/O fabric down. It takes no settings, because wanting different settings is never a reason to reach for it.

`connectTimeout` (default `30.seconds`) bounds a connect: if the OS delivers no outcome, connected or refused, within the deadline, the connect fails with `NetConnectTimeoutException`. `NetTlsConfig.handshakeTimeout` (default `30.seconds`) bounds a TLS handshake and reaps a connection that stalls mid-handshake, a slowloris guard (CWE-400). Both accept a positive `Duration` or `Duration.Infinity`, and on a `connectTls` they bound successive phases, so the worst case before it fails is their sum. The one process-wide setting is the driver count, the `kyo.net.ioPoolSize` flag.

## Errors

Every transport operation aborts a `NetException`, the sealed error type for the module, rooted directly in `KyoException` and disjoint from `kyo.Closed`: a transport failure travels its own `Abort[NetException]` row, separate from the `Abort[Closed]` row a connection's inbound/outbound channels use for a genuine close. The leaves carry the specific failure:

- `NetConnectException` / `NetConnectTimeoutException`: the OS connect failed, or the transport's connect deadline fired.
- `NetDnsResolutionException`: the host name did not resolve.
- `NetBindException`: the listener could not bind.
- `NetTlsHandshakeException`: the TLS handshake failed.
- `NetConnectionClosedException`: the transport closed while a read, send, TLS handshake, or STARTTLS upgrade was in flight (`.operation` names which).

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val handled: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connect("example.com", 80).safe.get

val recovered: Maybe[Connection] < Async =
    Abort.run[NetException](handled).map {
        case Result.Success(conn) => Present(conn)
        case _                    => Absent
    }
```

## Backend selection

The I/O backend and the TLS provider are chosen at startup, highest-priority available first, and a `-D` property forces a specific one:

- `-Dkyo.net.backend` selects the I/O backend: `io_uring` (Linux), `epoll` / `kqueue`, `nio` (the JVM floor), `node` (JS).
- `-Dkyo.net.tls` selects the TLS provider: `boringssl` (the primary), `openssl` (the Native system fallback), `jdk` (the JVM floor).

A forced-but-unavailable backend or provider fails closed (`NetBackendUnavailableException` / `NetTlsProviderUnavailableException`) rather than silently falling through to another, so a forced selection is honored or it errors.

## Putting it together

A complete client round-trip composes the pieces above into one `< Async` value: connect, write a request, read the reply, and close. Because every step is composed with `.safe.get` / `.safe.put` / `.safe.poll`, the whole thing is an ordinary effectful program you can run with `KyoApp`.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

def request(host: String, port: Int, payload: Span[Byte]): Maybe[Span[Byte]] < (Async & Abort[NetException] & Abort[Closed]) =
    NetPlatform.transport.connect(host, port).safe.get.map { conn =>
        conn.outbound.safe.put(payload).andThen {
            conn.inbound.safe.poll.map { reply =>
                conn.close()
                reply
            }
        }
    }
```

## Platform capability differences

- `stdio` is supported on every shipped transport: the posix transport, the pure-JDK NIO floor, and Node. It aborts `NetStdioAlreadyOpenException` if a stdio connection is already open (fds 0 and 1 are process-global, so only one can exist at a time); `NetStdioUnsupportedException` remains the contract for a transport with no byte stream to fds 0 and 1, such as an in-memory transport.
- io_uring requires Linux with a usable ring; where it is unavailable the transport falls back to epoll/kqueue or the NIO floor automatically.
