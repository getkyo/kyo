package kyo

import HttpResponse.Status

// ============================================================================
// Live integration tests against httpbin.org
// Run with: sbt 'kyo-http/testOnly kyo.HttpBinLiveTest'
// ============================================================================
class HttpBinLiveTest extends Test:

    // Use the default shared client (platform backend) instead of the test client,
    // since these tests make real HTTP calls to httpbin.org
    override protected def useTestClient: Boolean = false

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
        val request =
            HttpRequest.postText(s"$base/post", """{"title":"hello"}""", HttpHeaders.empty.add("Content-Type", "application/json"))
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("hello"))
        }
    }

    "PUT - with JSON body" in run {
        val request = HttpRequest.putText(s"$base/put", """{"updated":true}""", HttpHeaders.empty.add("Content-Type", "application/json"))
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
            HttpHeaders.empty.add("X-Custom-Header", "kyo-demo").add("Accept", "application/json")
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
            assert(!result.isSuccess)
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

    // ========================================================================
    // PATCH / HEAD / OPTIONS
    // ========================================================================

    "PATCH - with JSON body" in run {
        val request =
            HttpRequest.patchText(s"$base/patch", """{"patched":true}""", HttpHeaders.empty.add("Content-Type", "application/json"))
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("patched"))
        }
    }

    "HEAD - returns status with no body" in run {
        HttpClient.send(HttpRequest.head(s"$base/get")).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.isEmpty)
        }
    }

    "OPTIONS - returns Allow header" in run {
        HttpClient.send(HttpRequest.options(s"$base/get")).map { response =>
            assert(response.status == Status.OK)
            assert(response.header("Allow").isDefined)
        }
    }

    // ========================================================================
    // Request bodies: form and multipart
    // ========================================================================

    "POST - form-encoded body" in run {
        val request = HttpRequest.postForm(s"$base/post", Seq("field1" -> "value1", "field2" -> "value2"))
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            val body = response.bodyText
            assert(body.contains("value1"))
            assert(body.contains("value2"))
        }
    }

    "POST - multipart body" in run {
        val parts = Seq(
            HttpRequest.Part("file", Present("test.txt"), Present("text/plain"), "hello world".getBytes("UTF-8"))
        )
        val request = HttpRequest.multipart(s"$base/post", parts)
        HttpClient.send(request).map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("hello world"))
        }
    }

    // ========================================================================
    // Redirects
    // ========================================================================

    "redirect - follow redirects" in run {
        HttpClient.send(s"$base/redirect/3").map { response =>
            assert(response.status == Status.OK)
            assert(response.bodyText.contains("httpbin.org"))
        }
    }

    "redirect - limit exceeded" in run {
        Abort.run {
            HttpClient.withConfig(_.maxRedirects(2)) {
                HttpClient.send(s"$base/redirect/5")
            }
        }.map {
            case Result.Failure(_: HttpError.TooManyRedirects) => assert(true)
            case other                                         => fail(s"Expected TooManyRedirects but got $other")
        }
    }

    "redirect - disabled returns 302" in run {
        HttpClient.withConfig(_.followRedirects(false)) {
            HttpClient.send(s"$base/redirect/1").map { response =>
                assert(response.status == Status.Found)
                assert(response.header("Location").isDefined)
            }
        }
    }

    // ========================================================================
    // Cookies
    // ========================================================================

    "cookies - Set-Cookie header from response" in run {
        // Disable redirects to capture the Set-Cookie header directly
        HttpClient.withConfig(_.followRedirects(false)) {
            HttpClient.send(s"$base/cookies/set/testname/testvalue").map { response =>
                assert(response.status.isRedirect)
                val setCookie = response.header("Set-Cookie")
                assert(setCookie.isDefined)
                assert(setCookie.exists(_.contains("testname=testvalue")))
            }
        }
    }

    // ========================================================================
    // Client filters
    // ========================================================================

    "GET - basic auth filter" in run {
        HttpFilter.client.basicAuth("user", "pass").enable {
            HttpClient.send(HttpRequest.get(s"$base/basic-auth/user/pass")).map { response =>
                assert(response.status == Status.OK)
                assert(response.bodyText.contains("\"authenticated\""))
            }
        }
    }

    "GET - addHeader filter" in run {
        HttpFilter.client.addHeader("X-Test-Header", "kyo-test-value").enable {
            HttpClient.send(HttpRequest.get(s"$base/headers")).map { response =>
                assert(response.status == Status.OK)
                assert(response.bodyText.contains("kyo-test-value"))
            }
        }
    }

    // ========================================================================
    // Response inspection
    // ========================================================================

    "GET - content type from /html" in run {
        HttpClient.send(s"$base/html").map { response =>
            assert(response.status == Status.OK)
            assert(response.contentType.exists(_.contains("text/html")))
        }
    }

    "GET - status 201" in run {
        HttpClient.send(HttpRequest.get(s"$base/status/201")).map { response =>
            assert(response.status == Status.Created)
        }
    }

    "GET - status 204" in run {
        HttpClient.send(HttpRequest.get(s"$base/status/204")).map { response =>
            assert(response.status == Status.NoContent)
        }
    }

    // ========================================================================
    // Retry
    // ========================================================================

    "retry - exhausted on 503" in run {
        Abort.run {
            HttpClient.withConfig(_.retry(Schedule.fixed(100.millis).take(1))) {
                HttpClient.send(HttpRequest.get(s"$base/status/503"))
            }
        }.map {
            case Result.Failure(_: HttpError.RetriesExhausted) => assert(true)
            case other                                         => fail(s"Expected RetriesExhausted but got $other")
        }
    }

end HttpBinLiveTest
