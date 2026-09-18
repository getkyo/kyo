package kyo

class TestDeciderServerTest extends kyo.test.Test[Any]:

    val headers = Seq("content-type" -> "application/json")

    "the server captures the request and returns the enqueued response" in {
        TestDeciderServer.run { server =>
            server.enqueueBody("""{"ok":true}""").andThen {
                HttpClient.postText(s"${server.baseUrl}/systemone", "REQ-BODY", headers).map { responseBody =>
                    assert(responseBody == """{"ok":true}""", s"expected enqueued body, got: $responseBody")
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps == Chunk("REQ-BODY"), s"captured request mismatch: $caps")
                    }
                }
            }
        }
    }

    "scripts are served in order, then the default answer takes over" in {
        TestDeciderServer.run { server =>
            val request =
                """{"model":"jev-latest","state":"","questions":{"q1":{"type":"noul","instructions":"a"},""" +
                    """"q2":{"type":"choice","instructions":"b","criteria":{"x":null,"y":"why"}},""" +
                    """"q3":{"type":"score","instructions":"c","criteria":["lo","mid","hi"]}}}"""
            server.enqueueBody("first").andThen(server.enqueueStatus(503, "down", Seq("x-typesafe-request-id" -> "r1"))).andThen {
                for
                    first  <- HttpClient.postText(s"${server.baseUrl}/systemone", request, headers)
                    second <- HttpClient.postTextResponse(s"${server.baseUrl}/systemone", request, headers, failOnError = false)
                    third  <- HttpClient.postText(s"${server.baseUrl}/systemone", request, headers)
                    caps   <- server.captured
                yield
                    assert(first == "first")
                    assert(second.status.code == 503 && second.fields.body == "down")
                    assert(second.headers.get("x-typesafe-request-id") == Present("r1"))
                    assert(third ==
                        """{"model":"jev-1.13.0","answers":{"q1":{"type":"noul","noul":0.9},""" +
                        """"q2":{"type":"choice","choice":"x","confidence":1.0,"probabilities":{"x":1.0,"y":0.0}},""" +
                        """"q3":{"type":"score","score":0.0,"confidence":1.0,"legend":{},"probabilities":{"0":1.0,"1":0.0,"2":0.0}}},""" +
                        """"usage":{"input_tokens":10,"output_tokens":2}}""")
                    assert(caps.size == 3)
                end for
            }
        }
    }

    "a request the fixture cannot read is a 400 naming the fixture, not a decodable answer" in {
        TestDeciderServer.run { server =>
            HttpClient.postTextResponse(s"${server.baseUrl}/systemone", "not the request shape", headers, failOnError = false).map {
                response =>
                    assert(response.status.code == 400)
                    assert(response.fields.body.contains("TestDeciderServer could not read the request"))
            }
        }
    }

end TestDeciderServerTest
