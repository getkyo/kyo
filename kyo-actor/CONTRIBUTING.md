# Contributing to kyo-actor

This guide complements the root [CONTRIBUTING.md](../CONTRIBUTING.md), which covers global Kyo conventions (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, file organisation, visibility tiers, the test framework, cross-platform source placement, `AllowUnsafe`). Defer to the root guide for those; this file covers only what is specific to kyo-actor.

**The headline invariant:** every `Actor[E, A, B]` has exactly one consumer fiber. This is not an implementation detail; it is the load-bearing property that makes actor state thread-safe. Before modifying anything in this module, internalise both halves of that guarantee: the channel is created `MultiProducerSingleConsumer`, and `Actor.subscribe` is producer-only (its pump only calls `send`, never `take`).

## Architecture overview

kyo-actor provides three layered abstractions, all composing through `Actor.Subject`.

| Layer | Types | Purpose |
|-------|-------|---------|
| Core actor | `Actor[E, A, B]`, `Actor.Context[A]`, `Actor.Subject[A]` | Private bounded mailbox, single sequential consumer, FIFO, typed errors |
| Strand-safe request/reply | `Actor.ask`, `Actor.respond`, `Actor.respondLoop`, `Actor.PendingReplies`, `Actor.Ask[Req, Resp]` | Reply always completes; actor termination is never a strand |
| Publish/subscribe | `PubSub[A]`, `Subject.init(hub)`, `Actor.subscribe` | Push fan-out (`PubSub`) and pull fan-out (`Hub`) both surfaced through `Subject` |

Source layout: all code lives in `shared/src/main/scala/kyo/` and is cross-platform (JVM, Scala.js, Scala Native). There are no platform-specific source trees in this module.

---

## The actor model

### Mailbox and single-consumer invariant

`Actor.run` creates a bounded `Channel[A]` with `Access.MultiProducerSingleConsumer`. The consumer loop runs in a single `Fiber` and is the only reader of that channel. Because state held in `Var.run` or any other effect layer inside the behavior is also touched only by that one fiber, no synchronization is needed on the state itself.

```
mailbox: Channel[A]   (MPSC, bounded, one reader: the consumer loop)
  <- send / trySend   (any number of producers, concurrent)
  -> consumer fiber   (sole reader; runs the behavior)
```

**Invariant to preserve:** never add a second consumer to the mailbox. The pump fiber in `Actor.subscribe` is a producer: it calls `subject.send`, which calls `mailbox.put`. It never calls `mailbox.take`. Any change that adds a second `take`/`poll` on the channel breaks the invariant and introduces data races on actor state.

### Behavior model

There is no hidden runtime loop. `Actor.run` repeatedly calls `Poll.runFirst` on the behavior value. When the behavior asks for the next message, `Poll.runFirst` returns a continuation; the consumer loop calls `mailbox.take` and resumes the continuation with the result. When the behavior produces a value (`Loop.done`, a return, or a completed effect), the fiber completes.

The four receive combinators differ only in their termination condition:

| Combinator | Terminates when |
|------------|-----------------|
| `receiveAll` | Mailbox closes |
| `receiveMax(n)` | `n` messages processed |
| `receiveLoop` (no state) | Body returns `Loop.done`, or the mailbox closes |
| `receiveLoop` (with state) | Body returns `Loop.done(finalState)`, or the mailbox closes |

### `Actor.Context[A]`

```scala
opaque type Context[A] <: Poll[A] & Env[Subject[A]] & Abort[Closed] & Scope & Async
```

`Context[A]` is the effect row every receive combinator operates in. `Actor.run` provides it; behaviors never construct it directly. `Poll[A]` is what the combinator consumes from the mailbox. `Env[Subject[A]]` is what `Actor.self` and `Actor.reenqueue` read. `Abort[Closed]` surfaces mailbox-closure failures. `Scope` registers child actors with their parent's resource lifetime. `Async` allows suspension between messages.

### `Actor[E, A, B]`: the handle

```
E: errors that can terminate the actor if not handled internally
A: the message type (received via the mailbox)
B: the value produced when the behavior completes normally
```

The `fiber` field is `Fiber[B, Abort[Closed | E]]`. The covariance `+E` on the class means an `Actor[Nothing, A, B]` is a subtype of `Actor[Throwable, A, B]`; do not widen the error type unnecessarily.

