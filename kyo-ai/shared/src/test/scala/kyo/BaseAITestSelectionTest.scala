package kyo

import kyo.ai.Config

/** Unit coverage for BaseAITest's selection and availability probing. Extends BaseAITest to reach the private[kyo] members but
  * registers no backend leaf of its own, and probes only a local stand-in, so nothing hits a provider.
  */
class BaseAITestSelectionTest extends BaseAITest:

    "selectBackends" - {
        "an empty flag runs every backend" in {
            assert(selectBackends("").map(_.label) == allBackends.map(_.label), "no narrowing selects all")
            assert(selectBackends("   ").map(_.label) == allBackends.map(_.label), "blank narrows nothing")
        }

        "a single name selects exactly that backend" in {
            val selected = selectBackends("anthropic")
            assert(selected.map(_.label) == Chunk("Anthropic"), s"one name, one backend: ${selected.map(_.label)}")
        }

        "a comma list selects each named backend, order following the catalog" in {
            val selected = selectBackends("deepseek, anthropic").map(_.label)
            assert(selected.contains("DeepSeek") && selected.contains("Anthropic"), s"both named: $selected")
            assert(selected.size == 2, s"only the two named: $selected")
        }

        "hyphen and underscore spellings both match a two-word label" in {
            assert(selectBackends("claude-code").map(_.label) == Chunk("Claude Code"))
            assert(selectBackends("claude_code").map(_.label) == Chunk("Claude Code"))
        }

        "a name matching nothing throws, listing the known names" in {
            val ex = intercept[IllegalArgumentException](selectBackends("nope"))
            assert(ex.getMessage.contains("nope"), s"names the bad input: ${ex.getMessage}")
            assert(ex.getMessage.contains("anthropic"), s"lists the known names: ${ex.getMessage}")
        }
    }

    "claudeAuthenticated" - {
        "accepts an authenticated status response" in {
            assert(BaseAITest.claudeAuthenticated("""{"loggedIn":true,"authMethod":"oauth"}"""))
        }

        "rejects a logged-out status response" in {
            assert(!BaseAITest.claudeAuthenticated("""{"loggedIn":false,"authMethod":"none"}"""))
        }

        "rejects malformed and incomplete responses" in {
            assert(!BaseAITest.claudeAuthenticated("not json"))
            assert(!BaseAITest.claudeAuthenticated("""{"authMethod":"oauth"}"""))
        }
    }

    "apiProbe" - {
        case class Seen(path: String, authorization: Maybe[String], apiKey: Maybe[String]) derives CanEqual

        /** A provider stand-in answering both probe paths with `status`, recording what each request carried. */
        def withProvider[A](status: Int, body: String)(f: (String, AtomicRef[Chunk[Seen]]) => A < (Async & Scope))(using
            Frame
        ): A < (Async & Scope & Abort[HttpBindException]) =
            AtomicRef.init(Chunk.empty[Seen]).map { seen =>
                def route(path: String) =
                    HttpRoute.getRaw(path)
                        .request(_.headerOpt[String]("authorization").headerOpt[String]("x-api-key"))
                        .response(_.bodyText)
                        .handler { req =>
                            seen.updateAndGet(_.append(Seen(path, req.fields.authorization, req.fields.`x-api-key`)))
                                .andThen(HttpResponse(HttpStatus(status)).addField("body", body))
                        }
                HttpServer.initWith(HttpServerConfig.default)(route("v1/models"), route("v1/key")) { server =>
                    f(s"http://127.0.0.1:${server.port}/v1", seen)
                }
            }

        "a refused key is unavailable, naming the status and the provider's error type and code" in {
            withProvider(
                401,
                """{"error":{"message":"Authentication Fails, Your api key: ****dc00 is invalid","type":"authentication_error","param":null,"code":"invalid_request_error"}}"""
            ) { (url, _) =>
                BaseAITest.apiProbe(Config.DeepSeek, url, "bad-key").map { found =>
                    assert(found == Present(s"GET $url/models answered 401 authentication_error invalid_request_error"))
                }
            }
        }

        "a refusal outside 401 is unavailable too, read from a body that is an array" in {
            withProvider(
                400,
                """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]"""
            ) {
                (url, _) =>
                    BaseAITest.apiProbe(Config.Gemini, url, "bad-key").map { found =>
                        assert(found == Present(s"GET $url/models answered 400 INVALID_ARGUMENT"))
                    }
            }
        }

        "no free text from the body reaches the reason, even in a field meant for a code" in {
            val key = "sk-live-4f9Qz81bXk2"
            withProvider(401, s"""{"error":"Incorrect API key provided: $key","code":"Incorrect API key provided: $key","type":"$key"}""") {
                (url, _) =>
                    BaseAITest.apiProbe(Config.XAI, url, "bad-key").map { found =>
                        assert(found == Present(s"GET $url/models answered 401"))
                    }
            }
        }

        "an accepted key is available, and it rode as a bearer token" in {
            withProvider(200, """{"data":[]}""") { (url, seen) =>
                for
                    found    <- BaseAITest.apiProbe(Config.DeepSeek, url, "good-key")
                    requests <- seen.get
                yield
                    assert(found == Absent)
                    assert(requests == Chunk(Seen("v1/models", Present("Bearer good-key"), Absent)))
            }
        }

        "OpenRouter is probed on its key record, since its model listing answers without a key" in {
            withProvider(200, """{"data":{}}""") { (url, seen) =>
                BaseAITest.apiProbe(Config.OpenRouter, url, "good-key").andThen(seen.get).map { requests =>
                    assert(requests.map(_.path) == Chunk("v1/key"))
                }
            }
        }

        "Anthropic's key rides in its own header" in {
            withProvider(200, """{"data":[]}""") { (url, seen) =>
                BaseAITest.apiProbe(Config.Anthropic, url, "good-key").andThen(seen.get).map { requests =>
                    assert(requests == Chunk(Seen("v1/models", Absent, Present("good-key"))))
                }
            }
        }
    }

    "providerUnavailableMessage" - {
        "names the failure's type and never its detail, which carries the provider's body" in {
            val backend = selectBackends("deepseek").head
            val refused = AIProviderAuthException(
                "DeepSeek",
                """POST https://api.deepseek.com/v1/chat/completions returned 401. Body: {"error":{"message":"Your api key: sk-live-4f9Qz81bXk2 is invalid"}}"""
            )
            assert(providerUnavailableMessage(backend, refused) == "DeepSeek provider is unavailable: AIProviderAuthException")
        }
    }

    "failureReport" - {
        "a rate limit reports its type, status, error codes and wait, never the body" in {
            val key = "sk-live-4f9Qz81bXk2"
            TestCompletionServer.run { server =>
                val config =
                    Config.OpenAI.default.apiKey("test-key").apiUrl(server.baseUrl).retrySchedule(Schedule.never).timeout(5.seconds)
                for
                    _ <- server.enqueueStatus(
                        429,
                        s"""{"error":{"message":"Rate limit reached for key $key","type":"requests","code":"rate_limit_exceeded"}}""",
                        Seq("retry-after" -> "90")
                    )
                    result <- Abort.run[AIException](LLM.run(config)(AI.gen[String]))
                yield result.failure match
                    case Present(limit: AIRateLimitException) =>
                        val report = BaseAITest.failureReport(limit)
                        assert(
                            report == Present("AIRateLimitException 429 requests rate_limit_exceeded, retry after 90.seconds"),
                            s"report: $report"
                        )
                    case _ => fail(s"expected the stand-in's 429 as a rate limit, got ${result.getClass.getSimpleName}")
                end for
            }
        }

        "a failure that carries no provider answer reports as it is" in {
            assert(BaseAITest.failureReport(AIDecodeException("the result did not decode")) == Absent)
        }

        "a raw status failure inside a live leaf's result reports its status and codes, never the body" in {
            val refused = HttpStatusException(
                HttpStatus(401),
                "POST",
                "https://api.example.com/v1/chat/completions",
                """{"error":{"message":"Incorrect API key provided: sk-live-4f9Qz81bXk2","type":"invalid_request_error","code":"invalid_api_key"}}"""
            )
            assert(BaseAITest.reported(Result.fail(refused)) == "HttpStatusException 401 invalid_request_error invalid_api_key")
            assert(BaseAITest.reported(Result.succeed(42)) == "Success")
        }
    }

end BaseAITestSelectionTest
