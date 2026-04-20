# kyo-flow

Durable workflow engine for Kyo. Workflows are defined as composable, type-safe plans that the engine persists, coordinates across multiple executors, and recovers automatically after crashes.

A `Flow` is a plan, not an execution. You describe what should happen — values to compute, inputs to wait for, side effects to perform, branches to take — and the engine handles the rest. Every step is checkpointed to a store before the next begins. If the process crashes, another executor claims the work and replays from the last checkpoint, skipping steps that already completed. Side effects in steps must be idempotent because they may re-execute on recovery.

The engine coordinates multiple executors via time-limited claim leases, supports compensation handlers for saga-style rollback, provides retry and timeout per step, emits a full event audit trail, and exposes an auto-generated REST API. Workflow structure can be rendered as Mermaid, DOT, BPMN, ELK, or JSON diagrams. The module compiles across JVM, JavaScript, and Scala Native.

## Getting Started

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %% "kyo-flow" % "<latest version>"
```

## Outputs

The simplest workflow computes a value and stores it:

```scala
import kyo.*

val flow = Flow.init("hello")
    .output("greeting")(_ => "Hello, World!")
```

`Flow.init` creates a named workflow. `.output("greeting")` computes a value, persists it under the name `"greeting"`, and makes it available to subsequent steps.

The function passed to `.output` receives a context record containing all fields produced so far. Here there are none, so we ignore it with `_`. When there are prior fields, each one is accessible by name with full type safety:

```scala
val flow = Flow.init("pricing")
    .output("price")(_ => 100)
    .output("tax")(ctx => ctx.price * 0.08)
    .output("total")(ctx => ctx.price + ctx.tax)
```

`ctx.price` is statically typed as `Int` — accessing a field that doesn't exist is a compile error. This works through Kyo's `Record` type, which tracks fields as an intersection of `Name ~ Value` pairs. Each `.output` call adds a field to the record type:

```scala
// After .output("price"), the context type includes "price" ~ Int
// After .output("tax"), it includes "price" ~ Int & "tax" ~ Double
// After .output("total"), it includes all three fields
```

The three type parameters on `Flow[In, Out, S]` track this automatically:
- `In` — required inputs (accumulated via `.input`)
- `Out` — produced outputs (accumulated via `.output`, `.loop`, `.dispatch`, etc.)
- `S` — pending effect types from step computations

Run a workflow locally for testing:

```scala
val result = Flow.runLocal(flow)
// result.greeting == "Hello, World!"
```

## Inputs

An input declares a value the workflow needs from the outside world. The execution suspends at the input node until the value is delivered externally via a signal:

```scala
case class Order(item: String, qty: Int) derives Json

val flow = Flow.init("order")
    .input[Order]("order")
    .output("total")(ctx => ctx.order.qty * 100)
    .output("receipt")(ctx => s"${ctx.order.item} x${ctx.order.qty} = ${ctx.total}")
```

Input types must have a `Json` instance for serialization. Like outputs, each input adds a typed field to the context.

For testing, pre-populate inputs with `runLocal`:

```scala
val result = Flow.runLocal(flow, "order" ~ Order("Widget", 3))
// result.receipt == "Widget x3 = 300"
```

The `~` operator creates a typed record field: `"order" ~ Order("Widget", 3)` is a `Record["order" ~ Order]`. Multiple fields combine with `&`: `"x" ~ 1 & "y" ~ "hello"`.

In production, inputs arrive via the engine's signal API or the HTTP endpoint `POST /api/v1/executions/:eid/signal/order`.

## Steps

A step performs a side effect without producing a named value — HTTP calls, database writes, sending notifications:

```scala
val flow = Flow.init("notify")
    .input[String]("email")
    .output("message")(ctx => s"Welcome, ${ctx.email}!")
    .step("send")(ctx => sendEmail(ctx.email, ctx.message))
```

On replay after a crash, completed steps are skipped. Steps are tracked by their completion event in the audit trail, not by a stored value.

## Sleep

Sleep pauses the execution for a duration. The pause is durable — if the process restarts, the engine calculates the remaining time and resumes when it expires:

```scala
val flow = Flow.init("delayed")
    .input[String]("orderId")
    .step("process")(ctx => processOrder(ctx.orderId))
    .sleep("cooldown", 1.hour)
    .step("followUp")(ctx => sendFollowUp(ctx.orderId))
```

## Branching

Dispatch evaluates conditions in order and executes the first matching branch. It follows a builder pattern: start with `.dispatch[V]` specifying the result type, chain `.when` branches, and close with `.otherwise`:

```scala
val flow = Flow.init("approval")
    .input[Int]("amount")
    .dispatch[String]("decision")
    .when(ctx => ctx.amount > 1000, name = "review")(ctx => "needs review")
    .when(ctx => ctx.amount > 100, name = "auto")(ctx => "auto-approved")
    .otherwise(ctx => "instant", name = "default")
    .step("notify")(ctx => notifyResult(ctx.decision))
