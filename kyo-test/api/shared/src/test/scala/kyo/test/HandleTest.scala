package kyo.test

import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Env
import kyo.Maybe
import kyo.Scope
import kyo.Var
import kyo.kernel.<
import kyo.test.internal.TestContext
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Tests for the `.handle` combinator and the `EnrichedTestBuilder` carrier.
  *
  * This is a ScalaTest bootstrap: it exercises the framework's own DSL surface (`kyo.test`), so it cannot self-host on the
  * framework-under-test, matching the `TestBaseTest` convention.
  *
  * Positive registration runs the suite under a DISCOVERY-mode `kyo.test.internal.TestContext`: in discovery mode the cursor records the
  * leaf position (`peekRegisteredLeaf`) without forcing the deferred leaf body, which is what these assertions read. The terminal `-` type
  * rejection (under-discharged body) and the handler-contract rejection (`Var.runTuple` direct use) are exercised with
  * `scala.compiletime.testing.typeCheckErrors`, independent of the runner-side discharge.
  */
class HandleTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    /** A marker payload supplied to `Env.run` so a leaf body can read `Env.get[Db]`. */
    final case class Db(url: String)

    private val db: Db = Db("jdbc:test")

    /** Instantiate `make` under a discovery-mode context targeting `target`, then return the leaf the cursor produced.
      *
      * `TestBase` captures one thread-local at construction: the `next` registration context (`regCtx`). It must be primed before
      * instantiating, otherwise `takeFromThreadLocal` throws.
      */
    private def discover(target: Chunk[Int])(make: => Any): Maybe[(Chunk[String], TestResult)] =
        val ctx = new TestContext(target, discovery = true)
        TestContext.setForInstantiation(ctx)
        val _ = make
        ctx.signalPastEnd()
        ctx.peekRegisteredLeaf
    end discover

    "single .handle discharges one extra effect, registers a baseline leaf" in {
        val leaf = discover(Chunk(0)) {
            new kyo.test.Test[Any]:
                "uses-env".handle[Env[Db]](
                    [A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(db)(b)
                ) in Env.get[Db].map(_ => succeed)
        }
        assert(leaf.isDefined)
        leaf match
            case Maybe.Present((path, _)) => assert(path == Chunk("uses-env"))
            case _                        => fail("Expected a registered leaf at Chunk(0)")
        Future.successful(succeed)
    }

    "two chained .handle calls peel two effects to baseline" in {
        val leaf = discover(Chunk(0)) {
            new kyo.test.Test[Any]:
                "two-effects"
                    // First .handle peels directly to baseline (Env discharged, nothing left).
                    .handle[Env[Db]]([A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(db)(b))
                    // Second .handle peels Var[Int] down to the residual Env[Db] row tracked by the first.
                    .handle[Var[Int] & Env[Db]](
                        [A] => (b: A < (Var[Int] & Env[Db] & Async & Abort[Any] & Scope)) => Var.run(0)(b)
                    ) in {
                    Env.get[Db].map(_ => Var.update[Int](_ + 1)).unit
                }
        }
        assert(leaf.isDefined)
        leaf match
            case Maybe.Present((path, _)) => assert(path == Chunk("two-effects"))
            case _                        => fail("Expected a registered leaf at Chunk(0)")
        Future.successful(succeed)
    }

    "terminal - rejects an under-discharged body (contravariant -S)" in {
        // typeCheckErrors compiles the snippet in a synthetic file context (fileName starts with "<"),
        // so FindEnclosing.isInternal returns false and Frame.derive works without Frame.internal.
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            import kyo.Abort
            import kyo.Async
            import kyo.Env
            import kyo.Scope
            import kyo.Var
            import kyo.kernel.<
            final case class Db(url: String)
            val db = Db("x")
            new kyo.test.Test[Any]:
                "leaks-var".handle[Env[Db]](
                    [A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(db)(b)
                ) - { Env.get[Db].map(_ => Var.update[Int](_ + 1)).unit }
            """
        )
        assert(errors.nonEmpty, "Expected the under-discharged Var[Int] body to be rejected")
        // The terminal `-` requires `Unit < (Env[Db] & baseline)`; the body's residual row still carries `Var[Int]`. Contravariance of
        // `<`'s `-S` makes the wider-effect body unliftable to the narrower required type. The compiler reports a lift failure naming the
        // leaked `Var` effect.
        assert(
            errors.exists(e => e.message.contains("Var") && e.message.contains("lift")),
            s"Expected a rejection naming the leaked Var effect, got: ${errors.map(_.message)}"
        )
        Future.successful(succeed)
    }

    // The group node carries the extra `Env[Db]` row, so the suite declares it in `S` (the inner leaf bodies use `Env.get[Db]`); the
    // group-level `.handle` discharges it. The inner `-` calls register through the base `S = Env[Db]` extension, and the runner invokes
    // the composed transform freshly per descended leaf (O7).
    "group-level .handle applies per descended leaf (O7) ; leaf-a" in {
        val leaf = discover(Chunk(0, 0)) {
            new kyo.test.Test[Env[Db]]:
                "group".handle[Env[Db]](
                    [A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(db)(b)
                ) - {
                    "leaf-a" in Env.get[Db].map(_ => succeed)
                    "leaf-b" in Env.get[Db].map(_ => succeed)
                }
        }
        assert(leaf.isDefined)
        leaf match
            case Maybe.Present((path, _)) => assert(path == Chunk("group", "leaf-a"))
            case _                        => fail("Expected a registered leaf at Chunk(0, 0)")
        Future.successful(succeed)
    }

    "group-level .handle applies per descended leaf (O7) ; leaf-b" in {
        val leaf = discover(Chunk(0, 1)) {
            new kyo.test.Test[Env[Db]]:
                "group".handle[Env[Db]](
                    [A] => (b: A < (Env[Db] & Async & Abort[Any] & Scope)) => Env.run(db)(b)
                ) - {
                    "leaf-a" in Env.get[Db].map(_ => succeed)
                    "leaf-b" in Env.get[Db].map(_ => succeed)
                }
        }
        assert(leaf.isDefined)
        leaf match
            case Maybe.Present((path, _)) => assert(path == Chunk("group", "leaf-b"))
            case _                        => fail("Expected a registered leaf at Chunk(0, 1)")
        Future.successful(succeed)
    }

    "result-changing handler (Var.runTuple) does NOT fit the handler contract" in {
        // typeCheckErrors compiles the snippet in a synthetic file context (fileName starts with "<"),
        // so FindEnclosing.isInternal returns false and Frame.derive works without Frame.internal.
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            import kyo.Abort
            import kyo.Async
            import kyo.Scope
            import kyo.Var
            import kyo.kernel.<
            new kyo.test.Test[Any]:
                "tuple".handle[Var[Int]](
                    [A] => (b: A < (Var[Int] & Async & Abort[Any] & Scope)) => Var.runTuple(0)(b)
                ) - Var.update[Int](_ + 1).unit
            """
        )
        assert(errors.nonEmpty, "Expected the result-changing Var.runTuple handler to be rejected")
        assert(errors.exists(_.message.contains("Required")), s"Expected a 'Required' type-mismatch, got: ${errors.map(_.message)}")
        Future.successful(succeed)
    }

    "result-changing handler wrapped with .map(_._2) fits and registers a baseline leaf" in {
        val leaf = discover(Chunk(0)) {
            new kyo.test.Test[Any]:
                "tuple-wrapped".handle[Var[Int]](
                    [A] => (b: A < (Var[Int] & Async & Abort[Any] & Scope)) => Var.runTuple(0)(b).map(_._2)
                ) in Var.update[Int](_ + 1).unit
        }
        assert(leaf.isDefined)
        leaf match
            case Maybe.Present((path, _)) => assert(path == Chunk("tuple-wrapped"))
            case _                        => fail("Expected a registered leaf at Chunk(0)")
        Future.successful(succeed)
    }

end HandleTest
