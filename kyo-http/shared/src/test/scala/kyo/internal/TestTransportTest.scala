package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class TestTransportTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    "channel pairing works" in run {
        Scope.run {
            Channel.init[String](8).map { ch =>
                discard(Fiber.init {
                    ch.take.map { msg =>
                        ch.put(s"echo:$msg")
                    }.handle(Abort.run[Closed]).unit
                })
                Async.sleep(10.millis).andThen {
                    ch.put("hello").andThen {
                        ch.take.map { reply =>
                            assert(reply == "echo:hello")
                        }
                    }
                }
            }
        }
    }

end TestTransportTest
