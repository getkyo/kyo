package examples.ledger.api

import examples.ledger.db.DB
import java.util.concurrent.Executors
import kyo.*
import sttp.tapir.server.netty.*

object Server extends KyoApp:

    run {

        val timer = Timer(Executors.newSingleThreadScheduledExecutor())

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

            await(Console.println(s"Server starting on port $port..."))
            val binding = await(Routes.run(server)(Timer.let(timer)(Env.run(handler)(Endpoints.init))))
            await(Console.println(s"Server started: ${binding.localSocket}"))
        }
    }

end Server
