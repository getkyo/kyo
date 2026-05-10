package examples.ledger.api

import examples.ledger.db.DB
import kyo.*

object Server extends KyoApp:

    run {
        direct {
            val port = System.property[Int]("PORT", 9999).now

            val dbConfig =
                DB.Config(
                    System.property[String]("DB_PATH", "/tmp/").now,
                    System.property[Duration]("flushInternal", 1000.millis).now
                )

            val db       = Env.run(dbConfig)(DB.init).now
            val handler  = Env.run(db)(Handler.init).now
            val handlers = Env.run(handler)(Endpoints.init).now

            Console.printLine(s"Server starting on port $port...").now
            val server = HttpServer.init(port, "0.0.0.0")(handlers*).now
            Console.printLine(s"Server started on port ${server.port}").now
            Async.never.now
        }
    }

end Server
