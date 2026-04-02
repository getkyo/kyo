package kyo.internal

import kyo.*

class TransportListenerTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "TransportListener close" - {

        "close terminates connections stream" in run {
            Scope.run {
                StreamTestTransport.listen().map { listener =>
                    listener.close.andThen {
                        Async.timeout(2.seconds) {
                            listener.connections.take(1).run.map { chunk =>
                                assert(chunk.isEmpty)
                            }
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
                        // Give the fiber time to start waiting
                        Async.sleep(50.millis).andThen {
                            // Close the listener — the fiber should unblock
                            listener.close.andThen {
                                Async.timeout(2.seconds) {
                                    fiber.get.map { chunk =>
                                        assert(chunk.isEmpty)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end TransportListenerTest
