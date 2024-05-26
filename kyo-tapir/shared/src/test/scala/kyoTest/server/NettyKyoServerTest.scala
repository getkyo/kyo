package kyoTest.server

import kyo.*
import kyoTest.KyoTest
import sttp.tapir.server.netty.NettyKyoServer

class NettyKyoServerTest extends KyoTest:

    "start stop" in run {
        for
            bindings <- NettyKyoServer().start()
            _        <- bindings.stop()
        yield succeed
    }
end NettyKyoServerTest
