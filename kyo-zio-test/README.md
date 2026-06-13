# kyo-zio-test

<!-- doctest:setup
```scala
import kyo.*
import kyo.test.*
import zio.test.Gen
import zio.test.Spec
import zio.test.TestAspect
import zio.test.TestResult
import zio.test.assertCompletes
import zio.test.assertTrue
import zio.test.check
import zio.test.suite

final case class User(name: String, email: String):
    def normalized: User = copy(email = email.trim)

trait Conn:
    def loadUser(name: String): User
    def close(): Unit

def openConn(): Conn = new Conn:
    def loadUser(name: String) = User(name, s"$name@example.com")
    def close()                = ()
```
-->

kyo-zio-test plugs Kyo effects into ZIO Test. You extend `KyoSpecDefault` (or the generic `KyoSpecAbstract[S]` if you want a custom effect row), write a `spec` exactly as you would in a normal `ZIOSpecDefault`, and return values of type `TestResult < S` directly from each `test(...)` block. The library supplies the `TestConstructor` and `CheckConstructor` givens that teach ZIO Test how to interpret a Kyo computation as a test, so the resulting spec runs under ZIO Test's regular harness, with `TestAspect`, `check`, generators, timeouts, and reporting all working unchanged.

`KyoSpecDefault` fixes the effect row to `Async & Scope & Abort[Throwable]` and runs each test through `ZIOs.run(Scope.run(v))`. Use `KyoSpecAbstract[S]` directly when you need a different effect row (for example, an additional `Env[Config]` or a custom error type), supplying your own `run` to discharge those effects into a `ZIO[Environment, Throwable, In]`. The module is published for JVM, JavaScript, and Scala Native and depends on `kyo-core` and `kyo-zio`.

```scala
object HelloSpec extends KyoSpecDefault:
    def spec = suite("hello")(
        test("greets"):
            for greeting <- Sync.defer("hello, world")
            yield assertTrue(greeting == "hello, world")
    )
end HelloSpec
```

## Writing a spec

The common path: pick a name, extend `KyoSpecDefault`, define `spec`. Each `test(...)` body returns `TestResult < (Async & Scope & Abort[Throwable])`, so you can drop `Sync.defer`, `Async.delay`, and `Scope.acquireRelease` directly into a for-yield, and the assertion at the end is the test's result.

### The default effect row: `KyoSpecDefault`

When your test body only needs `Sync`, `Async`, `Scope`, and `Abort[Throwable]`, this is the base class to extend; it fixes the effect row to `Async & Scope & Abort[Throwable]` and discharges those effects automatically, so test bodies look like ordinary Kyo computations that happen to end in a `TestResult`.

```scala
object UserSpec extends KyoSpecDefault:
    def spec = suite("User")(
        test("trims whitespace from email"):
            for
                user <- Sync.defer(User("alice", "  alice@example.com  ").normalized)
            yield assertTrue(user.email == "alice@example.com")
        ,
        test("releases DB handle after the test"):
            for
                conn <- Scope.acquireRelease(Sync.defer(openConn()))(c => Sync.defer(c.close()))
                user <- Sync.defer(conn.loadUser("alice"))
            yield assertTrue(user.name == "alice")
    )
end UserSpec
```

> **Note:** Every test runs inside `Scope.run`, so any `Scope.acquireRelease` resource acquired in the body is released before the next test starts. `assertTrue` captures inside the for-yield see the live resource, not a closed-over handle from after release.

The Kyo side is otherwise unchanged: `Abort.fail[Throwable](e)` in a test body surfaces as a failed test, and a thrown exception inside `Sync.defer` does the same. Generators (`Gen.boolean`, `Gen.int`, ...) compose with Kyo bodies through `check`, which is covered below.

### Assembling the spec

If you have used `ZIOSpecDefault` before, `spec` is the same abstract member, with the same `Spec[Environment, Any]` return type; `Environment` is fixed to `Any` for both `KyoSpecDefault` and `KyoSpecAbstract`. The `suite` and `test` builders re-exposed on the class delegate to ZIO Test's, so the value you assemble here is a ZIO Test `Spec` in every respect.