```

The type parameter `[String]` is the type of the value all branches must produce. The result is persisted under the dispatch name (`"decision"`) and accessible downstream as `ctx.decision`. Calling `.otherwise` is required — the compiler enforces this by making further chaining methods unavailable until the dispatch is closed.

## Loops

A loop iterates until the body returns `Loop.done(value)`. Each iteration can return `Loop.continue` to keep going or `Loop.done(result)` to finish. The final value is stored as a named output:

```scala
val flow = Flow.init("poll")
    .input[String]("url")
    .loop("result") { ctx =>
        checkStatus(ctx.url).map {
            case "ready" => Loop.done("complete")
            case _       => Loop.continue
        }
    }
```

Loops can carry state between iterations. The second argument to `.loop` is the initial state, and the body receives `(state, ctx)`:

```scala
val flow = Flow.init("accumulate")
    .input[Int]("target")
    .loop("sum", 0) { (acc, ctx) =>
        if acc >= ctx.target then Loop.done(acc)
        else Loop.continue(acc + 1)
    }
```

**Scheduled loops** (`loopOn`) insert durable sleeps between iterations using a `Schedule`. Each iteration is checkpointed independently, so recovery resumes from the last completed iteration:

```scala
val flow = Flow.init("monitor")
    .input[String]("endpoint")
    .loopOn("check", Schedule.fixed(5.minutes)) { ctx =>
        probe(ctx.endpoint).map {
            case "healthy" => Loop.continue
            case status    => Loop.done(s"alert: $status")
        }
    }
```

## ForEach

ForEach processes each element of a collection and stores all results as a `Chunk`:

```scala
val flow = Flow.init("batch")
    .input[Seq[String]]("urls")
    .foreach("results")(ctx => ctx.urls)(url => fetch(url))
```

## Composition

### Sequential

`andThen` sequences two flows — the first completes, then the second starts with access to all prior outputs:

```scala
val combined = validateFlow.andThen(processFlow)
```

### Parallel

`zip` runs two flows in parallel and merges their outputs. Both must complete:

```scala
val parallel = pricingFlow.zip(inventoryFlow)
```

For more than two, use `gather`:

```scala
val all = Flow.gather(pricingFlow, inventoryFlow, shippingFlow)
```

### Racing

`race` runs two flows in parallel — first to complete wins, the other is abandoned:

```scala
val fastest = Flow.race(primaryFlow, fallbackFlow)
```

### Subflows

`subflow` embeds a child flow within a parent. The input mapper transforms the parent's context into the child's expected inputs:

```scala
val parent = Flow.init("parent")
    .input[Order]("order")
    .subflow("payment", paymentFlow)(ctx =>
        "amount" ~ (ctx.order.qty * ctx.order.price)
    )
    .step("ship")(ctx => ship(ctx.payment))
```

## Error Handling

### Retry and Timeout

Any output or step can specify a per-attempt timeout and a retry schedule:

```scala
val flow = Flow.init("resilient")
    .input[String]("url")
    .output("data",
        timeout = 10.seconds,
        retry = Maybe(Schedule.exponential(1.second, maxBackoff = 1.minute))
    )(ctx => fetchData(ctx.url))
```

Each attempt is independently timed. When the schedule exhausts, the last error propagates.

### Compensation

Outputs and steps can register compensation handlers that fire in reverse order when a later step fails. This implements the saga pattern for distributed transactions:

```scala
val flow = Flow.init("saga")
    .input[Order]("order")
    .outputCompensated("reservation")(ctx =>
        reserveInventory(ctx.order)
    )(ctx =>
        cancelReservation(ctx.reservation)
    )
    .outputCompensated("charge")(ctx =>
        chargeCard(ctx.order)
    )(ctx =>
        refundCard(ctx.charge)
    )
    .step("ship")(ctx => ship(ctx.order))
```

If `ship` fails, compensations run in reverse: first `refundCard`, then `cancelReservation`. Compensations must be idempotent.

For error recovery within a step body, use Kyo's `Abort.recover`:

```scala
.output("result")(ctx =>
    Abort.recover[Throwable](_ => "fallback")(riskyOperation(ctx.input))
)
```

### Exception Types

Engine operations fail with specific `FlowException` subtypes organized into sealed groups for pattern matching:

| Group | Exception | Meaning |
|-------|-----------|---------|
| `FlowWorkflowException` | `FlowWorkflowNotFoundException` | Workflow not in store |
| | `FlowWorkflowNotRegisteredException` | Workflow not registered with engine |
| `FlowExecutionStateException` | `FlowExecutionNotFoundException` | Execution not found |
| | `FlowExecutionTerminalException` | Cannot signal terminal execution |
| | `FlowDuplicateExecutionException` | Execution ID already exists |
| `FlowSignalException` | `FlowSignalNotFoundException` | Input name doesn't exist |
| | `FlowSignalTypeMismatchException` | Signal type doesn't match |
| | `FlowInputAlreadyDeliveredException` | Input already delivered |

API methods use precise Abort union types, so you can handle exactly the errors each method can produce.

## Running Workflows

### Local

`Flow.runLocal` runs a flow in-memory, blocking until completion. Useful for tests:

```scala
val result = Flow.runLocal(flow, "x" ~ 42)
```

### Server

`Flow.runServer` starts an HTTP server with REST endpoints for all registered workflows:

```scala
// In-memory store (development)
Flow.runServer(orderFlow, shippingFlow)

