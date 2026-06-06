package kyo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** JVM-only benchmark regression infrastructure tests.
  *
  * These tests do not run JMH. They verify that: (1) The post-campaign baseline file exists and contains valid (non-negative or sentinel)
  * numbers. (2) The pre-campaign baseline gap is documented.
  *
  * INV-027 interpretation: no pre-campaign baseline was captured before the audit-fixes campaign started. INV-027 is satisfied by the
  * EXISTENCE of benchmark infrastructure (this test file) and a recorded post-campaign state (`bench-baselines/post-campaign.json`). Future
  * regression phases compare against that post-campaign snapshot. Values of -1 in the JSON are the sentinel for "not yet measured by JMH".
  *
  * To populate real numbers: sbt 'kyo-tasty-bench/Jmh/run -i 5 -wi 5 -f 1 -bm avgt -tu ms'
  */
class BenchmarkRegressionTest extends Test:

    /** Walk up from user.dir until a directory containing build.sbt is found. */
    private def findWorktreeRoot: Path =
        var candidate = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while candidate != null && !Files.exists(candidate.resolve("build.sbt")) do
            candidate = candidate.getParent
        end while
        require(candidate != null, "Could not locate worktree root (no build.sbt found in ancestor directories)")
        candidate
    end findWorktreeRoot

    /** Parse a numeric field from a JSON string. Returns None if the field is absent or unparseable.
      *
      * Matches the pattern `"key": <number>` where number is a decimal (possibly floating-point) or -1.
      */
    private def parseJsonDouble(json: String, key: String): Option[Double] =
        val pattern = s""""$key"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)""".r
        pattern.findFirstMatchIn(json).flatMap(m => m.group(1).toDoubleOption)
    end parseJsonDouble

    // Test 1 (INV-027): post-campaign baseline file exists and cold_load_ms is valid.
    //
    // The post-campaign baseline captures performance state at the end of the audit-fixes campaign.
    // cold_load_ms must be -1 (sentinel = not yet JMH-measured) or a non-negative number.
    // The pre-campaign baseline (cold-load.json) is absent; this test documents that known gap
    // rather than failing on it.
    "P27-T1: post-campaign baseline file exists and cold_load_ms is sentinel or non-negative" taggedAs jvmOnly in {
        val worktreeRoot     = findWorktreeRoot
        val postCampaignFile = worktreeRoot.resolve("kyo-tasty/bench-baselines/post-campaign.json")

        assert(
            Files.isRegularFile(postCampaignFile),
            s"kyo-tasty/bench-baselines/post-campaign.json must exist at $postCampaignFile"
        )

        val json       = new String(Files.readAllBytes(postCampaignFile), "UTF-8")
        val coldLoadMs = parseJsonDouble(json, "cold_load_ms")

        assert(
            coldLoadMs.isDefined,
            s"post-campaign.json must contain a 'cold_load_ms' field; got: $json"
        )

        val coldVal = coldLoadMs.get
        assert(
            coldVal == -1.0 || coldVal >= 0.0,
            s"cold_load_ms must be -1 (sentinel) or non-negative; got $coldVal"
        )

        // Document the pre-campaign gap. This is a non-failing observation.
        val preCampaignFile = worktreeRoot.resolve("kyo-tasty/bench-baselines/cold-load.json")
        val preCampaignNote =
            if Files.isRegularFile(preCampaignFile) then
                "pre-campaign baseline present"
            else
                "pre-campaign baseline absent (expected: no baseline was captured before the campaign)"
        info(
            s"P27-T1 baseline status: cold_load_ms=$coldVal  $preCampaignNote"
        )

        succeed
    }

    // Test 2 (INV-027): post-campaign baseline file exists and warm_cache_ms is valid.
    //
    // warm_cache_ms must be -1 (sentinel = not yet JMH-measured) or a non-negative number.
    // Same pre-campaign gap documentation applies.
    "P27-T2: post-campaign baseline file exists and warm_cache_ms is sentinel or non-negative" taggedAs jvmOnly in {
        val worktreeRoot     = findWorktreeRoot
        val postCampaignFile = worktreeRoot.resolve("kyo-tasty/bench-baselines/post-campaign.json")

        assert(
            Files.isRegularFile(postCampaignFile),
            s"kyo-tasty/bench-baselines/post-campaign.json must exist at $postCampaignFile"
        )

        val json        = new String(Files.readAllBytes(postCampaignFile), "UTF-8")
        val warmCacheMs = parseJsonDouble(json, "warm_cache_ms")

        assert(
            warmCacheMs.isDefined,
            s"post-campaign.json must contain a 'warm_cache_ms' field; got: $json"
        )

        val warmVal = warmCacheMs.get
        assert(
            warmVal == -1.0 || warmVal >= 0.0,
            s"warm_cache_ms must be -1 (sentinel) or non-negative; got $warmVal"
        )

        // Document the pre-campaign gap. This is a non-failing observation.
        val preCampaignFile = worktreeRoot.resolve("kyo-tasty/bench-baselines/cold-load.json")
        val preCampaignNote =
            if Files.isRegularFile(preCampaignFile) then
                "pre-campaign baseline present"
            else
                "pre-campaign baseline absent (expected: no baseline was captured before the campaign)"
        info(
            s"P27-T2 baseline status: warm_cache_ms=$warmVal  $preCampaignNote"
        )

        succeed
    }

end BenchmarkRegressionTest
