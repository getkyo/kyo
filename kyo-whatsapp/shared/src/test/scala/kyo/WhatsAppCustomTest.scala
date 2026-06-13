package kyo

import kyo.WhatsAppId.*

class WhatsAppCustomTest extends BaseWhatsAppTest:

    val token   = "CUSTOM_TOKEN"
    val phoneId = WhatsAppId.PhoneNumberId("999000111")

    def makeConfig(port: Int): WhatsAppConfig =
        WhatsAppConfig(token, phoneId, baseUrl = s"http://localhost:$port")

    case class SomeDto(value: String) derives Schema, CanEqual
    case class InDto(name: String) derives Schema, CanEqual
    case class OutDto(result: String) derives Schema, CanEqual

    "custom GET appends path to baseUrl/apiVersion with bearer".notNative in {
        Channel.init[Tuple2[String, String]](1).map { captured =>
            val customPath = "/whatsapp_business_account_id/message_templates"
            val route      = HttpRoute.getJson[SomeDto]("v25.0" / "whatsapp_business_account_id" / "message_templates")
            val handler = route.handler { req =>
                val path = req.path
                val auth = req.headers.get("Authorization").getOrElse("")
                captured.put((path, auth)).map { _ =>
                    HttpResponse.ok(SomeDto("templates"))
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        val cfg = makeConfig(s.port)
                        WhatsApp.let(cfg) {
                            WhatsApp.custom[Unit, SomeDto](HttpMethod.GET, customPath).map { result =>
                                captured.take.map { case (path, auth) =>
                                    assert(path.endsWith(customPath))
                                    assert(auth == s"Bearer $token")
                                    assert(result == SomeDto("templates"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "custom POST sends the In body as JSON".notNative in {
        Channel.init[Tuple2[String, String]](1).map { captured =>
            val customPath = "/some/endpoint"
            val inDto      = InDto("hello")
            val route      = HttpRoute.postJson[OutDto, InDto]("v25.0" / "some" / "endpoint")
            val handler = route.handler { req =>
                val body = req.fields.body
                val path = req.path
                captured.put((path, body.name)).map { _ =>
                    HttpResponse.ok(OutDto("ok"))
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        val cfg = makeConfig(s.port)
                        WhatsApp.let(cfg) {
                            WhatsApp.custom[InDto, OutDto](HttpMethod.POST, customPath, Present(inDto)).map { result =>
                                captured.take.map { case (path, _) =>
                                    assert(path.endsWith(customPath))
                                    assert(result == OutDto("ok"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "custom DELETE issues a DELETE request with bearer".notNative in {
        Channel.init[Tuple2[String, String]](1).map { captured =>
            val customPath = "/some/resource/123"
            val route      = HttpRoute.deleteJson[SomeDto]("v25.0" / "some" / "resource" / "123")
            val handler = route.handler { req =>
                val path = req.path
                val auth = req.headers.get("Authorization").getOrElse("")
                captured.put((path, auth)).map { _ =>
                    HttpResponse.ok(SomeDto("deleted"))
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        val cfg = makeConfig(s.port)
                        WhatsApp.let(cfg) {
                            WhatsApp.custom[Unit, SomeDto](HttpMethod.DELETE, customPath).map { result =>
                                captured.take.map { case (path, auth) =>
                                    assert(path.endsWith(customPath))
                                    assert(auth == s"Bearer $token")
                                    assert(result == SomeDto("deleted"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "custom POST with absent body sends no body and decodes response".notNative in {
        Channel.init[String](1).map { captured =>
            val customPath = "/no/body/endpoint"
            val route      = HttpRoute.postJson[OutDto, Unit]("v25.0" / "no" / "body" / "endpoint")
            val handler = route.handler { req =>
                val path = req.path
                captured.put(path).map { _ =>
                    HttpResponse.ok(OutDto("empty-ok"))
                }
            }
            HttpClient.init().map { httpClient =>
                HttpServer.init(0, "localhost")(handler).map { s =>
                    HttpClient.let(httpClient) {
                        val cfg = makeConfig(s.port)
                        WhatsApp.let(cfg) {
                            WhatsApp.custom[Unit, OutDto](HttpMethod.POST, customPath).map { result =>
                                captured.take.map { path =>
                                    assert(path.endsWith(customPath))
                                    assert(result == OutDto("empty-ok"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "custom maps a Graph error through mapError".notNative in {
        val authErrBody =
            """{"error":{"code":190,"type":"OAuthException","message":"Token expired","fbtrace_id":"fb4"}}"""
        val route   = HttpRoute.getRaw("v25.0" / "x").response(_.bodyText)
        val handler = route.handler { _ => HttpResponse.badRequest(authErrBody) }
        HttpClient.init().map { httpClient =>
            HttpServer.init(0, "localhost")(handler).map { s =>
                HttpClient.let(httpClient) {
                    val cfg = makeConfig(s.port)
                    Abort.run[WhatsAppError](
                        WhatsApp.let(cfg) {
                            WhatsApp.custom[Unit, SomeDto](HttpMethod.GET, "/x")
                        }
                    ).map { result =>
                        result match
                            case Result.Failure(WhatsAppError.AuthError.TokenExpired()) => assert(true)
                            case other => assert(false, s"expected AuthError.TokenExpired, got: $other")
                    }
                }
            }
        }
    }

end WhatsAppCustomTest
