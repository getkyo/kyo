# kyo-actor

`kyo-actor` is a message-based concurrency primitive. Each actor owns a private mailbox, processes messages sequentially in FIFO order, and completes with either a success value or a typed error. You write actors by composing receive loops (`Actor.receiveAll`, `Actor.receiveMax`, `Actor.receiveLoop`) inside `Actor.run`, then drive them through their `subject` with `send`, `trySend`, or request-response `ask`.

`Actor.Subject[A]` is a separate, reusable abstraction. Any message sink (a `Promise`, a `Channel`, a `Queue.Unbounded`, or a custom pair of `send`/`trySend`) can be turned into a `Subject`, which is what makes `ask` work uniformly across actors and ad-hoc reply targets.

The module is cross-platform: JVM, Scala.js, and Scala Native, all from a single shared source set.

Send a message and get a reply in three lines:

```scala
import kyo.*

enum Counter:
    case Increment(replyTo: Actor.Subject[Int])

// Run a counter actor, send one message, and read the reply.
val result: Int < (Async & Scope & Abort[Closed]) =
    for
        actor <- Actor.run(Actor.receiveLoop[Counter](0) {
            case (Counter.Increment(replyTo), n) =>
                replyTo.send(n + 1).andThen(Loop.continue(n + 1))
        })
        count <- actor.ask(Counter.Increment(_))
    yield count
```

The sections below cover each building block. A full banking-account example that combines all of them appears in `Putting it together`.

## Defining an actor's behavior

An actor's behavior is a Kyo computation that polls its mailbox. The shape of the polling combinator controls when the actor stops processing and returns. All four combinators run in `Actor.Context[A]`, the effect row described at the end of this section.

### `Actor.receiveAll`: run until the mailbox closes

Run forever, processing every message, until something closes the mailbox.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val accountActor = Actor.run {
    Var.run(Account(1, 0.0)) {
        Actor.receiveAll[AccountMessage] {
            case AccountMessage.Deposit(amount, replyTo) =>
                Var.update[Account](a => a.copy(balance = a.balance + amount))
                    .map(a => replyTo.send(a.balance))
            case AccountMessage.GetBalance(replyTo) =>
                Var.use[Account](a => replyTo.send(a.balance))
            case AccountMessage.Withdraw(_, replyTo) =>
                replyTo.send(Left("Not implemented"))
        }
    }
}
```

The function's result type is discarded; `receiveAll` returns `Unit`. The actor only finishes when its mailbox is closed (via `actor.close` or when the surrounding `Scope` shuts down).

### `Actor.receiveMax`: process a fixed count, then return

Process at most `n` messages, then return. Useful when the caller knows exactly how many requests will be made.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val tenDeposits = Actor.run {
    Var.run(Account(1, 0.0)) {
        Actor.receiveMax[AccountMessage](10) {
            case AccountMessage.Deposit(amount, replyTo) =>
                Var.update[Account](a => a.copy(balance = a.balance + amount))
                    .map(a => replyTo.send(a.balance))
            case _ => ()
        }
    }
}
```

After ten messages have been processed, the actor's behavior returns and the actor completes normally.

### `Actor.receiveLoop` (no state)

Process messages until the body returns `Loop.done`. The body decides the termination condition.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val untilGetBalance = Actor.run {
    Var.run(Account(1, 0.0)) {
        Actor.receiveLoop[AccountMessage] {
            case AccountMessage.GetBalance(replyTo) =>
                Var.use[Account](a => replyTo.send(a.balance)).andThen(Loop.done)
            case AccountMessage.Deposit(amount, replyTo) =>
                Var.update[Account](a => a.copy(balance = a.balance + amount))
                    .map(a => replyTo.send(a.balance)).andThen(Loop.continue)
            case AccountMessage.Withdraw(_, replyTo) =>
                replyTo.send(Left("Closed")).andThen(Loop.continue)
        }
    }
}
```

The actor accepts deposits and withdrawals indefinitely; the first `GetBalance` shuts it down.

### `Actor.receiveLoop` with state

The state variants thread one to four values through the loop without `Var`. The body returns `Loop.continue(newState)` or `Loop.done(finalState)`, and the actor's final value is the last state.

```scala
import kyo.*

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

// Single state value: accumulate a running balance, stop when GetBalance arrives.
val accumulating: Double < (Async & Scope & Abort[Closed]) =
    Actor.run {
        Actor.receiveLoop[AccountMessage](0.0) {
            case (AccountMessage.Deposit(amount, replyTo), balance) =>
                val next = balance + amount
                replyTo.send(next).andThen(Loop.continue(next))
            case (AccountMessage.Withdraw(amount, replyTo), balance) if amount <= balance =>
                val next = balance - amount
                replyTo.send(Right(next)).andThen(Loop.continue(next))
            case (AccountMessage.Withdraw(_, replyTo), balance) =>
                replyTo.send(Left("Insufficient funds")).andThen(Loop.continue(balance))
            case (AccountMessage.GetBalance(replyTo), balance) =>
                replyTo.send(balance).andThen(Loop.done(balance))
        }
    }.map(_.await)
