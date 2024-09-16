package examples.ledger.api

import examples.ledger.db.DB
import java.util.concurrent.Executors
import kyo.*
import sttp.tapir.server.netty.*

object Server extends KyoApp:

    def flag(name: String, default: String) =
        Option(System.getenv(name))
            .getOrElse(System.getProperty(name, default))

    run {

        val port = flag("PORT", "9999").toInt

        val dbConfig =
            DB.Config(
                flag("DB_PATH", "/tmp/"),
                flag("flushInternalMs", "1000").toInt.millis
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

        val timer = Timer(Executors.newSingleThreadScheduledExecutor())

        val init = defer {
            val db      = await(Env.run(dbConfig)(DB.init))
            val handler = await(Env.run(db)(Handler.init))
            await(Env.run(handler)(Endpoints.init))
        }

        defer {
            await(Console.println(s"Server starting on port $port..."))
            val binding = await(Routes.run(server)(Timer.let(timer)(init)))
            await(Console.println(s"Server started: ${binding.localSocket}"))
        }
    }

end Server
