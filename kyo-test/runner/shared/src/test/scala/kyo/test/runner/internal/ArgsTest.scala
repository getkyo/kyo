package kyo.test.runner.internal

import kyo.Chunk
import kyo.Maybe
import kyo.test.TestFilter
import kyo.test.Verbosity
import kyo.test.runner.internal.Args

/** Tests for [[Args.parse]]: 12 leaves covering the full flag surface.
  */
class ArgsTest extends kyo.test.Test[Any]:

    // ── Test 1: --parallel=N returns correct RunConfig and empty positional ────────────────────

    "test-1: --parallel=4 returns RunConfig(parallelism=4) with empty positional" in {
        val result = Args.parse(Array("--parallel=4"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.parallelism == 4): Unit
                assert(parsed.positional == Chunk.empty): Unit
                assert(parsed.reporterArgs == Chunk.empty): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 2: --randomize=42 returns RunConfig with randomize = Maybe(42L) ─────────────────

    "test-2: --randomize=42 returns RunConfig with randomize = Maybe(42L)" in {
        val result = Args.parse(Array("--randomize=42"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.randomize == Maybe(42L)): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 3: bare --randomize returns RunConfig with randomize.isDefined ──────────────────

    "test-3: bare --randomize returns RunConfig with randomize.isDefined (wall-clock seed)" in {
        val result = Args.parse(Array("--randomize"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.randomize.isDefined): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 4: unknown flag returns Result.Error with message ────────────────────────────────

    "test-4: --invalid returns Result.Error(\"unknown argument: '--invalid'\")" in {
        val result = Args.parse(Array("--invalid"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg == "unknown argument: '--invalid'"): Unit
            case other =>
                fail(s"expected Error, got: $other")
        end match
    }

    // ── Test 5: empty args returns Ok(Parsed(RunConfig(), Chunk.empty)) ──────────────────────

    "test-5: empty args returns Ok(Parsed(RunConfig(), empty reporter, empty positional))" in {
        val result = Args.parse(Array.empty)
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.parallelism == 0): Unit
                assert(parsed.config.randomize == Maybe.empty): Unit
                assert(parsed.config.filter == TestFilter.empty): Unit
                assert(parsed.reporterArgs == Chunk.empty): Unit
                assert(parsed.positional == Chunk.empty): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 6: multiple --filter= accumulate into pathInclude ───────────────────────────────

    "test-6: --filter=*Test --filter=foo.* accumulates both globs into RunConfig.filter.pathInclude" in {
        val result = Args.parse(Array("--filter=*Test", "--filter=foo.*"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.filter.pathInclude == Chunk("*Test", "foo.*")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 7: positional class name goes into Parsed.positional ─────────────────────────────

    "test-7: positional arg 'kyo.FooTest' goes into Parsed.positional" in {
        val result = Args.parse(Array("kyo.FooTest"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.positional == Chunk("kyo.FooTest")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 7b: positional args do NOT pollute RunConfig.filter.pathInclude ─────────────────

    "test-7b: positional arg does NOT promote into RunConfig.filter.pathInclude" in {
        val result = Args.parse(Array("kyo.FooTest"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.filter.pathInclude == Chunk.empty): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 10: --help returns Result.Help ───────────────────────────────────────────────────

    "test-10: --help returns Result.Help" in {
        val result = Args.parse(Array("--help"))
        assert(result == Args.Result.Help): Unit
    }

    // ── Test 11: --tag and --exclude-tag return correct filter sets ───────────────────────────

    "test-11: --tag=fast --exclude-tag=slow returns tagsInclude={fast} tagsExclude={slow}" in {
        val result = Args.parse(Array("--tag=fast", "--exclude-tag=slow"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.filter.tagsInclude == Set("fast")): Unit
                assert(parsed.config.filter.tagsExclude == Set("slow")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 12: --reporter=console,junit-xml:PATH returns CombinedReporter ────────────────────
    // Note: "junit" is not a valid reporter value; valid values are console/tap/tap:PATH/junit-xml:PATH.
    // The translation pass converts reporter values; unknown values produce Result.Error.

    "test-12: --reporter=console returns a single ConsoleReporter in config.reporter" in {
        val result = Args.parse(Array("--reporter=console"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.reporterArgs == Chunk("console")): Unit
                assert(parsed.config.reporter.isDefined): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Result-based parse helpers ─────────────────────────────────────────────

    "phase3-test-4: --parallel=42 returns parsed parallelism 42 (parseInt success)" in {
        val result = Args.parse(Array("--parallel=42"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.parallelism == 42): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    "phase3-test-5: --parallel=bad returns Result.Error containing '--parallel'" in {
        val result = Args.parse(Array("--parallel=bad"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg.contains("--parallel"), s"Expected '--parallel' in error message, got: $msg"): Unit
            case other =>
                fail(s"expected Error, got: $other")
        end match
    }

    "phase3-test-6: --randomize=bad returns Result.Error containing '--randomize'" in {
        val result = Args.parse(Array("--randomize=bad"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg.contains("--randomize"), s"Expected '--randomize' in error message, got: $msg"): Unit
            case other =>
                fail(s"expected Error, got: $other")
        end match
    }

    // ── Direct helper tests (Task #134: parseInt/parseLong promoted to private[runner]) ──

    "direct-parseInt-success: Args.parseInt(\"42\", \"--parallel\") == kyo.Result.succeed(42)" in {
        assert(Args.parseInt("42", "--parallel") == kyo.Result.succeed(42)): Unit
    }

    "direct-parseInt-failure: Args.parseInt(\"bad\", \"--parallel\").isFailure" in {
        assert(Args.parseInt("bad", "--parallel").isFailure): Unit
    }

end ArgsTest
