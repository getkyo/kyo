package kyo

import caliban.*
import caliban.render
import caliban.schema.Schema
import caliban.schema.SchemaDerivation
import kyo.*
import kyoTest.KyoTest
import sttp.model.Uri
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.NettyKyoServer
import zio.Task
import zio.ZLayer

class resolversTest extends KyoTest:

    def runZIO[T](v: Task[T]): T =
        zio.Unsafe.unsafe(implicit u =>
            zio.Runtime.default.unsafe.run(v).getOrThrow()
        )

    def testServer(port: Int) =
        import scala.concurrent.duration.*
        NettyKyoServer(NettyConfig.default.copy(port = port, gracefulShutdownTimeout = Some(5.millis)))

    case class Query(
        k1: Int < Aborts[Throwable],
        k2: Int < ZIOs,
        k3: Int < (Aborts[Throwable] & ZIOs),
        k4: Int < IOs,
        k5: Int < Fibers
    ) derives Schema.SemiAuto

    "schema derivation" in {
        val expected = """type Query {
                         |  k1: Int!
                         |  k2: Int!
                         |  k3: Int!
                         |  k4: Int!
                         |  k5: Int!
                         |}""".stripMargin
        assert(render[Query].trim == expected)
    }

    "execution" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42, 42)))
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k1 k2 k3 k4 k5 }")
        yield assert(res.data.toString == """{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}""")
        end for
    }

    "arbitrary kyo effects" in runZIO {
        type Env = Vars[Int] & Envs[String]
        object schema extends SchemaDerivation[Runner[Env]]

        case class Query(k: Int < Env) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Vars.update[Int](_ + 1)
                v <- Vars.get[Int]
                s <- Envs.get[String]
            yield v + s.length
        )))
        val layer = ZLayer.succeed(new Runner[Env]:
            def apply[T: Flat](v: T < Env): Task[T] = ZIOs.run(Envs.run("kyo")(Vars.run(0)(v)))
        )
        for
            interpreter <- api.interpreter
            res         <- interpreter.execute("{ k }").provide(layer)
        yield assert(res.data.toString == """{"k":4}""")
        end for
    }

    "run server" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42, 42)))

        ZIOs.run {
            for
                server <- Resolvers.run(testServer(8080)) {
                    Resolvers.get(api)
                }
                res <- Requests.run {
                    Requests[String](_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k1 k2 k3 k4 k5 }"}"""))
                }
                _ <- server.stop()
            yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}}""")
        }
    }

    "run server with custom config" in runZIO {
        val api = graphQL(RootResolver(Query(42, 42, 42, 42, 42)))

        ZIOs.run {
            for
                server <- Resolvers.run(testServer(8081)) {
                    Resolvers.get(api).map(_.configure(Configurator.setEnableIntrospection(true)))
                }
                res <- Requests.run {
                    Requests[String](_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k1 k2 k3 k4 k5 }"}"""))
                }
                _ <- server.stop()
            yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}}""")
        }
    }

    "run server with arbitrary kyo effects" in runZIO {
        type Env = Vars[Int] & Envs[String]
        object schema extends SchemaDerivation[Runner[Env]]

        case class Query(k: Int < Env) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Vars.update[Int](_ + 1)
                v <- Vars.get[Int]
                s <- Envs.get[String]
            yield v + s.length
        )))
        val runner = new Runner[Env]:
            def apply[T: Flat](v: T < Env): Task[T] = ZIOs.run(Envs.run("kyo")(Vars.run(0)(v)))

        ZIOs.run {
            for
                server <- Resolvers.run(testServer(8082), runner) { Resolvers.get(api) }
                res <- Requests.run {
                    Requests[String](_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k }"}"""))
                }
                _ <- server.stop()
            yield assert(res == """{"data":{"k":4}}""")
        }
    }

end resolversTest
