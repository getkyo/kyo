# kyo-stm

<!-- doctest:setup
```scala
import kyo.*
import scala.concurrent.duration.*
```
-->

`kyo-stm` is a Software Transactional Memory (STM) module: a way to coordinate concurrent mutation of shared state without locks. You allocate transactional references (`TRef`, `TMap`, `TChunk`, `TTable`), read and write them inside a transaction, and call `STM.run` to commit. Transactions execute optimistically: each one builds a private log of reads and writes, and on commit the runtime acquires fine-grained read/write locks on just the references the transaction touched, validates that none have changed underneath it, and then applies the writes atomically. If validation fails, the runtime rolls back the log (no writes are visible) and reruns the transaction body under a configurable retry schedule. Lock acquisition is ordered by reference identity to prevent deadlocks, and an anti-starvation "barge" mode kicks in after a few polite retries so a writer cannot be held off indefinitely by sustained readers.

Because the body may rerun, the rule for what goes inside a transaction is narrow: pure operations on transactional references are the supported case. Anything observable outside the STM (println, network I/O, mutable non-transactional state) may be re-executed on retry and should run after the transaction commits, not inside it. The reward for that constraint is composability: a transaction over `ref1.update(...).andThen(ref2.update(...))` is a single atomic unit even when the two refs were written by code that did not know about each other, and nested `STM.run` calls join the parent transaction rather than committing independently.

```scala
val transfer: Unit < (Async & Abort[FailedTransaction]) =
    for
        from <- TRef.init(500)
        to   <- TRef.init(300)
        _ <- STM.run:
            for
                _ <- from.update(_ - 100)
                _ <- to.update(_ + 100)
            yield ()
    yield ()
```

## Transactional references

The four reference types share one pattern: allocate outside a transaction (the constructor returns `... < Sync`), then read and write inside `STM.run`. Pick the type by the shape of the state, not by how many readers or writers you expect. A money balance is a `TRef[Int]`. A keyed cache is a `TMap[String, V]`. An append-only log is a `TChunk[E]`. A set of records you want to look up by field is a `TTable`.

### TRef: single-cell state

A `TRef[A]` holds one value of type `A`. Use it when the state is a scalar (a counter, a balance, a status enum, a configuration snapshot). `TRef.init` allocates the ref; the value lives inside the runtime's transactional store, not in the surrounding `Sync` effect.

```scala
val balanceAfter: Int < (Async & Abort[FailedTransaction]) =
    for
        balance <- TRef.init(500)
        _ <- STM.run:
            balance.update(_ - 100)
        result <- STM.run(balance.get)
    yield result
```

The four operations on `TRef[A]` are read-only `get` and `use(f)`, and mutating `set(v)` and `update(f)`. `use(f)` is the continuation-style read: rather than reading into a Scala value and then chaining, you pass the function that consumes the value, and the implementation gets to skip materialising it through the effect machinery.

```scala
val balanceMatched: String < (Async & Abort[FailedTransaction]) =
    for
        balance <- TRef.init(500)
        result <- STM.run:
            balance.use:
                case b if b <= 0  => "empty"
                case b if b < 100 => "low"
                case _            => "ok"
    yield result
```

> **Note:** `TRef.init` returns `TRef[A] < Sync`, not `TRef[A] < STM`. Allocation runs in `Sync`, the resulting handle is then used inside `STM.run`. You almost never allocate a ref inside a transaction: the body may rerun, and a fresh ref on every attempt is rarely what you want.

### TRef.initWith: allocate and use in one shot

When you want a ref only for the duration of one transaction, `TRef.initWith` allocates the ref and hands it to a function, joining the enclosing transaction if one is in flight or opening a fresh one otherwise.

```scala
val withRef: Int < (Async & Abort[FailedTransaction]) =
    STM.run:
        TRef.initWith(10): ref =>
            for
                _ <- ref.update(_ * 3)
                r <- ref.get
            yield r
```

Calling `initWith` outside a transaction is the same as `init(value).map(use)`; calling it inside one threads the new ref into the same log so the allocation and first write commit together. `TMap.initWith` and `TChunk.initWith` follow the same pattern: allocate the structure inside the transaction and bind the body in one shot.

### TMap: keyed state with per-value concurrency

When many transactions update different keys of a shared map concurrently, reach for `TMap[K, V]`: updates to different keys touch different inner refs and never conflict with each other, while only structural changes (a `put` of a new key, a `remove`) write to the outer ref and serialize against each other. That per-key isolation comes from the internal shape: `TMap[K, V]` is `TRef[Map[K, TRef[V]]]`, one outer ref tracking the key set and one inner ref per value.

