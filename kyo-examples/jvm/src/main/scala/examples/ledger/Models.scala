package examples.ledger

import java.time.Instant
import kyo.*

case class Transaction(
    amount: Int,
    kind: String,
    description: Maybe[String] = Absent,
    timestamp: Maybe[Instant] = Absent
) derives Schema, CanEqual

sealed trait Result derives CanEqual

case object Denied
    extends Result

case class Processed(
    limit: Int,
    balance: Int
) extends Result derives Schema, CanEqual

case class Statement(
    balance: Balance,
    lastTransactions: Seq[Transaction]
) derives Schema, CanEqual

case class Balance(
    total: Int,
    date: Instant,
    limit: Int
) derives Schema, CanEqual
