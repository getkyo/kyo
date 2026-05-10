package examples.ledger.api

import examples.ledger.*
import examples.ledger.db.DB
import kyo.*

trait Handler:

    def transaction(
        account: Int,
        request: Transaction
    ): Processed < (Abort[HttpResponse.Halt] & Async)

    def statement(
        account: Int
    ): Statement < (Abort[HttpResponse.Halt] & Sync)

end Handler

object Handler:

    val init: Handler < Env[DB] = Env.use[DB](db => Live(db))

    final class Live(db: DB) extends Handler:

        private val notFound            = HttpResponse.halt(HttpResponse(HttpStatus.NotFound))
        private val unprocessableEntity = HttpResponse.halt(HttpResponse(HttpStatus.UnprocessableEntity))

        def transaction(account: Int, request: Transaction) = direct {
            import request.*
            if account < 0 || account > 5 then notFound.now
            else if description.isEmpty || description.exists(d => d.size > 10 || d.isEmpty())
            then unprocessableEntity.now
            else if kind != "c" && kind != "d" then unprocessableEntity.now
            else
                val desc   = description.get
                val value  = if kind == "c" then amount else -amount
                val result = db.transaction(account, value, desc).now
                result match
                    case Denied         => unprocessableEntity.now
                    case res: Processed => res
            end if
        }

        def statement(account: Int) = direct {
            if account < 0 || account > 5 then notFound.now
            else db.statement(account).now
        }

    end Live

end Handler
