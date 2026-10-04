package kyo.net

import kyo.*

/** Cross-backend typed-failure taxonomy for the public [[Transport]] surface.
  *
  * Every registered backend (io_uring/epoll/kqueue/NIO on JVM, io_uring/epoll/kqueue on Native, Node on JS) must report the SAME [[NetException]]
  * leaf for the same failure mode at the public seam, so a caller can tell the failures apart without string-matching a message: a name-resolution
  * failure is a [[NetDnsResolutionException]], a refused TCP connect is a [[NetConnectException]], a missing Unix socket is a
  * [[NetUnixConnectException]], and a TCP port outside 0 to 65535 is a [[NetConnectException]] or [[NetBindException]] naming it. The
  * scenarios run once and are driven over every backend by [[eachBackend]].
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

    // A port is 16 bits on the wire. Each out-of-range leaf targets a port whose low 16 bits name something reachable (a live listener, or
    // 0 for an ephemeral bind), so a backend that truncates instead of refusing succeeds and the leaf fails.

    private def refusedConnect(result: Result[NetException | Closed, Connection], port: Int)(using kyo.test.AssertScope): Unit =
        val ok = result match
            case Result.Failure(e: NetConnectException) => e.port == port
            case Result.Success(conn)                   =>
                conn.close()
                false
            case _ => false
        assert(ok, s"a connect to port $port must fail NetConnectException naming that port, got $result")
    end refusedConnect

    private def refusedBind(result: Result[NetException | Closed, Listener], port: Int)(using kyo.test.AssertScope): Unit =
        val ok = result match
            case Result.Failure(e: NetBindException) => e.port == port
            case Result.Success(listener)            =>
                listener.close()
                false
            case _ => false
        assert(ok, s"a listen on port $port must fail NetBindException naming that port, got $result")
    end refusedBind

    "connect to a port past 65535 fails NetConnectException naming it, never reaching the port it truncates to" - eachBackend { transport =>
        transport.listen("127.0.0.1", 0, 16)(_.close()).safe.get.map { listener =>
            val port = listener.port + 65536
            Abort.run[NetException | Closed](transport.connect("127.0.0.1", port).safe.get).map { result =>
                listener.close()
                refusedConnect(result, port)
            }
        }
    }

    "connect to a negative port fails NetConnectException naming it" - eachBackend { transport =>
        Abort.run[NetException | Closed](transport.connect("127.0.0.1", -1).safe.get).map(refusedConnect(_, -1))
    }

    "connectTls to a port past 65535 fails NetConnectException naming it, never reaching the port it truncates to" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            transport.listenTls("127.0.0.1", 0, 16, serverTls)(_.close()).safe.get.map { listener =>
                val port = listener.port + 65536
                Abort.run[NetException | Closed](transport.connectTls("127.0.0.1", port, clientTls).safe.get).map { result =>
                    listener.close()
                    refusedConnect(result, port)
                }
            }
    }

    "listen on a port past 65535 fails NetBindException naming it, never binding the port it truncates to" - eachBackend { transport =>
        Abort.run[NetException | Closed](transport.listen("127.0.0.1", 65536, 16)(_.close()).safe.get).map(refusedBind(_, 65536))
    }

    "listen on a negative port fails NetBindException naming it" - eachBackend { transport =>
        Abort.run[NetException | Closed](transport.listen("127.0.0.1", -1, 16)(_.close()).safe.get).map(refusedBind(_, -1))
    }

    "listenTls on a port past 65535 fails NetBindException naming it, never binding the port it truncates to" - eachBackendTls {
        (transport, serverTls, _) =>
            Abort.run[NetException | Closed](transport.listenTls("127.0.0.1", 65536, 16, serverTls)(_.close()).safe.get)
                .map(refusedBind(_, 65536))
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

end TransportFailureTaxonomyTest