> **Note:** ZIO environments are not supported: `Environment` is pinned to `Any` and `bootstrap` to `ZLayer.empty`, mirroring kyo-zio's `compiletime.error` on lifting an environment-bearing `ZIO[R, E, A]`. A spec cannot thread a `ZLayer` environment through Kyo test bodies.

```scala
object MultiSuiteSpec extends KyoSpecDefault:
    def spec = suite("orders")(
        suite("creation")(
            test("assigns an id"):
                Sync.defer(assertCompletes)
        ),
        suite("lifecycle")(
            test("closes the connection"):
                for
                    conn <- Scope.acquireRelease(Sync.defer(openConn()))(c => Sync.defer(c.close()))
                yield assertTrue(conn.loadUser("alice").name == "alice")
        )
    ) @@ TestAspect.timed
end MultiSuiteSpec
```

`TestAspect.timed`, `TestAspect.failing`, `TestAspect.flaky`, `TestAspect.timeout`, and the rest of ZIO Test's aspects attach to suites and individual tests the same way they would in a `ZIOSpecDefault`.

## Custom effect rows

When the test body needs to use an effect that is not in `Async & Scope & Abort[Throwable]` (for example, an `Env[Config]` for ambient configuration, or a typed `Abort[ValidationError]` in addition to `Abort[Throwable]`), extend `KyoSpecAbstract[S]` directly and supply your own `run` that knows how to discharge `S`.

### A custom effect row: `KyoSpecAbstract[S]`

When you need an effect row other than `Async & Scope & Abort[Throwable]`, extend this directly and supply your own `run`; `S` is the effect row that every test body in the spec is allowed to use. The `Environment` type and `bootstrap` `ZLayer` are fixed to `Any` and `ZLayer.empty`; specs that need a ZIO `ZLayer` provide it through ZIO Test's regular `provideLayer` on the assembled `Spec`, not through `bootstrap`.

```scala
final case class Config(baseUrl: String)

abstract class ConfiguredSpec
    extends KyoSpecAbstract[Async & Scope & Abort[Throwable] & Env[Config]]:

    def config: Config

    final override def run[In](
        v: => In < (Async & Scope & Abort[Throwable] & Env[Config])
    )(using Frame): zio.ZIO[Environment, Throwable, In] =
        ZIOs.run(Scope.run(Env.run(config)(v)))
end ConfiguredSpec

object OrderApiSpec extends ConfiguredSpec:
    def config = Config("https://api.example.com")

    def spec = suite("OrderApi")(
        test("uses the configured base url"):
            for
                base <- Env.use[Config](c => c.baseUrl)
            yield assertTrue(base == "https://api.example.com")
    )
end OrderApiSpec
```

The pattern: pick a base class that fixes the effect row and the `run` implementation, then per-spec subclasses fill in only the data (here, `config`) and the `spec`.

### Supplying the interpreter: `run`

Inside `KyoSpecAbstract[S]`, `run` is the single seam where Kyo effects discharge into ZIO; its signature is `protected def run[In](v: => In < S)(using Frame): ZIO[Environment, Throwable, In]`. For `KyoSpecDefault`, this is hardcoded to `ZIOs.run(Scope.run(v))`. For a custom subclass of `KyoSpecAbstract[S]`, the body is your composition of effect-discharging handlers: `Env.run`, `Var.run`, `Memo.run`, additional `Abort.run` calls, and so on, terminated by `ZIOs.run`.

```scala
abstract class TracedSpec
    extends KyoSpecAbstract[Async & Scope & Abort[Throwable] & Var[List[String]]]:

    final override def run[In](
        v: => In < (Async & Scope & Abort[Throwable] & Var[List[String]])
    )(using Frame): zio.ZIO[Environment, Throwable, In] =
        ZIOs.run(Scope.run(Var.run(List.empty)(v)))
end TracedSpec
```

The `S1 >: S` bound on the constructor givens (covered next) means individual tests can use a subset of the spec's `S`. They cannot widen beyond `S`: a test inside `OrderApiSpec` cannot use `Env[OtherConfig]`, because `OtherConfig` was not promised in the spec's type parameter.

