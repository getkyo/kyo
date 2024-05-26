package kyo

import _root_.caliban.*
import _root_.caliban.render
import _root_.caliban.schema.Schema
import kyo.*
import kyoTest.KyoTest
import sttp.model.Uri
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyKyoServer
import zio.Task

class resolversTest extends KyoTest:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    case class Query(
        k1: Int < Aborts[Throwable],
        k2: Int < ZIOs,
        k3: Int < (Aborts[Throwable] & ZIOs),
        k4: Int < IOs
    ) derives Schema.SemiAuto

    "schema derivation" in {
        val expected = """type Query {
                         |  k1: Int
                         |  k2: Int
                         |  k3: Int
                         |  k4: Int
                         |}""".stripMargin
        assert(render[Query].trim == expected)
    }

    "execution" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42)))
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k1 k2 k3 k4 }")
        yield assert(res.data.toString == """{"k1":42,"k2":42,"k3":42,"k4":42}""")
        end for
    }

    "run server" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42)))

        ZIOs.run {
            for
                server <- Resolvers.run { Resolvers.add(api) }
                res <- Requests.run {
                    Requests[String](_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k1 k2 k3 k4 }"}"""))
                }
                _ <- server.stop()
            yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42}}""")
        }
    }

    "run server under a nested path" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42)))

        ZIOs.run {
            for
                endpoints <- Resolvers.endpoints { Resolvers.add(api) }
                modifiedEndpoints = endpoints.map { endpoint =>
                    ServerEndpoint(
                        endpoint.endpoint.prependIn(stringToPath("api")),
                        endpoint.securityLogic,
                        endpoint.logic
                    )
                }
                server <- NettyKyoServer().addEndpoints(modifiedEndpoints).start()
                res <- Requests.run {
                    Requests[String](_
                        .post(Uri.unsafeApply(server.hostName, server.port, List("api")))
                        .body("""{"query":"{ k1 k2 k3 k4 }"}"""))
                }
                _ <- server.stop()
            yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42}}""")
        }
    }

end resolversTest
