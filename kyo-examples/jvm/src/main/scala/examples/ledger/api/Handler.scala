package examples.ledger.api

import examples.ledger.*
import examples.ledger.db.DB
import kyo.*
import sttp.model.StatusCode

trait Handler:

    def transaction(
        account: Int,
        request: Transaction
    ): Processed < (Abort[StatusCode] & Async)

    def statement(
        account: Int
    ): Statement < (Abort[StatusCode] & Sync)

end Handler

object Handler:

    val init: Handler < Env[DB] = direct {
        Live(Env.get[DB].now)
    }

    final class Live(db: DB) extends Handler:

        private val notFound            = Abort.fail[StatusCode](StatusCode.NotFound)
        private val unprocessableEntity = Abort.fail[StatusCode](StatusCode.UnprocessableEntity)

        def transaction(account: Int, request: Transaction) = direct {
            import request.*
            // validations
            if account < 0 || account > 5 then notFound.now
            else if description.isEmpty || description.exists(d =>
                    d.size > 10 || d.isEmpty()
                )
            then unprocessableEntity.now
            else if kind != "c" && kind != "d" then unprocessableEntity.now
            else ()
            end if

            val desc  = description.get
            val value = if kind == "c" then amount else -amount

            // perform transaction
            val result =
                db.transaction(account, value, desc).now

            result match
                case Denied         => unprocessableEntity.now
                case res: Processed => res
            end match
        }

        def statement(account: Int) = direct {
            // validations
            if account < 0 || account > 5 then notFound.now
            else ()

            // get statement
            db.statement(account).now
        }

    end Live

end Handler