// Durable store (production)
Flow.runServer(store, orderFlow, shippingFlow)
```

The server exposes:

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/workflows` | List workflows |
| GET | `/api/v1/workflows/:id` | Workflow metadata |
| GET | `/api/v1/workflows/:id/diagram` | Workflow diagram |
| POST | `/api/v1/workflows/:id/executions` | Start execution |
| GET | `/api/v1/executions/:eid` | Execution status |
| POST | `/api/v1/executions/:eid/signal/:name` | Deliver input |
| GET | `/api/v1/executions/:eid/inputs` | Input delivery status |
| GET | `/api/v1/executions/:eid/history` | Event history |
| GET | `/api/v1/executions/:eid/diagram` | Diagram with progress |
| POST | `/api/v1/executions/:eid/cancel` | Cancel execution |
| POST | `/api/v1/executions/search` | Search executions |
| POST | `/api/v1/executions/cancel` | Cancel matching executions |

To compose with your own endpoints, use `Flow.runHandlers`:

```scala
Flow.runHandlers(store, orderFlow).map { handlers =>
    HttpServer.init((myHandlers ++ handlers.toSeq)*)
}
```

### Engine

`FlowEngine` provides the full programmatic API without HTTP:

```scala
FlowEngine.init(store, orderFlow, shippingFlow).map { engine =>
    for
        handle <- engine.workflows.start(Flow.Id.Workflow("order"))
        _      <- handle.signal("order", Order("Widget", 3))
        status <- handle.status
    yield status
}
```

The engine runs worker fibers that poll the store, claim executions via time-limited leases, and interpret the flow step by step. Configuration:

```scala
FlowEngine.init(
    store,
    workerCount  = 4,
    lease        = 30.seconds,
    renewEvery   = 10.seconds,
    batchSize    = 8,
    pollTimeout  = 30.seconds,
    flows        = Seq(orderFlow, shippingFlow)
)
```

## Monitoring

### Status

Executions transition through a status machine:

```
Running ──→ Completed
Running ──→ Failed (compensations run first if registered)
Running ──→ WaitingForInput ──→ Running (on signal)
Running ──→ Sleeping ──→ Running (on expiry)
Running ──→ Compensating ──→ Failed
Any non-terminal ──→ Cancelled
```

```scala
engine.executions.describe(eid).map { detail =>
    detail.status      // Flow.Status
    detail.progress    // step-by-step node progress
    detail.inputs      // which inputs are delivered
}
```

### Events

Every state change is recorded as a `Flow.Event`:

```scala
engine.executions.history(eid).map { page =>
    page.events  // Chunk[Flow.Event]
    page.hasMore // pagination
}
```

Event kinds: `Created`, `StepStarted`, `StepCompleted`, `StepRetried`, `StepTimedOut`, `InputWaiting`, `InputReceived`, `SleepStarted`, `SleepCompleted`, `ExecutionResumed`, `ExecutionClaimed`, `ExecutionReleased`, `Completed`, `Failed`, `CompensationStarted`, `CompensationCompleted`, `CompensationFailed`, `Cancelled`.

### Diagrams

Render workflow structure or execution progress:

```scala
engine.workflows.diagram(wfId, Flow.DiagramFormat.Mermaid)
engine.executions.diagram(eid, Flow.DiagramFormat.Dot)
```

Supported formats: `Mermaid`, `Dot`, `Bpmn`, `Elk`, `Json`. Also available directly on a flow definition:

```scala
Flow.renderMermaid(orderFlow)
```

## Custom Store

The in-memory store (`FlowStore.initMemory`) is for development and testing. For production, implement `FlowStore` against a durable database:

```scala
class PostgresFlowStore(pool: ConnectionPool) extends FlowStore:
    def claimReady(...) = // SELECT ... FOR UPDATE SKIP LOCKED
    def updateStatus(...) = // UPDATE + INSERT in one transaction
    // ... 15 abstract methods total
```

Key invariants:
- `claimReady` never returns the same execution to two concurrent callers
- `updateStatus` writes event + status atomically
- `putFieldIfAbsent` is an atomic check-and-write (exactly-once)
- `renewClaim` returns false if the claim was taken by another executor
- Terminal status cannot revert to non-terminal

## Multi-Executor Coordination

Multiple engine instances on the same store coordinate automatically via claim leases:

```scala
// Instance A
FlowEngine.init(store, workerCount = 2, lease = 30.seconds, flows = Seq(orderFlow))

// Instance B (same store, separate process)
FlowEngine.init(store, workerCount = 2, lease = 30.seconds, flows = Seq(orderFlow))
```

If an executor crashes, its lease expires and another executor picks up the work.
