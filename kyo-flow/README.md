# kyo-flow

Durable workflow engine for Kyo. Workflows are defined as composable, type-safe plans that the engine persists, coordinates across multiple executors, and recovers automatically after crashes.

A `Flow` is a plan, not an execution. You describe what should happen (inputs to wait for, values to compute, side effects to perform, branches to take, collections to fan out over) and the engine handles persistence, crash recovery, and coordination. Each node in the plan carries a name, and that name is its durable key: the field its value is written under, the mark that says it finished, the row that says it is waiting. Every node is recorded before the next begins, so a crash means another executor claims the execution, replays the plan from the start, and skips every node whose record it finds. Side effects in step bodies must be idempotent, because the one node in flight when a process died is the one node that runs twice.

Two consequences of that model shape the whole API. First, waiting is not a status: an execution waiting for an input or serving out a durable sleep is still `Running`, and what it is waiting FOR lives on per-node wait rows, because a `race` of an input against a sleep waits for both at once. Second, anything that cannot happen instantly is a request rather than an act: cancelling an execution runs its compensation handlers first, so `cancel` answers with an outcome saying what the ask did and the terminal `Cancelled` lands at the end of the unwind.

The engine coordinates multiple executors via time-limited claim leases, supports compensation handlers for saga-style rollback, provides retry and timeout per node attempt, emits a full event audit trail, and exposes an auto-generated REST API. Workflow structure can be rendered as Mermaid, DOT, BPMN, ELK, or JSON diagrams. The module cross-builds to JVM, JavaScript, Scala Native, and Wasm.

## Getting started

Add the dependency to your `build.sbt`:

```
libraryDependencies += "io.getkyo" %% "kyo-flow" % "<latest version>"
```

<!-- doctest:setup
```scala
import kyo.*

case class Item(sku: String, qty: Int, price: Int) derives Schema
case class Order(id: String, customer: String, items: Seq[Item]) derives Schema
case class Approval(approved: Boolean, by: String) derives Schema

def reserveStock(item: Item): String < Async                                   = "resv-1"
def releaseStock(reservationId: String): Unit < (Async & Abort[FlowException]) = ()
def chargeCard(orderId: String, cents: Int): String < Async                    = "charge-1"
def refundCharge(chargeId: String): Unit < (Async & Abort[FlowException])      = ()
def ship(order: Order): Unit < Async                                           = ()
def notifyCustomer(orderId: String, text: String): Unit < Async                = ()
def carrierStatus(orderId: String): String < Async                             = "delivered"

val sampleOrder: Order = Order("o-1017", "ada", Seq(Item("widget", 3, 400), Item("gasket", 1, 250)))

val fulfilmentFlow: Flow[Any, Any, Any] = Flow.init("fulfilment")
val shippingFlow: Flow[Any, Any, Any]   = Flow.init("shipping")
val invoiceFlow: Flow[Any, Any, Any]    = Flow.init("invoice")
```
-->

Everything in this README works on one order-fulfilment workflow: an `Order` carrying line `Item`s, stock to reserve, a card to charge, an `Approval` to wait for when the total is large, and a shipment at the end. Here is the shape of it:

```scala
val fulfilment =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .outputCompensated("charge")(ctx => chargeCard(ctx.order.id, ctx.total))(ctx => refundCharge(ctx.charge))
        .step("ship")(ctx => ship(ctx.order))
```

