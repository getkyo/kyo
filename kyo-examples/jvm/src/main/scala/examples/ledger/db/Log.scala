package examples.ledger.db

import java.io.FileWriter
import kyo.*
import scala.jdk.CollectionConverters.*

trait Log:

    def transaction(
        balance: Int,
        account: Int,
        amount: Int,
        desc: String
    ): Unit < IO

end Log

object Log:

    case class Entry(balance: Int, account: Int, amount: Int, desc: String)

    val init: Log < (Env[DB.Config] & IO) = defer {
        val cfg = await(Env.get[DB.Config])
        val q   = await(Queue.initUnbounded[Entry](Access.MultiProducerSingleConsumer))
        val log = await(IO(Live(cfg.workingDir + "/log.dat", q)))
        await(Async.run(log.flushLoop(cfg.flushInterval)))
        log
    }

    class Live(filePath: String, q: Queue.Unbounded[Entry]) extends Log:

        private val writer = new FileWriter(filePath, true)

        def transaction(
            balance: Int,
            account: Int,
            amount: Int,
            desc: String
        ): Unit < IO =
            q.add(Entry(balance, account, amount, desc))

        private[Log] def flushLoop(interval: Duration): Unit < Async = defer {
            await(Async.sleep(interval))
            val entries = await(q.drain)
            await(append(entries))
            await(flushLoop(interval))
        }

        private def append(entries: Seq[Entry]) =
            IO {
                if entries.nonEmpty then
                    val str =
                        entries.map { e =>
                            s"${e.balance}|${e.account}|${e.amount}|${e.desc}"
                        }.mkString("\n")
                    writer.append(str + "\n")
                    writer.flush()
            }

    end Live

end Log
