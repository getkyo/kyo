package kyo.examples.ledger.api

import kyo.*
import kyo.examples.ledger.*
import kyo.examples.ledger.db.DB
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
        Live(await(Env.get[DB]))
    }

    final class Live(db: DB) extends Handler:

        private val notFound            = Abort.fail[StatusCode](StatusCode.NotFound)
        private val unprocessableEntity = Abort.fail[StatusCode](StatusCode.UnprocessableEntity)
        private val serviceUnavailable = Abort.fail[StatusCode](StatusCode.ServiceUnavailable)

        def transaction(account: Int, request: Transaction) = defer {
            import request.*
            // validations
            await {
                if account < 0 || account > 5 then notFound
                else if description.isEmpty || description.exists(d =>
                        d.size > 10 || d.isEmpty()
                    )
                then unprocessableEntity
                else if kind != "c" && kind != "d" then unprocessableEntity
                else ()
            }

            val desc  = description.get
            val value = if kind == "c" then amount else -amount

            // perform transaction
            val result =
                await(db.transaction(account, value, desc))

            result match
                case Denied         => await(unprocessableEntity)
                case res: Processed => res
            end match
        }

        def statement(account: Int) = defer {
            // validations
            await {
                if account < 0 || account > 5 then notFound
                else ()
            }

            // get statement
            await(db.statement(account))
        }

    end Live

end Handler