That value is a description. Nothing has run, nothing has been charged, and nothing has been written. Registering it with an engine and starting an execution is what makes it happen, and every one of those four nodes is recorded before the next one starts. The `charge` node also carries a second function, `ctx => refundCharge(ctx.charge)`: a compensation handler that undoes the charge if a later node fails. See [Undoing work when a later step fails](#undoing-work-when-a-later-step-fails).

## Describing a workflow

Reading a flow is reading a chain of named nodes. The names are the part to care about: each one is a durable key, so what you call a node decides what its field is called in the store, what its completion mark is, and what replay skips when it comes back. The constructs below differ in what they contribute to that record, which is why there are several rather than one.

### Inputs

An input is a value the workflow cannot compute for itself and must be handed from outside. The execution waits at that node until the value is delivered, and its type becomes part of the flow's `In` parameter, so a caller that does not supply it does not compile.

```scala
val awaitingOrder =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
```

`Flow.init(name).input[V](inputName)` has a shorthand for the common case where the workflow and its first input share a name:

```scala
val orderShorthand = Flow.input[Order]("order")
```

> **Note:** `Flow.input[V](name)` is shorthand for `Flow.init(name).input[V](name)`. Its single argument names BOTH the workflow and the input, so a caller who wants them named differently uses the longer form, `Flow.init(workflowName).input[V](inputName)`.

Input types need a `Schema` instance, since the delivered value is persisted. In production the value arrives through the engine's `signal` API or the HTTP endpoint `POST /api/v1/executions/{eid}/signal/order`. For a test, `runLocal` pre-populates them:

```scala
val flow   = Flow.init("fulfilment").input[Order]("order")
val result = Flow.runLocal(flow, "order" ~ Order("o-1017", "ada", Seq(Item("widget", 3, 400))))
```

The `~` operator builds a typed record field: `"order" ~ someOrder` has type `Record["order" ~ Order]`. Several fields combine with `&`: `"order" ~ someOrder & "priority" ~ true`.

> **Caution:** `Flow.runLocal` polls until the execution reaches a terminal status, with no bound. A flow with an input that the supplied record does not carry never terminalises, so `runLocal` never returns. Supply every declared input, or drive the flow through an engine instead.

> **Note:** `signal` requires an EXACT type match against the declared input, not a subtype or assignability check: the engine tests `Tag[V] =:= inputMeta.tag`. Signalling with a type narrower, wider, or otherwise different from the one the `input` node declared is refused, even in a case that would normally widen or narrow safely.

### Outputs

An output is a value the flow computes and keeps. It is persisted under its name and is then readable by every node after it, by that name, with its real type.

```scala
val priced =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("lines")(ctx => ctx.order.items.size)
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .output("summary")(ctx => s"${ctx.lines} line(s), ${ctx.total} cents")
```

`ctx.total` is statically an `Int`, and asking for a field no earlier node produced is a compile error. That works through Kyo's `Record` type, which tracks fields as an intersection of `Name ~ Value` pairs, and each node that produces a value adds one:

```scala
// After .output("lines"), the context type includes "lines" ~ Int
// After .output("total"), it includes "lines" ~ Int & "total" ~ Int
// After .output("summary"), it includes all three fields
```

The three type parameters on `Flow[In, Out, S]` carry that bookkeeping: `In` is the intersection of inputs the flow requires, `Out` is the intersection of values it produces, and `S` is the union of effects its node bodies use.

### Steps

A step is work with no value worth keeping: sending the shipment, writing an audit row, calling a notification service. It contributes a completion event rather than a field, and replay skips it once that event exists.

```scala
val shipping =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .step("ship")(ctx => ship(ctx.order))
        .step("notify")(ctx => notifyCustomer(ctx.order.id, s"shipped, ${ctx.total} cents"))
```

Both `output` and `step` run their body exactly where you put it in the chain. Reach for `output` when a later node needs the result, and for `step` when nothing does; a step that returns a value you then need again is a value the store never kept.

### Sleep

A sleep is a wait the store owns. The execution releases its in-memory state and comes back when the deadline passes, so a process restart in the middle costs nothing: the engine reads the original deadline and resumes at it.

```scala
val cooling =
    Flow.init("fulfilment")
        .input[Order]("order")
        .step("ship")(ctx => ship(ctx.order))
        .sleep("delivery-window", 48.hours)
        .step("ask-for-review")(ctx => notifyCustomer(ctx.order.id, "how did we do?"))
```

> **Note:** `sleep` declares neither `timeout` nor `retry`, unlike every other node builder, because it can honour neither. Bounding a wait is what racing the sleep against the thing being waited for is for; see [Running branches at the same time](#running-branches-at-the-same-time).

A durable sleep, a wait for an input, and a lost claim are all suspensions rather than failures. No compensation handler fires, the status stays `Running`, and what the execution is waiting for lives on its wait rows.

### Per-node knobs

Every node that runs a body you supplied takes `description`, `timeout`, `retry`, and `tags`: `output`, `step`, `dispatch`, `loop`, `loopOn`, and `foreach`. `input` and `sleep` take `description` and `tags` only, because neither runs a body, and a bound or a retry has nothing to act on without one. `timeout` bounds one ATTEMPT rather than the node's whole lifetime, and `retry` re-asks an attempt that failed for an accidental reason.

```scala
val charging =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .output(
            "charge",
            description = "authorise the card",
            timeout = 10.seconds,
            retry = Maybe(Schedule.exponentialBackoff(1.second, 2.0, 1.minute))
        )(ctx => chargeCard(ctx.order.id, ctx.total))
```

Each attempt is timed independently, and when the schedule exhausts, the last error propagates. Those knobs are also a value, `Flow.Meta(description, tags, timeout, retry, version)`, which is how the stateful `loop` and `loopOn` overloads accept them: Scala lets only one overload of a name define default arguments, and the stateless overload is the one that does.

> **Caution:** a `timeout` abandons the attempt rather than waiting for it. On the JVM and Native a body blocked in non-suspending code can still be running when the next attempt starts, so node bodies must be idempotent under overlap as well as under replay. See [Coordinating multiple executors](#coordinating-multiple-executors).

### Subflows and sequencing

A subflow embeds a whole flow inside another. The input mapper turns the parent's context into the child's input record, which is recorded under the subflow's path at entry, and the child's output record lands under the subflow's name.

```scala
val payment =
    Flow.init("payment")
        .input[String]("orderId")
        .input[Int]("amountCents")
        .output("chargeId")(ctx => chargeCard(ctx.orderId, ctx.amountCents))

val withPayment =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .subflow("payment", payment)(ctx => "orderId" ~ ctx.order.id & "amountCents" ~ ctx.total)
        .step("ship")(ctx => ship(ctx.order))
```

The child's nodes are keyed under the instance path, so `payment`'s `chargeId` is durably `payment~chargeId`. Embedding the same child twice gives two sets of fields under two paths rather than one set that two instances fight over. Entering `payment` costs one field write per child input, before the child's first node; the child's own nodes then write exactly as they would at top level.

> **Note:** entering a subflow records the child's inputs first. The mapper builds the child's input record, and each input is written under the child's path (`payment~orderId`, `payment~amountCents`) before the child's first node runs, once. A resumed subflow that finds its inputs recorded does not run the mapper again: the child runs against the inputs it was recorded with, plus its own durable fields, for the life of the execution. The mapper is effectful and can run more than once before its output lands (a crash between two of its input writes re-runs it for the ones still missing), so its side effects carry the same idempotency obligation a step body does, and nothing more.

`andThen` sequences two flows without nesting them: the first runs to completion, then the second starts with access to everything the first produced.

```scala
val endToEnd = fulfilmentFlow.andThen(shippingFlow)
```

### Node names are durable keys

The engine builds its own keys out of node names, using `~` to join a path and `#` to number a repetition: a subflow's node is `payment~chargeId`, a scheduled loop's third iteration is `delivery#2`, a fan-out's third item is `reservations~2`, and a dispatch's choice is `route#chosen`. Both characters are therefore reserved in a name you write, and a flow using either is refused at registration with `FlowReservedNameException`.

Registration also refuses a flow in which two nodes claim one durable name, with `FlowDuplicateNameException`, because one name is one field and one completion mark: the second write would overwrite the first and replay would skip the second forever. Two cases are deliberately blessed rather than refused: an `input` that two parallel branches wait on, which is one wait and one field, and the branches of a `race`, which share their result name precisely so the union result is readable downstream.

## Choosing, repeating, and fanning out

Four constructs cover the shapes a workflow takes when it is not a straight line, and the useful way to tell them apart is not what they compute but what they write down. Recovery skips exactly what was recorded, so the choice between them is a choice about how much re-running a crash costs and how many store writes you pay for that.

| Construct | What it checkpoints | What a crash mid-node re-runs | Store writes |
|---|---|---|---|
| `dispatch` | the branch it chose, before running it | the chosen branch's body | one field for the choice, one for the result |
| `loop` | nothing until the body says stop | every iteration from the first | one field, at the end |
| `loopOn` | every iteration | the iteration in flight | one field per iteration, its start and completion events, and the wait row and two events of the sleep between iterations |
| `foreach` | every item | the items in flight | one field for the item count, one per item, and one for the assembled result |

### Branching

A dispatch evaluates conditions in order and runs the first branch that matches. It starts with `.dispatch[V]` naming the result type, chains `.when` branches, and is closed with `.otherwise`.

```scala
val routed =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .dispatch[String]("route")
        .when(ctx => ctx.total > 100000, name = "manual-review")(_ => "review")
        .when(ctx => ctx.total > 10000, name = "supervisor")(_ => "supervisor")
        .otherwise(_ => "auto-approve", name = "auto")
        .step("record")(ctx => notifyCustomer(ctx.order.id, ctx.route))
```

The type parameter is what every branch must produce. The chosen value is persisted under the dispatch's name and read downstream as `ctx.route`, and the choice itself is recorded under `route#chosen` BEFORE the branch runs, so a recovered execution takes the same branch rather than re-evaluating conditions against a record that may have moved on.

Closing with `.otherwise` is not a convention. Until you call it, `output`, `step`, `input`, and `sleep` are defined on the partial dispatch as compile errors, so an unclosed dispatch fails to compile with a message telling you what is missing.

### Repeating without checkpoints

`loop` repeats a body until it answers `Loop.done(value)`, and stores that value under the loop's name.

```scala
val untilAccepted =
    Flow.init("fulfilment")
        .input[Order]("order")
        .loop("accepted") { ctx =>
            carrierStatus(ctx.order.id).map {
                case "accepted" => Loop.done(true)
                case _          => Loop.continue
            }
        }
```

A loop can carry state between iterations. The second argument is the initial state and the body receives it beside the context:

```scala
val counted =
    Flow.init("fulfilment")
        .input[Order]("order")
        .loop("attempts", 0) { (tries, ctx) =>
            if tries >= ctx.order.items.size then Loop.done(tries)
            else Loop.continue(tries + 1)
        }
```

> **Caution:** the whole loop is one durable node, and nothing is recorded until the body says stop. An execution that dies at iteration 40 of 50 begins again at iteration 1 when another executor recovers it, and every side effect those 40 iterations performed happens a second time. That is deliberate: a `loop` runs until its own body decides, so its iteration count is unbounded, and recording each one would write a field and an event per iteration for a loop that may converge over thousands. Use it for convergence over pure or idempotent-per-iteration work, and reach for one of the next two when the iterations do something you cannot afford twice.

### Repeating with a checkpoint per iteration

`loopOn` is a loop with a `Schedule` between iterations. The wait is durable, and every iteration is checkpointed: the state a continuing iteration carries and the value a finishing one produces are both recorded under the iteration's own key before the loop moves on.

```scala
val watching =
    Flow.init("fulfilment")
        .input[Order]("order")
        .loopOn("delivery", Schedule.fixed(30.minutes)) { ctx =>
            carrierStatus(ctx.order.id).map {
                case "delivered" => Loop.done("delivered")
                case _           => Loop.continue
            }
        }
```

An execution that dies mid-loop resumes at the iteration after the last one recorded, with that iteration's state. When you want the checkpointing and not the waiting, `Schedule.fixed(Duration.Zero)` is the schedule to pass: it is a `loop` whose iterations are durable, at the cost of the writes.

> **Caution:** a `loopOn` whose schedule runs out before the body returned `Loop.done` FAILS the execution with `FlowLoopExhaustedException`, naming the loop and the iterations it ran. It does not complete with the last state: the flow declared `N ~ V` and there is no `V` to write. Give the schedule more room than the body needs, or make the body decide.

### Fanning out over a collection

`foreach` is the shape to use when the work is genuinely N units. Each item's result is recorded under the item's own durable key as it lands, `reservations~0`, `reservations~1`, and so on, and the node's `Chunk[V]` is assembled from them when the last one arrives.

```scala
val reserving =
    Flow.init("fulfilment")
        .input[Order]("order")
        .foreach("reservations")(ctx => ctx.order.items)(item => reserveStock(item))
        .step("ship")(ctx => ship(ctx.order))
```

An execution that dies with 40 of 50 items recorded resumes at item 41: the recorded items are read back rather than run again, so a fan-out that charges N cards charges each of them once however many attempts recovery takes. The item that was in flight when the process died is the one exception, and it is the same window every node has. The cost is N field writes for a fan-out of N, which is what buys the guarantee.

`timeout` bounds ONE item, since a bound over the whole fan-out cannot be sized for the single slow one; `retry` re-asks one item that failed for an accidental reason, leaving recorded items exactly as they are; `concurrency` bounds how many items are in flight, defaulting to unbounded. Results are ordered by the collection rather than by completion, whatever the bound is.

```scala
val bounded =
    Flow.init("fulfilment")
        .input[Order]("order")
        .foreach(
            "reservations",
            timeout = 5.seconds,
            retry = Maybe(Schedule.fixed(1.second).take(3)),
            concurrency = 4
        )(ctx => ctx.order.items)(item => reserveStock(item))
```

> **Note:** items above a concurrency of 1 run in their own fibers. `Sync`, `Async` and `Abort` bodies need nothing for that, and neither does a body reading a context effect such as `Env`, which the kernel carries into a fiber. A body carrying `Var` or `Emit`, whose state has to be merged back, has no per-item state to merge here and should declare `concurrency = 1`.

> **Caution:** the collection must be a deterministic function of the record. A resumed attempt recomputes it, because that is what names the items the recorded results belong to, and the count the fan-out started with is recorded beside them. A recomputed collection whose size disagrees fails the node with `FlowNondeterministicCollectionException`, naming the node, the count it recorded, and the size it recomputed.

The node persists the whole `Chunk[V]` under the evidence for `Chunk[V]` rather than for `V`, so reading it back from a store is `store.getField[Chunk[String]](eid, "reservations")`, the same typed read every `FlowStore` implementation provides; asking for `getField[String]` answers `Absent` on the tag mismatch rather than failing.

## Undoing work when a later step fails

A workflow that reserved stock and charged a card, and then fails at the shipment, has to give both back. Compensation handlers are how a node says what undoing it means, and the engine runs them in reverse registration order when a later node fails.

```scala
val saga =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)
        .foreachCompensated("reservations")(ctx => ctx.order.items)(item => reserveStock(item))((_, reservationId) =>
            releaseStock(reservationId)
        )
        .outputCompensated("charge")(ctx => chargeCard(ctx.order.id, ctx.total))(ctx => refundCharge(ctx.charge))
        .step("ship")(ctx => ship(ctx.order))
```

If `ship` fails, the unwind refunds the charge, then releases each reservation. Handlers must be idempotent for the same reason node bodies must: an unwind interrupted part-way is resumed, and only the handlers whose completion was recorded are skipped.

Every node kind has a compensated variant: `outputCompensated`, `stepCompensated`, `loopCompensated`, `loopOnCompensated`, `foreachCompensated`, and `otherwiseCompensated` for a dispatch. Which record the handler receives is what differs, and it follows the shape of the node.

A handler on `output`, `step`, `loop`, `loopOn`, or a dispatch is NODE level: it receives the record the node's own value is in, and undoes the one thing the node did. A loop's handler sees the value the loop converged on, so a loop that booked a shipment per iteration undoes the booking it ended with, and a loop cancelled between iterations produced no value and registers nothing. A dispatch's handler sees the branch that ran; the branch that did not run wrote nothing, so there is no per-branch question to answer.

`foreachCompensated` is the exception, and it is per ITEM. The handler receives an item and what that item produced, and is registered as each item completes, so a run that charged three of five cards refunds three. A node-level handler would receive the node's stored value, and a fan-out's value exists only once every item has landed, which is to say it does not exist at the one moment unwinding matters. A resumed fan-out registers handlers for the items it read back as well as for the ones it ran, because an item recorded by an earlier attempt did its work just as much.

> **Note:** handlers run in reverse order of REGISTRATION, and a fan-out's items register in the order they complete rather than the order they were listed. The order among one fan-out's items is therefore a scheduling fact and not a contract. Work that must be undone in a particular order is a batch, and its handler belongs on the node that owns the batch.

> **Caution:** the one case that registers nothing is a detected nondeterministic collection. A fan-out whose recomputed collection changed size cannot say which item produced which recorded result, so it fails naming the mismatch instead of pairing them by a guess, and its own items are left as they are: their handlers never run. The unwind still runs every handler registered BEFORE the fan-out, and the already-recorded per-item fields stay in place as the evidence an operator remediates from.

A handler carries the forward node's own effect row rather than a bare one, because undoing a database write needs the same database. The engine's runner discharges both directions, so a compensating flow is built exactly like an uncompensated one and nothing has to close over a live client before the flow value exists.

> **Note:** handlers fire on `Throwable` failure and on cancellation, never on suspension. A flow waiting a week for an approval has run none of its handlers.

While an unwind is in progress the execution's status is `Compensating(cause)`, and `Flow.Cause` is either `Failure(error, kind)` or `Cancellation`. The cause is durable, on the status and on the `CompensationStarted` event, which is what lets an unwind that crashed be resumed: a resumed attempt re-raises the cause its interrupted attempt recorded rather than replaying forward and hoping the failing node fails a second time.

## Running branches at the same time

Three combinators run flows concurrently, and they differ in what ends the join: `zip` and `gather` wait for every branch, `race` takes the first value.

### zip and gather

`zip` runs two flows in parallel and merges their outputs. Both must complete.

```scala
val stockCheck =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("lines")(ctx => ctx.order.items.size)

val priceCheck =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("total")(ctx => ctx.order.items.map(i => i.qty * i.price).sum)

val checks = stockCheck.zip(priceCheck)
```

Both branches declare `input[Order]("order")`, which is the shared-input case registration blesses: one wait, one field, two readers. `gather` is the same join for two to five branches:

```scala
val everything = Flow.gather(fulfilmentFlow, shippingFlow, invoiceFlow)
```

### race

`race` runs two flows in parallel and takes the first VALUE. The natural use is bounding a wait that has no bound of its own, by racing it against a sleep.

```scala
val awaitApproval =
    Flow.init("approval")
        .input[Approval]("approval")
        .output("decision")(ctx => if ctx.approval.approved then "approved" else "declined")

val approvalDeadline =
    Flow.init("approval")
        .sleep("grace", 24.hours)
        .output("decision")(_ => "timed-out")

val decided = Flow.race(awaitApproval, approvalDeadline)
```

Both branches produce `decision`, which is what makes the union result readable downstream as `ctx.decision`, and it is the second case registration blesses rather than refuses.

This composition is also why `ExecutionDetail.waits` is a map rather than a single value. While the race is open the execution holds two wait rows at once, one `Wake.OnField("approval")` and one `Wake.At(deadline)`, and a single token could only ever answer one of them.

> **Note:** only a value decides a race. The first branch to produce one wins and the other is cancelled. A branch that FAILS while its sibling still waits decides nothing: the race keeps waiting on the survivors, and the failure stays recorded in the failing node's own events. Only when every branch has failed does the race fail, with the last failure as its verdict. That is what lets `race(notify-via-flaky-api, input("manual-ack"))` be rescued by the acknowledgement, which a failure-wins rule would have terminally failed while the answer it waits for was still deliverable.

### Isolate

All three combinators ask the call site for an `Isolate` for the combined effect row. Each branch runs in its own fiber, and the isolate is what carries a custom effect across that boundary: it is captured where the combined row is still visible, each branch runs against the captured state, and completed branches are restored into the caller's context.

Pure rows and `Async` rows derive one automatically, and so do context effects such as `Env` and `Local`, and `Check`. `Var` and `Emit` need one in scope:

```scala
given Isolate[Var[Int], Any, Var[Int]] = Var.isolate.update[Int]

val left  = Flow.init("fulfilment").input[Int]("a").output("b")(ctx => Var.get[Int].map(_ + ctx.a))
val right = Flow.init("fulfilment").input[Int]("c").output("d")(ctx => Var.get[Int].map(_ + ctx.c))
val both  = left.zip(right)
```

> **Caution:** a row no isolate can be built for does not compose, and that refusal is deliberate rather than a gap to work around. kyo-prelude ships no `Isolate[Abort[E]]`, so a flow whose nodes raise their own `Abort[E]` cannot be joined by `zip`, `race`, or `gather` today. Handle the `Abort` inside the node body, with `Abort.recover` or `Abort.run`, so the branch's row no longer carries it.

The asymmetry with `foreach` is worth knowing: `foreach` derives its own identity isolate internally and asks the caller for nothing, and the price of that is the `Var`/`Emit` restriction on fan-out bodies, which has no compile-time guard.

## Running a workflow

The entry points below span a unit test, a development server, and a production cluster. They differ in what backs the store and whether HTTP is involved; the flow value is the same in all of them.

### Locally, for tests

`Flow.runLocal` builds an in-memory store and a one-worker engine, drives the flow to completion, and answers with the full output record.

```scala
val demo   = Flow.init("fulfilment").input[Order]("order").output("lines")(ctx => ctx.order.items.size)
val record = Flow.runLocal(demo, "order" ~ Order("o-1017", "ada", Seq(Item("widget", 3, 400))))
```

Subflow fields are assembled into the returned record, so a parent that embedded `payment` reads `record.payment` as the child's own record, its inputs included: `record.payment.orderId` is the value the mapper supplied.

### Over HTTP

`Flow.runServer` starts an HTTP server exposing the REST API for every workflow you hand it.

```scala
// In-memory store, for development
val serverDev: HttpServer < (Async & Scope & Abort[HttpBindException | FlowDefinitionException | FlowStoreException]) =
    Flow.runServer(fulfilmentFlow, shippingFlow)

// Durable store, for production
val serverProd: HttpServer < (Async & Scope & Abort[HttpBindException | FlowDefinitionException | FlowStoreException]) =
    FlowStore.initMemory.map(store => Flow.runServer(store, fulfilmentFlow, shippingFlow))
```

> **Caution:** the zero-store overload uses `FlowStore.initMemory`, which is transient: every execution, field, and event is lost when the process exits. It is a development server. Production wants a store of your own; see [Backing it with your own store](#backing-it-with-your-own-store).

To mount the workflow endpoints beside your own, take the handlers instead of the server:

```scala
FlowStore.initMemory.map { store =>
    Flow.runHandlers(store, fulfilmentFlow).map { handlers =>
        HttpServer.init(handlers.toSeq*)
    }
}
```

> **Note:** `Flow.runHandlers` answers a pending `Chunk[HttpHandler[?, ?, ?]] < Any` rather than a bare `Chunk`, on purpose. A bare `Chunk` binds in a for-comprehension over `Chunk.flatMap`, which would quietly run the rest of the comprehension once per handler.

### The engine, without HTTP

`FlowEngine` is the full programmatic surface. Creating one starts worker fibers that poll the store, claim ready executions under a lease, and interpret the flow node by node.

```scala doctest:scope=env:engine
val engineEffect: FlowEngine < (Async & Scope & Abort[FlowDefinitionException | FlowStoreException]) =
    FlowStore.initMemory.map(store => FlowEngine.init(store, fulfilmentFlow, shippingFlow))
```

```scala doctest:scope=env:engine
engineEffect.map { engine =>
    for
        handle <- engine.workflows.start(Flow.Id.Workflow("fulfilment"))
        _      <- handle.signal("order", sampleOrder)
        status <- handle.status
    yield status
}
```

Beyond `start` and `signal`, `workflows` also answers `list` (every registered workflow's metadata), `describe(workflowId)` (one workflow's metadata), and `executions(workflowId)` (every execution of that workflow, regardless of status), all of which the HTTP layer exposes as routes. `engine.executorId` identifies this particular engine instance; it is the value recorded on `ExecutionClaimed` and `ExecutionResumed` events when this engine is the one that claimed or resumed an execution.

### Workflow ids: `init` versus `register`

`FlowEngine.init(store, flows*)` and `Flow.runServer` derive each workflow's id from the flow's OWN `Flow.init` name, which is why the example above starts `Flow.Id.Workflow("fulfilment")`. `FlowEngine#register` takes the id explicitly, and it does not have to match:

```scala doctest:scope=env:engine
engineEffect.map { engine =>
    engine.register(Flow.Id.Workflow("fulfilment-eu"), fulfilmentFlow)
}
```

That flow is now addressable as `fulfilment-eu` and only as `fulfilment-eu`; the name inside its `Flow.init` is decorative for this registration. Registering the same flow value under two ids is how you run two independent populations of executions from one definition.

Registration is also where a definition is checked. It refuses a flow with no name (`FlowUnnamedException`), one using a reserved character in a node name (`FlowReservedNameException`), and one in which two nodes claim a single durable name (`FlowDuplicateNameException`). Registration is the first point that has both the whole definition and an effect row, so a constructor could neither know that a later node will claim the same name nor report it as anything but a throw.

### Tuning, and the runner

Engine tuning is a `FlowEngine.Config`, or the same five values spelled out:

```scala
FlowStore.initMemory.map { store =>
    FlowEngine.init(
        store,
        workerCount = 4,
        lease = 30.seconds,
        renewEvery = 10.seconds,
        batchSize = 8,
        pollTimeout = 30.seconds,
        flows = Seq(fulfilmentFlow, shippingFlow)
    )
}
```

`lease` is the one that decides crash-recovery latency: an execution whose executor died is offered to another only once its claim has expired.

> **Caution:** a tuning the engine could not work under is REFUSED with `FlowInvalidConfigException` rather than clamped, and every bad setting is reported at once. A non-positive duration, a `workerCount` below 1, a `batchSize` below 1, and a `renewEvery` at or above `lease` all qualify. The last is the one that is actively harmful: the claim has already expired when the first renewal is presented, so the renewal is refused, the refusal interrupts the execution, and every node longer than the lease is interrupted, released, reclaimed, and re-run forever. Lowering `lease` without lowering `renewEvery` makes `init` fail rather than degrade.

When node bodies use effects the engine does not handle itself, the runner parameter supplies the handlers. It wraps the whole flow execution, forward pass and unwind together, so a compensation handler carrying the same row is discharged by the same runner:

```scala
FlowStore.initMemory.map { store =>
    val flow = Flow.init("fulfilment").output("warehouse")(_ => Env.use[String](region => s"warehouse-$region"))
    FlowEngine.init(store, flow)([v] => (c: v < Env[String]) => Env.run("eu-west")(c))
}
```

The same runner shape is accepted by `Flow.runLocal`, `Flow.runServer`, `Flow.runHandlers`, and `FlowEngine#register`.

## Watching an execution

Everything an operator asks about a running execution is answered from the store, which means it is answerable from any process, not only the one running the work.

### Status

```
Running ──→ Completed
Running ──→ Failed
Running ──→ Cancelled
Running ──→ Compensating ──→ Failed
Running ──→ Compensating ──→ Cancelled
```

`Flow.Status` has exactly five cases: `Running`, `Completed`, `Failed(error, kind)`, `Compensating(cause)`, and `Cancelled`. The two `Compensating` rows are the shape a terminalisation takes when the execution registered compensation handlers, cancelling included; with no handlers registered, the transition is directly to `Cancelled` or `Failed`. Which terminal an unwind lands on is a total function of the cause: a cancellation ends `Cancelled`, a failure ends `Failed`.

`Compensating ──→ Completed` is the one transition this module documents as impossible. A forward pass that reaches the end while resuming an unwind reached it in skip-only mode, having re-registered handlers and computed nothing, so answering with that record would complete an execution whose compensations are half run.

A `Running` execution can be working or waiting: waiting for a signal, or waiting for a durable sleep to expire. Neither is a separate status. Running the handlers IS one, `Compensating(cause)`, written through the claim before the first handler fires, which is what lets an unwind interrupted half way resume as an unwind rather than replay forward. What the execution is waiting for is on `ExecutionDetail.waits: Dict[String, Flow.Wake]`, keyed by node path, whose values are `Flow.Wake.At(instant)` for a sleep and `Flow.Wake.OnField(name)` for an input.

```scala doctest:scope=env:monitor
val eid: Flow.Id.Execution = Flow.Id.Execution("exec-123")
val wfId: Flow.Id.Workflow = Flow.Id.Workflow("fulfilment")

val describing =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.executions.describe(eid).map { detail =>
                val _status    = detail.status          // Flow.Status
                val _progress  = detail.progress        // per-node progress
                val _inputs    = detail.inputs          // which inputs are delivered
                val _waits     = detail.waits           // Dict[String, Flow.Wake], what each waiting node waits for
                val _cancelReq = detail.cancelRequested // asked to cancel, unwind not finished
            }
        }
    }
```

> **Caution:** `FlowEngine.Progress.NodeStatus`, the per-node rendering inside `detail.progress`, is a DIFFERENT vocabulary from `Flow.Status` and still carries `WaitingForInput`, `Sleeping(until)`, and `Compensated` alongside `Completed`, `Running`, `Pending`, and `Failed(error)`. Those are node-level facts about where an execution has got to, not execution-level statuses. A node reading `Sleeping` sits inside an execution whose status is `Running`.

`describe` on an execution whose definition this engine does not serve answers with empty inputs and empty progress beside a real state row, since both are derived from the definition.

A failed execution records what KIND of failure ended it. A node's own domain failure extends `FlowDomainException`, the one open branch of the exception hierarchy, and reaches the engine as a typed failure rather than a panic; the persisted `Flow.Status.Failed` then carries its class name beside the message, so "how many executions failed for payment reasons" is a query over a field rather than a `LIKE` over free text. An escaped throwable carries no kind, since nothing declared one.

```scala
case class ChargeDeclined(orderId: String, cents: Int)(using Frame)
    extends FlowDomainException(s"charge declined for $orderId: $cents cents over limit")
```

### Searching executions

`executions.search` answers the operator's questions that are not about one known execution: what is still waiting for an approval, what failed for a payment reason, what somebody has asked to stop.

```scala doctest:scope=env:monitor
val searching =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.executions.search(
                wfId = Maybe(wfId),
                filter = Maybe(FlowStore.ExecutionFilter.WaitingForInput(Maybe("approval")))
            ).map { page =>
                val _items = page.items // this page, most recently created first
                val _total = page.total // how many MATCHED, not how many came back
            }
        }
    }
```

`FlowStore.ExecutionFilter` is a deliberately different vocabulary from `Flow.Status`, because what an operator looks for includes facts that are not the lifecycle. Its arms are `Running`, `Compensating`, `Completed`, `Cancelled`, `Failed(kind)`, `Sleeping`, `WaitingForInput(name)`, `Cancelling`, and `Orphaned(servedHashes)`. A wait-kind arm matches when ANY of the execution's rows does.

The filter is evaluated by the STORE, before it paginates. Filtering a returned page would answer "the matches among the first 25 rows" to a caller who asked for "the first 25 matches", and the two differ by however many non-matching rows the page happened to contain. `SearchResult.total` is the size of the matched set, which is what a caller sizing a page control or deciding whether to ask for more is reading; answering it costs reading every match, since the store's vocabulary has no count.

> **Note:** on `history`, `search`, and `parked`, a negative `limit` reads as UNBOUNDED rather than as an empty page. An empty page is the one answer a caller cannot tell from "nothing matched", and paired with "another page follows" it is what a paging loop never escapes.

### Events

Every state change is appended to the execution's history, in order.

```scala doctest:scope=env:monitor
val reading =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.executions.history(eid).map { page =>
                val _events  = page.events  // Chunk[Flow.Event]
                val _hasMore = page.hasMore // pagination
            }
        }
    }
```

`Flow.EventKind` has 23 cases: `Created`, `StepStarted`, `StepCompleted`, `StepFailed`, `StepRetried`, `StepTimedOut`, `InputWaiting`, `InputReceived`, `InputDischarged`, `InputSupplied`, `SleepStarted`, `SleepCompleted`, `BranchChosen`, `ExecutionResumed`, `ExecutionClaimed`, `ExecutionReleased`, `Completed`, `Failed`, `CompensationStarted`, `CompensationCompleted`, `CompensationFailed`, `NodeCompensated`, `Cancelled`.

Three points repay a closer look. The three ways a value reaches an input stay apart in the history: `InputReceived` says a value arrived by `signal`, `InputSupplied` says a subflow's mapper supplied one at the instance's entry, and `InputDischarged` says a node consumed a signalled one, which an execution can sit a long way short of. `NodeCompensated` is per node, where `CompensationStarted`, `CompensationCompleted`, and `CompensationFailed` are per unwind, which is what lets an interrupted unwind tell the handlers that landed from the ones that did not. And `StepFailed(stepName, error, errorKind)` is what makes "which node failed" a read rather than a guess.

### Engine health

`FlowEngine#health` reports on the ENGINE, not on any execution: how many worker fibers are polling, how many the engine was configured with, and what the last failure to reach the store was.

```scala doctest:scope=env:monitor
val checking =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.health.map { h =>
                (h.isHealthy, h.workersAlive, h.workersConfigured, h.pollFailures, h.lastPollFailure)
            }
        }
    }
```

This is what a process-level health endpoint should read. A worker that cannot reach the store records the failure and keeps polling rather than dying, so `isHealthy`, which is `workersAlive == workersConfigured`, is what catches a process that is up while nothing is being executed. `lastPollFailure` is present whether or not the worker that hit it recovered, so a store failing intermittently is visible rather than silent.

## Cancelling

Cancelling runs an execution's compensation handlers, so it cannot be instantaneous and is not modelled as if it were. `cancel` puts a request on the row and returns; an executor observes it at the next node boundary, starts no further node, runs the registered handlers in reverse, and only then writes the terminal `Cancelled`.

```scala doctest:scope=env:monitor
val cancelling =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.executions.cancel(eid).map {
                case FlowStore.CancelOutcome.Accepted                => "the unwind will run"
                case FlowStore.CancelOutcome.AlreadyRequested        => "somebody asked first"
                case FlowStore.CancelOutcome.AlreadyTerminal(status) => s"nothing to cancel, ended ${status.show}"
            }
        }
    }
```

Those three answers are the only thing about a cancel that is observable on return, which is why the method does not answer `Unit`. `Accepted` means the request is now outstanding. `AlreadyRequested` means one already was. `AlreadyTerminal(status)` means there was nothing to cancel and carries what the execution ended as; it is an answer rather than a failure, because a caller that cancels something already finished got what it wanted and what it needs back is the terminal status. Requesting a cancel on an execution that does not exist answers `AlreadyTerminal(Cancelled)`, since a row that is not there cannot be running.

The HTTP endpoint mirrors that exactly: `accepted` is 202, `already-requested` and `already-terminal` are both 200, and there is no 4xx.

Between the request and the executor observing it, `Handle.status` still reads `Running`; from there to the terminal status an execution with handlers registered reads `Compensating(Cancellation)` while they run. The fact a caller wants in that window is `ExecutionDetail.cancelRequested`, and the filter that selects on it across a population is `ExecutionFilter.Cancelling`.

`executions.cancelAll(wfId)`, and the wire's `CancelAllResponse.requested`, count REQUESTS issued, not executions cancelled. One counted may still be unwinding minutes later, and one that finished on its own before the request was observed was never asked at all. Each is counted exactly once: an execution somebody had already asked to cancel is not counted again.

> **Note:** cancelling a held execution, one whose version no engine currently serves, stays outstanding until a matching definition is registered, because nothing observes the request until something serves its hash. That is the same rollback-as-recovery rule as the next section, seen from the cancel side.

## Deploying a new version

Changing a registered flow's structure changes its structural hash, and an execution belongs to the version it was STARTED under. In-flight executions of the old shape are therefore HELD rather than failed: no registered definition matches their hash, so no engine claims them, and they keep their fields, their history, and their place.

```scala doctest:scope=env:monitor
val held =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.parked(wfId).map { states =>
                states.map(state => (state.executionId, state.hash))
            }
        }
    }
```

`FlowEngine.parked` is a query, not a status: nothing is written about being held in either direction. Being held is a fact about what THIS engine serves, it goes stale the moment an operator registers something, and an execution held under one engine may be running perfectly well under another. Each entry carries the execution's own `hash`, which is the version it was started under and the fact an operator needs: whether anything still serves that version is what registration decides. The store-side predicate behind it is `ExecutionFilter.Orphaned(servedHashes)`, which has no spelling in the HTTP filter vocabulary precisely because its parameter is one engine's own served set.

Recovery is registration. Rolling the deployment back, or registering both the old and new definitions in the same process, makes the held executions claimable again and they resume on their own. Nothing evicts a registered version within a process, so serving both versions during a migration is a supported steady state rather than a trick.

Failing held executions instead would be unrecoverable and would skip every compensation on the way out, because the handlers live in the definition they can no longer be matched to.

## Seeing what you built

A flow value can be drawn before it has ever run, and drawn again with an execution's progress painted onto it.

```scala doctest:scope=env:monitor
val drawing =
    FlowStore.initMemory.map { store =>
        FlowEngine.init(store, fulfilmentFlow).map { engine =>
            engine.workflows.diagram(wfId, Flow.DiagramFormat.Mermaid).map { structure =>
                engine.executions.diagram(eid, Flow.DiagramFormat.Dot).map(progress => (structure, progress))
            }
        }
    }
```

Formats are `Mermaid`, `Dot`, `Bpmn`, `Elk`, and `Json`. A flow value renders directly too, with no engine involved:

```scala
val mermaid = Flow.renderMermaid(fulfilmentFlow)
val dot     = Flow.renderDot(fulfilmentFlow)
val json    = Flow.renderJson(fulfilmentFlow)
```

> **Caution:** `Flow.DiagramFormat.fromString` answers `Mermaid` for anything it does not recognise, so a typo in a format parameter produces a silently different diagram rather than an error.

`Flow.lint` reports the name collisions registration would refuse, without needing an engine, which makes it usable in a unit test over a flow value:

```scala
val warnings = Flow.lint(fulfilmentFlow)
```

> **Note:** `lint` is public and returns `Seq[FlowLint.Warning]`, but `FlowLint` itself is `private[kyo]`. A caller outside this module can call `lint` and pattern-match or print the warnings it gets back, but cannot declare a variable of type `FlowLint.Warning`, since the type is not visible outside the module.

It flags duplicate node names, with the count of nodes claiming each name and each one's call site, and dispatches with no conditional branch. The duplicate-name warnings are exactly the collisions registration refuses, no broader: a lint that warned about the canonical race, or about an input two branches wait on, would teach its author that correct code is wrong, and a lint a user learns to ignore protects nothing. Registration runs that check itself, along with the unnamed-flow and reserved-character checks, so linting is an early copy of a gate you cannot skip. The empty-dispatch warning is advice rather than a gate; registration accepts a dispatch with only an `otherwise`.

## Failures, and how they are recorded

The exception hierarchy is sealed and grouped so that `Abort` unions stay precise: a method that can only fail to find a workflow declares only that, and a caller handling `Abort[FlowSignalException]` sees the three signal failures and nothing else.

| Group | Exception | Meaning |
|-------|-----------|---------|
| `FlowWorkflowException` | `FlowWorkflowNotFoundException` | Workflow not in store |
| | `FlowWorkflowNotRegisteredException` | Workflow not registered with this engine |
| `FlowDefinitionException` | `FlowDuplicateNameException` | Two nodes claim one durable name |
| | `FlowReservedNameException` | A node name uses `~` or `#` |
| | `FlowUnnamedException` | The flow has no name to be registered under |
| `FlowExecutionStateException` | `FlowExecutionNotFoundException` | Execution not found |
| | `FlowExecutionTerminalException` | Cannot signal a terminal execution |
| | `FlowDuplicateExecutionException` | Execution id already exists |
| `FlowSignalException` | `FlowSignalNotFoundException` | Input name is not in the definition |
| | `FlowSignalTypeMismatchException` | Signal type is not EXACTLY the declared input's type (checked with `Tag[V] =:= inputMeta.tag`, not a subtype check) |
| | `FlowInputAlreadyDeliveredException` | Input already delivered |
| `FlowException` directly | `FlowStepTimeoutException` | A node exceeded the `timeout` its `Meta` declared |
| | `FlowLoopExhaustedException` | A `loopOn`'s schedule ran out before the body produced a value |
| | `FlowNondeterministicCollectionException` | A fan-out's collection recomputed to a different size on replay |
| | `FlowInvalidConfigException` | `FlowEngine.init` was handed a tuning it cannot work under |
| | `FlowExecutionFailedException` | `runLocal`'s flow ended `Failed` |
| | `FlowCancelledException` | Raised at a node boundary once a cancel has been requested |

`FlowDefinitionException` is itself a `FlowWorkflowException`, so `Abort.run[FlowWorkflowException]` catches registration refusals along with lookup failures.

`FlowStepTimeoutException` is the one flow failure a node's retry schedule re-asks: a slow attempt is a measurement rather than a verdict, which is what declaring `timeout` and `retry` together is for. Once the schedule is exhausted, it terminalises the execution with its kind.

`FlowDomainException` is the one OPEN branch, and it is what a node's own business failure extends. Without it a domain failure had nowhere typed to go, since a runner has to produce `Abort[FlowException]`, and turning a declined charge into a panic threw away the type on the way to a status that keeps a string. A failure extending it reaches the engine as a failure, and its class name lands in `Flow.Status.Failed.kind`, which is what makes `ExecutionFilter.Failed(kind)` a useful query.

`FlowStoreException` is a separate, open hierarchy: the error channel every `FlowStore` method carries, so a store implementation reports a backend failure as a value rather than by panicking. A store that knows a failure is transient marks it with `FlowStoreException.Retryable`. The engine treats a store failure as transient in both places it can happen: a failure while polling is recorded on `health` and retried, and a failure while running an execution is recorded there too and leaves the execution exactly as claimable as it was, because a store the engine could not reach has said nothing about the work. That holds whatever the flow registered: a store failure is not a verdict, so it starts no unwind and runs no compensation handler, and the next attempt carries on from where this one stopped.

For recovery inside a node body, use Kyo's `Abort.recover`:

```scala
val recovering =
    Flow.init("fulfilment")
        .input[Order]("order")
        .output("charge")(ctx =>
            Abort.recover[Throwable](_ => "unpaid")(chargeCard(ctx.order.id, 1))
        )
```

## Coordinating multiple executors

Several engines over one store coordinate without configuration. `claimReady` hands each ready execution to exactly one executor under a renewable, time-limited lease; if an executor dies, its lease expires and another picks the execution up.

```scala
FlowStore.initMemory.map { store =>
    // Instance A
    FlowEngine.init(store, workerCount = 2, lease = 30.seconds, flows = Seq(fulfilmentFlow))
    // Instance B, same store, separate process
    FlowEngine.init(store, workerCount = 2, lease = 30.seconds, flows = Seq(fulfilmentFlow))
}
```

A node that was in flight when an executor died may run again on the executor that reclaims the lease, while a node already recorded as completed is skipped on replay. That is why node side effects must be idempotent, and the obligation has three faces worth naming separately.

**The store's guarantee is convergence, not exactly-once execution.** What the store converges on is one writer's view of an execution, not that a node's side effect ran once. A lapsed executor may already have charged a card before its write is refused by the claim fence: the lease expires, `claimReady` hands the execution to another executor under a new token, and every write the dead executor still had in flight is refused. The refusal protects the record, not the card. Idempotence therefore lives in the side effect itself.

**Self-concurrency is part of the same demand.** A timed-out attempt is abandoned rather than awaited, so on the JVM and Native a node body blocked in non-suspending code can still be running when its own next attempt begins. Two attempts of one node can be in flight at once inside a single executor, and idempotence has to hold under that overlap, not only under re-execution after a crash.

**A subflow's input mapper runs until the child's inputs are recorded, and not after.** Entering a subflow writes each of the child's inputs under its path, write-once, before the child's first node; a resumed subflow whose inputs are recorded re-enters the child without running the mapper, against the record. The obligation on the mapper is therefore the idempotency every effectful body carries, because it can run again after a crash between two of its input writes, and not determinism: the record, not the mapper, is what the child ran against.

## Backing it with your own store

`FlowStore.initMemory` loses everything when the process exits. Production means implementing `FlowStore` against a durable database, and the SPI is shaped so that the database, not the engine, is the thing that decides.

```scala doctest:expect=skipped
class PostgresFlowStore(pool: ConnectionPool) extends FlowStore:
    def claimReady(): Unit = ??? // SELECT ... FOR UPDATE SKIP LOCKED, handing back a Claimed per row
    def signal(): Unit     = ??? // UPDATE + INSERT in one transaction, refused on a terminal row
    // ... the readers and the three administrative writes: 12 on FlowStore and 8 on Claimed, 20 in all
end PostgresFlowStore
```

The trait splits in two: 12 on `FlowStore` and 8 on `Claimed` (six verbs and two accessors, `state` and `satisfied`), 20 in all. `FlowStore` carries `claimReady`, the three writes anybody may make (`createExecutionIfAbsent`, `signal`, `requestCancel`), the five readers (`getExecution`, `listExecutions`, `getField`, `getAllFields`, `getHistory`), and the three workflow-metadata methods (`putWorkflow`, which writes, and `getWorkflow` and `listWorkflows`, which read). Those three keep a `FlowEngine.WorkflowInfo` per registered workflow: its id, its `Flow.Meta`, its nodes, its inputs, its output names, and its structural hash. Every write made while RUNNING an execution is a method on `FlowStore.Claimed`, the handle `claimReady` hands back, which carries the generation the claim was granted under: two accessors, `state` and `satisfied`, and six verbs, `appendEvent`, `recordWait`, `renewClaim`, `updateStatus`, `recordProgress`, and `finish`. A write that carries no evidence of the claim it was made under cannot be refused by anything except the writer's own good manners, which is what this split removes.

### The claim lease

One rule decides every write through a `Claimed` handle, and it has two halves. A row's claim is either active, carrying a token and an expiry, or absent. A write is applied only if the claim is active, the presented token is the active claim's token, AND the claim has not expired against the store's own clock. The token alone refuses a superseded writer and says nothing about an executor whose lease expired while nobody else took the row, which still bears the highest token; the expiry alone says nothing about a writer whose row was taken while its lease still had time to run.

`claimReady` replaces whatever claim a row has with an active one carrying a strictly greater token. `Claimed.finish` is the only verb that makes a claim absent, and that absence is what gives a row's wait rows their meaning: they are a finished attempt's statement of what the execution waits for, which is what readiness may gate on.

> **Note:** an attempt that ends WITHOUT calling `finish` leaves the claim to expire, and that is correct rather than a leak. Readiness reads an active-and-expired claim as "the last attempt died mid-flight", returns the execution regardless of its wait rows, and lets the replay heal the ledger. Releasing there instead would bless a partial ledger as a finished attempt's statement, which is a permanent wedge rather than a slow recovery.

### The nine invariants

- **I1** `claimReady` never returns the same execution to two concurrent callers.
- **I2** A transition writes its status, its event, and its ledger effect atomically, with no window in which a reader sees one without the others. This covers recording a wait, recording progress and clearing the row it discharges, and ending an attempt.
- **I3** `signal` is an atomic check-and-write against both the field and the lifecycle: exactly one delivery of a name wins, and one arriving after the execution finished is refused.
- **I4** A renewal succeeds only on an ACTIVE, UNEXPIRED claim whose token is the one presented. An expired claim is dead, and only a new `claimReady` generation revives the execution.
- **I5** Terminal status cannot revert to non-terminal.
- **I6** Read-your-writes consistency within a single caller.
- **I7** `getHistory` returns events in append order.
- **I8** `claimReady` returns an execution exactly when its lifecycle is not terminal, its claim is free or expired, the caller serves its version, and ANY of: a cancel request is outstanding, it has no outstanding wait rows, one of its rows is satisfied, or its claim is active and expired.
- **I9** A claimed row records who holds it, until when, and under which generation. The executor id is recorded for reporting and is NEVER a write authority: two workers of one engine share one, so the token is what a write is judged against.

Three details around them are the ones an implementor is likely to get wrong on the first pass.

`claimReady` answering EMPTY is never proof that the timeout elapsed. Two things become ready with no write to wake anyone, a `Wake.At` row whose instant simply passed and a claim that simply expired, so an implementation must make one final attempt at the deadline. A caller reads an empty answer as a signal to re-read the served set and ask again with another blocking call, never as "nothing happened for the whole timeout".

`recordWait` is put-if-absent on the ROW and always-append on the EVENT. A sleep re-recorded on a later attempt keeps its ORIGINAL deadline; a store that overwrote would push the deadline forward on every replay and turn a finite sleep into one that never fires.

`recordProgress` is write-once per path, but the refusal is split by what the write carries. A VALUE-carrying write is refused by field presence. A VALUELESS one is refused only by its own recorded completion, because an input's discharge is by construction a valueless progress at a path the signalled value already occupies. A store must therefore keep a separate per-execution record of the paths at which a valueless progress landed; a field cannot serve as that mark.

### Outcome vocabularies

Each verb answers with what the store did rather than raising: `WriteOutcome` (`Applied`, `ClaimLost`), `StatusOutcome` (`Applied`, `ClaimLost`, `WrongSideOfTerminal`), `ProgressOutcome` (`Recorded`, `AlreadyRecorded`, `ClaimLost`), `SignalOutcome` (`Delivered`, `AlreadyDelivered`, `AlreadyTerminal`), and `CancelOutcome` (`Accepted`, `AlreadyRequested`, `AlreadyTerminal`). An attempt ends by handing `finish` a `Claimed.Outcome`: `Terminal(status, event)`, which retires every wait row, or `Suspended(waitingOn)`, which keeps exactly the named rows and retires the rest.

The rows themselves are `FlowStore.ExecutionState`, carrying the lifecycle, the claim, the structural hash, the wait rows, and the cancel flag, so a caller listing a page of executions needs no per-row ledger read. Field values are `FlowStore.FieldData(value, tag)`, JSON text beside the erased tag of the declared type; use the companion's `FieldData.apply[V](value, tag: Tag[V])` to widen the `Tag[V]` the SPI hands you.

### The conformance suite

`FlowStoreTest` is an abstract test class in this module's test sources. An implementation extends it and supplies `makeStore`, and the suite then exercises the SPI contract against the real backend. It is organised invariant by invariant, I1 through I9, and then verb by verb: `claimReady`'s blocking and acceptance rules, `renewClaim`, `finish`, `requestCancel`, `listExecutions` with its filters and pagination, field operations, signal delivery, execution state, event history, and workflow metadata. Running it green is how a store demonstrates it upholds the invariants above, rather than by inspection.

## The HTTP endpoints

`Flow.runServer` and `Flow.runHandlers` expose these routes. They are what a dashboard, an operator tool, or a service in another language talks to.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/workflows` | List workflows |
| GET | `/api/v1/workflows/{id}` | Workflow metadata |
| GET | `/api/v1/workflows/{id}/diagram` | Workflow diagram, `?format=` |
| POST | `/api/v1/workflows/{id}/executions` | Start an execution |
| GET | `/api/v1/executions/{eid}` | Status, waits, cancel flag, inputs, progress |
| GET | `/api/v1/executions/{eid}/inputs` | Input delivery status |
| GET | `/api/v1/executions/{eid}/history` | Event history |
| GET | `/api/v1/executions/{eid}/diagram` | Diagram with progress overlay, `?format=` |
| POST | `/api/v1/executions/{eid}/signal/{name}` | Deliver an input, JSON body decoded against the declared input schema |
| POST | `/api/v1/executions/{eid}/cancel` | Request cancellation; 202 `accepted`, 200 `already-requested`, 200 `already-terminal` carrying the terminal status; no 4xx |
| POST | `/api/v1/executions/cancel` | Request cancellation of matching executions, answering `requested`, the count of requests issued |
| POST | `/api/v1/executions/search` | Search executions |

The search body's `status` field takes the wire filter vocabulary: `running`, `completed`, `cancelled`, `cancelling`, `compensating`, `failed`, `failed:<kind>`, `sleeping`, `waiting`, and `waiting:<name>`. An unrecognised string is a 400 rather than "no filter", and so are a bare `failed:` and a bare `waiting:`, which would otherwise narrow to the empty kind and match nothing.

`ExecutionFilter.Orphaned` has no spelling here, deliberately: its parameter is one engine's own served set, which is not something a client can name. An operator reaches that predicate through `FlowEngine.parked` instead.
