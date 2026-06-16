package kyo.internal

import kyo.*

class SlackTransportTest extends kyo.test.Test[Any]:

    "inMemory streams scripted frames in order and records outbound frames" in {
        val scripted = Chunk("f1", "f2", "f3")
        val cfg      = HttpWebSocket.Config()
        SlackTransport.inMemory(scripted).map { case (transport, recorded) =>
            transport.connect("url", cfg) { conn =>
                conn.put("ackA").andThen(conn.stream.run)
            }.map { frames =>
                assert(frames == Chunk("f1", "f2", "f3"))
                Abort.run[Closed](recorded.drain).map { result =>
                    assert(result == Result.succeed(Chunk("ackA")))
                }
            }
        }
    }

    "inMemory close is observable on the recorded channel" in {
        val cfg = HttpWebSocket.Config()
        SlackTransport.inMemory(Chunk.empty).map { case (transport, recorded) =>
            transport.connect("url", cfg) { conn =>
                conn.close
            }.andThen {
                Abort.run[Closed](recorded.put("after-close")).map { result =>
                    assert(result.isFailure, "put on closed channel should abort with Closed")
                }
            }
        }
    }

end SlackTransportTest
