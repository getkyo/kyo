# kyo-test

kyo-test is the Kyo project's own test framework. You write a suite by extending `Test[S]` and registering cases with two infix operators on string names. `"name" - { ... }` declares a GROUP whose body runs immediately at registration, so its nested `-`/`in` calls fire and build the tree (loops, helper defs, and conditionals inside a group all register their children). `"name" in { ... }` declares a LEAF, and the leaf body is the test: a Kyo computation of type `Unit < (S & Async & Abort[Any] & Scope)` that is captured, not run, until the runner executes it.

Because the body is an ordinary Kyo value, an asynchronous test and a synchronous one share the same shape: a bare `()` or `assert(...)` auto-lifts into the effectful type, with no `Future` or `toFuture` shim. Inside a leaf you assert with a single power-`assert` that renders a subexpression diagram on failure.

The type parameter `S` is an additive extra effect row. The baseline `Async & Abort[Any] & Scope` is always present and cannot be dropped; `Test[Any]` is the common case (baseline only), spelled explicitly because Scala 3 has no default type arguments. A suite that needs an extra effect (a database `Env[Db]` or a `Var`) declares it in `S` and discharges it with `.handle` at the leaf or group level, so the runner only ever sees a baseline-shaped leaf. Decorators chain off the name before the operator (`"slow op".retry(3).timeout(5.seconds) in { ... }`). Property-based and snapshot testing are opt-in by extending `PropertyTest[S]` or `SnapshotTest[S]` instead of `Test[S]`. The whole surface compiles and runs on JVM, JS, and Scala Native.

<!-- doctest:setup
```scala
import kyo.*
import kyo.test.*

case class Account(id: Int, owner: String, balanceCents: Long)
final case class Db(url: String)
case class Url(scheme: String, host: String, path: String)

object Bank:
    def balance(id: Int): Long < (Env[Db] & Sync)              = 0L
    def deposit(id: Int, cents: Long): Unit < (Env[Db] & Sync) = ()
    def withdraw(id: Int, cents: Long): Unit < (Env[Db] & Abort[InsufficientFunds] & Sync) =
        Abort.fail(InsufficientFunds(id))
end Bank

case class InsufficientFunds(accountId: Int) extends Exception(s"account $accountId overdrawn")

def parse(raw: String): Result[String, Url] =
    raw.split("://", 2) match
        case Array(scheme, rest) if rest.contains("/") =>
            val slash = rest.indexOf('/')
            Result.succeed(Url(scheme, rest.take(slash), rest.drop(slash)))
        case _ => Result.fail(s"malformed url: $raw")
```
-->

```scala
import kyo.*
import kyo.test.*

class BankTest extends Test[Any]:
    "a fresh account has zero balance" in {
        val account = Account(1, "ada", balanceCents = 0L)
        assert(account.balanceCents == 0L)
    }
end BankTest
```

A suite is a class; its body runs once at construction and registers every case. You point sbt at the module and the runner discovers the class, instantiates it, and executes each leaf.

## Writing a suite

A suite is the unit you register and run. Extend `Test[S]`, pick `S` for the extra effects your leaves need, and fill the class body with `-` groups and `in` leaves.

### `Test[S]` and the baseline

`Test[Any]` is the suite you reach for first: its leaves use only the always-present baseline `Async & Abort[Any] & Scope`. You write `Test[Any]` explicitly because Scala 3 has no default type argument; the `Any` is the empty extra row, not "any effect".

The baseline is unioned into every leaf body regardless of `S`, so you cannot narrow it away. `Abort[Any]` (not `Abort[Throwable]`) is deliberate: a leaf may abort with any value, and the runner converts a non-`Throwable` abort into a `LeafAborted` failure at its boundary. `Async` lets a leaf suspend; `Scope` lets a leaf acquire resources that are released when the leaf ends.

