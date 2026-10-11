package kyo.internal

import kyo.*
import kyo.internal.websocket.WebSocketSession

class WebSocketSessionTest extends kyo.BaseHttpTest:

    "closeOnReturn" - {

        "closes with 1000 when no close was decided and the reader still runs" in {
            AtomicRef.init(Absent: Maybe[(Int, String)]).map { ref =>
                WebSocketSession.closeOnReturn(ref, false).andThen(ref.get).map { reason =>
                    assert(reason == Present((1000, "")))
                }
            }
        }

        "decides nothing once the reader has ended" in {
            AtomicRef.init(Absent: Maybe[(Int, String)]).map { ref =>
                WebSocketSession.closeOnReturn(ref, true).andThen(ref.get).map { reason =>
                    assert(reason == Absent)
                }
            }
        }

        "keeps a reason already decided" in {
            AtomicRef.init(Present((4001, "bye")): Maybe[(Int, String)]).map { ref =>
                WebSocketSession.closeOnReturn(ref, false).andThen(ref.get).map { reason =>
                    assert(reason == Present((4001, "bye")))
                }
            }
        }

        // The reader records a peer's Close concurrently with the handler's return; the probe of the reader's state is where that
        // interleaving is pinned, after the decision has begun and before it is written.
        "keeps a peer's Close recorded while the decision is being made" in {
            AtomicRef.init(Absent: Maybe[(Int, String)]).map { ref =>
                val peerCloseLandsNow = ref.set(Present((4001, "bye"))).andThen(false)
                WebSocketSession.closeOnReturn(ref, peerCloseLandsNow).andThen(ref.get).map { reason =>
                    assert(reason == Present((4001, "bye")), s"the close reason after a peer's Close 4001 landed mid-decision: $reason")
                }
            }
        }
    }

end WebSocketSessionTest
