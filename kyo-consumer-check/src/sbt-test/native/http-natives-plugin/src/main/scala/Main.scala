import kyo.*

// An HTTPS round trip over loopback: a server terminating TLS with the fixture's self-signed localhost certificate
// answers a request from a client that trusts it.
object Main extends KyoApp:
    run {
        val tls = HttpTlsConfig(certChainPath = Present("server.pem"), privateKeyPath = Present("server.key"))
        Abort.run[Any] {
            Scope.run {
                HttpServer.init(HttpServerConfig.default.host("127.0.0.1").port(0).tls(tls))(
                    HttpHandler.getText("hello") { _ => "hello" }
                ).map { server =>
                    HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true))) {
                        HttpClient.getText(s"https://127.0.0.1:${server.port}/hello")
                    }
                }
            }
        }.map { result =>
            val outcome = result match
                case Result.Success(body)  => body
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Console.printLine(s"CONSUMER https=$outcome")
        }
    }
end Main
