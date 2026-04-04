package kyo

import kyo.*

class HttpUnixSocketTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private def tempSocketPath()(using Frame): String < Sync =
        Sync.defer {
            val tmpDir = java.nio.file.Files.createTempDirectory("kyo-unix-http-test")
            tmpDir.resolve("test.sock").toString
        }

    private def cleanupSocket(path: String): Unit =
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path))
        val parent = java.nio.file.Path.of(path).getParent
        if parent != null then
            discard(java.nio.file.Files.deleteIfExists(parent))
        end if
    end cleanupSocket

    private def encodeSocketPath(path: String): String =
        java.net.URLEncoder.encode(path, "UTF-8")

    private def mkUnixUrl(socketPath: String, httpPath: String): String =
        s"http+unix://${encodeSocketPath(socketPath)}$httpPath"

    // ── Transport integration (real HttpServer over Unix socket) ──────────────

    "transport integration" - {

        "HTTP GET over Unix socket" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.getRaw("test").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("hello"))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        val url = mkUnixUrl(sockPath, "/test")
                        HttpClient.getText(url).map { text =>
                            assert(text == "hello")
                        }
                    }
                }
            }
        }

        "HTTP POST with body over Unix socket" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
                val handler = route.handler(req => HttpResponse.ok(req.fields.body))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        val url = mkUnixUrl(sockPath, "/echo")
                        HttpClient.postText(url, "ping").map { text =>
                            assert(text == "ping")
                        }
                    }
                }
            }
        }

        "server address is Unix" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.getRaw("test").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("ok"))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        assert(server.address == HttpServerAddress.Unix(sockPath))
                        assert(server.port == -1)
                        assert(server.host == "localhost")
                        succeed
                    }
                }
            }
        }

        "non-existent socket path fails with HttpConnectException" in run {
            Abort.run[HttpException] {
                HttpClient.getText("http+unix://%2Ftmp%2Fnonexistent_kyo_unix_test.sock/test")
            }.map { result =>
                assert(result.isFailure)
                assert(result.failure.exists(_.isInstanceOf[HttpConnectException]))
            }
        }

        "error response (404) over Unix socket" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.getRaw("exists").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("ok"))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        val url = mkUnixUrl(sockPath, "/missing")
                        case class Dummy(x: Int) derives Json
                        Abort.run[HttpException] {
                            HttpClient.getJson[Dummy](url)
                        }.map { result =>
                            assert(result.isFailure)
                            result.failure match
                                case Present(e: HttpStatusException) =>
                                    assert(e.status == HttpStatus.NotFound)
                                case other =>
                                    fail(s"Expected HttpStatusException but got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

end HttpUnixSocketTest
