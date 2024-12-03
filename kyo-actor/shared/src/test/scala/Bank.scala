import java.io.IOException
import java.util.concurrent.locks.LockSupport
import kyo.*

// A demo of the new Actor encoding

enum Kind:
    case Deposit, Withdraw, Interest

object Logger:
    sealed trait Message

    case class Transaction(
        account: Int,
        kind: Kind,
        amount: Double,
        balance: Double
    ) extends Message

    case class Sync(replyTo: Subject[Unit]) extends Message

    // Example of a stateful actor. Note how this new design uses Poll instead of a regular
    // function input. This approach enables better composability with Kyo effects. For example,
    // this actor uses a Var to keep the log of transactions. There's no need for a dedicated API
    // for stateful actors since they can be implemented directly in the actor body via Var.run.
    def init: Actor[IOException, Message, Unit] < Async =
        Actor.run {
            Var.run(Chunk.empty[Transaction]) {
                // Note how Poll enables more flexible scoping. The apply method takes a function
                // that consumes all values. There are other Poll methods that allow reading a single
                // or a few values as well.
                Poll[Message] {
                    case transaction: Transaction =>
                        Console.printLine(transaction).andThen(
                            Var.updateDiscard[Chunk[Transaction]](_.append(transaction))
                        )
                    case Sync(replyTo) =>
                        // Used just to wait for the logger queue to be consumed
                        replyTo.send(())
                }
            }
        }
end Logger

object Account:
    sealed trait Message
    case class Deposit(amount: Double)                                    extends Message
    case class Withdraw(amount: Double, replyTo: Subject[WithdrawResult]) extends Message
    case class GetBalance(replyTo: Subject[Balance])                      extends Message
    case class ApplyInterest(rate: Double)                                extends Message

    case class WithdrawResult(success: Boolean, msg: String, balance: Double)
    case class Balance(balance: Double)

    def init(id: Int, logger: Subject[Logger.Message]) =
        Actor.run {
            // Use a Var[Double] to keep the account balance
            Var.run(0d) {
                Poll[Message] {

                    case GetBalance(replyTo) =>
                        Var.use[Double](balance => replyTo.send(Balance(balance)))

                    case Deposit(amount) =>
                        for
                            newBalance <- Var.update[Double](_ + amount)
                            _          <- logger.send(Logger.Transaction(id, Kind.Deposit, amount, newBalance))
                        yield ()

                    case Withdraw(amount, replyTo) =>
                        Var.use[Double] { currentBalance =>
                            if currentBalance < amount then
                                replyTo.send(WithdrawResult(false, "Insufficient funds", currentBalance))
                            else
                                for
                                    newBalance <- Var.update[Double](_ - amount)
                                    _          <- logger.send(Logger.Transaction(id, Kind.Withdraw, amount, newBalance))
                                    _          <- replyTo.send(WithdrawResult(true, "Withdrawal successful", newBalance))
                                yield ()
                        }

                    case ApplyInterest(rate) =>
                        for
                            balance <- Var.get[Double]
                            interest   = balance * rate
                            newBalance = balance + interest
                            _ <- Var.set(newBalance)
                            _ <- logger.send(Logger.Transaction(id, Kind.Interest, interest, newBalance))
                        yield ()
                }
            }
        }
end Account

case class Bank(
    logger: Subject[Logger.Message],
    nextTransactionId: AtomicLong,
    nextAccountId: AtomicInt
):
    def newAccount: Subject[Account.Message] < Async =
        nextAccountId.incrementAndGet.map(Account.init(_, logger).map(_.subject))
end Bank

object Bank:
    def init: Bank < Async =
        for
            logger            <- Logger.init
            nextTransactionId <- AtomicLong.init
            nextAccountId     <- AtomicInt.init
        yield Bank(logger.subject, nextTransactionId, nextAccountId)
end Bank

object Demo extends KyoApp:
    run {
        for
            bank     <- Bank.init
            account1 <- bank.newAccount
            _        <- Async.parallelUnbounded((0 until 100).map(_ => account1.send(Account.Deposit(1))))
            // Messages that contain a replyTo field can be used with `ask` to wait for the reply
            _        <- Async.parallelUnbounded((0 until 50).map(_ => account1.ask(Account.Withdraw(1, _))))
            _        <- bank.logger.ask(Logger.Sync(_))
            response <- account1.ask(Account.GetBalance(_))
        yield response
    }
end Demo
