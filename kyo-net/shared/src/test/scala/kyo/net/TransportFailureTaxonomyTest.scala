package kyo.net

import kyo.*

/** Cross-backend typed-failure taxonomy for the public [[Transport]] surface.
  *
  * Every registered backend (io_uring/epoll/kqueue/NIO on JVM, io_uring/epoll/kqueue on Native, Node on JS) must report the SAME [[NetException]]
  * leaf for the same failure mode at the public seam, so a caller can tell the failures apart without string-matching a message: a name-resolution
  * failure is a [[NetDnsResolutionException]], a refused TCP connect is a [[NetConnectException]], and a missing Unix socket is a
  * [[NetUnixConnectException]]. The scenarios run once and are driven over every backend by [[eachBackend]].
  *
  * The unresolvable host uses the RFC 2606 reserved `.invalid` TLD, which a conformant resolver answers with NXDOMAIN. The refused port is
  * obtained by binding a listener (a port known to have been free) and closing it, so a connect to it is refused rather than timing out.
  */
class TransportFailureTaxonomyTest extends Test:

    import AllowUnsafe.embrace.danger

    "connect to an unresolvable host fails NetDnsResolutionException" - eachBackend { transport =>
        Abort.run[NetException | Closed](transport.connect("nonexistent.invalid", 80).safe.get).map { result =>
            val ok = result match
                case Result.Failure(_: NetDnsResolutionException) => true
                case Result.Success(conn)                         =>
                    conn.close()
                    false
                case _ => false
            assert(ok, s"an unresolvable host must fail NetDnsResolutionException, got $result")
        }
    }

    "connect to a refused TCP port fails NetConnectException" - eachBackend { transport =>
        // 127.0.0.1:1 has no listener in any normal environment, so the loopback connect is refused with a RST (an immediate ECONNREFUSED,
        // never a filtered timeout, since loopback is not firewalled), deterministically across backends without a bind/close race.
        Abort.run[NetException | Closed](transport.connect("127.0.0.1", 1).safe.get).map { result =>
            val ok = result match
                case Result.Failure(_: NetConnectException) => true
                case Result.Success(conn)                   =>
                    conn.close()
                    false
                case _ => false
            assert(ok, s"a connect to a refused port must fail NetConnectException, got $result")
        }
    }

    "connectUnix to a missing socket fails NetUnixConnectException" - eachBackend { transport =>
        val path = s"/tmp/kyo-net-missing-${TlsTestCertShared.uniquePathTag()}.sock"
        Abort.run[NetException | Closed](transport.connectUnix(path).safe.get).map { result =>
            val ok = result match
                case Result.Failure(_: NetUnixConnectException) => true
                case Result.Success(conn)                       =>
                    conn.close()
                    false
                case _ => false
            assert(ok, s"connectUnix to a missing socket must fail NetUnixConnectException, got $result")
        }
    }

    // Longer than sun_path on every platform (104 bytes on macOS/BSD, 108 on Linux), so no backend can bind or connect it.
    private def overlongSocketPath(): String = s"/tmp/kyo-net-${"x" * 200}-${TlsTestCertShared.uniquePathTag()}.sock"

    "listenUnix on a path longer than sun_path fails NetBindException carrying why" - eachBackend { transport =>
        Abort.run[NetException | Closed](transport.listenUnix(overlongSocketPath(), 16)(_ => ()).safe.get).map {
            case Result.Failure(e: NetBindException) =>
                assert(NetException.show(e.cause).nonEmpty, s"the failure must carry its cause: ${e.getMessage}")
            case Result.Success(listener) =>
                listener.close()
                fail("a listen on a path longer than sun_path must fail")
            case other => fail(s"expected NetBindException, got $other")
        }
    }

    "connectUnix to a path longer than sun_path fails NetUnixConnectException carrying why" - eachBackend { transport =>
        Abort.run[NetException | Closed](transport.connectUnix(overlongSocketPath()).safe.get).map {
            case Result.Failure(e: NetUnixConnectException) =>
                assert(NetException.show(e.cause).nonEmpty, s"the failure must carry its cause: ${e.getMessage}")
            case Result.Success(conn) =>
                conn.close()
                fail("a connect to a path longer than sun_path must fail")
            case other => fail(s"expected NetUnixConnectException, got $other")
        }
    }

end TransportFailureTaxonomyTest
