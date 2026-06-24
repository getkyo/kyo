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

`Transport.connect` opens a client TCP connection and returns a fiber that completes with an open `Connection`. The platform default transport is `NetPlatform.transport`, shared for the process lifetime.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val connected: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connect("example.com", 80).safe.get
```

A second `connect` overload takes a `NetTlsConfig` and terminates TLS as part of the connect, so the returned connection is already encrypted:

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val tls = NetTlsConfig(sniHostname = Present("example.com"))

val secured: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connect("example.com", 443, tls).safe.get
```

`connectUnix` opens a Unix-domain socket connection by path, and `stdio` wraps the process's standard input and output as a connection where the platform supports it:

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

val viaUnix: Connection < (Async & Abort[NetException]) =
    NetPlatform.transport.connectUnix("/tmp/app.sock").safe.get
```

## Reading and writing

A `Connection` exposes two channels of byte spans, `inbound` and `outbound`. There is no `write` method: you write by putting a span on `outbound`, and you read by taking from `inbound`. Both are `Channel.Unsafe[Span[Byte]]`, so compose them with `.safe`.

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

## Listening

`Transport.listen` binds a server socket and runs a handler once per accepted connection. The handler is `Connection => Unit`: it runs fire-and-forget on the accept carrier and returns immediately, so anything beyond a trivial setup spawns its own carrier with `Fiber.Unsafe.init`. A long-running synchronous handler stalls accepts.

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

A second `listen` overload takes a `NetTlsConfig` and terminates TLS for every accepted connection, and `listenUnix` binds a Unix-domain server socket by path.

## STARTTLS

`upgradeToTls` turns an already-open plaintext connection into a TLS one in place (STARTTLS-style), after the pre-handshake bytes have been exchanged. It is supported on every platform. The returned fiber completes with the new TLS connection, or aborts a `NetException` when the connection cannot be upgraded or the handshake fails.

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*

def startTls(conn: Connection): Connection < (Async & Abort[NetException]) =
    val tls = NetTlsConfig(sniHostname = Present("example.com"))
    NetPlatform.transport.upgradeToTls(conn, tls, channelCapacity = 1024).safe.get
end startTls
```

## TLS configuration

`NetTlsConfig` configures both client and server TLS. The fields you set depend on the role:

- A verifying client needs a reference identity: set `sniHostname` (which both sends SNI and binds the verification name). `trustAll` disables verification for testing only.
- A server presents a certificate chain and key: `certChainPath` and `privateKeyPath`.
- Mutual TLS adds client-certificate verification on the server: set `clientAuth` to `Required` or `Optional`, and point `trustStorePath` (falling back to `caCertPath`) at the CA that signs the client certs.
- `minVersion` / `maxVersion` pin the TLS version window; the default floor is TLS 1.2.

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

## Configuring the transport

`NetPlatform.transport` uses `TransportConfig.default`. To tune it, pass a `TransportConfig` to the `transport(config)` overload. Tune the config with `copy`:

```scala
import AllowUnsafe.embrace.danger
import kyo.*
import kyo.net.*
import scala.concurrent.duration.*

val config = TransportConfig.default.copy(
    channelCapacity = 4096,
    handshakeTimeout = 10.seconds
)

val tuned: Transport = NetPlatform.transport(config)
```

A `transport(config)` instance owns its resources, so the caller MUST `close()` it. The process-global `NetPlatform.transport`, by contrast, is shared and must NOT be closed.

A finite `handshakeTimeout` also arms the transport's own connect deadline: a connect to an unreachable peer then fails with `NetConnectTimeoutException` rather than hanging until the OS gives up, and on a TLS server it reaps a stalled handshake (a slowloris guard). The default `handshakeTimeout` is infinite, leaving timeout composition to the caller via `Async.timeout`.

## Errors

Every transport operation aborts a `NetException`, the sealed error type for the module. `NetException` extends `kyo.Closed`, so it flows through `Abort[Closed]` handlers, while the leaves carry the specific failure:

- `NetConnectException` / `NetConnectTimeoutException`: the OS connect failed, or the transport's connect deadline fired.
- `NetDnsResolutionException`: the host name did not resolve.
- `NetBindException`: the listener could not bind.
- `NetTlsHandshakeException`: the TLS handshake failed.

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

A forced-but-unavailable backend or provider fails closed (aborts `Closed`) rather than silently falling through to another, so a forced selection is honored or it errors.

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

## Known limitations

- The whole surface is unsafe-tier by design. If you want a safe, composable HTTP API, use kyo-http; kyo-net is the floor it sits on.
- `stdio` is supported on the posix and Node transports, not the pure-JDK NIO floor; it aborts `NetStdioUnsupportedException` where unsupported.
- io_uring requires Linux with a usable ring; where it is unavailable the transport falls back to epoll/kqueue or the NIO floor automatically.
