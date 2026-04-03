package kyo.internal

import kyo.*

class TransportListenerTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "TransportListener close" - {

        "close terminates connections stream" in run {
            Scope.run {
                StreamTestTransport.listen().map { listener =>
                    listener.close.andThen {
                        listener.connections.take(1).run.map { chunk =>
                            assert(chunk.isEmpty)
                        }
                    }
                }
            }
        }

        "close while waiting for connection does not hang" in run {
            Scope.run {
                StreamTestTransport.listen().map { listener =>
                    // Start pulling (will block waiting for a connection)
                    Fiber.init {
                        listener.connections.take(1).run
                    }.map { fiber =>
                        // Close the listener — the fiber should unblock
                        listener.close.andThen {
                            fiber.get.map { chunk =>
                                assert(chunk.isEmpty)
                            }
                        }
                    }
                }
            }
        }
    }

end TransportListenerTest