When a leaf needs more (a database handle, mutable state, a seeded RNG), declare it in `S` and discharge it with `.handle` (see [Per-test and per-suite setup](#per-test-and-per-suite-setup)).

### Groups (`-`) and leaves (`in`): two operators, no inference

`"name" - { body }` is ALWAYS a group: the body runs at registration so its nested `-`/`in` calls fire and build the tree. `"name" in { body }` is ALWAYS a leaf: the body is the test and is deferred until the runner executes it. There is no compile-time inference; the operator you choose says exactly what the node is.

```scala
class TransfersTest extends Test[Any]:
    "transfers" - { // group: this block runs now, registering children
        "deposit increases the balance" in {
            val updated = Account(1, "ada", balanceCents = 0L).copy(balanceCents = 500L)
            assert(updated.balanceCents == 500L)
        }
        "a new account starts empty" in {
            assert(Account(2, "bob", 0L).balanceCents == 0L)
        }
    }
end TransfersTest
```

> **Caution:** choosing the wrong operator silently changes whether the body runs during discovery. A `-` whose body is just assertions runs those assertions at registration time, not as a test, so they never count as a leaf and a failure there crashes discovery rather than reporting a failed test. Assertions belong inside `in`.

Because a group body is ordinary Scala that runs at registration, you can generate leaves with loops, helper defs, and conditionals; every `in` they reach registers a child:

```scala
class HostParseTest extends Test[Any]:
    "parse" - {
        for host <- List("a.com", "b.org") do
            s"accepts $host" in {
                assert(parse(s"https://$host/").isSuccess)
            }
    }
end HostParseTest
```

The leaf body is an ordinary Kyo value, so a synchronous and an asynchronous test have the same type. A bare `()` or `assert(...)` auto-lifts; an `Async` body composes with `.map` and is accepted through the baseline `Async`:

```scala
class AsyncBalanceTest extends Test[Any]:
    "balance settles after a sleep" in {
        Async.sleep(10.millis).andThen {
            assert(Account(1, "ada", 0L).balanceCents == 0L)
        }
    }
end AsyncBalanceTest
```

## Assertions

Everything in this section is something you call inside a leaf body to make a claim or steer the leaf's outcome. The single power-`assert` is the workhorse; the rest cover expected exceptions, eventual consistency, preconditions, and compile-time checks.

> **Note:** The assertion family compiles only inside an `in { ... }` leaf, never in a `-` group builder, the suite class body, or a plain helper method. That is because each assertion needs the leaf's `AssertScope`, which the `in` operator supplies implicitly; see [Asserting inside a helper](#asserting-inside-a-helper-assertscope) to share that scope with a helper.

### `assert`: the power-assert

There is one assertion. `assert(cond)` throws `AssertionFailed` on `false`, carrying a diagram of every subexpression's value so you see why the condition was false without re-running under a debugger. `assert(cond, msg)` appends your message to that diagram.

```scala
class AccountAssertTest extends Test[Any]:
    "balance math" in {
        val a = Account(1, "ada", balanceCents = 250L)
        assert(a.balanceCents * 2 == 500L)
        assert(a.owner == "ada", "owner should survive the copy")
    }
end AccountAssertTest
```

### `fail`, `cancel`, and `assume`: steering the outcome

`fail(msg)` ends the leaf as a failure unconditionally. The `fail(cause: Throwable)` overload preserves a caught exception (and its stack trace) as the failure's cause, for when an observed exception is itself the reason the test failed.

`cancel(msg)` ends the leaf as cancelled, not failed (throwing `TestCancelled`): cancellation says the test could not run (a missing dependency, the wrong platform), not that the system misbehaved. `assume(cond, msg)` is the conditional form: it cancels the leaf when `cond` is false and otherwise does nothing.

```scala
class PreconditionTest extends Test[Any]:
    "settlement requires a connection" in {
        val connected = false
        assume(connected, "no DB connection; skipping")
        fail("unreachable when the assumption holds")
    }
end PreconditionTest
```

> **Caution:** `assume` cancels (skips) the test on a false condition; it does not fail it. Without this method a bare `assume` would bind to `scala.Predef.assume`, which throws an `AssertionError`, recording a failure instead of a skip.

When you need a conditional outcome, `assert` is the failure form and `assume` is the skip form: use `assert(x)` when a false `x` means a bug, and `assume(x)` when a false `x` means the test does not apply here.

### `intercept`: expected exceptions

When the behavior under test is "this should throw", assert it with `intercept[E]`. It evaluates the body, asserts that it throws an `E`, and returns the caught exception so you can assert on its fields. `interceptMessage[E](msg)` additionally requires the thrown exception's message to equal `msg`.

```scala
class WithdrawTest extends Test[Any]:
    "overdraw is rejected" in {
        val e = intercept[InsufficientFunds] {
            throw InsufficientFunds(accountId = 7)
        }
        assert(e.accountId == 7)
    }
end WithdrawTest
```

`interceptThrown[E]` and `interceptThrownMessage[E](msg)` are the same assertions but return `Unit`; reach for them when you only care that the exception was thrown, not for its value.

### `assertEventually`: eventual consistency

For state that becomes correct asynchronously (a balance that posts after settlement, a queue that drains), `assertEventually(cond)` re-evaluates `cond` every 10ms until it yields `true`. It returns a leaf body, so it is the last expression in the `in` block.

```scala
class SettlementTest extends Test[Any]:
    "posted balance settles" in {
        AtomicLong.init(0L).map { posted =>
            assertEventually(posted.get.map(_ == 0L))
        }
    }
end SettlementTest
```

> **Note:** `assertEventually` is bounded by the per-test timeout, not by an explicit attempt count. A condition still false when the timeout fires surfaces as the leaf's `TimedOut`/failure, not as an `assertEventually`-specific error.

### `typeCheck`: compile-time assertions

To assert that a code string does or does not compile, use the compile-time checks. `typeCheck(code)` asserts it compiles; `typeCheckFailure(code)(expected)` asserts it does not compile and that the error contains `expected`; `typeCheckFailure(code)` asserts only that it fails. These pin API contracts that ordinary tests cannot reach, like "this illegal construction is rejected".

```scala
class TypeContractTest extends Test[Any]:
    "an Int does not satisfy a String parameter" in {
        typeCheckFailure("""def f(s: String): String = s; f(42)""")("Found")
    }
end TypeContractTest
```

### Asserting inside a helper: `AssertScope`

Every assertion needs the leaf's `AssertScope`: the per-leaf object the runner uses to record failures (including those raised by a detached or leaked fiber) and to count how many assertions the leaf evaluated. The `in` operator supplies it implicitly, which is why the assert family compiles inside a leaf body but not in a `-` group, the suite class body, or a plain method.

To assert from a shared helper, give the helper a `(using AssertScope)` parameter so it joins the calling leaf's scope. Its assertions then count toward that leaf, so a leaf whose only checks live in such a helper still satisfies the [no-assertion check](#write-meaningful-tests-no-assertion-enforcement).

```scala
import kyo.*
import kyo.test.*

class HelperAssertTest extends Test[Any]:
    def assertValidAccount(a: Account)(using AssertScope): Unit =
        assert(a.id > 0)
        assert(a.owner.nonEmpty)

    "a fresh account is valid" in {
        assertValidAccount(Account(1, "ada", balanceCents = 0L))
    }
end HelperAssertTest
```

## Decorating tests

A decorator is metadata you chain onto the name before the `-`/`in` operator. It controls selection, retries, timing, lifecycle, and platform without changing the body. Each decorator produces a `TestBuilder`, the carrier the operator consumes; you rarely name `TestBuilder` yourself, you just chain methods and end with `-` or `in`.

```scala
class DecoratedTest extends Test[Any]:
    "slow settlement".retry(3).timeout(5.seconds) in {
        assert(Account(1, "ada", 0L).balanceCents == 0L)
    }
end DecoratedTest
```

### Selection: `focus`, `ignore`, `only`, `tagged`, `slow`

`.focus` restricts the run to focused leaves only (everything else reports `Skipped`), the way you isolate one test while iterating. `.ignore` (optionally `.ignore(reason)`) marks a leaf as ignored: its body never runs and it reports `Ignored`, recording the optional reason; use it both to disable a test and to mark one whose body is not written yet. `.only(cond)` registers the leaf only when `cond` is true at registration; a false condition reports `Skipped`. `.tagged("name", ...)` attaches tags for filtering at run time; `.slow` is shorthand for `.tagged("slow")`.

```scala
class SelectionTest extends Test[Any]:
    "wip".focus in { assert(1 == 1) }
    "broken".ignore in { assert(1 == 2) }
    "ci-only".only(sys.env.contains("CI")) in { assert(true) }
    "heavy".tagged("slow", "db") in { assert(true) }
end SelectionTest
```

### Lifecycle: `pendingUntilFixed`

`.pendingUntilFixed` (optionally `.pendingUntilFixed(reason)`) DOES run the body and inverts the outcome: a still-failing body reports `Pending`, a now-passing body reports `Failed`. The failure is a tripwire telling you the underlying bug is fixed and the marker should be removed. To skip a test's body entirely, whether it is disabled or not yet written, reach for `.ignore` / `.ignore(reason)` from the [selection group](#selection-focus-ignore-only-tagged-slow) above.

```scala
class LifecycleTest extends Test[Any]:
    "blocked on issue #123".pendingUntilFixed in {
        assert(brokenFeatureWorks())
    }

    def brokenFeatureWorks(): Boolean = false
end LifecycleTest
```

> **Caution:** `.pendingUntilFixed` RUNS the body and flips its result, unlike `.ignore`, which skips the body entirely. Reaching for `.ignore` when you meant `.pendingUntilFixed` leaves a now-fixed test silently skipped forever; reaching for `.pendingUntilFixed` on a test you only meant to disable runs a body you expected to skip.

### Resilience and timing: `timeout`, `retry`, `flaky`, `times`

`.timeout(d)` bounds this leaf to `d`, overriding the suite default. `.retry(n)` retries up to `n` times with no backoff; `.retry(schedule)` retries on a `Schedule` you supply. `.flaky` is the curated form for known-flaky leaves: 3 retries with linear 100ms backoff, plus a `"flaky"` tag so CI can filter them. `.times(n)` repeats the body `n` times (every run must pass); `.nonFlaky` (or `.nonFlaky(n)`) is the readable alias for that and the inverse of `.flaky`: it runs the leaf 100 times (or `n`) and requires every run to pass, to prove a leaf is not flaky.

```scala
class ResilienceTest extends Test[Any]:
    "settles within a window".timeout(2.seconds) in {
        assert(true)
    }
    "occasionally races".flaky in {
        assert(true)
    }
    "retry on a custom policy".retry(Schedule.exponential(50.millis, factor = 2.0).take(4)) in {
        assert(true)
    }
end ResilienceTest
```

When a `Passed` or `Failed` result reports `attempts > 1`, a retry decorator rescued (or exhausted on) that leaf; `attempts == 1` means it settled on the first try.

### Platform: `jvm`/`js`/`native`/`wasm`, `notX`, `onlyX`

Three families select platforms. `.jvm` / `.js` / `.native` / `.wasm` add a platform to the include set (additive). `.notJvm` / `.notJs` / `.notNative` / `.notWasm` remove one. `.onlyJvm` / `.onlyJs` / `.onlyNative` / `.onlyWasm` restrict to exactly one. On a non-matching platform the leaf is compile-excluded: the body is never emitted, no code for the leaf reaches the platform's output, and the leaf is absent entirely. It produces no `TestResult` and is never reported. Filters compose: a second filter intersects with the first, so `.notNative.notWasm` excludes the leaf on both Native and WebAssembly while leaving it on the JVM and Scala.js.

```scala
class PlatformTest extends Test[Any]:
    "uses java.nio".onlyJvm in {
        assert(true)
    }
    "not on Scala Native".notNative in {
        assert(true)
    }
    "stack-unsafe on AOT targets".notNative.notWasm in {
        assert(true)
    }
end PlatformTest
```

> **Note:** three mechanisms look similar but behave differently. Platform filters (`.onlyJvm`, `.notJs`, etc.) compile-exclude the leaf on a disabled platform: the body is never emitted and no result is produced. `.ignore` registers the leaf and runs nothing; it reports `Ignored` at runtime. `.only(false)` and non-focused leaves under `.focus` register the leaf and report `Skipped` at runtime.

`.onlyJvm` is semantically `.jvm` alone, but it reads unambiguously as single-platform intent; prefer it when you mean "exactly JVM" rather than "also JVM".

## Per-test and per-suite setup

A leaf that uses an effect beyond the baseline (`Env` or `Var`) must discharge it before the runner sees the leaf. Two surfaces cover this: `.handle` discharges per leaf or per group, and `aroundLeaf` wraps every leaf in the suite.

### `.handle`: discharge an extra effect

`.handle[S1](h)` chains off a name (or a decorated `TestBuilder`) and peels `S1` down to baseline. The handler `h` is a result-PRESERVING polymorphic function with type `[A] => (A < (S1 & Async & Abort[Any] & Scope)) => A < (Async & Abort[Any] & Scope)`: it takes a body that still needs `S1` and returns one that needs only baseline, without changing the result value. The suite declares the extra effect in `S` so the leaf body can use it.

```scala
class DbLeafTest extends Test[Env[Db]]:
    "reads the balance".handle[Env[Db]](
        [A] => (body: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(Db("jdbc:test"))(body)
    ) in {
        Bank.balance(1).map(b => assert(b == 0L))
    }
end DbLeafTest
```

Chain `.handle` to peel several effects; each call peels one row down toward the row the previous call left:

```scala
class TwoEffectTest extends Test[Var[Int] & Env[Db]]:
    "uses Env then Var"
        .handle[Env[Db]]([A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(Db("jdbc:test"))(b))
        .handle[Var[Int] & Env[Db]](
            [A] => (b: A < (Var[Int] & Env[Db] & Async & Abort[Any] & Scope)) => Var.run(0)(b)
        ) in {
        Env.get[Db].andThen(Var.update[Int](_ + 1)).unit
    }
end TwoEffectTest
```

> **Caution:** the handler must be result-PRESERVING. Result-changing handlers like `Var.runTuple` or `Console.withOut` return `(X, A)` and do NOT fit the `[A] => ... => A < baseline` contract; wrap them yourself with `.map(_._2)` to drop the extra component before the body reaches `.handle`.

The terminal operator rejects an under-discharged body at compile time: a body whose residual row still needs an effect beyond what you discharged is not a subtype of the baseline-shaped type the operator requires, and the compiler reports a lift failure naming the leaked effect.

> **Note:** group-level `.handle` is applied freshly around EACH descended leaf body, so every leaf in the group gets fresh `Var`/`Env` state rather than sharing one discharge across the group.

kyo-`Random` is not a row effect: every operation suspends `Sync`, which the baseline already provides through `Async`, so you use it directly inside a leaf without declaring anything in `S`. For a deterministic RNG, wrap the body in `Random.withSeed(seed) { ... }` (it returns `< Sync`). The property runner's RNG (below) is a separate, pure seed and does not touch kyo-`Random`.

### `aroundLeaf` and the suite hooks

When every leaf in a suite needs the same wrapper (open a connection, set a config), override `aroundLeaf` instead of repeating `.handle` at each `in`. It is the kyo-test equivalent of ScalaTest's `withFixture`: a single point that wraps each raw leaf body before the runner applies retry, timeout, and scope discharge.

```scala
class ConnectedSuite extends Test[Any]:
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Sync.defer(()).andThen(body) // e.g. open and close a connection around each leaf

    "leaf runs inside the wrapper" in {
        assert(true)
    }
end ConnectedSuite
```

Other per-suite hooks you can override: `name` (the suite display name), `timeout` (the default per-leaf bound), `randomize` / `randomSeed` (shuffle leaf order), and `config` (the suite's own `RunConfig`, e.g. `override def config = super.config.sequential`).

> **Note:** the default per-leaf `timeout` is 60 seconds, but `Duration.Infinity` (no timeout) when a debugger is attached, so a breakpoint does not trip the bound. The effective limit therefore differs between a debug run and a normal run.

## Property-based testing

Property testing is opt-in: extend `PropertyTest[S]` instead of `Test[S]` and call `forAll` in the class body. `forAll(gen) { value => ... }` runs the body over `numSamples` samples (default 100) drawn from `gen`; on the first failing sample it shrinks toward a minimal counterexample before reporting.

```scala
import kyo.*
import kyo.test.*
import kyo.test.prop.*

class DepositPropertyTest extends PropertyTest[Any]:
    forAll(Gen.long) { amount =>
        val before = 0L
        val after  = before + amount - amount
        assert(after == before)
    }
end DepositPropertyTest
```

`forAll` comes in arities 1 through 4; the higher arities take that many generators and pass that many arguments:

```scala
import kyo.test.prop.*

class UrlPropertyTest extends PropertyTest[Any]:
    forAll(Gen.string, Gen.string) { (host, path) =>
        assert(Url("https", host, path).host == host)
    }
end UrlPropertyTest
```

> **Note:** inside `PropertyTest`, the body row is `Abort[Throwable]` (not the `Abort[Any]` of a plain `Test` leaf). The property name is auto-synthesized from the source `Frame` (`forAll @ file:line`), so two `forAll` calls on the same source line collide on name; keep one `forAll` per line.

> **Note:** `forAll`'s RNG is a pure splittable seed derived from `nonRandomSeed` (default 42L) unless the suite sets `randomize = true`. It is unrelated to kyo-`Random`. A deterministic kyo-`Random` scenario wraps the body in `Random.withSeed(...)` (it suspends `Sync`, so no `S` declaration is needed). Override `numSamples`, `maxShrinks` (default 100), or `nonRandomSeed` per suite to tune the run.

### `Gen`: generators with shrinking

A `Gen[A]` samples a value together with the full lazy tree of its shrink candidates, so the combinators carry shrinking automatically. The built-ins are `const`, `int`, `long`, `double`, `string`, `boolean`, `list`, `listOfN`, `map`, `oneOf`, and `frequency`. Combine them with `map`, `flatMap`, and `filter`, and derive a generator for any case class or sealed trait with `Gen.derive[A]`.

The primitive generators bias toward edge values rather than drawing purely uniformly. `Gen.int` and `Gen.long` mostly draw uniformly within `[-size, size]` but occasionally inject the type boundaries `Int.MinValue`/`Int.MaxValue` and `Long.MinValue`/`Long.MaxValue`, which fall OUTSIDE `[-size, size]`; both still shrink toward 0. `Gen.double` injects `0.0`, `-0.0`, `1.0`, `-1.0`, `NaN`, the two infinities, and `Double.MinValue`/`Double.MaxValue` as edges, so a property over doubles that assumes finiteness must guard for `NaN` and the infinities itself. `Gen.string`, `Gen.list`, and `Gen.map` bias toward the empty and the singleton (or maximum-length) case. `Gen.oneOf` and `Gen.frequency` shrink toward earlier choices (lower index is treated as simpler), and `Gen.derive[A]` on a sealed trait shrinks toward earlier constructors.

For independent components, compose generators applicatively: `Gen.zipWith(ga, gb)(f)` combines two generators with `f`, and `Gen.zip`/`Gen.zip3`/`Gen.zip4` return tuples of two, three, or four generators. Each component shrinks independently, so the counterexample minimizes component by component.

```scala
import kyo.*
import kyo.test.*
import kyo.test.prop.*

class ZipPropertyTest extends PropertyTest[Any]:
    val pair: Gen[(Int, Int)] = Gen.zipWith(Gen.int, Gen.int)((x, y) => (x, y))

    forAll(pair) { case (x, y) =>
        assert(x + y == y + x)
    }
end ZipPropertyTest
```

> **Note:** `flatMap` shrinking is NOT guaranteed minimal: the inner generator is re-sampled for each outer shrink, and the dependency between the components blocks component-wise minimization. Prefer `zipWith`/`zip` when the components are independent; reach for `flatMap` only when the second value genuinely depends on the first.

```scala
import kyo.*
import kyo.test.prop.*

class AccountPropertyTest extends PropertyTest[Any]:
    val accounts: Gen[Account] = Gen.derive[Account]

    forAll(accounts) { account =>
        assert(account.copy(owner = account.owner).owner == account.owner)
    }

    forAll(Gen.int.map(_.abs)) { n =>
        assert(n >= 0)
    }
end AccountPropertyTest
```

> **Caution:** `Gen.filter(p)` retries up to a default budget of 1000 samples; `Gen.filter(p, budget)` takes a caller-supplied budget (which must be positive). When the budget is exhausted without an accepted value it throws `GenFilterExhaustedException`, whose `budget` and `attempts` fields let you inspect the exhaustion programmatically rather than parsing the message. Prefer generating valid values directly (e.g. `Gen.int.map(_.abs)`) over filtering a narrow predicate. Function-typed generators (`Gen[Int => Int]`) are not supported.

When `forAll` fails, it throws `PropertyFailedException`, carrying the original failing sample, the shrunk minimal counterexample, the underlying cause, and the seed so you can reproduce the exact run. Copy that seed into `forAllSeeded(seed, gen) { body }` (arities 1 through 4, mirroring `forAll`) to pin the failing run and replay it deterministically, regardless of the suite's `randomize`/`nonRandomSeed`.

```scala
import kyo.*
import kyo.test.*
import kyo.test.prop.*

class ReplayPropertyTest extends PropertyTest[Any]:
    // A run reported "Seed: 987654321"; pin it to replay the exact run.
    forAllSeeded(987654321L, Gen.list(Gen.int)) { xs =>
        assert(xs.sum == xs.foldLeft(0)(_ + _))
    }
end ReplayPropertyTest
```

### `Shrink`: custom generators

When you build a `Gen` by hand and want the same shrinking the built-ins use, call the standalone algorithms in `Shrink`: `Shrink.int`, `Shrink.long`, `Shrink.double`, `Shrink.string`, and `Shrink.list(chunk, elemShrink)`. These are the exact functions the built-in generators delegate to. `Shrink.double` drives toward `0.0` and toward integral values (`2.7` shrinks to its integral neighbor `2.0` first, then halves toward `0.0`); there is no lower floor, so it reaches `0.0` exactly. `NaN` shrinks to `0.0`, and the infinities shrink through `Double.MaxValue` toward `0.0`.

```scala
import kyo.Chunk
import kyo.test.prop.Shrink

val intShrinks: Chunk[Int]       = Shrink.int(100)    // 50, 25, 12, 6, 3, 1, 0
val doubleShrinks: Chunk[Double] = Shrink.double(2.7) // 2.0 (integral neighbor), then halving toward 0.0; no floor
```

## Write meaningful tests: no-assertion enforcement

A leaf that runs to completion having evaluated zero assertions is itself a bug: it proves nothing. kyo-test enforces this at run time. After a leaf body joins, the runner counts how many assertions it evaluated; a leaf that would otherwise pass with a count of zero is flipped to `Failed` with the message `leaf passed without evaluating any assertion`. The whole assert family counts: `assert`, `fail`, `intercept`, `assertEventually`, `typeCheck`, `assertSnapshot`, `assertSchemaSnapshot`, and a property `forAll`. `cancel` and `assume` do not count, because they steer the outcome rather than make a claim.

This is a run-level setting, `failOnNoAssertion`, on by default. ScalaTest forced this at compile time through its `Assertion` return type; kyo-test cannot, because a leaf body is an effectful `Unit < (S & Async & Abort[Any] & Scope)`, so the check runs after the body completes.

When a leaf genuinely has nothing to assert (a smoke test that only needs to confirm the body runs without error), write `succeed` (or `succeed("note")` to record why) as the per-leaf opt-out. It is an alias for `assert(true)`: it flows through the assert runtime, counts as one evaluation, and always passes:

```scala
import kyo.*
import kyo.test.*

class SmokeTest extends Test[Any]:
    "pure smoke: the pipeline starts without error" in {
        succeed("nothing to claim; just confirm the body ran")
    }
end SmokeTest
```

To turn the check off for a whole suite (for a suite whose leaves assert through a domain helper that the runtime does not see), override `config`:

```scala
import kyo.*
import kyo.test.*

class HelperAssertSuite extends Test[Any]:
    override def config: RunConfig = super.config.failOnNoAssertion(false)

    "asserts through a helper the runtime cannot count" in {
        assert(true)
    }
end HelperAssertSuite
```

## Snapshot testing

Snapshot testing is opt-in: extend `SnapshotTest[S]` and call `assertSnapshot(actual, name)`. It renders `actual` via its `Render[A]` instance and compares the text against a stored file under `snapshotDir` (default `"test-snapshots"`), in a subdirectory named after the suite. A `Render[A]` is derived automatically for case classes, so a domain value snapshots without extra wiring.

```scala
import kyo.*
import kyo.test.*
import kyo.test.snapshot.*

class StatementSnapshotTest extends SnapshotTest[Any]:
    "monthly statement" in {
        val statement = Account(1, "ada", balanceCents = 12_300L)
        assertSnapshot(statement, "monthly")
    }
end StatementSnapshotTest
```

> **Caution:** the first run of `assertSnapshot` writes the proposed snapshot file and FAILS with `SnapshotNotFound`; the test passes only on a later run once you have reviewed the written file. To accept new or changed snapshots, run with `KYO_TEST_SNAPSHOT=update` in the environment (or override `snapshotUpdateMode` per suite). Snapshot names may not contain path separators, spaces, `.`, or `..`.

### `assertSchemaSnapshot`: schema-based snapshots

When a `Schema[A]` instance is in scope, reach for `assertSchemaSnapshot(actual, name)` instead. It renders `actual` through its `Schema[A]` using a `SnapshotCodec` (default `SnapshotCodec.Yaml`, a readable field-named format) and stores the result under `${snapshotDir}/${suite}/${name}.snap.yaml`, a distinct extension from `assertSnapshot`'s plain `.snap` so the two never collide on the same name.

```scala
import kyo.*
import kyo.test.*
import kyo.test.snapshot.*

case class Statement(id: Int, balanceCents: Long, generatedAt: Long) derives Schema

class SchemaStatementSnapshotTest extends SnapshotTest[Any]:
    "monthly statement" in {
        val statement = Statement(1, balanceCents = 12_300L, generatedAt = java.lang.System.currentTimeMillis())
        assertSchemaSnapshot(statement, "monthly-schema", _.normalize(_.set(_.generatedAt)(0L)))
    }
end SchemaStatementSnapshotTest
```

The third argument builds a `SnapshotConfig[A]`, the single per-call customization point: `.normalize` scrubs non-deterministic fields (timestamps, ids) before both encoding and comparison, so a repeated run with a fresh timestamp still matches the stored snapshot. A suite that needs a different serialization format overrides the per-suite `snapshotCodec` hook (default `SnapshotCodec.Yaml`); the text presets are `Yaml`, `Json`, and `Ion`, the binary presets (stored as raw wire bytes) are `Protobuf`, `Bson`, `MsgPack`, and `IonBinary`, and `SnapshotCodec.Text`/`Binary` wrap any other kyo-schema `Codec`.

> **Note:** the comparison is structural and format-tolerant: a hand-reformatted stored snapshot that still decodes to an equal value passes. A mismatch reports the changed field paths (dotted for a nested field, e.g. `b.y`) plus a unified text diff for text codecs. A stored snapshot that fails to decode at all (a genuine schema evolution) fails with `SnapshotSchemaEvolution` instead, distinct from a value mismatch.

Reach for `assertSnapshot` when a `Render` instance is all you need (a quick, flat text snapshot); reach for `assertSchemaSnapshot` when you have a `Schema[A]` and want readable field-named output, field normalization, format-tolerant comparison, or schema-evolution detection.

## Running and selecting tests

You run a module's suites through sbt; the runner discovers every `Test` subclass, instantiates it, and executes its leaves.

```sh
# Run every suite in a module
sbt 'kyo-coreJVM/test'

# Run a single suite
sbt 'kyo-coreJVM/testOnly kyo.ChannelTest'
```

### CLI flags

The command-line entry point (`kyo.test.runner.Cli`) takes flags that map onto the run configuration:

| Flag | Effect |
|------|--------|
| `--parallel=N` | Concurrency: `1` = within-suite sequential, `0` (auto) or `N > 1` = parallel (the global pool sets the real degree) |
| `--randomize` / `--randomize=SEED` | Shuffle leaf order (time-seeded, or a fixed seed to reproduce) |
| `--filter=GLOB` | Include only leaves whose dot-joined path matches GLOB (repeatable) |
| `--tag=NAME` / `--exclude-tag=NAME` | Include / exclude leaves by tag (repeatable) |
| `--reporter=VALUE` | Add a reporter: `console`, `tap`, `tap:PATH`, `junit-xml:PATH` (comma-separated or repeatable) |
| `--verbose` / `--quiet` | Raise / lower console detail |
| `--count` / `--list` | Discovery only: report the leaf count, or print every leaf's full path; no body runs |
| `--help` | Print usage |

Exit codes are `0` (all passed, or nothing ran), `1` (a leaf failed, was cancelled, or timed out), and `2` (argument parse error).

### `RunConfig`, `TestFilter`, `Verbosity`: the programmatic equivalents

`RunConfig` is the in-code form of the flags, with copy-style builders. `RunConfig.default` is the starting point; `.sequential` is shorthand for `parallelism = 1`.

```scala
import kyo.*
import kyo.test.*

val config: RunConfig =
    RunConfig.default
        .parallelism(4)
        .filter(TestFilter(pathInclude = Chunk("transfers.*"), tagsExclude = Set("slow")))
        .verbosity(Verbosity.Quiet)
```

`TestFilter` selects leaves: `pathInclude`/`pathExclude` are globs against the dot-joined leaf path, `tagsInclude`/`tagsExclude` are exact tag names. Includes act as allow-lists; excludes apply after. `TestFilter.empty` runs everything. `Verbosity` is `Quiet` / `Normal` / `Verbose`. Two `RunConfig` flags toggle discovery-only modes: `countOnly` reports the leaf count without running bodies, and `listOnly` additionally prints every leaf path (implying count-only). `strictStructure` turns on strict leaf-name-path validation, rejecting duplicate or structurally invalid paths at registration time.

> **Note:** `parallelism = 1` means within-suite sequential (the suite's leaves run one at a time); `0` (the default) and any `N > 1` mean parallel: leaves are pushed to the process-global pool and the pool's `globalK` bound sets the real degree of concurrency; `N > 1` is no longer a per-suite cap. On Scala Native `globalK = 1`, so all leaves run sequentially regardless of the requested value.

### Outcomes and counters

Each leaf produces one `TestResult`: `Passed`, `Failed`, `Cancelled`, `Pending`, `Ignored`, `TimedOut`, or `Skipped`. `Passed` and `Failed` carry an `attempts` count recording retry usage. The runner aggregates these into a `TestReport` (with a `SuiteReport` per suite), which exposes counters: `totalLeaves`, `passed`, `failed`, `cancelled`, `pending`, `ignored`, `timedOut`, `skipped`, and `totalDuration`.

### Reporters

A `TestReporter` is a lifecycle-event sink (`onRunStart`, `onSuiteStart`, `onLeafStart`, `onLeafComplete`, `onSuiteComplete`, `onRunComplete`). The built-ins are `ConsoleReporter` (human-readable), `TapReporter` (TAP version 13), `JUnitXmlReporter` (JVM-only), and `CombinedReporter` (fan-out to several). Construct them through the `Reporters` factory:

```scala
import kyo.test.*
import kyo.test.runner.*

val reporter: TestReporter =
    Reporters.combined(
        Reporters.console(Verbosity.Normal),
        Reporters.tap(java.lang.System.out)
    )
```

The runner itself is `TestRunner`: `runReport(suiteClass, config)` yields the whole run as a Kyo value (`TestReport < (Async & Abort[Throwable] & Scope)`), and `runToFuture(...)` produces the single `Future` at the sbt edge. You rarely call either directly; sbt and the CLI invoke them for you.

## Build wiring

A project enables kyo-test through the `SbtKyoTestPlugin` AutoPlugin, which adds the `kyo-test-runner` Test dependency and registers the framework with sbt:

```scala doctest:expect=skipped
// project/plugins.sbt
addSbtPlugin("io.getkyo" % "sbt-kyo-test-publish" % "<version>")

// build.sbt
lazy val myProject = project.enablePlugins(SbtKyoTestPlugin)
```

> **Note:** suite discovery from the command-line runner is JVM-only (it reads the `META-INF/services/kyo.test.Test` service-loader file); on JS and Native the CLI discovers nothing, so run those platforms through sbt, whose own fingerprint-based discovery finds every `Test` subclass. `JUnitXmlReporter` is JVM-only as well.
