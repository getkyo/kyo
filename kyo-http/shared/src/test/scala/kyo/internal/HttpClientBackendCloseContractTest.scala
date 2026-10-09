package kyo.internal

import kyo.*

class HttpClientBackendCloseContractTest extends kyo.BaseHttpTest with ResettingServerImpl:

    // No Content-Length and no chunked coding: the body runs until the connection ends (RFC 9112 section 6.3 item 8).
    private val closeFramedHead = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n"

    "a plaintext body framed by the connection's close" - {

        // A reset is not the end of a close-framed body: only an orderly close is (RFC 9112 section 6.3, a message whose length the close
        // decides is complete only when the connection closed normally). The server resets only after the stream has delivered the first
        // bytes, so they and the head are read before the reset by construction.
        "fails when the server resets the connection mid-body".pendingUntilFixed(
            "H5: a plaintext close-framed body accepts a peer reset as a complete body"
        ) in {
            Latch.init(1).map { reset =>
                resettingServer((closeFramedHead + "partial").getBytes("ISO-8859-1"), reset).map { port =>
                    AtomicRef.init("").map { received =>
                        Abort.run[HttpException](
                            HttpClient.getStreamBytes(s"http://127.0.0.1:$port/").foreach { span =>
                                received.updateAndGet(_ + new String(span.toArrayUnsafe, "ISO-8859-1")).andThen(reset.release)
                            }
                        ).map { outcome =>
                            received.get.map { body =>
                                assert(body == "partial", s"observed: $body")
                                assert(outcome.isFailure, s"the stream ended as a complete body after the server reset: $outcome")
                            }
                        }
                    }
                }
            }
        }
    }

end HttpClientBackendCloseContractTest