```

The two-, three-, and four-state overloads work the same way, with `Loop.continue(s1, s2, ...)` and the final tuple as the actor's value.

When you need both state and the discipline of `Var`, `Var.run` composed around the receive combinator is the idiomatic choice (see the composition opening). The state overloads exist for the case where the loop body is the natural shape and you do not also need ambient `Var` access from elsewhere in the behavior.

### `Actor.Context[A]`

Every receive combinator runs in `Actor.Context[A]`, an opaque alias for `Poll[A] & Env[Actor.Subject[A]] & Abort[Closed] & Scope & Async`.

```
opaque type Context[A] <: Poll[A] & Env[Actor.Subject[A]] & Abort[Closed] & Scope & Async
```

- `Poll[A]` is what the receive combinators consume.
- `Env[Actor.Subject[A]]` is what `Actor.self` and `Actor.reenqueue` read.
- `Abort[Closed]` surfaces mailbox-closure failures.
- `Scope` registers the child actor with its parent's resource lifetime.
- `Async` lets the behavior await effects between messages.

You never construct a `Context` yourself; `Actor.run` provides it. The row appears in the signatures so the type system can track exactly which capabilities a behavior uses.

> **Note:** The actor has no hidden runtime loop. `Actor.run` repeatedly does `Poll.runFirst` on your behavior, and whenever the behavior asks for another message it does a `Channel.take` from the mailbox and resumes. The behavior decides when to stop (return a value, or `Loop.done`), which is why `receiveAll`, `receiveMax`, and `receiveLoop` differ only in their termination condition.

## Starting and driving an actor

`Actor.run` spawns the actor, registers its lifecycle with the surrounding `Scope`, and returns a handle of type `Actor[E, A, B]`. The handle exposes the actor's `subject` (re-exported as `send`, `trySend`, `ask`), its `fiber`, and the lifecycle methods `await` and `close`.

### `Actor.run`

Two overloads exist: one with default mailbox capacity, one with explicit capacity.

```scala
// Default capacity (Actor.defaultCapacity).
val a1: Actor[Nothing, Int, Unit] < (Async & Scope) =
    Actor.run(Actor.receiveMax[Int](100)(_ => ()))

// Explicit capacity for backpressure tuning.
val a2: Actor[Nothing, Int, Unit] < (Async & Scope) =
    Actor.run(50)(Actor.receiveMax[Int](100)(_ => ()))
```

Both overloads return an `Actor` value scoped to the surrounding `Scope.run`. The `Scope` requirement is how parent-child cleanup works (see Supervision and lifecycle).

> **Note:** The mailbox is created with `Access.MultiProducerSingleConsumer`. Many senders can publish concurrently; only one fiber may consume, which the actor itself guarantees. Don't share the underlying channel between two actor instances.

### `Actor.defaultCapacity`: the bounded mailbox (default 128)

When you call `Actor.run(behavior)` without specifying a capacity, the mailbox size comes from `Actor.defaultCapacity`. This value is read once at module load from the JVM system property `kyo.actor.capacity.default`; if the property is absent the fallback is **128** messages.

```scala
// Set via -Dkyo.actor.capacity.default=512 on the JVM command line,
// or via java.lang.System.setProperty before kyo.Actor is touched.
val cap: Int = Actor.defaultCapacity
```

### The `Actor[E, A, B]` handle

The handle has three type parameters:

- `E`: errors that can terminate the actor if it doesn't handle them internally (via `Abort.recover`, `Retry`, etc.).
- `A`: the message type the actor accepts.
- `B`: the value the actor's behavior produces when it completes normally.

The handle exposes:

- `subject: Actor.Subject[A]` and the re-exported `send` / `trySend` / `ask`.
- `fiber: Fiber[B, Abort[Closed | E]]`, the underlying fiber for lifecycle monitoring.
- `await: B < (Async & Abort[Closed | E])`, the actor's final value.
- `close: Maybe[Seq[A]] < Sync`, mailbox shutdown.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val driveActor: Double < (Async & Abort[Closed] & Scope) =
    for
        actor <- Actor.run {
            Var.run(Account(1, 0.0)) {
                Actor.receiveMax[AccountMessage](2) {
                    case AccountMessage.Deposit(amount, replyTo) =>
                        Var.update[Account](a => a.copy(balance = a.balance + amount))
                            .map(a => replyTo.send(a.balance))
                    case _ => ()
                }
            }
        }
        _       <- actor.send(AccountMessage.Deposit(50.0, Actor.Subject.noop))
        balance <- actor.ask(AccountMessage.Deposit(25.0, _))
        _       <- actor.await
    yield balance
```

