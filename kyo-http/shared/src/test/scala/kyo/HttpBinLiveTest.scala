package kyo

import HttpResponse.Status

// ============================================================================
// Live integration tests against httpbin.org
// Run with: sbt 'kyo-http/testOnly kyo.HttpBinLiveTest'
// ============================================================================
class HttpBinLiveTest extends Test:

    case class HttpBinResponse(url: String, origin: String) derives Schema, CanEqual

    val base = "https://httpbin.org"

    // ========================================================================
    // GET
    // ========================================================================

    "GET - basic request" in run {
        HttpClient.send(s"$base/get").map { response =>
            assert(response.status == Status.OK)
            assert(response.header("Content-Type").exists(_.contains("application/json")))
            assert(response.bodyText.contains("httpbin.org"))
        }
    }

    "GET - typed JSON parsing" in run {
        HttpClient.get[HttpBinResponse](s"$base/get").map { body =>
            assert(body.url == s"$base/get")
            assert(body.origin.nonEmpty)
        }
    }

    // ========================================================================
    // POST / PUT / DELETE
    // ========================================================================

    "POST - with JSON body" in run {
        val request = HttpRequest.postText(s"$base/post", """{"title":"hello"}""", Seq("Content-Type" -> "application/json"))
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("hello"))
        }
    }

    "PUT - with JSON body" in run {
        val request = HttpRequest.putText(s"$base/put", """{"updated":true}""", Seq("Content-Type" -> "application/json"))
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("updated"))
        }
    }

    "DELETE - request" in run {
        HttpClient.send(HttpRequest.delete(s"$base/delete")).map { response =>
            assert(response.status == Status.OK)
        }
    }

    // ========================================================================
    // Custom headers and auth
    // ========================================================================

    "GET - custom headers echoed back" in run {
        val request = HttpRequest.get(
            s"$base/headers",
            Seq("X-Custom-Header" -> "kyo-demo", "Accept" -> "application/json")
        )
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("kyo-demo"))
        }
    }

    "GET - bearer auth filter" in run {
        HttpFilter.client.bearerAuth("demo-token-123").enable {
            HttpClient.send(HttpRequest.get(s"$base/bearer")).map { response =>
                assert(response.status == Status.OK)
                assert(response.bodyText.contains("demo-token-123"))
            }
        }
    }

    "GET - bearer auth rejected without token" in run {
        Abort.run {
            HttpClient.get[String](s"$base/bearer")
        }.map { result =>
            assert(result.isFailure)
        }
    }

    // ========================================================================
    // Client configuration
    // ========================================================================

    "GET - with baseUrl" in run {
        HttpClient.withConfig(_.baseUrl(base)) {
            for
                get  <- HttpClient.send(HttpRequest.get("/get"))
                head <- HttpClient.send(HttpRequest.get("/headers"))
            yield
                assert(get.status == Status.OK)
                assert(head.status == Status.OK)
        }
    }

    "GET - with timeout" in run {
        HttpClient.withConfig(_.timeout(10.seconds)) {
            HttpClient.send(s"$base/get").map { response =>
                assert(response.status == Status.OK)
            }
        }
    }

    // ========================================================================
    // Error handling
    // ========================================================================

    "GET - handle 404" in run {
        Abort.run {
            HttpClient.get[String](s"$base/status/404")
        }.map {
            case Result.Failure(HttpError.StatusError(status, _)) =>
                assert(status == Status.NotFound)
            case other =>
                fail(s"Expected StatusError(404) but got $other")
        }
    }

    "GET - handle connection error" in run {
        Abort.run {
            HttpClient.get[String]("http://localhost:1/nonexistent")
        }.map { result =>
            assert(result.isFailure)
        }
    }

    // ========================================================================
    // Streaming
    // ========================================================================

    "stream - read response as byte stream" in run {
        HttpClient.stream(s"$base/stream/5").map { response =>
            assert(response.status == Status.OK)
            response.bodyStream.run.map { chunks =>
                val body = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                assert(body.contains("\"id\": 0"))
                assert(body.contains("\"id\": 4"))
            }
        }
    }

    "stream - large payload" in run {
        HttpClient.stream(s"$base/stream-bytes/10000?seed=42").map { response =>
            assert(response.status == Status.OK)
            response.bodyStream.run.map { chunks =>
                val totalBytes = chunks.foldLeft(0)(_ + _.size)
                assert(totalBytes == 10000)
            }
        }
    }

    // ========================================================================
    // Parallel and race
    // ========================================================================

    "parallel - concurrent requests" in run {
        val requests = (1 to 5).map { _ =>
            HttpClient.send(s"$base/get")
        }
        Async.collectAll(requests).map { responses =>
            assert(responses.size == 5)
            assert(responses.forall(_.status == Status.OK))
        }
    }

    "race - fastest response wins" in run {
        Async.race(
            HttpClient.send(HttpRequest.get(s"$base/delay/3")),
            HttpClient.send(HttpRequest.get(s"$base/get"))
        ).map { response =>
            assert(response.status == Status.OK)
            // /get should win over /delay/3
            assert(response.bodyText.contains("httpbin.org/get"))
        }
    }

end HttpBinLiveTest
