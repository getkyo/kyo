package kyo.examples.ledger.db

import java.io.FileWriter
import kyo.*
import scala.jdk.CollectionConverters.*

trait Log:

    def transaction(
        balance: Int,
        account: Int,
        amount: Int,
        desc: String
    ): Unit < IOs

end Log

object Log:

    case class Entry(balance: Int, account: Int, amount: Int, desc: String)

    val init: Log < (Envs[DB.Config] & IOs) = defer {
        val cfg = await(Envs.get[DB.Config])
        val q   = await(Queues.initUnbounded[Entry](Access.Mpsc))
        val log = await(IOs(Live(cfg.workingDir + "/log.dat", q)))
        await(Fibers.init(log.flushLoop(cfg.flushInterval)))
        log
    }

    class Live(filePath: String, q: Queues.Unbounded[Entry]) extends Log:

        private val writer = new FileWriter(filePath, true)

        def transaction(
            balance: Int,
            account: Int,
            amount: Int,
            desc: String
        ): Unit < IOs =
            q.add(Entry(balance, account, amount, desc))

        private[Log] def flushLoop(interval: Duration): Unit < Fibers = defer {
            await(Fibers.sleep(interval))
            val entries = await(q.drain)
            await(append(entries))
            await(flushLoop(interval))
        }

        private def append(entries: Seq[Entry]) =
            IOs {
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