### `send`, `trySend`, `ask`

These come from `Subject`, exported on the `Actor` handle for convenience. The distinction is delivery semantics:

- `send(message)`: reliable delivery. May suspend until the mailbox has room. Aborts with `Closed` if the mailbox is shut.
- `trySend(message)`: non-blocking. Returns `false` if the mailbox is at capacity, never suspends.
- `ask(f)`: request-response. Threads a fresh one-shot reply subject into the message; suspends until the reply arrives.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val accountUse: Double < (Async & Abort[Closed] & Scope) =
    for
        actor    <- Actor.run(Actor.receiveAll[AccountMessage](_ => ()))
        accepted <- actor.trySend(AccountMessage.Deposit(10.0, Actor.Subject.noop))
        _        <- actor.send(AccountMessage.Deposit(20.0, Actor.Subject.noop))
        balance  <- actor.ask(AccountMessage.GetBalance(_))
    yield balance
```

When you need `send` to fail rather than suspend, use `trySend` and check the boolean. When you don't need a reply, use `send` with `Actor.Subject.noop`. When you do need a reply, use `ask`.

## Talking to other actors and to yourself

Inside a behavior, the actor has access to its own subject via `Env[Actor.Subject[A]]`. This is what lets one actor hand its address to another (for replies) or send itself follow-up work (for state machines).

### `Actor.self` and `Actor.selfWith`

`Actor.self[A]` returns the current actor's `Actor.Subject[A]` from the environment. `Actor.selfWith[A](f)` applies a function to the self-subject in one call.

```scala
case class Ping(replyTo: Actor.Subject[Pong])
case class Pong(replyTo: Actor.Subject[Ping])

val pingPong =
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
    yield result
```

The pong actor doesn't know who sent it a `Ping`; the sender embeds its own `Actor.Subject[Pong]` in `ping.replyTo`, and the pong actor uses `Actor.self[Ping]` to embed its own address in the `Pong` it sends back.

> **Note:** `Actor.self[A]` and `Actor.reenqueue[A]` only type-check inside an `Actor.run` body whose message type is `A`. The compiler enforces this through the missing `Context[A]` capability: calling them outside an actor with the matching `A` produces a missing-effect error.

### `Actor.reenqueue`

`Actor.reenqueue(msg)` sends a message to the current actor's own mailbox.

```scala
sealed trait Job
case object Tick               extends Job
case class Schedule(work: Int) extends Job

val scheduler = Actor.run {
    Actor.receiveAll[Job] {
        case Tick =>
            // Process the tick, then re-arm.
            Actor.reenqueue[Job](Tick)
        case Schedule(work) =>
            // Run work, then push another Tick.
            Actor.reenqueue[Job](Tick)
    }
}
```

> **Note:** Re-enqueued messages go to the back of the FIFO queue, not the front. If the mailbox already contains 50 messages, the re-enqueued one is processed after all of them.

### `Subject#ask`: a one-shot reply channel per request

`ask` is request-response. It takes a function `Actor.Subject[B] => A`, builds a fresh `Promise[B, Any]`, wraps that promise in a one-shot `Actor.Subject[B]`, and sends `f(replySubject)` to the target. It returns the value the recipient sends to that reply subject.

```scala
import kyo.*

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val accountQuery: Double < (Async & Abort[Closed] & Scope) =
    for
        actor   <- Actor.run(Actor.receiveAll[AccountMessage](_ => ()))
        balance <- actor.ask(AccountMessage.GetBalance(_))
        // Equivalent without ask:
        promise <- Promise.init[Double, Any]
        _       <- actor.send(AccountMessage.GetBalance(Actor.Subject.init(promise)))
        manual  <- promise.get
    yield balance
```

`ask` is just the manual form, packaged. The recipient sees no difference between `actor.ask(f)` and a hand-rolled `Promise` plus `send`.

> **Note:** The reply subject `ask` creates is single-shot. The recipient must send exactly one message to `replyTo`. A second message aborts with `Closed` (see `Actor.Subject.init(promise)` below).

## Subjects: anything that receives messages

`Actor.Subject[A]` is the interface every message recipient implements. It has three methods:

```scala
import kyo.*

trait SubjectExample[A]:
    def send(message: A): Unit < (Async & Abort[Closed])
    def trySend(message: A): Boolean < (Sync & Abort[Closed])
    def ask[B](f: Actor.Subject[B] => A): B < (Async & Abort[Closed])
end SubjectExample
```

Actors are subjects (the `Actor` handle re-exports its subject), but so are promises, channels, queues, and any custom sink you construct with `Actor.Subject.init`. This is what lets `ask` work uniformly: the reply target doesn't have to be an actor.

