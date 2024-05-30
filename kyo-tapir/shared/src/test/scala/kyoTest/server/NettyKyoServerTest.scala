package kyoTest.server

import kyo.*
import kyoTest.KyoTest
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.NettyKyoServer

class NettyKyoServerTest extends KyoTest:

    def testServer() =
        import scala.concurrent.duration.*
        NettyKyoServer(NettyConfig.default.copy(gracefulShutdownTimeout = Some(5.millis)))

    "start stop" in run {
        for
            bindings <- testServer().start()
            _        <- bindings.stop()
        yield succeed
    }
end NettyKyoServerTest
