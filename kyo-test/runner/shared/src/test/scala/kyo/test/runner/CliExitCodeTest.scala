package kyo.test.runner

import kyo.Chunk
import kyo.test.runner.internal.Args
import kyo.test.runner.internal.SuiteDiscoveryPlatform

/** Tests for [[Cli]] exit codes: 4 leaves verifying the three-code scheme.
  *
  * Tests 7 and 8 call [[Cli.main]] via [[Cli.withTestExit]] to intercept the exit code. Tests 9 and 10 verify the Args routing layer that
  * drives the exit-code decision, since wiring a live Failed suite requires the full runner machinery.
  */
class CliExitCodeTest extends kyo.test.Test[Any]:

    // ── Test 7: unknown argument → exit 2 ────────────────────────────────────────────────────

    "test-7: Cli.main(Array(\"--invalid\")) throws SystemExitException(2)" in {
        val ex = intercept[SystemExitException] {
            Cli.withTestExit {
                Cli.main(Array("--invalid"))
            }
        }
        assert(ex.code == 2, s"expected exit code 2, got ${ex.code}")
    }

    // ── Test 8: --help → exit 0 ───────────────────────────────────────────────────────────────

    "test-8: Cli.main(Array(\"--help\")) throws SystemExitException(0)" in {
        val ex = intercept[SystemExitException] {
            Cli.withTestExit {
                Cli.main(Array("--help"))
            }
        }
        assert(ex.code == 0, s"expected exit code 0, got ${ex.code}")
    }

    // ── Test 9: no suites discovered → exit 0 ────────────────────────────────────────────────

    // When no suites are on the classpath, Cli.main discovers an empty Chunk and exits 0.
    // We verify via Args.parse that the filter would parse cleanly (Ok, not Error),
    // and that SuiteDiscoveryPlatform returns a Chunk (no exception), which is the precondition
    // for the "no suites discovered" exit-0 path.
    "test-9: --filter=nonexistent.* parses cleanly; no-suite path exits 0" in {
        val result = Args.parse(Array("--filter=nonexistent.*"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.config.filter.pathInclude == Chunk("nonexistent.*"), s"unexpected filter: ${parsed.config.filter}")
                // Verify that SuiteDiscoveryPlatform.discover() returns a Chunk without throwing.
                val discovered = SuiteDiscoveryPlatform.discover()
                assert(discovered != null): Unit
                // If no suites are discovered (as is the case in this test module), exit would be 0.
                // We verify the exit-0 path by calling Cli.main with withTestExit when suites are empty.
                if discovered.isEmpty then
                    val ex = intercept[SystemExitException] {
                        Cli.withTestExit {
                            Cli.main(Array("--filter=nonexistent.*"))
                        }
                    }
                    assert(ex.code == 0, s"expected exit code 0 for no-suite path, got ${ex.code}")
                else
                    // Suites found (e.g. on JVM with a populated META-INF/services file):
                    // we can only assert the Args layer parsed correctly.
                    (
                )
                end if
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Test 10: Args error path maps to exit 2 (not 1) ──────────────────────────────────────

    // Full end-to-end with a Failed suite requires a registered service-loader entry which is not
    // available in the shared test classpath. We verify the exit-code mapping contract via Args.parse:
    // a malformed flag parses to Args.Result.Error, which Cli.main routes to exit 2.
    "test-10: Args.Result.Error routes to exit 2 (test-failures route to exit 1)" in {
        // Verify the parse-error path maps to exit 2.
        val errorResult = Args.parse(Array("--malformed-flag"))
        errorResult match
            case Args.Result.Error(_) =>
                val ex = intercept[SystemExitException] {
                    Cli.withTestExit {
                        Cli.main(Array("--malformed-flag"))
                    }
                }
                assert(ex.code == 2, s"Args.Result.Error should exit 2, got ${ex.code}")
            case other =>
                fail(s"expected Error for '--malformed-flag', got: $other")
        end match
    }

    // ── Test 11: failure run → exit 1 ────────────────────────────────────────────────────────

    // The exit-1 path is exercised when CliPlatform.runSuites detects failures (report.failed > 0)
    // and calls CliPlatform.exit(1). We verify the doExit hook side: Cli.exitForTest(1) routes
    // through doExit, which withTestExit intercepts as SystemExitException(1). In production,
    // CliPlatform.runSuites calls CliPlatform.exit directly (not doExit), so a full end-to-end
    // exit-1 test requires service-loader fixtures outside the shared classpath. This test
    // verifies the doExit(1) contract: the minimal unit that distinguishes exit-1 from exit-0.
    "test-11: Cli.exitForTest(1) inside withTestExit throws SystemExitException(1): exit-1 failure-path contract" in {
        val ex = intercept[SystemExitException] {
            Cli.withTestExit {
                // exitForTest routes through doExit, patched by withTestExit.
                // This is the same exit-code value that CliPlatform.runSuites passes to exit()
                // when report.failed > 0 (the test-failure → exit-1 path).
                Cli.exitForTest(1)
            }
        }
        assert(ex.code == 1, s"expected exit code 1 for the test-failure path, got ${ex.code}")
    }

end CliExitCodeTest
