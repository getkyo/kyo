package kyo.net

import kyo.*

/** The transport PRODUCES the typed [[NetConnectTimeoutException]] on its internal connect deadline. A client connect whose SYN goes
  * unanswered (a black-hole endpoint) parks until the transport's finite `connectTimeout` fires; the deadline fails the connect with
  * `NetConnectTimeoutException(host, port, timeout)`, the typed leaf the kyo-http client maps to `HttpConnectTimeoutException`.
  *
  * The deadline arm is the only producer of the timeout leaf, so a deadline-fired close surfaces `NetConnectTimeoutException` while an
  * OS-failure close (refused, unreachable) surfaces the generic `NetConnectException`. Each cell runs on a controlled clock: the deadline is
  * latched as armed, survives to one millisecond before its instant, and fires at it.
  *
  * Native cancels: a connect to the RFC 5737 TEST-NET-1 black hole can fail fast with an unreachable error on a Native host rather than
  * parking in SYN_SENT, which surfaces `NetConnectException` instead of exercising the deadline. The posix connect-deadline arm is the same
  * code on Native.
  */
class TransportConnectTimeoutProducedTest extends Test:

    import AllowUnsafe.embrace.danger

    // 192.0.2.1 is in 192.0.2.0/24, RFC 5737 TEST-NET-1: a reserved, routable-but-unanswered address, so a TCP connect parks in SYN_SENT until
    // the deadline rather than being refused. The same black hole the kyo-http connectTimeout test uses.
    private val blackHoleHost = "192.0.2.1"
    private val blackHolePort = 80

    private def assumeBlackHole(): Unit =
        if kyo.internal.Platform.isNative then cancel("a TEST-NET-1 connect can fail fast as unreachable on Native instead of parking")

    /** Runs `connect`, which must park until its deadline, and answers its outcome once the deadline fired at exactly `timeout`. */
    private def atDeadline[A](tc: Clock.TimeControl, timeout: Duration)(connect: => A < (Async & Abort[NetException]))(using
        Frame
    ): Result[NetException, A] < (Async & Scope) =
        for
            outcome <- Fiber.init(Abort.run[NetException](connect))
            _       <- tc.awaitPendingSleepers(1)
            _       <- tc.advance(timeout.minusOrZero(1.millis))
            _       <- tc.awaitPendingSleepers(1)
            _       <- tc.advance(1.millis)
            result  <- outcome.get
        yield result

    "a connect that does not complete by its deadline fails with NetConnectTimeoutException at it" - eachBackendOnClock { (transport, tc) =>
        assumeBlackHole()
        val timeout = 200.millis
        atDeadline(tc, timeout)(transport.connect(blackHoleHost, blackHolePort, timeout).safe.get).map {
            case Result.Failure(e: NetConnectTimeoutException) =>
                assert(
                    e.host == blackHoleHost && e.port == blackHolePort && e.timeout == timeout,
                    s"expected NetConnectTimeoutException($blackHoleHost, $blackHolePort, $timeout), got $e"
                )
            case Result.Success(conn) =>
                conn.close()
                fail(s"expected the connect deadline to fire, got a successful connect to a black-hole address")
            case other =>
                fail(s"expected the connect deadline to fire, got $other (a NetConnectException means an OS close beat the deadline)")
        }
    }

    // connectTimeout bounds the TCP phase whether or not the connection goes on to handshake, so a TLS connect to a black hole produces the
    // same typed leaf at the same deadline.
    "a TLS connect that does not complete its TCP phase by the deadline fails with NetConnectTimeoutException at it" - eachBackendOnClock {
        (transport, tc) =>
            assumeBlackHole()
            val timeout = 200.millis
            val tls     = NetTlsConfig(trustAll = true, sniHostname = Present("localhost"))
            atDeadline(tc, timeout)(transport.connectTls(blackHoleHost, blackHolePort, tls, timeout).safe.get).map {
                case Result.Failure(e: NetConnectTimeoutException) =>
                    assert(e.timeout == timeout, s"expected the TLS connect's own $timeout deadline, got ${e.timeout}")
                case Result.Success(conn) =>
                    conn.close()
                    fail("expected the TLS connect deadline to fire, got a successful connect to a black-hole address")
                case other =>
                    fail(s"expected the TLS connect deadline to fire, got $other")
            }
    }

    // A listener that accepts is what makes this a test of the deadline: without it firing first, the connect would succeed.
    "a zero connect deadline fails the connect with NetConnectTimeoutException even when the peer would accept" - eachBackend { transport =>
        for
            listener <- transport.listen("127.0.0.1", 0, 16)(conn => conn.close()).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            outcome  <- Abort.run[NetException](transport.connect("127.0.0.1", listener.port, Duration.Zero).safe.get)
        yield outcome match
            case Result.Failure(e: NetConnectTimeoutException) =>
                assert((e.port, e.timeout) == (listener.port, Duration.Zero))
            case Result.Success(conn) =>
                conn.close()
                fail("a zero connect deadline let the connect complete")
            case other => fail(s"expected NetConnectTimeoutException(${Duration.Zero}), got $other")
        end for
    }

end TransportConnectTimeoutProducedTest