`close` keeps its name because it is consistent with `Channel.close`, `Hub.close`, `Queue.close`, and `PubSub.close`, all of which produce `Closed`. The actor-domain verbs `stop` / `Terminated` are reserved for a future death-watch feature and must not be introduced here.

---

## `Actor.Subject[A]`: the uniform send handle

`Subject[A]` is the `ActorRef` analog: a write-only sink that abstracts over the delivery topology. Its three abstract methods are:

```
send(message: A): Unit < (Async & Abort[Closed])      // reliable; suspends under backpressure
trySend(message: A): Boolean < (Sync & Abort[Closed]) // non-blocking; false if at capacity
ask[B](f: Subject[B] => A): B < (Async & Abort[Closed])
```

The `ask` method on `Subject` builds a one-shot reply promise, sends the request, then waits via `awaitReply` (a `private[kyo]` hook). The default `awaitReply` simply waits on the reply promise. The actor mailbox subject overrides it to race the reply against the actor's termination signal, so `actor.subject.ask` is strand-safe. A sink built without that override (a channel, queue, hub, promise, or any `Subject.init(send, trySend)`, including a `contramap`ped subject) keeps the default wait and is not strand-safe. See "Strand-safe request/reply" below for the full model.

### Contravariance and `contramap`

`Subject` is a write-only sink, so it is contravariant in its message type: you can always pass a `Subject[Fruit]` where a `Subject[Apple]` is expected (because a sink that accepts any fruit certainly accepts apples). `contramap` is the lawful adapter:

```scala
def contramap[B](f: B => A): Subject[B]
```

There is no `map` on `Subject`. `send` returns `Unit`; there is no value to map over from the recipient's perspective. Do not add one.

The `contramap`-produced subject uses the default `awaitReply`, not a lifecycle-aware one. When a caller needs a strand-safe `ask`, they must hold the original `Actor` handle, not a contramapped view.

### `Subject.init` overloads

| Constructor | Delivery | `trySend` |
|-------------|----------|-----------|
| `Subject.init(promise)` | Completes promise; second send returns `Abort[Closed]` | Same; one-shot |
| `Subject.init(channel)` | `channel.put` (suspends) | `channel.offer` (false if full) |
| `Subject.init(queue)` | `queue.add` (unbounded, never suspends) | `queue.add`; always `true` |
| `Subject.init(hub)` | `hub.put` (suspends) | `hub.offer` (false if full) |
| `Subject.init(send, trySend)` | Custom pair | Custom |

The `Subject.noop` singleton discards every message and is implemented as an `asInstanceOf` cast on a single `val _noop`, so it allocates once at module load.

---

## Strand-safe request/reply

### The defect that `PendingReplies` fixes

Before this mechanism, `Subject.ask` enqueued the request message and then called `promise.get`. If the actor terminated (scope shutdown, `close`, panic, handler failure) after the message was enqueued but before the reply was sent, `promise.get` would suspend forever, stranding the caller.

### `PendingReplies`

`PendingReplies` is a per-actor registry of in-flight `ask` reply promises. It is created once per `Actor.run` call and holds:

- A `CopyOnWriteArraySet[Promise[Any, Abort[Closed]]]` of still-pending replies.
- An `AtomicBoolean.Unsafe` flag (`terminated`) set before the termination sweep iterates.

Two operations:

- `awaitReply[C, E](reply, send)(onTerminated)`: registers `reply`, runs `send`, awaits `reply.get`. If the actor ends first, the sweep fails the promise with `Closed` and `Abort.recover[Closed]` routes to `onTerminated`. The registration is removed on resolution via `Sync.ensure`.
- `terminate()(using AllowUnsafe)`: called once from the consumer fiber's `onComplete` callback. Sets `terminated = true`, then iterates a `toArray` snapshot and calls `completeDiscard(Result.fail(Closed(...)))` on each entry. Completion is first-wins: a promise already completed by the recipient is unaffected.

### The add-after-termination race

A request can be accepted by the mailbox, the actor can terminate and run the sweep, and only then does the caller register the reply promise. The `terminated` flag closes this race: `awaitReply` checks the flag after `add` and self-fails if it is already set. Either the sweep sees the freshly added entry, or the entry self-fails. The two branches are mutually exclusive because `completeDiscard` is first-wins.