### `Actor.Subject.noop`

A subject that discards every message. `trySend` always returns `false`.

```scala
import kyo.*

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

// Fire-and-forget into a void:
val drop: Unit < (Async & Abort[Closed]) =
    Actor.Subject.noop[AccountMessage].send(AccountMessage.Deposit(10.0, Actor.Subject.noop))
```

Useful when an actor's protocol requires a `replyTo` but you don't care about the reply, or as a placeholder during testing.

### `Actor.Subject.init(promise)`

Wraps a `Promise[A, Any]` as a one-shot subject. The first message completes the promise with a successful `Result`. Subsequent messages abort with `Closed`.

```scala
val singleShot: Double < (Async & Abort[Closed]) =
    for
        promise <- Promise.init[Double, Any]
        subject = Actor.Subject.init(promise)
        _ <- subject.send(42.0)
        // subject.send(99.0) here would Abort[Closed]
        value <- promise.get
    yield value
```

> **Caution:** Only the first send succeeds. If you ask a recipient to "reply once," but it tries to reply twice, the second send fails. This is the contract that makes `Subject#ask` safe.

### `Actor.Subject.init(channel)`

Wraps a bounded `Channel[A]` as a subject. `send` uses `channel.put` (suspends under backpressure); `trySend` uses `channel.offer` (returns `false` if the channel is full).

```scala
import kyo.*

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val buffered: Boolean < (Async & Abort[Closed] & Scope) =
    for
        channel <- Channel.init[AccountMessage](capacity = 8)
        subject = Actor.Subject.init(channel)
        _  <- subject.send(AccountMessage.Deposit(1.0, Actor.Subject.noop))
        ok <- subject.trySend(AccountMessage.Deposit(2.0, Actor.Subject.noop))
    yield ok
```

### `Actor.Subject.init(queue)`

Wraps an unbounded `Queue.Unbounded[A]` as a subject. Both `send` and `trySend` use `queue.add`, and `trySend` always returns `true`.

```scala
import kyo.*

case class Transaction(id: Int, kind: String, amount: Double, balance: Double)

val sink: Boolean < (Async & Abort[Closed] & Scope) =
    for
        queue <- Queue.Unbounded.init[Transaction]()
        subject = Actor.Subject.init(queue)
        _  <- subject.send(Transaction(1, "deposit", 10.0, 10.0))
        ok <- subject.trySend(Transaction(1, "deposit", 20.0, 30.0))
    yield ok
```

`Actor.Subject.init(channel)` and `Actor.Subject.init(queue)` share a constructor name but have different backpressure: the channel-backed subject can reject (`trySend` may return `false`) and suspend (`send` may block on `put`); the queue-backed subject accepts everything and never suspends. Pick by whether you want producer flow control (channel) or audit-log shape (queue).

### `Actor.Subject.init(send, trySend)`

The low-level constructor. You supply the two operations directly.

```scala
import java.lang.System as J
import kyo.*

case class Transaction(id: Int, kind: String, amount: Double, balance: Double)

val logger: Actor.Subject[Transaction] =
    Actor.Subject.init(
        send = tx => Sync.defer(J.err.println(s"send: $tx")),
        trySend = tx => Sync.defer(J.err.println(s"trySend: $tx")).andThen(true)
    )
```

Use this when none of the wrapper-style constructors fit (e.g. you want to forward to an arbitrary sink, throttle, or log).

## Supervision and lifecycle

Actors don't have explicit supervision strategies. Supervision falls out of two things: parent-child `Scope` inheritance, and the fact that any Kyo handler (`Retry`, `Abort.recover`, `Var.run`) can wrap an actor's behavior without changing how the actor processes messages.

### Parent-child scope inheritance

Because `Actor.run` returns `Actor[E, A, B] < (Scope & Async & S)`, every actor registers itself with the enclosing `Scope`. When a child actor is launched from inside a parent actor's behavior, the child's scope is the parent's scope. When the parent completes (normally or via failure), every child shuts down.

```scala
import kyo.*

val supervised: String < (Async & Scope & Abort[Closed]) =
    for
        cleanedUp <- Latch.init(2)
        events    <- Queue.Unbounded.init[String]()
        parent <- Actor.run {
            for
                child1 <- Actor.run {
                    Scope.ensure(events.add("child1 cleaned up").andThen(cleanedUp.release))
                        .andThen(Actor.receiveAll[Int](v => events.add(s"child1 got $v")))
                }
                child2 <- Actor.run {
                    Scope.ensure(events.add("child2 cleaned up").andThen(cleanedUp.release))
                        .andThen(Actor.receiveAll[Int](v => events.add(s"child2 got $v")))
                }
                _ <- child1.send(1)
                _ <- child2.send(2)
            yield "parent complete"
        }
        result <- parent.await
        _      <- cleanedUp.await
    yield result
```

