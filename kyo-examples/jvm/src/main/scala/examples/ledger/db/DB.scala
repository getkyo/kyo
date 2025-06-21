package examples.ledger.db

import examples.ledger.*
import kyo.{Result as _, *}

trait DB:

    def transaction(
        account: Int,
        amount: Int,
        desc: String
    ): Result < Sync

    def statement(
        account: Int
    ): Statement < Sync

end DB

object DB:

    case class Config(
        workingDir: String,
        flushInterval: Duration
    )

    val init: DB < (Env[Config] & Sync) = direct {
        val index = Index.init.now
        val log   = db.Log.init.now
        Live(index, log)
    }

    class Live(index: Index, log: db.Log) extends DB:

        def transaction(account: Int, amount: Int, desc: String): Result < Sync =
            index.transaction(account, amount, desc).map {
                case Denied => Denied
                case result: Processed =>
                    log.transaction(result.balance, account, amount, desc).andThen(result)
            }

        def statement(account: Int): Statement < Sync =
            index.statement(account)

    end Live
end DB
