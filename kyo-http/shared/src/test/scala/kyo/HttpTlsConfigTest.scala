package kyo

import scala.language.implicitConversions

class HttpTlsConfigTest extends BaseHttpTest:

    import HttpPath.*

    private val serverTls = internal.HttpTestPlatformBackend.serverTlsConfig

    // The test server's certificate is self-signed, so it is its own CA: trusting it through caCertPath is a client that trusts the CA that
    // signed the server's certificate, and nothing else does.
    private val serverCa: String = serverTls.certChainPath.getOrElse(throw new IllegalStateException("the test server has no certificate"))

    private val ping = HttpRoute.getRaw("ping").response(_.bodyText).handler(_ => HttpResponse.ok("pong"))

    /** Fetches `/ping` from a TLS server over 127.0.0.1 through a client configured with `clientTls`. */
    private def fetch(clientTls: HttpTlsConfig)(using Frame): Result[HttpException, String] < (Async & Scope & Abort[HttpBindException]) =
        HttpServer.init(HttpServerConfig.default.port(0).host("127.0.0.1").tls(serverTls))(ping).map { server =>
            HttpClient.init(defaultTlsConfig = clientTls).map { client =>
                HttpClient.let(client)(Abort.run[HttpException](HttpClient.getText(s"https://127.0.0.1:${server.port}/ping")))
            }
        }

    "caCertPath" - {

        "a client trusting the CA that signed the server's certificate connects" in {
            fetch(HttpTlsConfig(caCertPath = Present(serverCa), sniHostname = Present("localhost"))).map { result =>
                assert(result == Result.succeed("pong"), s"a client trusting the server's CA must connect, got $result")
            }
        }

        "without it the same client refuses the server, whose CA the default trust store does not hold" in {
            fetch(HttpTlsConfig(sniHostname = Present("localhost"))).map { result =>
                assert(
                    result.isFailure,
                    s"the platform trust store does not hold the test CA, so the handshake must be refused, got $result"
                )
            }
        }
    }

    "hostnameVerification" - {

        // The certificate names localhost; the client asks for a different name, with the chain itself trusted through caCertPath.
        val otherName = HttpTlsConfig(caCertPath = Present(serverCa), sniHostname = Present("other.example"))

        "true (the default) refuses a certificate for another name" in {
            assert(otherName.hostnameVerification)
            fetch(otherName).map { result =>
                assert(result.isFailure, s"a certificate for localhost must not be accepted for other.example, got $result")
            }
        }

        "false accepts a certificate for another name, still requiring a trusted chain" in {
            fetch(otherName.copy(hostnameVerification = false)).map { result =>
                assert(result == Result.succeed("pong"), s"with name checking off a trusted chain must connect, got $result")
            }
        }

        "false does not trust an untrusted chain" in {
            fetch(HttpTlsConfig(sniHostname = Present("other.example"), hostnameVerification = false)).map { result =>
                assert(result.isFailure, s"turning off the name check must not turn off chain validation, got $result")
            }
        }
    }

    "tlsProvider" - {

        "a pinned provider the transport cannot supply fails the connection instead of being replaced" in {
            fetch(HttpTlsConfig(trustAll = true, tlsProvider = Present("kyo-no-such-provider"))).map { result =>
                assert(result.isFailure, s"an unavailable pinned provider must fail closed, got $result")
            }
        }

        "unpinned, the same client connects through the platform's selection" in {
            fetch(HttpTlsConfig(trustAll = true)).map { result =>
                assert(result == Result.succeed("pong"), s"an unpinned client must connect, got $result")
            }
        }
    }

    "defaults match kyo-net's: no CA override, hostname verification on, no pinned provider" in {
        assert(HttpTlsConfig.default.caCertPath == Absent)
        assert(HttpTlsConfig.default.hostnameVerification)
        assert(HttpTlsConfig.default.tlsProvider == Absent)
    }

end HttpTlsConfigTest