## How it plugs into ZIO Test

Most users never touch the two givens in this section. They are documented because they explain why `test(...)` and `check(...)` accept Kyo bodies in the first place, and because anyone writing a custom test wrapper needs to mirror them.

### `KyoTestConstructor`

When you write `test("label") { body: TestResult < S }`, this is the given that resolves; it is defined inside `KyoSpecAbstract` as a `TestConstructor.WithOut[Any, A < S1, Spec[Any, Throwable]]` for any `A <: TestResult` and any `S1 >: S`. Because it is a class-body given (not a companion-object given), it comes into scope through inheritance when your spec class extends `KyoSpecAbstract` or `KyoSpecDefault`. It is not importable as a standalone name. When ZIO Test's `test(label)(assertion)` resolves its `TestConstructor` implicit inside the spec body, this inherited given matches and produces a `Spec[Any, Throwable]` that runs `assertion` through the spec's `run`.

```scala
object DirectConstructorSpec extends KyoSpecDefault:
    def spec = suite("constructor")(
        test("works with for-yield"):
            for
                x <- Sync.defer(40)
                y <- Sync.defer(2)
            yield assertTrue(x + y == 42)
        ,
        test("works with a single Kyo expression"):
            Sync.defer(assertCompletes)
    )
end DirectConstructorSpec
```

> **Caution:** The constructor forces the `TestResult` value (`val _ = result.result`) before returning, so a pure-side `assertTrue` failure inside the `result` surfaces as a failed test instead of being silently dropped. A custom test wrapper that builds a `Spec.test` from a Kyo body should mirror this pattern; without it, evaluation laziness can hide failures.

> **Note:** The library copies the `trace` annotation locally because `zio.test.TestAnnotation.trace` is `private[zio]`. This is a deliberate workaround so that source locations attach to the constructed `Spec.test`, not a bug.

### `KyoCheckConstructor`

When you write `check(gen) { ... : TestResult < S }`, this is the corresponding given; it is defined inside `KyoSpecAbstract` as a `CheckConstructor.WithOut[Any, A < S1, Any, Throwable]` for any `A <: TestResult` and any `S1 >: S`. Like `KyoTestConstructor`, it is a class-body given that comes into scope through inheritance inside the spec body; it is not importable as a standalone name. The generator block returns `TestResult < S` and is discharged through the spec's `run` exactly like a `test` body, so generators compose with `Sync.defer`, `Async.delay`, `Abort.fail`, and the rest of the Kyo effect surface.

```scala
object PropertySpec extends KyoSpecDefault:
    def spec = suite("properties")(
        test("pure check"):
            check(Gen.int) { i =>
                assertTrue(i == i)
            }
        ,
        test("kyo-bodied check"):
            check(Gen.int, Gen.int) { (a, b) =>
                for
                    sum <- Sync.defer(a + b)
                yield assertTrue(sum == a + b)
            }
    )
end PropertySpec
```

Both givens apply the same `result.result` force, and both accept any `S1 >: S`. The practical consequence: a test body's effect row may be a SUBSET of the spec's `S` (a test that only uses `Sync` inside a spec parameterized by `Async & Scope & Abort[Throwable]` typechecks), but it can never widen beyond `S`.

### `test` and `suite`

If you wondered why `test` and `suite` resolve inside `def spec` without an explicit `import zio.test.*`, it is because `KyoSpecAbstract` re-exposes them as methods on the class. They delegate to ZIO Test's own builders using the appropriate `TestConstructor` and `SuiteConstructor` givens, so the values you build are ordinary ZIO Test `Spec`s. If you do import `zio.test.*`, both forms still work.

## Cross-platform

Whether you target JVM, JavaScript, or Scala Native, the same API is published; the default-effect-row spec, the abstract spec, the constructor givens, and every example in this README compile on all three platforms. Test bodies that use platform-restricted Kyo APIs (for example, JVM-only file I/O) are bound by those APIs' own platform support, not by anything in kyo-zio-test.
