package examples.ledger.api

import examples.ledger.*
import java.time.Instant
import kyo.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.json.JsonEncoder

object Endpoints:

    val init: Unit < (Env[Handler] & Routes) = defer {

        val handler = await(Env.get[Handler])

        val apiRoutes =
            await {
                Routes.add(
                    _.post
                        .in("clientes" / path[Int]("id") / "transacoes")
                        .errorOut(statusCode)
                        .in(jsonBody[Transaction])
                        .out(jsonBody[Processed])
                )(handler.transaction)
            }

            await {
                Routes.add(
                    _.get
                        .in("clientes" / path[Int]("id") / "extrato")
                        .errorOut(statusCode)
                        .out(jsonBody[Statement])
                )(handler.statement)
            }

            await {
                Routes.add(
                    _.get
                        .in("health")
                        .out(stringBody)
                )(_ => "ok")
            }
        end apiRoutes

        val endpoints  = await(Routes.get(apiRoutes).map(rs => rs.map(r => r.endpoint))).toList
        val docsRoutes = await(Routes.from(SwaggerInterpreter().fromServerEndpoints(endpoints, "Ledger API", "1.0")))
        val allRoutes  = await(Routes.collect(apiRoutes, docsRoutes))
        allRoutes
    }

end Endpoints
