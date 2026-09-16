package kyo

import kyo.test.HostFilter

/** What serving means on a host that cannot listen.
  *
  * A browser page has no port to bind and no way to accept a connection, so `HttpServer` fails where it would have bound rather than on the
  * first request that never arrives.
  */
class HttpServerHostTest extends kyo.test.Test[Any]:

    override protected def hostFilters = Chunk(HostFilter.OnlyBrowser)

    "a page cannot serve, and says so at the bind" in {
        Abort.run[HttpBindException](HttpServer.initUnscoped(0, "127.0.0.1")()).map {
            case Result.Failure(e) =>
                assert(
                    e.cause.isInstanceOf[HttpUnsupportedOnHostException],
                    s"the bind failed because of ${e.cause}"
                )
            case other => assert(false, s"the server init ended as $other")
        }
    }

end HttpServerHostTest
