package kyo.net

import kyo.*

/** Cross-backend proof that closing an idle listener actually releases its OS listen fd, so the port becomes free again.
  *
  * Closing a server while it is registered with the driver's poller for accept interest must release the listening socket even when the driver
  * is otherwise idle. On a backend whose poll wait is bounded (io_uring's reap timeout, the epoll/kqueue poller's 100ms wait) the deferred
  * teardown runs within that wait. On the NIO floor the selector waits with no timeout, so unless the listener close wakes it the deferred
  * `kill()` (the real `nd.close(fd)`) never runs and the socket leaks in LISTEN indefinitely (flaky, last-server-biased: later connection
  * activity wakes the selector and masks it, the last server in an idle suite does not).
  *
  * Release is asserted through the public API, with no JVM-only fd counting, by re-binding the freed port: a fresh `listen` on the same port
  * does a synchronous `bind()` BEFORE it registers accept interest, so a leaked LISTEN socket makes that bind fail (address in use) without the
  * re-listen's own activity waking the original selector and masking the leak (a connect through the same transport WOULD wake it). The re-bind
  * is polled on a bounded, Clock-paced loop so a backend that releases within its poll wait passes promptly while a true leak fails at the
  * deadline. Run over every registered backend via [[eachBackend]]: each must release the fd on an idle close.
  */
class TransportListenerFdReleaseTest extends Test:

    import AllowUnsafe.embrace.danger

    "closing an idle listener releases its listen fd so the port can be re-bound" - eachBackend { transport =>
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            val port = listener.port
            // Idle close: no client ever connected, so nothing other than the close itself can wake the driver.
            listener.close()
            // Re-bind the port until it succeeds (released) or the deadline passes (leaked). A failed re-listen's bind never registers accept
            // interest, so it cannot wake the original selector and mask a leak; a bounded-wait backend's own poll wave frees the fd meanwhile.
            Loop(0) { attempt =>
                Abort.run[Closed](transport.listen("127.0.0.1", port, 16)(_ => ()).safe.get).map {
                    case Result.Success(reListener) =>
                        reListener.close()
                        Loop.done(true)
                    case _ =>
                        if attempt >= 100 then Loop.done(false)
                        else Async.sleep(50.millis).andThen(Loop.continue(attempt + 1))
                }
            }.map { released =>
                assert(
                    released,
                    s"listener fd not released after idle close: port $port could not be re-bound within the deadline (leaked LISTEN socket)"
                )
            }
        }
    }

end TransportListenerFdReleaseTest
