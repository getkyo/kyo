package kyo.net

import kyo.*

/** Node exposes no way to set `SO_RCVBUF` or `SO_SNDBUF`: `socket.bufferSize` reports bytes queued for writing and sets nothing. A caller that
  * asks for a socket buffer size on this backend therefore cannot get one, and the operation fails closed rather than handing back a socket
  * that quietly lacks the setting. That is the same config-truthfulness rule a pinned TLS provider the transport cannot supply follows.
  *
  * `Absent` is the default, so ordinary use never reaches this; the round-trip suites passing on JS is what covers that side.
  */
class JsTransportSocketOptionsTest extends Test:

    import AllowUnsafe.embrace.danger

    private val bufferSize = 65536

    "a Present soRcvBuf fails closed rather than being ignored" in {
        val transport = NetPlatform.transport
        val config    = NetConfig.default.copy(soRcvBuf = Present(bufferSize))
        Abort.run[NetException](transport.connect("127.0.0.1", 1, config = config).safe.get).map {
            case Result.Failure(e: NetSocketOptionUnsupportedException) =>
                assert(e.option == "SO_RCVBUF", s"expected the rejected option to be named, got ${e.option}")
            case other =>
                assert(false, s"expected NetSocketOptionUnsupportedException(SO_RCVBUF), got $other")
        }
    }

    "a Present soSndBuf fails closed rather than being ignored" in {
        val transport = NetPlatform.transport
        val config    = NetConfig.default.copy(soSndBuf = Present(bufferSize))
        Abort.run[NetException](transport.connect("127.0.0.1", 1, config = config).safe.get).map {
            case Result.Failure(e: NetSocketOptionUnsupportedException) =>
                assert(e.option == "SO_SNDBUF", s"expected the rejected option to be named, got ${e.option}")
            case other =>
                assert(false, s"expected NetSocketOptionUnsupportedException(SO_SNDBUF), got $other")
        }
    }

    "a listen with a Present socket buffer fails closed too" in {
        val transport = NetPlatform.transport
        val config    = NetConfig.default.copy(soRcvBuf = Present(bufferSize))
        Abort.run[NetException](transport.listen("127.0.0.1", 0, 16, config)(_ => ()).safe.get).map {
            case Result.Failure(_: NetSocketOptionUnsupportedException) => succeed
            case other => assert(false, s"expected NetSocketOptionUnsupportedException from listen, got $other")
        }
    }

    "the default config, which asks for no buffer sizes, is unaffected" in {
        val transport = NetPlatform.transport
        transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
            transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                conn.close()
                listener.close()
                succeed
            }
        }
    }

end JsTransportSocketOptionsTest
