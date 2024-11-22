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
    ): Statement < (Abort[StatusCode] & IO)

end Handler

object Handler:

    val init: Handler < Env[DB] = defer {
        Live(~Env.get[DB])
    }

    final class Live(db: DB) extends Handler:

        private val notFound            = Abort.fail[StatusCode](StatusCode.NotFound)
        private val unprocessableEntity = Abort.fail[StatusCode](StatusCode.UnprocessableEntity)

        def transaction(account: Int, request: Transaction) = defer {
            import request.*
            // validations
            if account < 0 || account > 5 then ~notFound
            else if description.isEmpty || description.exists(d =>
                    d.size > 10 || d.isEmpty()
                )
            then ~unprocessableEntity
            else if kind != "c" && kind != "d" then ~unprocessableEntity
            else ()
            end if

            val desc  = description.get
            val value = if kind == "c" then amount else -amount

            // perform transaction
            val result =
                ~db.transaction(account, value, desc)

            result match
                case Denied         => ~unprocessableEntity
                case res: Processed => res
            end match
        }

        def statement(account: Int) = defer {
            // validations
            if account < 0 || account > 5 then ~notFound
            else ()

            // get statement
            ~db.statement(account)
        }

    end Live

end Handler