```scala
val stockAfter: Map[String, Int] < (Async & Abort[FailedTransaction]) =
    for
        stock <- TMap.init[String, Int]("sku-1" -> 10, "sku-2" -> 5)
        _ <- STM.run:
            for
                _ <- stock.updateWith("sku-1"):
                    case Present(n) => Maybe(n - 1)
                    case Absent     => Absent
                _ <- stock.put("sku-3", 20)
            yield ()
        snap <- STM.run(stock.snapshot)
    yield snap
```

> **Note:** Two concurrent transactions decrementing different SKUs never conflict, because each touches a different inner `TRef[Int]`. Two concurrent transactions inserting brand-new SKUs do conflict on commit, because both write the outer key set.

The read surface is `get`, `getOrElse`, `contains`, `size`, `isEmpty`, `nonEmpty`, `keys`, `values`, `entries`, `snapshot`, plus `use(key)(f)` for continuation-style reads, `fold(z)(f)` for accumulation, and `findFirst(f)` for short-circuiting search.

```scala
val totalStock: Int < (Async & Abort[FailedTransaction]) =
    for
        stock <- TMap.init[String, Int]("sku-1" -> 10, "sku-2" -> 5)
        total <- STM.run(stock.fold(0)((acc, _, qty) => acc + qty))
    yield total
```

The write surface is `put`, `updateWith`, `remove`, `removeDiscard`, `removeAll(keys)`, `clear`, and `filter(p)` (in-place: drops entries that fail the predicate). `remove` returns the previous value as a `Maybe`; `removeDiscard` is the same operation when you don't need the previous value back.

`updateWith` is the precondition-respecting update. Pass a function from `Maybe[V]` to `Maybe[V]`: `Absent` returned means "delete this key (if present)", `Present(v)` means "set this key to `v`".

```scala
val reserved: Map[String, Int] < (Async & Abort[FailedTransaction]) =
    for
        stock <- TMap.init[String, Int]("sku-1" -> 1)
        _ <- STM.run:
            stock.updateWith("sku-1"):
                case Present(n) if n > 0 => Maybe(n - 1)
                case Present(_)          => Absent
                case Absent              => Absent
        snap <- STM.run(stock.snapshot)
    yield snap
```

### TChunk: transactional sequence

`TChunk[A]` is the right choice when the access pattern reads or rewrites the sequence as a whole (append-only logs, snapshots of recent events, ordered lists of size known to stay small enough that whole-chunk replacement is cheap). It is the wrong choice if you want to mutate isolated positions concurrently: that pattern is a `TMap[Int, A]`, because `TChunk` writes will always conflict on the single underlying ref. The reason is the internal shape: `TChunk[A]` is `TRef[Chunk[A]]`, and every write replaces the whole chunk.

```scala
val events: Chunk[String] < (Async & Abort[FailedTransaction]) =
    for
        log  <- TChunk.init[String]
        _    <- STM.run(log.append("opened"))
        _    <- STM.run(log.append("transferred"))
        snap <- STM.run(log.snapshot)
    yield snap
```

The read surface is `size`, `isEmpty`, `get(index)`, `head`, `last`, `use(f)`, `snapshot`. The write surface is `append`, `take(n)`, `drop(n)`, `dropRight(n)`, `slice(from, until)`, `concat(other)`, `filter(p)`, and `compact` (rebuilds the underlying chunk in indexed form so the GC can release any larger chunk it was a slice of).

> **Note:** `TChunk` operations like `take`, `drop`, and `slice` are persistent on the `Chunk` side but transactional on the `TChunk` side: they replace the stored chunk wholesale. Two transactions that both call `append` will serialize on commit even though they don't touch overlapping positions.

### TTable: record store with auto IDs

A `TTable[F]` is a CRUD-shaped record store. The field schema `F` is a kyo-data record type built from `~` and `&`: `"name" ~ String & "age" ~ Int` says "a record with a `name: String` and an `age: Int` field". IDs are assigned from a lock-free atomic counter, so concurrent `insert` calls never contend on a shared TRef.

```scala
val people =
    for
        table <- TTable.init["name" ~ String & "age" ~ Int]
        _     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
        _     <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 25))
        snap  <- STM.run(table.snapshot)
    yield snap
```

The operations on `TTable[F]` are `get(id)`, `insert(record)`, `update(id, record)`, `upsert(id, record)`, `remove(id)`, `size`, `isEmpty`, and `snapshot`. `update` returns the previous record (`Maybe[Record[F]]`); `upsert` writes regardless of whether the row existed. The ID type `Id` is a path-dependent opaque `Int` subtype; cross the boundary with `unsafeId(id)` only when you must.

### TTable.Indexed: compile-time-checked queries

`TTable.Indexed[F, Indexes]` is a `TTable[F]` with auto-maintained secondary indexes. The second type parameter `Indexes` is the subset of fields you want indexed; the compiler rejects queries whose filter mentions a field that isn't in `Indexes`.

