package kyo.test.runner

import kyo.*
import kyo.test.RunConfig
import kyo.test.runner.internal.Args

/** Tests for [[Args.parse]]: the CLI argument parser. */
class ArgsTest extends kyo.test.Test[Any]:

    // ── Test 1: --parallel=N ───────────────────────────────────────────────────────────────────

    "parses --parallel=N" in {
        val result = Args.parse(Array("--parallel=8"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.parallelism == 8): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 2: --randomize and --randomize=N ─────────────────────────────────────────────────

    "parses --randomize and --randomize=N" in {
        // bare --randomize uses currentTimeMillis (just check it's Present)
        val result1 = Args.parse(Array("--randomize"))
        result1 match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.randomize.isDefined): Unit
            case other =>
                fail(s"expected Ok for --randomize, got: $other")
        end match

        // --randomize=SEED uses the given seed
        val result2 = Args.parse(Array("--randomize=99999"))
        result2 match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.randomize == Maybe(99999L)): Unit
            case other =>
                fail(s"expected Ok for --randomize=99999, got: $other")
        end match
    }

    // ── Test 4: --tag and --exclude-tag (repeatable) ──────────────────────────────────────────

    "parses --tag and --exclude-tag (repeatable)" in {
        val result = Args.parse(Array("--tag=fast", "--tag=unit", "--exclude-tag=slow", "--exclude-tag=integration"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.filter.tagsInclude == Set("fast", "unit")): Unit
                assert(parsed.config.filter.tagsExclude == Set("slow", "integration")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 5: --filter (repeatable) ─────────────────────────────────────────────────────────

    "parses --filter (repeatable)" in {
        val result = Args.parse(Array("--filter=**/login", "--filter=**/signup"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.filter.pathInclude == Chunk("**/login", "**/signup")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 6: --reporter with multiple comma-separated values ───────────────────────────────

    "parses --reporter with multiple comma-separated values" in {
        val result = Args.parse(Array("--reporter=console,tap"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.reporterArgs == Chunk("console", "tap")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 8: --help returns Result.Help ────────────────────────────────────────────────────

    "--help returns Help result (usage would be printed and exit 0)" in {
        val result = Args.parse(Array("--help"))
        assert(result == Args.Result.Help): Unit
    }

    "usage string is non-empty" in {
        assert(Args.usage.nonEmpty): Unit
        assert(Args.usage.contains("--parallel")): Unit
        assert(Args.usage.contains("--help")): Unit
    }

    // ── Test 9: invalid arg returns Result.Error ──────────────────────────────────────────────

    "invalid arg returns Error result (error would be printed and exit non-zero)" in {
        val result = Args.parse(Array("--unknown-flag=foo"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg.contains("unknown")): Unit
            case other =>
                fail(s"expected Error, got: $other")
        end match
    }

    "malformed --parallel value returns Error" in {
        val result = Args.parse(Array("--parallel=notanumber"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg.contains("parallel")): Unit
            case other =>
                fail(s"expected Error, got: $other")
        end match
    }

    // ── --heartbeat-interval and the overlay ──────────────────────────────────────────────────

    "parses --heartbeat-interval=D" in {
        Args.parse(Array("--heartbeat-interval=30s")) match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.heartbeatInterval == 30.seconds): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    "malformed --heartbeat-interval value returns Error" in {
        Args.parse(Array("--heartbeat-interval=soon")) match
            case Args.Result.Error(msg) =>
                assert(msg.contains("heartbeat-interval")): Unit
            case other =>
                fail(s"expected Error, got: $other")
        end match
    }

    // The runners apply the flags on top of the suite's own config. A field no flag names keeps the suite's value.
    "the overlay changes only the fields the flags name" in {
        val suiteConfig = RunConfig.default.timeout(5.seconds).sequential.heartbeatInterval(10.seconds)
        Args.parse(Array("--filter=**/login", "--heartbeat-interval=30s")) match
            case Args.Result.Ok(parsed) =>
                val effective = parsed.overlay(suiteConfig)
                assert(effective.timeout == 5.seconds): Unit
                assert(effective.parallelism == 1): Unit
                assert(effective.heartbeatInterval == 30.seconds): Unit
                assert(effective.filter.pathInclude == Chunk("**/login")): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    "with no flags the overlay is the identity" in {
        val suiteConfig = RunConfig.default.timeout(5.seconds).sequential
        Args.parse(Array.empty[String]) match
            case Args.Result.Ok(parsed) =>
                assert(parsed.overlay(suiteConfig) == suiteConfig): Unit
                assert(parsed.config == RunConfig.default): Unit
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

end ArgsTest
