package examples.ledger.api

import examples.ledger.db.DB
import java.util.concurrent.Executors
import kyo.*
import sttp.tapir.server.netty.*

object Server extends KyoApp:

    val clock =
        import AllowUnsafe.embrace.danger
        Clock(Clock.Unsafe(Executors.newSingleThreadScheduledExecutor()))

    run {

        defer {
            val port = System.property[Int]("PORT", 9999).now

            val dbConfig =
                DB.Config(
                    System.property[String]("DB_PATH", "/tmp/").now,
                    System.property[Duration]("flushInternal", 1000.millis).now
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

            val db      = Env.run(dbConfig)(DB.init).now
            val handler = Env.run(db)(Handler.init).now

            Console.printLine(s"Server starting on port $port...").now
            val binding = Routes.run(server)(Clock.let(clock)(Env.run(handler)(Endpoints.init))).now
            Console.printLine(s"Server started: ${binding.localSocket}").now
        }
    }

end Server