```scala
val matches: Chunk[Record["name" ~ String & "age" ~ Int]] < (Async & Abort[FailedTransaction]) =
    for
        table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
        _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
        _       <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 25))
        _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 35))
        results <- STM.run(table.query("name" ~ "Alice"))
    yield results
```

The query surface is `queryIds(filter)` (just the IDs) and `query(filter)` (the full records). A filter that mentions a non-indexed field is rejected at compile time:

```scala
// table indexes "name" only; querying by "age" fails to compile:
//   table.query("age" ~ 30)
// Cannot query on fields that are not indexed.
// The filter contains fields that are not part of the table's index configuration.
// Filter fields: "age" ~ Int
// Indexed fields: "name" ~ String
```

`indexFields` returns the set of indexed field names at runtime. Indexes are maintained automatically across `insert`, `update`, `upsert`, and `remove`: an update that changes an indexed field drops the old index entry and adds the new one in the same transaction.

## Running a transaction

`STM.run` is the discharge point: it turns a `... < (STM & ...)` computation into one that no longer has `STM` in its row, and adds `FailedTransaction` to the `Abort` row. Each call runs the body, attempts to commit, and on commit failure reruns the body under a retry schedule. Nested calls are not separate transactions; they join the parent.

### STM.run: commit a transaction

The simplest form takes a transactional body and runs it under the default retry schedule.

```scala
val result: Int < (Async & Abort[FailedTransaction]) =
    for
        ref <- TRef.init(0)
        _   <- STM.run(ref.update(_ + 1))
        r   <- STM.run(ref.get)
    yield r
```

The returned type carries `Async` (because the retry loop suspends between attempts) and `Abort[E | FailedTransaction]` where `E` is whatever the body's `Abort` row already contained. A body that raises a user error is still subject to a probe-commit: if the log is stale the runtime retries, on the theory that the error may have been computed from stale state; if the log is valid the user error propagates.

### STM.run: custom retry schedule

The second `STM.run` overload takes a `Schedule` from kyo-core. Use it when the default budget is wrong for your workload: a foreground request handler might want a tight bound and a `FailedTransaction` it can map to a 503; a background reconciler might want `Schedule.forever`.

```scala
val tightlyBudgeted: Int < (Async & Abort[FailedTransaction]) =
    for
        ref <- TRef.init(0)
        _ <- STM.run(Schedule.fixed(1.millis).take(10)):
            ref.update(_ + 1)
        r <- STM.run(ref.get)
    yield r
```

The default schedule (`STM.defaultRetrySchedule`) is `Schedule.fixed(1.millis).jitter(0.5).take(Async.defaultConcurrency * 16)`: a 1ms fixed delay with 50% jitter, capped at sixteen times the default concurrency level. The cap scales with concurrency because under N-way contention a transaction needs roughly O(N) retries to win its commit; the multiplier gives tail margin without becoming unbounded.

> **Note:** A genuinely livelocked transaction fails with `FailedTransaction` after the budget is exhausted, rather than looping forever. If you see `FailedTransaction` in production you should treat it as a signal that contention has exceeded your retry budget, not as a transient error to be ignored.

### STM.retry and STM.retryIf: abort and reschedule

`STM.retry` aborts the current attempt and reschedules the whole body under the retry policy. `STM.retryIf(cond)` is the conditional form: if `cond` is true the attempt aborts, otherwise execution continues.

```scala
val withdraw: Int < (Async & Abort[FailedTransaction]) =
    for
        balance <- TRef.init(500)
        _ <- STM.run:
            for
                b <- balance.get
                _ <- STM.retryIf(b < 100)
                _ <- balance.update(_ - 100)
            yield ()
        r <- STM.run(balance.get)
    yield r
```

> **Caution:** `STM.retry` is not a "wait until condition" primitive. It forces the current attempt to abandon and rerun the body. The body is what re-checks the condition, so if you write `retryIf(cond)` and nothing the body reads ever changes, the body will keep retrying with the same answer until the schedule is exhausted. Make sure the condition depends on at least one ref that other transactions write to.

`STM.retry` and `STM.retryIf` cover two different shapes. Use `STM.retryIf(cond)` when you want to express a precondition inline (the most common case). Use `STM.retry` when the body has computed enough state to decide on its own that the current attempt should not be allowed to commit.

### Nested STM.run

An `STM.run` call inside another `STM.run` does NOT start a new transaction. It shares the parent's transaction context and only isolates the ref log: success of the inner block propagates to the parent, failure rolls back only the inner log.

```scala
val composite: Int < (Async & Abort[FailedTransaction]) =
    for
        ref <- TRef.init(0)
        r <- STM.run:
            for
                _ <- ref.update(_ + 1)
                _ <- STM.run(ref.update(_ + 10)) // not a new transaction
                v <- ref.get
            yield v
    yield r
```

