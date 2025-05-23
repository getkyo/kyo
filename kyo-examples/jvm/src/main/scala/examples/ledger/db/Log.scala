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
        val cfg = Env.get[DB.Config].now
        val q   = Queue.Unbounded.init[Entry](Access.MultiProducerSingleConsumer).now
        val log = IO(Live(cfg.workingDir + "/log.dat", q)).now
        val _   = Async.run(log.flushLoop(cfg.flushInterval)).now
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

        private[Log] def flushLoop(interval: Duration): Unit < (Async & Abort[Closed]) = defer {
            Async.sleep(interval).now
            val entries = q.drain.now
            append(entries).now
            flushLoop(interval).now
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
