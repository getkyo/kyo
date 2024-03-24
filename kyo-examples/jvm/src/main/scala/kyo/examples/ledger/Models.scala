package kyo.examples.ledger

import java.time.Instant
import zio.json.JsonCodec

case class Transaction(
    amount: Int,
    kind: String,
    description: Option[String],
    timestamp: Option[Instant]
) derives JsonCodec

sealed trait Result

case object Denied
    extends Result

case class Processed(
    limit: Int,
    balance: Int
) extends Result derives JsonCodec

case class Statement(
    balance: Balance,
    lastTransactions: Seq[Transaction]
) derives JsonCodec

case class Balance(
    total: Int,
    date: Instant,
    limit: Int
) derives JsonCodec
