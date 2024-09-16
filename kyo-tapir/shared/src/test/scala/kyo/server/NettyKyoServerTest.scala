package kyo.server

import kyo.*
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.NettyKyoServer

class NettyKyoServerTest extends Test:

    def testServer() =
        import scala.concurrent.duration.*
        NettyKyoServer(NettyConfig.default.copy(gracefulShutdownTimeout = Some(5.millis))).port(9999)

    "start stop" in run {
        for
            bindings <- testServer().start()
            _        <- bindings.stop()
        yield succeed
    }
end NettyKyoServerTest