When `parent` finishes (here, the `yield "parent complete"` runs as soon as its body completes; the parent has no receive loop), both `Scope.ensure` finalizers fire. The latch is released twice.

The same shape works when the parent fails: any `Abort.fail` that escapes the parent triggers child shutdown.

### Closing an actor

`actor.close` shuts the mailbox and returns the unprocessed messages.

```scala
import kyo.*

val drain: Maybe[Seq[Int]] < (Async & Scope & Abort[Closed]) =
    for
        actor    <- Actor.run(Actor.receiveAll[Int](_ => Async.sleep(50.millis)))
        _        <- actor.send(1)
        _        <- actor.send(2)
        leftover <- actor.close
    yield leftover
```

> **Caution:** `close` does NOT interrupt the message currently being processed. The actor finishes its current message, then completes. If you need to abort in-flight work, `close` is not the right tool; cancel the actor's `fiber` instead.

### Supervision is handler composition, not a strategy API

The actor loop only halts when its behavior stops polling. That means any Kyo handler placed around the behavior controls how the actor reacts to failures and what state it carries between iterations. Three patterns cover most supervision needs.

**Retry on transient errors.** Wrap the behavior in `Retry[E]`; when the behavior aborts with `E`, `Retry` resurrects it and the actor keeps polling the same mailbox.

```scala
case object TemporaryError
case class TestMessage(v: Int, replyTo: Actor.Subject[Int])

val retrying =
    Actor.run {
        Retry[TemporaryError.type](Schedule.repeat(2)) {
            Actor.receiveAll[TestMessage] { msg =>
                msg.replyTo.send(msg.v + 1)
                    .andThen(Abort.when(msg.v == 42)(TemporaryError))
            }
        }
    }
```

If a message of value 42 aborts the loop, `Retry` re-runs the receive block; the next message picks up from the mailbox. When the retry budget runs out (`Schedule.repeat(2)` = three attempts total), the actor terminates and `await` surfaces the failure.

**Recover with a fallback behavior.** Wrap with `Abort.recover[E]`; the recovery branch runs on the same mailbox.

```scala
import kyo.*

case object TemporaryError

val recovering =
    Actor.run {
        Abort.recover[TemporaryError.type] { _ =>
            // Fallback behavior, same mailbox.
            Actor.receiveMax[Int](2) { msg => /* log and continue */ () }
        } {
            Actor.receiveAll[Int] { msg =>
                if msg < 0 then Abort.fail(TemporaryError)
                else /* normal processing */ ()
            }
        }
    }
```

After the first negative message, the actor switches from the primary to the fallback behavior. The mailbox is the same; only the consumer changes.

**Per-actor state via `Var.run`.** Wrap with `Var.run(initial)` to give the behavior mutable state without exposing it outside the actor.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

val stateful =
    Actor.run {
        Var.run(Account(1, 0.0)) {
            Actor.receiveMax[AccountMessage](10) {
                case AccountMessage.Deposit(amount, replyTo) =>
                    Var.update[Account](a => a.copy(balance = a.balance + amount))
                        .map(a => replyTo.send(a.balance))
                case _ => ()
            }
        }
    }
```

Any state that's specific to one actor instance lives inside its `Var.run`. The state is isolated from other actors and from the surrounding effect row.

Combine the three: `Abort.recover` outside, `Retry` inside, `Var.run` innermost. That gives you "retry transient errors, escalate to a fallback behavior on permanent errors, keep per-actor state across both."

```scala
import kyo.*

case object TemporaryError
case object PermanentError

val supervisedActor =
    Actor.run {
        Abort.recover[PermanentError.type] { _ =>
            Actor.receiveMax[Int](1) { _ => /* fallback */ () }
        } {
            Retry[TemporaryError.type](Schedule.repeat(2)) {
                Actor.receiveAll[Int] { msg =>
                    if msg == 0 then Abort.fail(PermanentError)
                    else if msg < 0 then Abort.fail(TemporaryError)
                    else /* process */ ()
                }
            }
        }
    }
```

The actor's behavior is just a Kyo computation; handlers compose around it the same way they would around any other Kyo program. There's no separate "supervisor" type because there doesn't need to be one.

## Publish/Subscribe

Actors can participate in a fan-out topology by combining two building blocks:

- `Subject.init(hub)` creates a publish-side subject that forwards every `send` or `trySend` call into the hub, broadcasting to all active listeners.
- `Actor.subscribe(hub)(adapt)` subscribes the current actor to a hub, funneling each event through `adapt` into the actor's own mailbox as an ordinary message.

The key property is that subscription preserves the single-consumer invariant. The pump fiber created by `Actor.subscribe` is a producer only: it enqueues events into the actor's mailbox. The actor remains the sole consumer, so all mailbox messages (both direct sends and hub-delivered events) are processed sequentially by the same loop, with no concurrent access to state.

```scala
import kyo.*
import kyo.Actor.Subject

