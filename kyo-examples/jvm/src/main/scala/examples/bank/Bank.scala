package examples.bank

import kyo.*
import kyo.Actor.Subject

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

opaque type Amount = BigDecimal
object Amount:
    val zero: Amount                     = BigDecimal(0)
    def apply(value: BigDecimal): Amount = value
    def apply(value: Int): Amount        = BigDecimal(value)
    given CanEqual[Amount, Amount]       = CanEqual.derived

    extension (self: Amount)
        def plus(other: Amount): Amount        = self + other
        def minus(other: Amount): Amount       = self - other
        def isLessThan(other: Amount): Boolean = self < other
        def show: String                       = s"$$${self.setScale(2, BigDecimal.RoundingMode.HALF_UP)}"
    end extension
end Amount

case class Account(id: Int, balance: Amount)

enum AccountMessage:
    case Deposit(amount: Amount, replyTo: Subject[Amount])
    case Withdraw(amount: Amount, replyTo: Subject[Result[String, Amount]])
    case GetBalance(replyTo: Subject[Amount])
end AccountMessage

case class Transaction(accountId: Int, kind: String, amount: Amount, balance: Amount) derives CanEqual
case class FraudSignal(tx: Transaction) derives CanEqual

// Renders a withdraw outcome: the new balance on success, the domain error on failure.
def showResult(result: Result[String, Amount]): String =
    result match
        case Result.Success(balance) =>
            import Amount.show
            balance.show
        case Result.Failure(error) => error
        case Result.Panic(ex)      => s"unexpected: ${ex.getMessage}"

// Creates an account actor that owns its balance in a Var, publishes committed Transactions
// to the shared topic, and replies to each caller. It handles exactly `capacity` messages
// before completing.
private def makeAccountActor(id: Int, capacity: Int, topic: PubSub[Transaction])(
    using Frame
): Actor[Closed, AccountMessage, Unit] < (Scope & Async) =
    Actor.run {
        Var.run(Account(id, Amount.zero)) {
            Actor.receiveMax[AccountMessage](capacity) {
                case AccountMessage.Deposit(amount, replyTo) =>
                    for
                        newBalance <- Var.update[Account](acc => acc.copy(balance = acc.balance.plus(amount)))
                        _          <- topic.publish(Transaction(id, "deposit", amount, newBalance.balance))
                        _          <- replyTo.send(newBalance.balance)
                    yield ()

                case AccountMessage.Withdraw(amount, replyTo) =>
                    Var.use[Account] { acc =>
                        if acc.balance.isLessThan(amount) then
                            replyTo.send(Result.fail("Insufficient funds"))
                        else
                            for
                                newBalance <- Var.update[Account](a => a.copy(balance = a.balance.minus(amount)))
                                _          <- topic.publish(Transaction(id, "withdraw", amount, newBalance.balance))
                                _          <- replyTo.send(Result.succeed(newBalance.balance))
                            yield ()
                    }

                case AccountMessage.GetBalance(replyTo) =>
                    Var.use[Account](acc => replyTo.send(acc.balance))
            }
        }
    }
end makeAccountActor

// ---------------------------------------------------------------------------
// Application entrypoint
// ---------------------------------------------------------------------------

object Bank extends KyoApp:

    run {
        Scope.run {
            for
                // Shared ordered topic: every published Transaction reaches all subscribers
                // in the same total order.
                topic <- PubSub.linearized[Transaction]

                // Backing queues so we can print results after actors finish.
                auditQueue <- Queue.Unbounded.init[Transaction]()
                fraudQueue <- Queue.Unbounded.init[FraudSignal]()

                // Audit observer: receives Transaction directly and stores it.
                audit <- Actor.run {
                    Actor.receiveMax[Transaction](4)(auditQueue.add(_))
                }

                // Fraud observer: receives FraudSignal (via contramap) and stores it.
                fraud <- Actor.run {
                    Actor.receiveMax[FraudSignal](4)(fraudQueue.add(_))
                }

                // Subscribe both observers BEFORE any publish so there is no readiness race.
                _ <- topic.subscribe(audit.subject)
                _ <- topic.subscribe(fraud.subject.contramap(FraudSignal(_)))

                // Account A handles 1 deposit + 1 withdraw = 2 messages.
                accountA <- makeAccountActor(1, 2, topic)
                // Account B handles 1 deposit + 2 withdraws (one fails) = 3 messages.
                accountB <- makeAccountActor(2, 3, topic)

                // Seed balances sequentially so the two deposit transactions arrive in a
                // deterministic order at the topic.
                _ <- accountA.ask(AccountMessage.Deposit(Amount(100), _))
                _ <- accountB.ask(AccountMessage.Deposit(Amount(50), _))

                // Concurrent ATM withdraws: A succeeds, B-small succeeds, B-large fails.
                withdrawA      = Amount(30)
                withdrawBSmall = Amount(20)
                withdrawBLarge = Amount(200)
                // Async.gather preserves input order: result(0)=A withdraw, result(1)=B small, result(2)=B over-limit.
                withdrawResults <- Async.gather(Seq(
                    accountA.ask(AccountMessage.Withdraw(withdrawA, _)),
                    accountB.ask(AccountMessage.Withdraw(withdrawBSmall, _)),
                    accountB.ask(AccountMessage.Withdraw(withdrawBLarge, _))
                ))

                // Wait for both observers to finish consuming their 4 transactions.
                _ <- audit.await
                _ <- fraud.await

                // Drain the collected results.
                auditObserved <- auditQueue.drain
                fraudSignals  <- fraudQueue.drain

                // Print narrative.
                _ <- Console.printLine("=== ATM / Banking Pub-Sub Demo ===\n")
                _ <- Console.printLine("Accounts A and B start empty; A receives $100.00, B receives $50.00.\n")

                _ <- Console.printLine("-- Audit log (ordered by publication) --")
                _ <- Kyo.foreachDiscard(auditObserved) { tx =>
                    Console.printLine(
                        s"  acct=${tx.accountId}  ${tx.kind.padTo(8, ' ')}  ${tx.amount.show.padTo(10, ' ')}  balance ${tx.balance.show}"
                    )
                }

                _ <- Console.printLine("\n-- Fraud signals (same order) --")
                _ <- Kyo.foreachDiscard(fraudSignals) { sig =>
                    val tx = sig.tx
                    Console.printLine(
                        s"  acct=${tx.accountId}  ${tx.kind.padTo(8, ' ')}  ${tx.amount.show.padTo(10, ' ')}  balance ${tx.balance.show}"
                    )
                }

                _ <- Console.printLine(
                    s"\n-- Observers saw the same order: ${auditObserved == fraudSignals.map(_.tx)} --"
                )

                _ <- Console.printLine("\n-- ATM withdraw results --")
                _ <- Console.printLine(s"  A  withdraw ${withdrawA.show}  -> ${showResult(withdrawResults(0))}")
                _ <- Console.printLine(s"  B  withdraw ${withdrawBSmall.show}  -> ${showResult(withdrawResults(1))}")
                _ <- Console.printLine(s"  B  withdraw ${withdrawBLarge.show} -> ${showResult(withdrawResults(2))}")
            yield ()
        }
    }

end Bank