### Invariant a contributor must preserve

The `PendingReplies` set must stay bounded by concurrently in-flight asks. It must never grow with the actor's lifetime. Every path that calls `awaitReply` must have a corresponding `Sync.ensure(waiters.remove(entry))` that fires on success, failure, and interruption.

### Which `ask` is strand-safe, and what it surfaces

There are three `ask` entry points, and they differ in how they couple to the actor's liveness:

| Entry point | Strand-safe | Terminal outcome on early actor death |
|-------------|-------------|---------------------------------------|
| `Actor.ask` (the instance method on the handle) | Yes | Refines to the actor's `E` or a panic; an interrupt reads as `Closed` |
| `actor.subject.ask` (the mailbox subject) | Yes | Collapses to `Closed` |
| `subject.ask` on a contramapped or arbitrary sink | No | Suspends forever if the recipient never replies |

`Actor.ask` does NOT go through the `Subject.ask` / overridden-`awaitReply` path. It builds its own typed `Promise[C, Abort[Closed]]` and calls `_pending.awaitReply` directly; on termination its `onTerminated` continuation polls `_fiber` non-interruptibly and maps the result to `Abort[Closed | E]` or a panic. That fiber poll is why `Actor.ask` can surface the actor's actual `E`/panic.

`actor.subject.ask` IS strand-safe: the actor's mailbox subject overrides the `private[kyo] awaitReply` hook to delegate to the same `pending.awaitReply`, but its `onTerminated` only fails with `Closed`. So `actor.subject.ask` never strands a caller; it just cannot distinguish a handler failure from a plain shutdown, both collapse to `Closed`.

`contramap` builds a NEW subject from the underlying `send` / `trySend` (via `Subject.init`); it does NOT carry the override. Its `ask` therefore uses the default `awaitReply`, which only waits on the reply promise with no termination signal. The same is true of any sink built from `Subject.init` (channel, queue, hub, promise, custom). When you need a strand-safe `ask`, hold the original `Actor` handle (best, refines `E`/panic) or its `subject` (strand-safe, collapses to `Closed`), never a contramapped view.

```scala
// Actor.ask signature (instance method on the handle)
def ask[C](f: Subject[C] => A)(using frame: Frame): C < (Async & Abort[Closed | E])

// Subject.ask signature (base class; the mailbox subject overrides awaitReply)
def ask[B](f: Subject[B] => A)(using Frame): B < (Async & Abort[Closed])
```

For `E = Nothing` (every actor in this module that models per-request errors in the reply type), `Closed | Nothing = Closed`, so the `Actor.ask` widening is zero source impact.

### `Actor.respond` and `Actor.respondLoop`

`respond` is the framework-owned request/reply combinator. It wraps the receive loop so the reply is always sent by the framework, never the handler:

```scala
def respond[Req, Resp](handler: Req => Resp < S): Unit < (Context[Ask[Req, Resp]] & S)
```

The framework sends `handler(msg.request)` to `msg.replyTo` before looping. A handler that forgets to reply cannot strand a caller because there is no opportunity to forget: `handler` only produces the response value, not the send.

`respondLoop` is the stateful variant, threading `State` across requests. Like the other stateful loops it returns the final `State`, the state after the last processed request, yielded when the mailbox closes:

```scala
def respondLoop[Req, Resp, State](state: State)(handler: (Req, State) => (Resp, State) < S): State < (Context[Ask[Req, Resp]] & S)
```

Both require `Tag[Poll[Ask[Req, Resp]]]` in scope. The `Ask[Req, Resp]` envelope is what the actor's message type is set to; callers use `actor.ask(request)` via the `A =:= Ask[Req, Resp]` evidence on the instance method.

Model expected per-request errors in `Resp` (for example `Result[E, Resp]`). A handler that calls `Abort.fail` terminates the actor; in-flight callers observe this through `Actor.ask`.

---

## Publish/subscribe

### Hub bridge: pull-based (`Subject.init(hub)` + `Actor.subscribe`)

`Subject.init(hub)` creates a publish-side subject that maps `send`/`trySend` to `hub.put`/`hub.offer`. Any number of callers can hold this subject and publish without knowing who is listening.