// Log levels for the hub.
enum LogLevel derives CanEqual:
    case Info
    case Warn
    case Error
end LogLevel

case class LogEvent(level: LogLevel, message: String)

// A subscriber actor that prints log events.
// The latch signals when the subscription is ready, so no hub
// event is published before the listener exists.
val pubSubExample: Unit < (Async & Scope & Abort[Closed]) =
    for
        hub <- Hub.init[LogEvent]
        publisher = Subject.init(hub) // publish bridge: send/trySend -> hub.put/offer
        subscribed <- Latch.init(1)
        logger <- Actor.run {
            Actor.subscribe(hub)(identity) // funnels hub events into this actor's mailbox
                .andThen(subscribed.release)
                .andThen(Actor.receiveMax[LogEvent](3) { event =>
                    Sync.defer(()) // handle the event (print, record, etc.)
                })
        }
        _ <- subscribed.await // wait until the listener exists before publishing
        _ <- publisher.send(LogEvent(LogLevel.Info, "server started"))
        _ <- publisher.send(LogEvent(LogLevel.Warn, "high memory usage"))
        _ <- publisher.send(LogEvent(LogLevel.Error, "disk full"))
        _ <- logger.await
    yield ()
```

`Subject.init(hub)` and `Actor.subscribe` are symmetric: the former is the write side (any code can hold a `Subject[E]` and publish without knowing who is listening), and the latter is the read side (the actor registers once and receives all subsequent events through its normal mailbox). Multiple actors can each call `Actor.subscribe` on the same hub, and each will receive every event independently.

The `adapt` function in `Actor.subscribe(hub)(adapt)` converts the hub's event type to the actor's message type. When they are the same, use `identity`. When the actor handles a sealed union and hub events are one variant, use a case-class constructor: `Actor.subscribe(hub)(MyMessage.HubEvent(_))`.

## Topic: push-based publish/subscribe

`Topic[A]` is a push-based fan-out primitive. Publishing a value delivers it directly into each subscriber's mailbox (or other sink). There are two constructors with different ordering guarantees:

| Constructor | Fan-out style | Order guarantee |
|---|---|---|
| `Topic.init[A]` | Concurrent: each subscriber is notified in a separate async fiber | Per-subscriber FIFO; no cross-subscriber total order under concurrent publishers |
| `Topic.linearized[A]` | Sequential through one actor mailbox | Total order: every subscriber sees publishes in the same sequence |

**Topic vs Hub.** `Hub` is a pull primitive: listeners drain buffered events at their own rate. `Topic` is push: it calls `send` on each subscriber sink and awaits delivery. Use `Topic` when subscribers are actors whose mailboxes are the natural buffer. Use `Hub` when you need rate-isolated streaming consumers that drain independently.

### `Topic.init`: plain concurrent fan-out

```scala
import kyo.*
import kyo.Actor.Subject

enum Msg derives CanEqual:
    case Direct(n: Int)
    case Event(text: String)
end Msg

val topicInitExample: Set[String] < (Async & Scope & Abort[Closed]) =
    for
        topic <- Topic.init[String]
        seen  <- Queue.Unbounded.init[String]()
        actor <- Actor.run(Actor.receiveMax[Msg](2) {
            case Msg.Direct(n) => seen.add(s"direct:$n")
            case Msg.Event(t)  => seen.add(s"event:$t")
        })
        _   <- topic.subscribe(actor.subject.contramap(Msg.Event(_)))
        _   <- topic.publish("hi")
        _   <- actor.subject.send(Msg.Direct(1))
        _   <- actor.await
        out <- seen.drain
    yield out.toSet
```

`actor.subject.contramap(Msg.Event(_))` adapts the `Subject[Msg]` into a `Subject[String]`. The topic holds a `Subject[String]` and calls `send` on it; the adapter maps each `String` to a `Msg.Event` before forwarding to the actor's mailbox. Direct sends to `actor.subject` and topic-delivered events share the same mailbox, so the actor processes them sequentially with no concurrent state access.

`topic.subscribe` completes (is awaited) before control returns, so a subscriber added before a `publish` call is guaranteed to receive that value. No readiness latch is needed.

`Scope` auto-unsubscribes: when the enclosing scope closes, the subscription is removed and subsequent publishes skip that sink. Subscribers whose `send` fails with `Closed` are pruned automatically on the next publish.

### `Topic.linearized`: total order across subscribers

`Topic.linearized` serializes all operations (publish, subscribe, unsubscribe) through one actor mailbox, so every subscriber observes events in the same sequence even under concurrent publishers. Use it when cross-subscriber ordering is required; accept the cost of one actor hop per publish.

```scala
import kyo.*
import kyo.Actor.Subject

