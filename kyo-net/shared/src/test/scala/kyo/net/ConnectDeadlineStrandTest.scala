package kyo.net

import kyo.*

/** Transport-level stress guard for the connect-deadline lost wakeup: a connect whose completion (write readiness) the driver never delivers.
  *
  * Every connect arms the transport's connect deadline, racing the OS connect's write readiness, against one real plaintext listener that
  * accepts and immediately closes. Each cell runs on a transport whose clock never advances, so no deadline can fire: every connect must
  * complete, and a dropped wakeup leaves one parked, which hangs the leaf to its cap. A wall-clock deadline would instead report a slow
  * runner as a lost wakeup, or hide a lost wakeup behind a timeout the runner happened to reach.
  */
class ConnectDeadlineStrandTest extends Test:

    import AllowUnsafe.embrace.danger

    "sequential connects under a finite connect deadline all complete" - eachBackendOnClock { (transport, _) =>
        val total = 200
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(conn => conn.close()).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            failures <- Loop(0, Chunk.empty[String]) { (i, failures) =>
                if i >= total then Loop.done(failures)
                else
                    Abort.run[NetException](transport.connect("127.0.0.1", listener.port, 30.seconds).safe.get).map {
                        case Result.Success(conn) =>
                            conn.close()
                            Loop.continue(i + 1, failures)
                        case other => Loop.continue(i + 1, failures.append(s"connect $i: $other"))
                    }
            }
        yield assert(failures.isEmpty, s"${failures.size} of $total sequential connects failed: ${failures.take(5).mkString("; ")}")
        end for
    }

    "a concurrent burst of connects all complete: none fails" - eachBackendOnClock { (transport, _) =>
        // Concurrency exposes two connect-arm hazards the sequential guard cannot: a caller-carrier registerChannel racing the poll carrier's
        // selector rebuild throws ClosedSelectorException, which surfaced as an empty-cause NetConnectException; and the OP_CONNECT arm's
        // guarded wakeup coalescing under the burst loses the connect-completion edge. Every connect must complete: a peer that already closed
        // yields a clean connected-then-EOF, not a connect failure, and a lost edge parks the connect until the leaf's cap.
        val concurrency = 128
        for
            listener <- transport.listen("127.0.0.1", 0, 256)(conn => conn.close()).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            outcomes <- Async.foreach(0 until concurrency, concurrency) { _ =>
                Abort.run[NetException](transport.connect("127.0.0.1", listener.port, 30.seconds).safe.get).map {
                    case Result.Success(conn) =>
                        conn.close()
                        Absent
                    case Result.Failure(e) => Present(e.getClass.getSimpleName)
                    case Result.Panic(e)   => Present(s"panic:${e.getClass.getSimpleName}")
                }
            }
        yield
            val failures = outcomes.flatMap(_.toList)
            assert(
                failures.isEmpty,
                s"${failures.size} of $concurrency concurrent connects failed: ${failures.distinct.mkString(", ")} " +
                    "(NetConnectException: the registerChannel and selector rebuild race)"
            )
        end for
    }

end ConnectDeadlineStrandTest
