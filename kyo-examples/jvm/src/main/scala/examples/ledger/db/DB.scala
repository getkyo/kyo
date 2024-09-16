package examples.ledger.db

import examples.ledger.*
import kyo.{Result as _, *}

trait DB:

    def transaction(
        account: Int,
        amount: Int,
        desc: String
    ): Result < IO

    def statement(
        account: Int
    ): Statement < IO

end DB

object DB:

    case class Config(
        workingDir: String,
        flushInterval: Duration
    )

    val init: DB < (Env[Config] & IO) = defer {
        val index = await(Index.init)
        val log   = await(db.Log.init)
        Live(index, log)
    }

    class Live(index: Index, log: Log) extends DB:

        def transaction(account: Int, amount: Int, desc: String): Result < IO =
            index.transaction(account, amount, desc).map {
                case Denied => Denied
                case result: Processed =>
                    log.transaction(result.balance, account, amount, desc).andThen(result)
            }

        def statement(account: Int): Statement < IO =
            index.statement(account)

    end Live
end DB