val topicLinearizedExample: Boolean < (Async & Scope & Abort[Closed]) =
    for
        topic <- Topic.linearized[Int]
        a     <- Channel.init[Int](64)
        b     <- Channel.init[Int](64)
        _     <- topic.subscribe(Subject.init(a))
        _     <- topic.subscribe(Subject.init(b))
        _     <- Async.foreach(1 to 10)(topic.publish) // concurrent publishers
        as    <- a.drainUpTo(10)
        bs    <- b.drainUpTo(10)
    yield as == bs && as.size == 10 // both subscribers agree on the same total order
```

The `as == bs` equality is exactly the property `Topic.init` does not guarantee: under concurrent publishers, two `init` subscribers may observe the same values in different orders, so swapping `Topic.linearized` for `Topic.init` here would no longer be guaranteed to hold.

`Topic.linearized` requires `Scope & Async` because it spawns an actor. The actor's lifetime is tied to the enclosing scope: when the scope closes, the actor shuts down and the topic closes with it. Calling `topic.close` explicitly also shuts the backing actor; in-flight callers complete (with `Closed`) rather than being stranded, because every operation uses the strand-safe `actor.ask` path.

### `Subject.contramap`: subscribing an actor with a sum message type

An actor that handles several message kinds with a sum type subscribes to a `Topic[E]` by adapting its `Subject[Msg]` with `contramap`:

```scala
import kyo.*
import kyo.Actor.Subject

enum AppMsg derives CanEqual:
    case UserAction(payload: String)
    case SystemEvent(text: String)
end AppMsg

val contramapExample: Set[String] < (Async & Scope & Abort[Closed]) =
    for
        topic <- Topic.init[String]
        log   <- Queue.Unbounded.init[String]()
        actor <- Actor.run(Actor.receiveMax[AppMsg](3) {
            case AppMsg.UserAction(p)  => log.add(s"action:$p")
            case AppMsg.SystemEvent(t) => log.add(s"event:$t")
        })
        _       <- topic.subscribe(actor.subject.contramap(AppMsg.SystemEvent(_)))
        _       <- topic.publish("startup")
        _       <- topic.publish("ready")
        _       <- actor.subject.send(AppMsg.UserAction("click"))
        _       <- actor.await
        entries <- log.drain
    yield entries.toSet
```

The actor receives both topic events (as `AppMsg.SystemEvent`) and direct sends (as `AppMsg.UserAction`) through a single serialized mailbox. There is no separate fan-in fiber; `contramap` just wraps the `send`/`trySend` calls with the mapping function.

### Topic API summary

| Method | Effects | Description |
|---|---|---|
| `publish(value)` | `Async & Abort[Closed]` | Delivers to all subscribers; suspends until each delivery completes; prunes closed sinks |
| `subscribe(subscriber)` | `Async & Abort[Closed] & Scope` | Adds a subscriber; `Scope` auto-removes it on close |
| `subscriberCount` | `Async & Abort[Closed]` | Current subscriber count |
| `close` | `Sync` | Closes the topic; subsequent publish/subscribe fail with `Closed` |

## Request/Reply

`Actor.respond[Req, Resp](handler)` creates an actor whose sole job is to map requests to replies. The framework automatically sends the handler's return value back to the caller, so the actor can never accidentally forget to reply.

```scala
import kyo.*

// A simple echo actor that doubles integers.
val requestReplyExample: Int < (Async & Scope & Abort[Closed]) =
    for
        doubler <- Actor.run(Actor.respond[Int, Int](n => n * 2))
        result  <- doubler.ask(21) // strand-safe: never strands the caller
        _       <- doubler.close
    yield result
```

The `actor.ask(request)` extension on a `respond` actor is lifecycle-aware. It completes with one of four outcomes:

- The reply the handler produced (success).
- `Abort[Closed]` if the actor shut down before replying.
- `Abort[E]` if the handler failed with a typed error.
- A panic if the handler panicked.

The caller is never stranded: `ask` races the reply promise against the actor's termination signal, so it always completes even if the actor exits mid-request.

For per-request errors, model them in the reply type (for example `Result[E, Resp]` or `Either[E, Resp]`) rather than aborting. A handler that calls `Abort.fail` terminates the actor, surfacing the failure to every in-flight caller.

```scala
import kyo.*

// Request/reply with a typed error carried in the reply.
case class DivRequest(a: Int, b: Int)
enum DivResult derives CanEqual:
    case Ok(value: Int)
    case DivByZero

val safeDiv: DivResult < (Async & Scope & Abort[Closed]) =
    for
        actor <- Actor.run(Actor.respond[DivRequest, DivResult] { req =>
            if req.b == 0 then DivResult.DivByZero
            else DivResult.Ok(req.a / req.b)
        })
        result <- actor.ask(DivRequest(10, 2))
        _      <- actor.close
    yield result
