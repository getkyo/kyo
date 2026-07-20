package kyo.net

import kyo.*

/** The transport PRODUCES the typed [[NetConnectTimeoutException]] on its internal connect-deadline. A client connect whose SYN
  * goes unanswered (a black-hole endpoint) parks until the transport's finite `connectTimeout` fires; the deadline arm fails the connect promise
  * with `NetConnectTimeoutException(host, port, timeout)`, the typed leaf the kyo-http client maps to `HttpConnectTimeoutException`.
  *
  * This is the close-cause discrimination: the deadline arm is the only producer of the timeout leaf, so a deadline-fired close
  * surfaces `NetConnectTimeoutException` while an OS-failure close (refused/unreachable) surfaces the generic `NetConnectException`. Without the
  * deadline producer a black-hole connect would park indefinitely (or surface the generic connect failure); this asserts the typed timeout leaf is produced.
  *
  * The deadline is the transport's own Clock-driven timer, NOT a caller-side `Async.timeout` (the distinct property [[TransportConnectDeadlineTest]]
  * asserts and leaves green). The finite `connectTimeout` is the deterministic latch (the black hole never answers, so the deadline always wins);
  * a generous outer `Async.timeout` survival window turns a regression (no deadline armed, so the connect hangs) into a failure rather than a hang,
  * never a sleep-as-synchronization.
  *
  * Native is excluded (`.notNative`): a connect to the RFC 5737 TEST-NET-1 black hole can fail-fast with an unreachable error on a Native host
  * rather than parking in SYN_SENT, which would surface `NetConnectException` (the OS-close arm) instead of exercising the deadline. The posix
  * connect-deadline arm is identical on Native and is compiled there; only this black-hole-dependent reproduction is JVM/JS-scoped, matching the
  * established `kyo-http` connect-timeout test's `.notNative` exception.
  */
class TransportConnectTimeoutProducedTest extends Test:

    import AllowUnsafe.embrace.danger

    // 192.0.2.1 is in 192.0.2.0/24, RFC 5737 TEST-NET-1: a reserved, routable-but-unanswered address, so a TCP connect parks in SYN_SENT until
    // the deadline rather than being refused. The same black hole the kyo-http connectTimeout test uses.
    private val blackHoleHost = "192.0.2.1"
    private val blackHolePort = 80

    "a connect that does not complete by its deadline fails with NetConnectTimeoutException".notNative in {
        given Frame = Frame.internal
        // A finite, short connectTimeout arms the transport's internal connect-deadline. The connect to the black hole never completes, so the
        // deadline always wins; the produced leaf is the typed NetConnectTimeoutException, NOT the generic NetConnectException.
        val timeout   = 200.millis
        val transport = NetPlatform.transport
        Abort.run[NetException | Closed | Timeout](
            // A generous survival window: if the deadline were NOT armed (the regression) the connect would hang and this would time out, failing
            // the assertion below rather than hanging the suite. With the deadline armed, the connect fails well within the window.
            Async.timeout(5.seconds)(transport.connect(blackHoleHost, blackHolePort, timeout).safe.get)
        ).map { outcome =>
            outcome match
                case Result.Failure(e: NetConnectTimeoutException) =>
                    assert(
                        e.host == blackHoleHost && e.port == blackHolePort && e.timeout == timeout,
                        s"expected NetConnectTimeoutException($blackHoleHost, $blackHolePort, $timeout), got $e"
                    )
                case other =>
                    assert(
                        false,
                        s"expected the internal connect-deadline to produce NetConnectTimeoutException($blackHoleHost, $blackHolePort, $timeout), " +
                            s"got $other (a Timeout means no deadline was armed; a NetConnectException means an OS-close beat the deadline)"
                    )
            end match
        }
    }

    // The same guard for a TLS connect. connectTimeout bounds the TCP phase whether or not the connection goes on to handshake, so a TLS connect
    // to a black hole must produce the same typed leaf at the same deadline. The NIO floor armed the deadline only on its plaintext connect
    // path, so a connectTls there parked until the caller's own timeout with no transport-level bound at all.
    "a TLS connect that does not complete its TCP phase by the deadline fails with NetConnectTimeoutException".notNative in {
        given Frame   = Frame.internal
        val timeout   = 200.millis
        val transport = NetPlatform.transport
        val tls       = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
        Abort.run[NetException | Closed | Timeout](
            Async.timeout(5.seconds)(transport.connectTls(blackHoleHost, blackHolePort, tls, timeout).safe.get)
        ).map { outcome =>
            outcome match
                case Result.Failure(e: NetConnectTimeoutException) =>
                    assert(e.timeout == timeout, s"expected the TLS connect's own $timeout deadline, got ${e.timeout}")
                case other =>
                    assert(
                        false,
                        s"expected NetConnectTimeoutException($timeout) from the TLS connect deadline, got $other " +
                            "(a Timeout means the TLS connect path armed no deadline)"
                    )
            end match
        }
    }

end TransportConnectTimeoutProducedTest
