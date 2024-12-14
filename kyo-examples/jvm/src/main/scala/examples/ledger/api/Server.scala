package examples.ledger.api

import examples.ledger.db.DB
import java.util.concurrent.Executors
import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.*
import sttp.tapir.swagger.bundle.SwaggerInterpreter

object Server extends KyoApp:

    val clock =
        import AllowUnsafe.embrace.danger
        Clock(Clock.Unsafe(Executors.newSingleThreadScheduledExecutor()))

    run {

        defer {
            val port = await(System.property[Int]("PORT", 9999))

            val dbConfig =
                DB.Config(
                    await(System.property[String]("DB_PATH", "/tmp/")),
                    await(System.property[Duration]("flushInternal", 1000.millis))
                )

            val options =
                NettyKyoServerOptions
                    .default(enableLogging = false)
                    .forkExecution(false)

            val cfg =
                NettyConfig.default
                    .withSocketKeepAlive
                    .copy(lingerTimeout = None)

            val server =
                NettyKyoServer(options, cfg)
                    .host("0.0.0.0")
                    .port(port)

            val db      = await(Env.run(dbConfig)(DB.init))
            val handler = await(Env.run(db)(Handler.init))

            await(Console.printLine(s"Server starting on port $port..."))
            // This Works
            val binding = await(Routes.runCustom(server)(Clock.let(clock)(Env.run(handler)(Endpoints.init)))(addDocs))
            // Comment out the above line and uncomment the below line to see the broken behavior. Is it related to the "Discarded non-Unit value" warning?
            // val binding = await(runRoutes(server)(Clock.let(clock)(Env.run(handler)(Endpoints.init)))(addDocs))
            await(Console.printLine(s"Server started: ${binding.localSocket}"))
        }
    }

    // Why doesn't this work if defined here but it works when defined in Routes?
    @scala.annotation.nowarn("msg=Discarded non-Unit value")
    @scala.annotation.nowarn("msg=discarded non-Unit value")
    def runRoutes[A, S](server: NettyKyoServer)(
        v: Unit < (Routes & S)
    )(f: List[ServerEndpoint[Any, KyoSttpMonad.M]] => List[ServerEndpoint[Any, KyoSttpMonad.M]])(using
        Frame
    ): NettyKyoServerBinding < (Async & S) =
        Emit.run[kyo.Route].apply[Unit, Async & S](v).map { (routes, _) =>
            IO(server.addEndpoints(f(routes.toSeq.map(_.endpoint).toList)).start()): NettyKyoServerBinding < (Async & S)
        }
    end runRoutes

    def addDocs(endpoints: List[ServerEndpoint[Any, KyoSttpMonad.M]]): List[ServerEndpoint[Any, KyoSttpMonad.M]] =
        endpoints ++ (SwaggerInterpreter().fromServerEndpoints(endpoints, "Ledger API", "1.0"))
    end addDocs

end Server