```

## Putting it together

The example below combines `Actor.run`, `Var.run`, `Actor.receiveAll`, `send`, `ask`, and `close` in a single banking-account actor. It is the kind of program you would write after reading the sections above.

```scala
import kyo.*

case class Account(id: Int, balance: Double)

enum AccountMessage:
    case Deposit(amount: Double, replyTo: Actor.Subject[Double])
    case Withdraw(amount: Double, replyTo: Actor.Subject[Either[String, Double]])
    case GetBalance(replyTo: Actor.Subject[Double])
end AccountMessage

// Start an actor that owns Account state and serves messages.
val program: Double < (Async & Scope & Abort[Closed]) =
    for
        account <- Actor.run {
            Var.run(Account(1, 0.0)) {
                Actor.receiveAll[AccountMessage] {
                    case AccountMessage.Deposit(amount, replyTo) =>
                        Var.update[Account](a => a.copy(balance = a.balance + amount))
                            .map(a => replyTo.send(a.balance))
                    case AccountMessage.Withdraw(amount, replyTo) =>
                        Var.use[Account] { a =>
                            if a.balance < amount then replyTo.send(Left("Insufficient funds"))
                            else
                                Var.update[Account](_.copy(balance = a.balance - amount))
                                    .map(a => replyTo.send(Right(a.balance)))
                        }
                    case AccountMessage.GetBalance(replyTo) =>
                        Var.use[Account](a => replyTo.send(a.balance))
                }
            }
        }
        // Request-response: ask threads a one-shot reply Subject into the message.
        _      <- account.ask(AccountMessage.Deposit(100.0, _))
        result <- account.ask(AccountMessage.Withdraw(40.0, _))
        // Fire-and-forget: send returns once the mailbox accepts the message.
        _ <- account.send(AccountMessage.Deposit(10.0, Actor.Subject.noop))
        // Close: stop accepting new messages, in-flight processing finishes.
        _ <- account.close
        // Await: get the actor's final value (or its failure).
        balance <- account.ask(AccountMessage.GetBalance(_))
    yield balance
```

## Job dispatcher with a worker pool

This example shows when to use a Hub versus point-to-point `send`. The Hub is for events that every observer must see (started, completed). The worker `send` is for work distribution: each job goes to exactly one worker via round-robin, so work is not fanned out.

```scala
import kyo.*
import kyo.Actor.Subject

case class Job(id: Int) derives CanEqual

enum JobEvent derives CanEqual:
    case Started(id: Int)
    case Completed(id: Int)

// A minimal dispatcher: a small worker pool (two workers), one monitor, three jobs.
// Round-robin index lives in Var; there is no Var.getAndUpdate,
// so Var.use reads the index and Var.update advances it.
val dispatcherExample: Unit < (Async & Scope & Abort[Closed]) =
    for
        hub          <- Hub.init[JobEvent]
        monitorReady <- Latch.init(1)
        // Monitor subscribes to the hub and collects all events.
        monitor <- Actor.run {
            Actor.subscribe(hub)(identity)
                .andThen(monitorReady.release)
                .andThen(Actor.receiveMax[JobEvent](6) { _ => () }) // 3 started + 3 completed
        }
        _ <- monitorReady.await // ensure listener exists before any publish
        workers <- Kyo.foreach(0 until 2) { _ =>
            Actor.run {
                // Each worker processes jobs and publishes completion to the shared hub.
                Actor.receiveAll[Job] { job =>
                    Abort.run[Closed](hub.put(JobEvent.Completed(job.id))).unit
                }
            }
        }
        workerCount = workers.size
        dispatcher <- Actor.run {
            Var.run(0) {
                Actor.receiveAll[Job] { job =>
                    for
                        i <- Var.use[Int](identity)                               // read current index
                        _ <- Var.update[Int](x => (x + 1) % workerCount)          // advance round-robin
                        _ <- Abort.run[Closed](hub.put(JobEvent.Started(job.id))) // fan-out: observers see start
                        _ <- workers(i).send(job)                                 // point-to-point: one worker gets the job
                    yield ()
                }
            }
        }
        _ <- Kyo.foreach(1 to 3)(id => dispatcher.send(Job(id)))
        _ <- monitor.await
        // Close the dispatcher first so every dispatched job reaches a worker
        // before the workers stop, then close the workers.
        _ <- dispatcher.close
        _ <- Kyo.foreachDiscard(workers)(_.close)
    yield ()
```

The split between Hub and `send` reflects two different communication shapes: a Hub delivers each event to all current listeners (fan-out, for observability), while a direct `send` to a worker delivers each job to exactly one recipient (point-to-point, for work distribution). Using a Hub for both would duplicate work across workers; using only point-to-point would leave the monitor blind to events.
