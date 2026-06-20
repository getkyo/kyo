package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.Actor.Subject

class ActorTest extends kyo.test.Test[Any]:

    private case class Publish(value: Int, replyTo: Subject[Int])

    opaque type Amount = BigDecimal
    object Amount:
        val zero: Amount                     = BigDecimal(0)
        def apply(value: BigDecimal): Amount = value
        def apply(value: Int): Amount        = BigDecimal(value)
        given CanEqual[Amount, Amount]       = CanEqual.derived
    end Amount

    extension (self: Amount)
        private def plus(other: Amount): Amount        = self + other
        private def minus(other: Amount): Amount       = self - other
        private def isLessThan(other: Amount): Boolean = self < other
    end extension

    "ask lifecycle (no stranded callers)" - {
        "completes the caller when a scoped actor closes with a queued ask message" in {
            for
                fiber <- Scope.run {
                    for
                        actor <- Actor.run(capacity = 1) {
                            Actor.receiveLoop[Publish](msg => msg.replyTo.send(msg.value).andThen(Loop.continue))
                        }
                        f <- Fiber.initUnscoped(actor.ask(Publish(1, _)))
                    yield f
                }
                // The 2s timeout is the strand guard: a stranded caller hangs and the
                // uncaught Timeout fails the leaf. Reaching any Closed/success result proves non-stranding.
                // Outcome is racy at capacity=1 (the queued ask may be processed or dropped on close), so accept either.
                result <- Abort.run[Closed](Async.timeout(2.seconds)(fiber.get))
            yield assert(result.isSuccess || result.isFailure)
        }
        "completes the caller with a failure when the handler panics before replying" in {
            for
                result <- Abort.run[Closed | Timeout] {
                    Async.timeout(2.seconds) {
                        Scope.run {
                            for
                                actor <- Actor.run(capacity = 16) {
                                    Actor.receiveLoop[Publish](_ => Abort.panic(new RuntimeException("boom")))
                                }
                                r <- actor.ask(Publish(1, _))
                            yield r
                        }
                    }
                }
            yield assert(result.isPanic || result.isFailure)
        }
        "leaves the actor alive across many sequential asks without accumulating reply waiters" in {
            Scope.run {
                for
                    actor <- Actor.run(capacity = 1) {
                        Actor.receiveLoop[Publish](msg => msg.replyTo.send(msg.value).andThen(Loop.continue))
                    }
                    _       <- Loop.indexed(i => if i >= 1000 then Loop.done else actor.ask(Publish(i, _)).andThen(Loop.continue))
                    alive   <- actor.fiber.poll.map(_.isEmpty)
                    pending <- Sync.defer(actor.pendingReplies)
                    _       <- actor.close
                yield
                    assert(alive, "actor terminated mid-run")
                    assert(pending == 0, s"reply waiters accumulated: $pending")
            }
        }
        "returns the reply under normal operation" in {
            for
                actor  <- Actor.run(Actor.receiveLoop[Publish](msg => msg.replyTo.send(msg.value).andThen(Loop.continue)))
                result <- actor.ask(Publish(7, _))
                _      <- actor.close
            yield assert(result == 7)
        }
    }

    "basic actor operations" - {
        "completes with final value" in {
            for
                actor  <- Actor.run("a")
                result <- actor.await
            yield assert(result == "a")
        }

        "processes messages" in {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Actor.receiveMax[Int](3)(sum.addAndGet(_)))
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- actor.await
                finalSum <- sum.get
            yield assert(finalSum == 6)
        }

        "processes messages and returns value" in {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Actor.receiveMax[Int](3)(sum.addAndGet(_)).andThen(sum.get))
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                finalSum <- actor.await
            yield assert(finalSum == 6)
        }

        "stops after processing N messages" in {
            for
                counter <- AtomicInt.init(0)
                actor   <- Actor.run(Actor.receiveMax[Int](1)(_ => counter.incrementAndGet))
                _       <- actor.send(1)
                _       <- actor.await
                result  <- Abort.run(actor.send(1))
                count   <- counter.get
            yield assert(result.isFailure && count == 1)
        }
    }

    "inter-actor communication" - {
        "ping pong between actors" in {
            case class Ping(replyTo: Subject[Pong])
            case class Pong(replyTo: Subject[Ping])

            for
                pongActor <- Actor.run {
                    Actor.receiveMax[Ping](3) { ping =>
                        Actor.self[Ping].map(self => ping.replyTo.send(Pong(self)))
                    }
                }
                pingActor <- Actor.run {
                    Var.runTuple(0) {
                        Actor.receiveMax[Pong](3) { pong =>
                            Var.update[Int](_ + 1).map { r =>
                                Actor.self[Pong].map(self => pongActor.send(Ping(self))).andThen(r)
                            }
                        }
                    }
                }
                _      <- pingActor.send(Pong(pongActor.subject))
                result <- pingActor.await
            yield assert(result == (3, ()))
            end for
        }

        "broadcast to multiple actors" in {
            for
                results <- Queue.Unbounded.init[Int]()
                workers <-
                    Kyo.foreach(1 to 3) { id =>
                        Actor.run(Actor.receiveMax[Int](3)(msg => results.add(msg * id)))
                    }
                scheduler <- Actor.run {
                    Actor.receiveMax[Int](3) { msg =>
                        Async.foreach(workers)(_.send(msg))
                    }
                }
                _        <- scheduler.send(1)
                _        <- scheduler.send(2)
                _        <- scheduler.send(3)
                _        <- scheduler.await
                _        <- Async.foreach(workers)(_.await)
                received <- results.drain.map(_.sorted)
            yield assert(received == List(1, 2, 2, 3, 3, 4, 6, 6, 9))
        }

    }

    "error handling" - {
        case object TestError

        "propagates errors" in {
            for
                actor <- Actor.run {
                    Actor.receiveAll[Int] { v =>
                        if v == 42 then Abort.fail(TestError)
                        else ()
                    }
                }
                _      <- actor.send(1)
                _      <- actor.send(42)
                result <- Abort.run(actor.await)
            yield assert(result == Result.fail(TestError))
        }
    }

    "actor hierarchy" - {
        "child actors are properly cleaned up when parent finishes" in {
            for
                consumed         <- Latch.init(2)
                cleanedUp        <- Latch.init(2)
                childActorStates <- Queue.Unbounded.init[String]()
                parentActor <- Actor.run {
                    for
                        childActor1 <- Actor.run {
                            for
                                _ <- Scope.ensure(childActorStates.add("child1 cleaned up").andThen(cleanedUp.release))
                                _ <- childActorStates.add("child1 started")
                            yield Actor.receiveAll[Int] { _ =>
                                childActorStates.add("child1 received message").andThen(consumed.release)
                            }
                        }
                        childActor2 <- Actor.run {
                            for
                                _ <- Scope.ensure(childActorStates.add("child2 cleaned up").andThen(cleanedUp.release))
                                _ <- childActorStates.add("child2 started")
                            yield Actor.receiveAll[Int] { _ =>
                                childActorStates.add("child2 received message").andThen(consumed.release)
                            }
                        }
                        _ <- childActor1.send(1)
                        _ <- childActor2.send(2)
                        _ <- consumed.await
                    yield "parent complete"
                }
                result <- parentActor.await
                _      <- cleanedUp.await
                events <- childActorStates.drain
            yield
                assert(result == "parent complete")
                assert(events.contains("child1 started"))
                assert(events.contains("child2 started"))
                assert(events.contains("child1 received message"))
                assert(events.contains("child2 received message"))
                assert(events.contains("child1 cleaned up"))
                assert(events.contains("child2 cleaned up"))
        }

        "child actors are cleaned up when parent fails" in {
            case object ParentError

            for
                messageReceived <- Latch.init(1)
                childCleaned    <- Latch.init(1)
                parentActorFiber <-
                    Actor.run {
                        for
                            childActor <- Actor.run {
                                for
                                    _ <- Scope.ensure(childCleaned.release)
                                yield Actor.receiveAll[Int] { _ =>
                                    messageReceived.release
                                }
                            }
                            _ <- childActor.send(1)
                            _ <- messageReceived.await
                            _ <- Abort.fail(ParentError)
                        yield "never reached"
                    }
                _      <- childCleaned.await
                result <- Abort.run(parentActorFiber.await)
            yield assert(result.isFailure)
            end for
        }

        "parallel child actor creation and cleanup works correctly".notJs in {
            val actorCount = 50

            for
                allReceived    <- Latch.init(actorCount)
                startCounter   <- AtomicInt.init(0)
                cleanupCounter <- AtomicInt.init(0)
                parentActor <- Actor.run {
                    for
                        childActors <- Async.fill(actorCount) {
                            Actor.run {
                                for
                                    _ <- Scope.ensure(cleanupCounter.incrementAndGet)
                                    _ <- startCounter.incrementAndGet
                                yield Actor.receiveAll[Int] { _ =>
                                    allReceived.release
                                }
                            }
                        }
                        _ <- Async.foreach(childActors)(_.send(1))
                        _ <- allReceived.await
                    yield "parent done"
                }
                result     <- parentActor.await
                startCount <- startCounter.get
                _          <- assertEventually(cleanupCounter.get.map(_ == actorCount))
            yield
                assert(result == "parent done")
                assert(startCount == actorCount)
            end for
        }

        "multi-level hierarchy" in {
            case class Message(value: Int, replyTo: Subject[Int])

            for
                results <- Queue.Unbounded.init[Int]()
                grandparent <- Actor.run {
                    for
                        parents <- Async.foreach(1 to 2) { parentId =>
                            Actor.run {
                                for
                                    children <- Async.foreach(1 to 2) { childId =>
                                        Actor.run {
                                            Actor.receiveMax[Message](3) { msg =>
                                                val result = msg.value * parentId * childId
                                                results.add(result).andThen {
                                                    msg.replyTo.send(result)
                                                }
                                            }
                                        }
                                    }
                                yield Actor.receiveMax[Message](3) { msg =>
                                    Async.foreach(children)(_.send(msg))
                                }
                            }
                        }
                    yield Actor.receiveMax[Message](3) { msg =>
                        Async.foreach(parents)(_.send(msg))
                    }
                }
                promise   <- Promise.init[Int, Any]
                _         <- grandparent.send(Message(5, Subject.init(promise)))
                _         <- promise.get
                _         <- assertEventually(results.size.map(_ == 4))
                processed <- results.drain
            yield assert(processed.toSet == Set(5 * 1 * 1, 5 * 1 * 2, 5 * 2 * 1, 5 * 2 * 2))
            end for
        }
    }

    "backpressure and capacity" - {
        "handles mailbox at capacity".onlyJvm in {
            for
                counter <- AtomicInt.init(0)
                actor   <- Actor.run(100)(Actor.receiveMax[Int](150)(counter.addAndGet(_)))
                _       <- Async.foreach(1 to 150)(i => actor.send(i))
                _       <- actor.await
                sum     <- counter.get
            yield assert(sum == (1 to 150).sum)
        }

        "under concurrency".onlyJvm in {
            for
                results  <- Queue.Unbounded.init[Int]()
                actor    <- Actor.run(50)(Actor.receiveMax[Int](1000)(results.add(_)))
                _        <- Async.fill(10)(Async.foreach(1 to 100)(i => actor.send(i)))
                _        <- actor.await
                received <- results.drain
            yield
                assert(received.size == 1000)
                assert(received.toSet == (1 to 100).toSet)
        }
    }

    "graceful shutdown" in {
        for
            started   <- Latch.init(1)
            exit      <- Latch.init(1)
            processed <- AtomicBoolean.init
            actor <- Actor.run {
                Actor.receiveAll[Int] { msg =>
                    for
                        _ <- started.release
                        _ <- exit.await
                        _ <- processed.set(true)
                    yield ()
                }
            }
            _ <- actor.send(1)
            _ <- started.await
            _ <- actor.close
            _ <- exit.release
            _ <- assertEventually(processed.get)
        yield ()
    }

    "resource management" - {
        "properly cleans up resources on normal completion" in {
            for
                resourceCleaned <- AtomicBoolean.init(false)
                actor <- Actor.run {
                    Scope.ensure(resourceCleaned.set(true)).andThen {
                        Actor.receiveMax[Int](3) { _ => () }
                    }
                }
                _       <- actor.send(1)
                _       <- actor.send(2)
                _       <- actor.send(3)
                _       <- actor.await
                cleaned <- resourceCleaned.get
            yield assert(cleaned)
        }

        "cleans up resources on error" in {
            case object TestError
            for
                resourceCleaned <- AtomicBoolean.init(false)
                actor <- Actor.run {
                    Scope.ensure(resourceCleaned.set(true)).andThen {
                        Actor.receiveMax[Int](1) { _ =>
                            Abort.fail(TestError)
                        }
                    }
                }
                _      <- actor.send(1)
                result <- Abort.run(actor.await)
                _      <- assertEventually(resourceCleaned.get)
            yield assert(result == Result.fail(TestError))
            end for
        }
    }

    "concurrency" - {
        "handles multiple senders".onlyJvm in {
            for
                sum    <- AtomicInt.init(0)
                actor  <- Actor.run(Actor.receiveMax[Int](100)(sum.addAndGet(_).unit))
                _      <- Async.foreach(1 to 100)(i => actor.send(i))
                _      <- actor.await
                result <- sum.get
            yield assert(result == 5050)
        }

        "maintains message order".onlyJvm in {
            for
                queue  <- Queue.Unbounded.init[Int]()
                actor  <- Actor.run(Actor.receiveMax[Int](100)(queue.add(_)))
                _      <- Kyo.foreach(1 to 100)(i => actor.send(i))
                _      <- actor.await
                result <- queue.drain
            yield assert(result == (1 to 100))
        }
    }

    "banking simulation" - {
        case class Account(id: Int, balance: Amount)

        enum AccountMessage:
            case Deposit(amount: Amount, replyTo: Subject[Amount])
            case Withdraw(amount: Amount, replyTo: Subject[Result[String, Amount]])
            case GetBalance(replyTo: Subject[Amount])
        end AccountMessage

        case class Transaction(accountId: Int, kind: String, amount: Amount, balance: Amount) derives CanEqual

        case class FraudSignal(tx: Transaction) derives CanEqual

        "handles concurrent transactions correctly" in {
            for
                loggedTransactions <- Queue.Unbounded.init[Transaction]()
                logger <- Actor.run {
                    Actor.receiveMax[Transaction](11) { tx =>
                        loggedTransactions.add(tx)
                    }
                }
                account <- Actor.run {
                    Var.run(Account(1, Amount.zero)) {
                        // 10 deposits + 2 GetBalance + 2 withdraws = 14
                        Actor.receiveMax[AccountMessage](14) {
                            case AccountMessage.Deposit(amount, replyTo) =>
                                for
                                    newBalance <- Var.update[Account](acc => acc.copy(balance = acc.balance.plus(amount)))
                                    _          <- logger.send(Transaction(1, "deposit", amount, newBalance.balance))
                                    _          <- replyTo.send(newBalance.balance)
                                yield ()

                            case AccountMessage.Withdraw(amount, replyTo) =>
                                Var.use[Account] { acc =>
                                    if acc.balance.isLessThan(amount) then
                                        replyTo.send(Result.fail("Insufficient funds"))
                                    else
                                        for
                                            newBalance <- Var.update[Account](a => a.copy(balance = a.balance.minus(amount)))
                                            _          <- logger.send(Transaction(1, "withdraw", amount, newBalance.balance))
                                            _          <- replyTo.send(Result.succeed(newBalance.balance))
                                        yield ()
                                }

                            case AccountMessage.GetBalance(replyTo) =>
                                Var.use[Account](acc => replyTo.send(acc.balance))
                        }
                    }
                }
                _        <- Async.fill(10)(account.ask(AccountMessage.Deposit(Amount(10), _)))
                balance1 <- account.ask(AccountMessage.GetBalance(_))
                result1  <- account.ask(AccountMessage.Withdraw(Amount(200), _))
                result2  <- account.ask(AccountMessage.Withdraw(Amount(50), _))
                balance2 <- account.ask(AccountMessage.GetBalance(_))
                _        <- account.await
                _        <- logger.await
                logs     <- loggedTransactions.drain
            yield
                assert(balance1 == Amount(100))
                assert(result1 == Result.fail("Insufficient funds"))
                assert(result2 == Result.succeed(Amount(50)))
                assert(balance2 == Amount(50))
                assert(logs.count(_.kind == "deposit") == 10)
                assert(logs.count(_.kind == "withdraw") == 1)
                assert(logs.last.balance == Amount(50))
        }

        "publishes transactions to multiple observers in a consistent order (linearized Topic)" in {
            // nested Scope tears down the Topic's internal linearizer actor before the assertions run
            Scope.run {
                for
                    topic      <- Topic.linearized[Transaction]
                    auditQueue <- Queue.Unbounded.init[Transaction]()
                    fraudQueue <- Queue.Unbounded.init[FraudSignal]()

                    // audit observer: receives Transaction directly
                    audit <- Actor.run {
                        Actor.receiveMax[Transaction](4)(auditQueue.add(_))
                    }
                    // fraud observer: receives FraudSignal via contramap
                    fraud <- Actor.run {
                        Actor.receiveMax[FraudSignal](4)(fraudQueue.add(_))
                    }

                    // subscribe BOTH observers before any publish (subscribe is awaited, so deterministic)
                    _ <- topic.subscribe(audit.subject)
                    _ <- topic.subscribe(fraud.subject.contramap(FraudSignal(_)))

                    makeAccountActor = (id: Int, capacity: Int) =>
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

                    // account A: handles 1 Deposit + 1 Withdraw = 2 messages
                    accountA <- makeAccountActor(1, 2)
                    // account B: handles 1 Deposit + 2 Withdraws = 3 messages
                    accountB <- makeAccountActor(2, 3)

                    // seed accounts sequentially (awaited), producing 2 deposit transactions in order
                    _ <- accountA.ask(AccountMessage.Deposit(Amount(100), _))
                    _ <- accountB.ask(AccountMessage.Deposit(Amount(50), _))

                    // concurrent ATM withdraws: accountA succeeds, accountB succeeds, accountB fails (200 > 50)
                    withdrawResults <- Async.gather(Seq(
                        accountA.ask(AccountMessage.Withdraw(Amount(30), _)),
                        accountB.ask(AccountMessage.Withdraw(Amount(20), _)),
                        accountB.ask(AccountMessage.Withdraw(Amount(200), _))
                    ))

                    // wait for both observers to finish consuming their 4 transactions
                    _ <- audit.await
                    _ <- fraud.await

                    auditObserved <- auditQueue.drain
                    fraudSignals  <- fraudQueue.drain
                yield
                    // both observers received the same 4 transactions in the same total order
                    assert(auditObserved.size == 4)
                    assert(auditObserved == fraudSignals.map(_.tx))

                    // the 4 successful transactions are exactly: deposit 100, deposit 50, withdraw 30, withdraw 20
                    val expectedSet = Set(
                        Transaction(1, "deposit", Amount(100), Amount(100)),
                        Transaction(2, "deposit", Amount(50), Amount(50)),
                        Transaction(1, "withdraw", Amount(30), Amount(70)),
                        Transaction(2, "withdraw", Amount(20), Amount(30))
                    )
                    assert(auditObserved.toSet == expectedSet)

                    // the failing withdraw returns a domain error, never published
                    assert(withdrawResults.size == 3)
                    val failResult = withdrawResults(2)
                    assert(failResult == Result.fail("Insufficient funds"))
                end for
            }
        }
    }

    "receiveLoop" - {
        "processes messages until done" in {
            for
                sum <- AtomicInt.init(0)
                actor <- Actor.run {
                    Actor.receiveLoop[Int] { msg =>
                        if msg == 0 then Loop.done
                        else sum.addAndGet(msg).andThen(Loop.continue)
                    }
                }
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- actor.send(0)
                _        <- actor.await
                finalSum <- sum.get
            yield assert(finalSum == 6)
        }

        "can maintain state between iterations" in {
            for
                results <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    Var.run(0) {
                        Actor.receiveLoop[String] { msg =>
                            if msg == "stop" then Loop.done
                            else
                                for
                                    count <- Var.update[Int](_ + 1)
                                    _     <- results.add(s"$msg-$count")
                                yield Loop.continue
                        }
                    }
                }
                _        <- actor.send("a")
                _        <- actor.send("b")
                _        <- actor.send("c")
                _        <- actor.send("stop")
                _        <- actor.await
                received <- results.drain
            yield assert(received == List("a-1", "b-2", "c-3"))
        }

        "can maintain single state value" in {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[Int](0) { (msg, sum) =>
                        if msg == 0 then Loop.done(sum)
                        else Loop.continue(sum + msg)
                    }
                }
                _      <- actor.send(10)
                _      <- actor.send(20)
                _      <- actor.send(30)
                _      <- actor.send(0)
                result <- actor.await
            yield assert(result == 60)
        }

        "can maintain two state values" in {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[String]("", 0) { (msg, str, count) =>
                        if msg == "stop" then Loop.done((str, count))
                        else Loop.continue(str + msg, count + 1)
                    }
                }
                _      <- actor.send("a")
                _      <- actor.send("b")
                _      <- actor.send("c")
                _      <- actor.send("stop")
                result <- actor.await
            yield assert(result == ("abc", 3))
        }

        "can maintain three state values" in {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[Int](0, 0, 1) { (msg, sum, count, product) =>
                        if msg == 0 then Loop.done((sum, count, product))
                        else Loop.continue(sum + msg, count + 1, product * msg)
                    }
                }
                _      <- actor.send(2)
                _      <- actor.send(3)
                _      <- actor.send(4)
                _      <- actor.send(0)
                result <- actor.await
            yield assert(result == (9, 3, 24))
        }

        "can maintain four state values" in {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[String]("", 0, 0, true) { (msg, str, length, wordCount, valid) =>
                        if msg == "stop" then Loop.done((str, length, wordCount, valid))
                        else if msg == "invalid" then Loop.continue(str, length, wordCount, false)
                        else if msg == " " then Loop.continue(str + msg, length + 1, wordCount + 1, valid)
                        else Loop.continue(str + msg, length + msg.length, wordCount, valid)
                    }
                }
                _      <- actor.send("Hello")
                _      <- actor.send(" ")
                _      <- actor.send("World")
                _      <- actor.send("invalid")
                _      <- actor.send("stop")
                result <- actor.await
            yield
                assert(result._1 == "Hello World")
                assert(result._2 == 11)
                assert(result._3 == 1)
                assert(result._4 == false)
        }
    }

    "multiple receive calls" - {

        "combines receiveMax and receiveAll" in {
            for
                results <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    for
                        _ <- Actor.receiveMax[Int](2) { msg =>
                            results.add(s"receiveMax: $msg")
                        }
                        _ <- Actor.receiveAll[Int] { msg =>
                            results.add(s"receiveAll: $msg")
                        }
                    yield ()
                }
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- assertEventually(results.size.map(_ == 3))
                _        <- actor.close
                received <- results.drain
            yield assert(received == List("receiveMax: 1", "receiveMax: 2", "receiveAll: 3"))
        }

        "combines receiveLoop and receiveMax" in {
            for
                results <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    for
                        sum <- Actor.receiveLoop[Int](0) { (msg, acc) =>
                            if msg == 0 then Loop.done(acc)
                            else Loop.continue(acc + msg)
                        }
                        _ <- Actor.receiveMax[Int](2) { msg =>
                            results.add(s"After loop: $msg (sum was $sum)")
                        }
                    yield sum
                }
                _      <- actor.send(10)
                _      <- actor.send(20)
                _      <- actor.send(0)
                _      <- actor.send(30)
                _      <- actor.send(40)
                result <- actor.await
                logs   <- results.drain
            yield
                assert(result == 30)
                assert(logs == List("After loop: 30 (sum was 30)", "After loop: 40 (sum was 30)"))
        }
    }

    "supervision" - {

        case object TemporaryError
        case object PermanentError

        case class TestMessage(v: Int, replyTo: Subject[Int])

        "Retry" in {
            for
                attempts <- AtomicInt.init(0)
                actor <- Actor.run {
                    Retry[TemporaryError.type] {
                        attempts.incrementAndGet.map { count =>
                            Actor.receiveAll[TestMessage] { msg =>
                                msg.replyTo.send(msg.v + 1)
                                    .andThen(Abort.when(msg.v == 42)(TemporaryError))
                            }
                        }
                    }
                }
                v1    <- actor.ask(TestMessage(1, _))
                v2    <- actor.ask(TestMessage(42, _))
                v3    <- actor.ask(TestMessage(2, _))
                v4    <- actor.ask(TestMessage(3, _))
                v5    <- actor.ask(TestMessage(42, _))
                v6    <- actor.ask(TestMessage(4, _))
                count <- attempts.get
            yield assert(
                count == 3 && v1 == 2 && v2 == 43 && v3 == 3 && v4 == 4 && v5 == 43 && v6 == 5
            )
        }

        "Retry limit" in {
            for
                attempts <- AtomicInt.init(0)
                actor <- Actor.run {
                    Retry[TemporaryError.type](Schedule.repeat(2)) {
                        attempts.incrementAndGet.map { count =>
                            Actor.receiveAll[TestMessage] { msg =>
                                msg.replyTo.send(msg.v + 1)
                                    .andThen(Abort.when(msg.v == 42)(TemporaryError))
                            }
                        }
                    }
                }
                v1     <- actor.ask(TestMessage(1, _))
                v2     <- actor.ask(TestMessage(42, _))
                v3     <- actor.ask(TestMessage(2, _))
                _      <- actor.ask(TestMessage(42, _))
                _      <- actor.ask(TestMessage(42, _))
                _      <- Async.sleep(100.millis)
                _      <- actor.fiber.getResult // wait for actor to terminate (retries exhausted)
                result <- Abort.run(actor.ask(TestMessage(3, _)))
                count  <- attempts.get
            yield assert(
                count == 3 && v1 == 2 && v2 == 43 && v3 == 3 && result.isFailure
            )
        }

        "Abort" in {
            for
                events <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    Abort.recover[TemporaryError.type] { _ =>
                        events.add("Recovered from error").andThen {
                            Actor.receiveMax[Int](2) { msg =>
                                events.add(s"Processing $msg")
                            }
                        }
                    } {
                        Actor.receiveAll[Int] { msg =>
                            if msg < 0 then Abort.fail(TemporaryError)
                            else events.add(s"Received $msg")
                        }
                    }
                }
                _   <- actor.send(1)
                _   <- actor.send(-1)
                _   <- actor.send(2)
                _   <- actor.send(3)
                _   <- actor.await
                log <- events.drain
            yield assert(log == List(
                "Received 1",
                "Recovered from error",
                "Processing 2",
                "Processing 3"
            ))
        }

        "mixed" in {
            for
                attempts <- AtomicInt.init(0)
                events   <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    Abort.recover[PermanentError.type] { _ =>
                        events.add("Switched to fallback behavior").andThen {
                            Actor.receiveMax[Int](1) { msg =>
                                events.add(s"Fallback processing: $msg")
                            }
                        }
                    } {
                        Retry[TemporaryError.type](Schedule.repeat(2)) {
                            attempts.incrementAndGet.map { count =>
                                events.add(s"Attempt #$count").andThen {
                                    Actor.receiveAll[Int] { msg =>
                                        if msg == 0 then Abort.fail(PermanentError)
                                        else if msg < 0 then Abort.fail(TemporaryError)
                                        else events.add(s"Processing: $msg (attempt $count)")
                                    }
                                }
                            }
                        }
                    }
                }
                _     <- actor.send(1)
                _     <- actor.send(-1)
                _     <- actor.send(2)
                _     <- actor.send(-1)
                _     <- actor.send(3)
                _     <- actor.send(0)
                _     <- actor.send(4)
                _     <- actor.await
                count <- attempts.get
                log   <- events.drain
            yield
                assert(count == 3)
                assert(log == List(
                    "Attempt #1",
                    "Processing: 1 (attempt 1)",
                    "Attempt #2",
                    "Processing: 2 (attempt 2)",
                    "Attempt #3",
                    "Processing: 3 (attempt 3)",
                    "Switched to fallback behavior",
                    "Fallback processing: 4"
                ))
        }

    }

    "subscribe" - {
        "delivers hub events through the actor mailbox" in {
            // A Hub listener only receives events published after it is created. The actor establishes
            // its subscription asynchronously, so the publisher must wait on `subscribed` before sending,
            // otherwise early events are dropped and receiveMax(3) never completes.
            for
                hub        <- Hub.init[Int]
                acc        <- AtomicInt.init(0)
                subscribed <- Latch.init(1)
                actor <- Actor.run {
                    Actor.subscribe(hub)(identity)
                        .andThen(subscribed.release)
                        .andThen(Actor.receiveMax[Int](3)(acc.addAndGet(_).unit))
                }
                pub = Subject.init(hub)
                _ <- subscribed.await
                _ <- pub.send(1)
                _ <- pub.send(2)
                _ <- pub.send(3)
                _ <- actor.await
                v <- acc.get
            yield assert(v == 6)
        }
        "preserves single-consumer serialization across interleaved direct sends and hub events" in {
            // The accumulator lives in Var, touched only by the single consumer loop. If a second
            // consumer existed, concurrent updates would lose increments and the sum would be wrong.
            // `subscribed` gates hub publishing until the listener exists so no event is dropped.
            for
                hub        <- Hub.init[Int]
                subscribed <- Latch.init(1)
                total <- Actor.run {
                    Var.run(0) {
                        Actor.subscribe(hub)(identity)
                            .andThen(subscribed.release)
                            .andThen {
                                Actor.receiveMax[Int](200)(n => Var.update[Int](_ + n).unit).andThen(Var.use[Int](identity))
                            }
                    }
                }
                pub    = Subject.init(hub)
                direct = total.subject
                _      <- subscribed.await
                _      <- Async.foreach(1 to 100)(_ => Abort.run[Closed](direct.send(1)).unit)
                _      <- Async.foreach(1 to 100)(_ => Abort.run[Closed](pub.send(1)).unit)
                result <- total.await
            yield assert(result == 200)
        }
    }

    private case class Job(id: Int)
    private enum JobEvent derives CanEqual:
        case Started(id: Int)
        case Completed(id: Int)

    "job dispatcher with worker pool" - {
        "distributes jobs to workers and observers see start then completion for every job" in {
            val jobCount    = 12
            val workerCount = 4
            for
                hub          <- Hub.init[JobEvent]
                observed     <- Queue.Unbounded.init[JobEvent]()
                monitorReady <- Latch.init(1)
                monitor <- Actor.run {
                    Actor.subscribe(hub)(identity)
                        .andThen(monitorReady.release)
                        .andThen(Actor.receiveMax[JobEvent](jobCount * 2)(observed.add(_)))
                }
                _ <- monitorReady.await
                workers <- Kyo.foreach(0 until workerCount) { _ =>
                    Actor.run(Actor.receiveAll[Job](job => Abort.run[Closed](hub.put(JobEvent.Completed(job.id))).unit))
                }
                dispatcher <- Actor.run {
                    Var.run(0) {
                        Actor.receiveAll[Job] { job =>
                            for
                                i <- Var.use[Int](identity)
                                _ <- Var.update[Int](x => (x + 1) % workerCount)
                                _ <- Abort.run[Closed](hub.put(JobEvent.Started(job.id)))
                                _ <- workers(i).send(job)
                            yield ()
                        }
                    }
                }
                _      <- Kyo.foreach(1 to jobCount)(id => dispatcher.send(Job(id)))
                _      <- monitor.await
                events <- observed.drain
            yield
                val started   = events.collect { case JobEvent.Started(id) => id }
                val completed = events.collect { case JobEvent.Completed(id) => id }
                assert(started.toSet == (1 to jobCount).toSet)
                assert(completed.toSet == (1 to jobCount).toSet)
                assert(completed.size == jobCount)
            end for
        }
    }

    "respond" - {
        "handles a request and returns a non-Unit reply" in {
            for
                actor <- Actor.run(Actor.respond[Int, Int](x => x + 1))
                r     <- actor.ask(5)
                _     <- actor.close
            yield assert(r == 6)
        }
        "handles a Unit-reply request" in {
            for
                acc   <- AtomicInt.init(0)
                actor <- Actor.run(Actor.respond[Int, Unit](x => acc.addAndGet(x).unit))
                _     <- actor.ask(10)
                _     <- actor.close
                v     <- acc.get
            yield assert(v == 10)
        }
        "propagates a handler panic to the caller" in {
            for
                result <- Abort.run[Closed | Timeout] {
                    Async.timeout(2.seconds) {
                        Scope.run {
                            for
                                actor <- Actor.run(Actor.respond[Int, Int](_ => Abort.panic(new RuntimeException("boom"))))
                                r     <- actor.ask(1)
                            yield r
                        }
                    }
                }
            yield assert(result.isPanic)
        }
    }

    "respondLoop" - {
        "threads state across requests, replying the running total without Var" in {
            Scope.run {
                for
                    actor <- Actor.run {
                        Actor.respondLoop[Int, Int, Int](0) { (req, total) =>
                            val next = total + req
                            (next, next)
                        }
                    }
                    r1 <- actor.ask(10)
                    r2 <- actor.ask(5)
                    r3 <- actor.ask(3)
                    _  <- actor.close
                yield
                    assert(r1 == 10)
                    assert(r2 == 15)
                    assert(r3 == 18)
            }
        }
        "threads a compound state, replying a value that diverges from any single input" in {
            Scope.run {
                for
                    actor <- Actor.run {
                        // State is (runningTotal, requestCount); the reply is total minus count, which echoes
                        // neither the input nor the prior reply, so a correct reply requires threading both values.
                        Actor.respondLoop[Int, Int, (Int, Int)]((0, 0)) { (req, state) =>
                            val (total, count) = state
                            val nextTotal      = total + req
                            val nextCount      = count + 1
                            (nextTotal - nextCount, (nextTotal, nextCount))
                        }
                    }
                    r1 <- actor.ask(10)
                    r2 <- actor.ask(5)
                    r3 <- actor.ask(3)
                    _  <- actor.close
                yield
                    assert(r1 == 9)  // total 10, count 1
                    assert(r2 == 13) // total 15, count 2
                    assert(r3 == 15) // total 18, count 3
            }
        }
        "ask(req) resolves via the =:= instance method for a respondLoop actor" in {
            Scope.run {
                for
                    actor <- Actor.run(Actor.respondLoop[Int, Int, Int](0)((req, total) => (total + req, total + req)))
                    reply <- actor.ask(7)
                    _     <- actor.close
                yield assert(reply == 7)
            }
        }
    }

end ActorTest
