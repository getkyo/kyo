package kyo

class TestCompletionServerTest extends kyo.test.Test[Any]:

    "the server captures the request and returns the enqueued response" in {
        TestCompletionServer.run { server =>
            server.enqueueBody("""{"ok":true}""").andThen {
                HttpClient.postText(
                    s"${server.baseUrl}/chat/completions",
                    "REQ-BODY",
                    Seq("content-type" -> "application/json")
                ).map { responseBody =>
                    assert(responseBody == """{"ok":true}""", s"expected enqueued body, got: $responseBody")
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        assert(
                            caps.head == TestCompletionServer.Captured("v1/chat/completions", "REQ-BODY"),
                            s"captured request mismatch: ${caps.head}"
                        )
                    }
                }
            }
        }
    }

end TestCompletionServerTest
