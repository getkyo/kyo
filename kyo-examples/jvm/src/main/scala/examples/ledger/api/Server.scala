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
            val binding = await(Routes.run(server)(Clock.let(clock)(Env.run(handler)(Endpoints.init))))
            // This Works
            // val binding = await(Routes.run(server)(Clock.let(clock)(Env.run(handler)(Endpoints.init))) { endpoints =>
            //     // Also include Swagger documentation generation
            //     endpoints ++ (SwaggerInterpreter().fromServerEndpoints(endpoints, "Ledger API", "1.0"))
            // })
            await(Console.printLine(s"Server started: ${binding.localSocket}"))
        }
    }

end Server
