package kyo.examples.ledger.db

import kyo.{Result as _, *}
import kyo.examples.ledger.*

trait DB:

    def transaction(
        account: Int,
        amount: Int,
        desc: String
    ): Result < IOs

    def statement(
        account: Int
    ): Statement < IOs

end DB

object DB:

    case class Config(
        workingDir: String,
        flushInterval: Duration
    )

    val init: DB < (Envs[Config] & IOs) = defer {
        val index = await(Index.init)
        val log   = await(Log.init)
        Live(index, log)
    }

    class Live(index: Index, log: Log) extends DB:

        def transaction(account: Int, amount: Int, desc: String): Result < IOs =
            index.transaction(account, amount, desc).map {
                case Denied => Denied
                case result: Processed =>
                    log.transaction(result.balance, account, amount, desc).andThen(result)
            }

        def statement(account: Int): Statement < IOs =
            index.statement(account)

    end Live
end DB
