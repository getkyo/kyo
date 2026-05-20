package examples.ledger.api

import examples.ledger.*
import kyo.*
import kyo.HttpPath.*

object Endpoints:

    private val transactionRoute =
        HttpRoute.postRaw("clientes" / Capture[Int]("id") / "transacoes")
            .request(_.bodyJson[Transaction])
            .response(_.bodyJson[Processed])

    private val statementRoute =
        HttpRoute.getRaw("clientes" / Capture[Int]("id") / "extrato")
            .response(_.bodyJson[Statement])

    private val healthRoute =
        HttpRoute.getRaw("health").response(_.bodyText)

    val init: Seq[HttpHandler[?, ?, ?]] < Env[Handler] =
        Env.use[Handler] { handler =>
            val transactionHandler = transactionRoute.handler { req =>
                handler.transaction(req.fields.id, req.fields.body).map(HttpResponse.ok(_))
            }

            val statementHandler = statementRoute.handler { req =>
                handler.statement(req.fields.id).map(HttpResponse.ok(_))
            }

            val healthHandler = healthRoute.handler { _ =>
                HttpResponse.ok("ok")
            }

            Seq(transactionHandler, statementHandler, healthHandler)
        }

end Endpoints