`Actor.subscribe(hub)(adapt)` funnels hub events into the subscribing actor's mailbox. Internally it creates a `Scope`-managed listener via `hub.listen`, then launches a pump fiber:

```
pump fiber: listener.take -> adapt -> subject.send (into actor mailbox)
```

The pump is producer-only. It never calls `mailbox.take`. The actor keeps a single consumer. All mailbox messages, both direct sends and hub-delivered events, are processed sequentially by the same behavior loop, with no concurrent access to state.

The pump stops when the listener or the actor mailbox closes (a `Result.Failure(Closed)` from either direction). Real failures (panics) propagate.

### PubSub: push-based

`PubSub[A]` delivers each published value directly into subscriber sinks by calling `send` on each one. The subscriber's own mailbox (or channel, or queue) is the buffer.

| Constructor | Fan-out style | Order guarantee |
|-------------|---------------|-----------------|
| `PubSub.init[A]` | Concurrent via `Async.foreach` | Per-subscriber FIFO; no total order across subscribers under concurrent publishers |
| `PubSub.linearized[A]` | Sequential through one actor mailbox | Total order: every subscriber observes publishes in the same sequence |

Both constructors take an optional `concurrency: Int` (`PubSub.init[A](concurrency)`, `PubSub.linearized[A](concurrency)`): it bounds how many subscribers a single publish delivers to in parallel through `Async.foreach`. The no-arg forms delegate with `Async.defaultConcurrency`. The bound must be `>= 1` (a value of `1` delivers sequentially); a smaller value fails with `IllegalArgumentException`. For `linearized` the bound only limits parallelism within one publish's fan-out: the total order across publishes is unaffected.

**When to use each:**

- Use `PubSub.init` when per-subscriber FIFO suffices and you do not need all subscribers to agree on the relative order of concurrent publishes.
- Use `PubSub.linearized` when all subscribers must observe events in the same total order (for example: audit and fraud observers on the same transaction stream, where `auditObserved == fraudObserved.map(_.tx)` must hold).
- Use `Hub` (pull) when subscribers need to drain at their own rate. A slow subscriber to a `PubSub` applies backpressure to every publisher; a slow `Hub` listener falls behind independently.

**Closed-sink pruning:** on each publish, subscribers that return `Closed` are collected and removed from the set in one atomic update. This is lazy: a recently-closed sink may still appear in `subscriberCount` until the next publish reaches it.

### `PubSub.Command[A]` and the dynamic-Tag rule

`PubSub.linearized` creates an actor whose message type is `Command[A]`, a sealed enum. The method signature requires several composed tags as `using` parameters:

```scala
def linearized[A: Tag](using
    frame: Frame,
    stateTag: Tag[Var[Set[Subject[A]]]],
    commandTag: Tag[Command[A]],
    pollTag: Tag[Poll[Command[A]]],
    emitTag: Tag[Emit[Command[A]]],
    subjectTag: Tag[Subject[Command[A]]]
): PubSub[A] < (Scope & Async)
```

This is the dynamic-Tag rule in `package kyo`: the compiler cannot synthesize a tag for a composed type like `Command[A]` when `A` is abstract inside the method. The tags must be lifted to `using` parameters so they are resolved at the concrete call site where `A` is known. Any new method in this module that creates an actor or effect whose type involves an abstract type parameter must follow the same pattern.

### `Subject.contramap` in pub/sub

`contramap` is the standard way to subscribe an actor with a sum message type to a `PubSub[E]`:

```scala
topic.subscribe(actor.subject.contramap(MyMsg.Event(_)))
```

The topic holds a `Subject[E]`; the adapter maps each `E` to a `MyMsg.Event` before forwarding to the actor's mailbox. Direct sends and topic-delivered events share the same mailbox and are processed sequentially.

---

## Conventions

### Cross-platform discipline

All source lives in `shared/src/main/scala/kyo/` and `shared/src/test/scala/kyo/`. There are no `jvm/`, `js/`, or `native/` source trees in kyo-actor. If a change requires platform-specific behavior, discuss before adding a platform split.

Tests run on JVM, Scala.js, and Scala Native from the shared test tree. Never move a test into a platform-specific subtree to dodge a platform-cost; fix the underlying issue.

### `AllowUnsafe` sites

