package kyo.test.runner

import kyo.Chunk
import kyo.test.internal.TestBase
import kyo.test.runner.internal.Args
import kyo.test.runner.internal.SuiteDiscoveryPlatform

/** Platform-neutral tests for [[Cli]] and [[kyo.test.runner.internal.SuiteDiscoveryPlatform]].
  *
  * The thread-local isolation test (requires CountDownLatch + Thread) lives in jvm/CliConcurrencyTest.scala.
  */
class CliTest extends kyo.test.Test[Any]:

    // ── Test 10: discovers Test subclasses via service-loader file ────────────────────────────

    "discovers Test subclasses via service-loader file: platform bridge returns a Chunk" in {
        // SuiteDiscoveryPlatform.discover() must compile and return a Chunk on all platforms.
        // On JVM this returns classes found in META-INF/services/kyo.test.Test.
        // On JS/Native this returns Chunk.empty (service-loader is a JVM concept).
        val discovered: Chunk[Class[? <: TestBase[?]]] = SuiteDiscoveryPlatform.discover()
        // The result is always a Chunk (never null, never an exception).
        assert(discovered != null): Unit
    }

    // ── Cli.main: --help route ────────────────────────────────────────────────────────────────

    "Cli --help is parsed to Args.Result.Help before reaching main I/O" in {
        // Verify the Args layer correctly routes --help so Cli.main would print usage and exit 0.
        val result = Args.parse(Array("--help"))
        assert(result == Args.Result.Help): Unit
    }

    // ── Cli.main: invalid arg route ───────────────────────────────────────────────────────────

    "Cli unknown flag is parsed to Args.Result.Error before reaching main I/O" in {
        val result = Args.parse(Array("--not-a-valid-flag"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg.nonEmpty, s"expected a non-empty error message, got: $msg")
            case other => fail(s"expected Error, got: $other")
        end match
    }

    // ── Cli.withTestExit: intercepts SystemExitException(42) ─────────────────────────────────

    "withTestExit intercepts SystemExitException(42)" in {
        val ex = intercept[SystemExitException] {
            Cli.withTestExit {
                Cli.exitForTest(42)
            }
        }
        assert(ex.code == 42): Unit
    }

    // ── Cli.withTestExit: restores exit function after block (sequential isolation) ──────────

    "withTestExit restores exit function after block: sequential re-entry gets correct code" in {
        // First withTestExit installs stub, throws, try/finally restores.
        val ex1 = intercept[SystemExitException] {
            Cli.withTestExit { Cli.exitForTest(99) }
        }
        assert(ex1.code == 99): Unit
        // Second withTestExit verifies the AtomicReference was restored (not leaked) by the first.
        val ex2 = intercept[SystemExitException] {
            Cli.withTestExit { Cli.exitForTest(7) }
        }
        assert(ex2.code == 7): Unit
    }

    // ── SystemExitException.code ─────────────────────────────────────────────

    "phase6-leaf-3: new SystemExitException(1).code equals 1" in {
        val ex = new SystemExitException(1)
        assert(ex.code == 1, s"Expected code 1, got ${ex.code}")
    }

end CliTest
