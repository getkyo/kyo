package kyo

import caliban.*
import caliban.render
import caliban.schema.Schema
import caliban.schema.SchemaDerivation
import scala.concurrent.Future
import sttp.model.Uri
import sttp.tapir.*
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.NettyKyoServer
import zio.Task
import zio.ZIO
import zio.ZLayer

class ResolverTest extends Test:

    def runZIO[A](v: ZIO[Any, Any, A]): Future[A] =
        zio.Unsafe.unsafe(implicit u =>
            Future.successful(zio.Runtime.default.unsafe.run(v).getOrThrowFiberFailure())
        )

    def testServer(port: Int) =
        import scala.concurrent.duration.*
        NettyKyoServer(NettyConfig.default.copy(port = port, gracefulShutdownTimeout = Some(5.millis)))

    case class Query(
        k1: Int < Abort[Throwable],
        k2: Int < Async,
        k3: Int < (Abort[Throwable] & Async),
        k4: Int < Sync,
        k5: Int < Async
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
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[Runner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Var.update[Int](_ + 1)
                v <- Var.get[Int]
                s <- Env.get[String]
            yield v + s.length
        )))
        val layer = ZLayer.succeed(new Runner[Environment]:
            def apply[A](v: A < Environment): Task[A] = ZIOs.run(Env.run("kyo")(Var.run(0)(v))))
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
                res <-
                    Requests(_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k1 k2 k3 k4 k5 }"}"""))
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
                res <-
                    Requests(_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k1 k2 k3 k4 k5 }"}"""))
                _ <- server.stop()
            yield assert(res == """{"data":{"k1":42,"k2":42,"k3":42,"k4":42,"k5":42}}""")
        }
    }

    "run server with arbitrary kyo effects" in runZIO {
        type Environment = Var[Int] & Env[String]
        object schema extends SchemaDerivation[Runner[Environment]]

        case class Query(k: Int < Environment) derives schema.SemiAuto

        val api = graphQL(RootResolver(Query(
            for
                _ <- Var.update[Int](_ + 1)
                v <- Var.get[Int]
                s <- Env.get[String]
            yield v + s.length
        )))
        val runner = new Runner[Environment]:
            def apply[A](v: A < Environment): Task[A] = ZIOs.run(Env.run("kyo")(Var.run(0)(v)))

        ZIOs.run {
            for
                server <- Resolvers.run(testServer(8082), runner) { Resolvers.get(api) }
                res <-
                    Requests(_
                        .post(Uri.unsafeApply(server.hostName, server.port))
                        .body("""{"query":"{ k }"}"""))
                _ <- server.stop()
            yield assert(res == """{"data":{"k":4}}""")
        }
    }
end ResolverTest