> **Note:** kyo-stm has no `orElse` operator. Nested composition works through two mechanisms: `Abort` (an inner block's failure can be caught with `Abort.run` without affecting the outer transaction's commit) and the shared transaction log (everything the inner block writes is visible to the rest of the outer body and commits with it). A nested `STM.run` call joins the parent transaction rather than committing independently.

## What may run inside a transaction

Optimistic concurrency means the body of a transaction may be re-executed any number of times before it commits. Two consequences follow.

### Side effects: after commit, not inside

Anything observable outside the STM (printing, sending a network message, mutating a non-transactional `var`) will be re-executed on every retry, with no rollback. The rule is: pure operations on transactional refs go inside the transaction; everything else goes after.

```scala
val transferAndNotify: Unit < (Async & Abort[FailedTransaction] & Sync) =
    for
        from <- TRef.init(500)
        to   <- TRef.init(300)
        _ <- STM.run:
            for
                _ <- from.update(_ - 100)
                _ <- to.update(_ + 100)
            yield ()
        // After commit, side effects run exactly once.
        _ <- Sync.defer(println("transferred 100"))
    yield ()
```

> **Caution:** A `println`, log emit, or counter increment inside the transaction body will be executed once per attempt. If a transaction conflicts ten times before committing, you will see ten log lines for one logical transfer. This is intentional, not a bug: the runtime cannot know which effects are safe to retry. Push them out.

### Isolate requirement

Because a failed attempt must carry no changes into the next retry, the runtime needs to snapshot transactional state on entry and restore it cleanly each time. That snapshotting is provided by an `Isolate` instance: `STM.run` requires an `Isolate[S, Async & Abort[E | FailedTransaction], S]` for whatever effects `S` the body uses. A `Var[Int]` inside a transaction, for example, needs an isolate so the runtime can capture its value before the attempt and reset to that value on each retry.

Effects without an `Isolate` instance cannot be used inside a transaction. Effects with an `Isolate` instance (the standard kyo-prelude effects: `Var`, `Emit`, `Choice`, `Memo`, `Aspect`) compose naturally; the isolate threading is invisible at the call site.

### Anti-starvation: barging

After `bargeThreshold` polite retries (default 4) a writer ignores the `readTick` fairness yield and acquires its write lock past pending readers. Without this, a writer competing against a continuous stream of readers on the same ref could be held off indefinitely. The threshold is tunable via the `kyo.bargeThreshold` system property; the default is intentionally low so that the common, low-contention path stays fully polite.

Barging is opacity-neutral: correctness is enforced by `validate` in `TRef.lock`, not by the `readTick` yield that barging drops. The barging writer still acquires only an unheld lock; it never steals a lock another transaction physically holds.

## Low-level escape hatches

### TRef.Unsafe.init: allocate outside any orchestration

`TRef.Unsafe.init` allocates a `TRef` with a synthetic tick, bypassing both the `Sync` allocation path and the in-transaction `withCurrentTransactionOrNew` orchestration. It exists for library integrations and performance-sensitive code that needs to hand out refs without paying the orchestration cost.

```scala
import AllowUnsafe.embrace.danger
val unsafelyAllocated: TRef[Int] = TRef.Unsafe.init(42)
```

> **Caution:** Calling `TRef.Unsafe.init` from inside an in-flight transaction produces a ref whose tick is unrelated to the transaction tick and can cause spurious retries. Use `TRef.initWith` instead when you want to allocate inside a transaction; the orchestration is the whole point.

## When a transaction gives up

### FailedTransaction

`FailedTransaction` is the terminal failure raised when the retry schedule is exhausted. It extends `KyoException` and appears in the `Abort` row of `STM.run`'s return type. When the underlying cause was a user `Abort.fail(...)` that survived a probe-commit (the log was valid, the user error was real), the `FailedTransaction` carries the underlying `Result.Error` in its message.

```scala
val handled: String < Async =
    val tx: Int < (Async & Abort[FailedTransaction]) =
        for
            ref <- TRef.init(0)
            _ <- STM.run(Schedule.fixed(1.millis).take(1)):
                STM.retry
        yield 0
    tx.handle(Abort.run).map:
        case Result.Success(_) => "committed"
        case Result.Failure(_) => "gave up after retries"
        case p: Result.Panic   => s"panic: ${p.exception}"
end handled
```

Treat `FailedTransaction` as a real failure, not a transient error. If you see it in production, contention has exceeded your retry budget; the fix is either to widen the budget (a custom `Schedule` passed to `STM.run`) or to reshape the data so that fewer transactions touch the same ref (per-key locking via `TMap` rather than a single `TRef[Map[...]]`).