`PendingReplies` holds mutable state accessed from the `Sync`-only `onComplete` callback, which cannot suspend. The `AllowUnsafe` sites are:

- `AtomicBoolean.Unsafe.init(false)` at construction.
- In `terminate`: `terminated.set(true)`, then `completeDiscard(...)` on each swept reply.
- In `awaitReply` (the `register` step): `terminated.get()` to detect the add-after-termination race, then `completeDiscard(...)` to self-fail when it fires.

Each is marked with a `// Unsafe:` comment explaining which safe-tier contract it bridges. Every new unsafe site in this module must follow the same convention.

### Suspended allocation of mutable state

`PendingReplies` is constructed inside `Sync.defer(new PendingReplies)` in `Actor.run`. This preserves referential transparency: the constructor has a side effect (allocating a `CopyOnWriteArraySet`), so it must run inside a `Sync` suspension, not at the definition site of the `for` comprehension.

Any new mutable state holder introduced to `Actor.run` must be allocated inside `Sync.defer` for the same reason.

### `private[kyo]` over `protected`

The root CONTRIBUTING.md prohibits `protected`. Use `private[kyo]` for cross-package visibility within the `kyo` package. `Actor.PendingReplies`, `Subject.awaitReply`, and `PubSub.Command` are all `private[kyo]`.

### Kyo types

| Use this | Not this |
|----------|----------|
| `Maybe` | `Option` |
| `Result` | `Either` / `Try` |
| `Chunk` | `List` / `Seq` (in public APIs) |

### Test file naming

Test files follow the 1:1 rule with source files:

| Source | Test file |
|--------|-----------|
| `Actor.scala` | `ActorTest.scala`, `SubjectTest.scala` |
| `PubSub.scala` | `PubSubTest.scala` |

`Actor.scala` defines both `Actor` and its nested `Subject`, so it has two test files split by aspect: `ActorTest.scala` covers the actor surface, `SubjectTest.scala` covers the `Subject` surface. Both share the `Actor.scala` source prefix, so neither is an orphan. Do not create orphan test files. Scratch / reproduction files must be removed before a change is considered complete: fold the validated assertions into the matching `*Test.scala` and delete the scratch file.

### README doctests

The module README (`kyo-actor/README.md`) is doctest-validated by `sbt 'kyo-actorJVM/doctest'`. Every fenced `scala` block in `README.md` must compile and produce the expected output. `CONTRIBUTING.md` (this file) is not doctest-validated.

---

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-actorJVM/test'

# A single test class
sbt 'kyo-actorJVM/testOnly kyo.ActorTest'

# Validate README code blocks
sbt 'kyo-actorJVM/doctest'
```

Building automatically runs scalafmt. Re-read any file you edit after building; formatting may have changed it.

See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for full conventions on naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

---

## Decision checklist: before adding a new X

Run through this list before touching the actor internals or adding a new public surface.

1. **New receive combinator.** Does it call `Poll.values`, `Poll.one`, or `Poll.runFirst` exclusively? Does it run in `Context[A]`? Does it not expose a second channel consumer? Add it to `ActorTest` (not a new file).

2. **New `Subject.init` overload.** Does `trySend` match the underlying sink's non-blocking API? Does it work on all three platforms? Does the scaladoc describe the backpressure behavior?

3. **New `ask`-style method.** Does it register with `PendingReplies` before sending the message? Does it have a `Sync.ensure` that removes the registration on all exit paths (reply, failure, interruption)? Does the caller always complete; can it strand?

4. **New pub/sub primitive.** Is it push (like `PubSub`) or pull (like `Hub` bridge)? Does subscriber delivery preserve the single-consumer invariant? Does a slow subscriber apply backpressure only to itself (pull) or to all publishers (push)?

5. **New actor-internal mutable state.** Is it allocated inside `Sync.defer`? Is each `AllowUnsafe` site annotated with `// Unsafe:`?

6. **New method with abstract type parameters involving composed tags.** Are the composed tags lifted to `using` parameters so they are resolved at the concrete call site? (See the dynamic-Tag rule above.)

7. **New test.** Does it fold into the existing `ActorTest`, `SubjectTest`, or `PubSubTest`? Does it assert on a concrete value, not just `assert(true)` or type membership? Does it include at least one edge case (closed actor, empty mailbox, concurrent senders)?
