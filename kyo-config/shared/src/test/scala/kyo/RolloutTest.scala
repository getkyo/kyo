package kyo

import org.scalatest.freespec.AnyFreeSpec

class RolloutTest extends AnyFreeSpec {

    // Helper: create a path array from a string
    private def pathOf(s: String): Array[String] =
        if (s.isEmpty) Array.empty[String] else s.split("/")

    "Rollout.select" - {

        // ── Basic values ──

        "plain value with no rollout syntax returns Some(value)" in {
            assert(Rollout.select("hello", pathOf("prod"), 0) == Right(Some("hello")))
        }

        "empty string returns None" in {
            assert(Rollout.select("", pathOf("prod"), 0) == Right(None))
        }

        "value with special characters (spaces, unicode) as plain value" in {
            assert(Rollout.select("hello world", pathOf("prod"), 0) == Right(Some("hello world")))
            assert(Rollout.select("cafe\u0301", pathOf("prod"), 0) == Right(Some("cafe\u0301")))
        }

        // ── Terminal choices ──

        "single terminal returns Some(value)" in {
            assert(Rollout.select("fallback", pathOf("prod"), 0) == Right(Some("fallback")))
        }

        "terminal after non-matching conditions returns terminal" in {
            assert(Rollout.select("a@staging;b@dev;fallback", pathOf("prod"), 0) == Right(Some("fallback")))
        }

        "terminal in middle stops evaluation" in {
            // "middle" is a terminal (no @), so it matches immediately;
            // "c@prod" is never reached
            assert(Rollout.select("a@staging;middle;c@prod", pathOf("prod"), 0) == Right(Some("middle")))
        }

        // ── Single condition matching ──

        "exact single-segment match" in {
            assert(Rollout.select("yes@prod", pathOf("prod"), 0) == Right(Some("yes")))
        }

        "exact multi-segment match" in {
            assert(Rollout.select("yes@prod/us-east-1", pathOf("prod/us-east-1"), 0) == Right(Some("yes")))
        }

        "single condition no match returns None" in {
            assert(Rollout.select("yes@staging", pathOf("prod"), 0) == Right(None))
        }

        "single condition with deeper rollout path (prefix match)" in {
            assert(Rollout.select("yes@prod", pathOf("prod/us-east-1/az1"), 0) == Right(Some("yes")))
        }

        // ── Multiple choices ──

        "first choice matches returns first value" in {
            assert(Rollout.select("a@prod;b@staging;c@dev", pathOf("prod"), 0) == Right(Some("a")))
        }

        "second choice matches returns second value" in {
            assert(Rollout.select("a@staging;b@prod;c@dev", pathOf("prod"), 0) == Right(Some("b")))
        }

        "third choice matches returns third value" in {
            assert(Rollout.select("a@staging;b@dev;c@prod", pathOf("prod"), 0) == Right(Some("c")))
        }

        "three choices with specificity ordering (most-specific first)" in {
            val expr = "regional@prod/us-east-1;env@prod;fallback"
            // Specific region match
            assert(Rollout.select(expr, pathOf("prod/us-east-1"), 0) == Right(Some("regional")))
            // Region doesn't match, but env does
            assert(Rollout.select(expr, pathOf("prod/eu-west-1"), 0) == Right(Some("env")))
            // Nothing matches except terminal
            assert(Rollout.select(expr, pathOf("staging"), 0) == Right(Some("fallback")))
        }

        "four choices mimicking real multi-region config" in {
            val expr = "10@prod/us-east-1;20@prod/eu-west-1;5@prod/ap-southeast-1;1@prod"
            assert(Rollout.select(expr, pathOf("prod/us-east-1/az1"), 0) == Right(Some("10")))
            assert(Rollout.select(expr, pathOf("prod/eu-west-1/az1"), 0) == Right(Some("20")))
            assert(Rollout.select(expr, pathOf("prod/ap-southeast-1"), 0) == Right(Some("5")))
            assert(Rollout.select(expr, pathOf("prod/ap-northeast-1"), 0) == Right(Some("1")))
        }

        "no choice matches and no terminal returns None" in {
            assert(Rollout.select("a@staging;b@dev", pathOf("prod"), 0) == Right(None))
        }

        // ── Prefix matching ──

        "selector 'prod' matches deep path 'prod/us-east-1/az1/cluster/pod'" in {
            assert(Rollout.select("yes@prod", pathOf("prod/us-east-1/az1/cluster/pod"), 0) == Right(Some("yes")))
        }

        "selector 'prod/us-east-1' matches path 'prod/us-east-1/az1'" in {
            assert(Rollout.select("yes@prod/us-east-1", pathOf("prod/us-east-1/az1"), 0) == Right(Some("yes")))
        }

        "selector longer than path does not match" in {
            assert(Rollout.select("yes@prod/us-east-1/az1", pathOf("prod"), 0) == Right(None))
        }

        "exact length match (selector same depth as path)" in {
            assert(Rollout.select("yes@prod/us-east-1", pathOf("prod/us-east-1"), 0) == Right(Some("yes")))
        }

        "single segment selector against multi-segment path" in {
            assert(Rollout.select("yes@prod", pathOf("prod/region/az"), 0) == Right(Some("yes")))
        }

        // ── Wildcards ──

        "wildcard matches any single segment" in {
            assert(Rollout.select("yes@*", pathOf("prod"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@*", pathOf("staging"), 0) == Right(Some("yes")))
        }

        "wildcard at root level" in {
            assert(Rollout.select("yes@*", pathOf("anything"), 0) == Right(Some("yes")))
        }

        "wildcard in middle: prod/*/az1" in {
            assert(Rollout.select("yes@prod/*/az1", pathOf("prod/us-east-1/az1"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@prod/*/az1", pathOf("prod/eu-west-1/az1"), 0) == Right(Some("yes")))
            // Wrong third segment
            assert(Rollout.select("yes@prod/*/az1", pathOf("prod/us-east-1/az2"), 0) == Right(None))
        }

        "multiple wildcards: */*/az1" in {
            assert(Rollout.select("yes@*/*/az1", pathOf("prod/us-east-1/az1"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@*/*/az1", pathOf("staging/eu-west-1/az1"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@*/*/az1", pathOf("dev/any/az1"), 0) == Right(Some("yes")))
        }

        "wildcard at end of selector" in {
            assert(Rollout.select("yes@prod/*", pathOf("prod/us-east-1"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@prod/*", pathOf("prod/eu-west-1"), 0) == Right(Some("yes")))
        }

        "wildcard does NOT match empty (path must have a segment there)" in {
            // Selector is "*" (one segment), but path is empty
            assert(Rollout.select("yes@*", pathOf(""), 0) == Right(None))
        }

        // ── Percentages ──

        "0% never matches regardless of bucket" in {
            for (b <- 0 until 100) {
                assert(Rollout.select("yes@prod/0%", pathOf("prod"), b) == Right(None), s"bucket=$b")
            }
        }

        "100% always matches regardless of bucket" in {
            for (b <- 0 until 100) {
                assert(Rollout.select("yes@prod/100%", pathOf("prod"), b) == Right(Some("yes")), s"bucket=$b")
            }
        }

        "50% with bucket 49 matches (49 < 50)" in {
            assert(Rollout.select("yes@prod/50%", pathOf("prod"), 49) == Right(Some("yes")))
        }

        "50% with bucket 50 does not match (50 >= 50)" in {
            assert(Rollout.select("yes@prod/50%", pathOf("prod"), 50) == Right(None))
        }

        "1% with bucket 0 matches" in {
            assert(Rollout.select("yes@prod/1%", pathOf("prod"), 0) == Right(Some("yes")))
        }

        "99% with bucket 98 matches" in {
            assert(Rollout.select("yes@prod/99%", pathOf("prod"), 98) == Right(Some("yes")))
        }

        "99% with bucket 99 does not match" in {
            assert(Rollout.select("yes@prod/99%", pathOf("prod"), 99) == Right(None))
        }

        "percentage with path prefix: must match path AND be in bucket" in {
            // Path matches, bucket in range
            assert(Rollout.select("yes@prod/50%", pathOf("prod"), 25) == Right(Some("yes")))
            // Path matches, bucket out of range
            assert(Rollout.select("yes@prod/50%", pathOf("prod"), 75) == Right(None))
            // Path doesn't match, bucket in range
            assert(Rollout.select("yes@staging/50%", pathOf("prod"), 25) == Right(None))
        }

        "percentage with wildcard prefix: */50%" in {
            assert(Rollout.select("yes@*/50%", pathOf("prod"), 25) == Right(Some("yes")))
            assert(Rollout.select("yes@*/50%", pathOf("prod"), 75) == Right(None))
            assert(Rollout.select("yes@*/50%", pathOf("staging"), 25) == Right(Some("yes")))
        }

        "bare percentage without path: 50%" in {
            // Selector is just "50%": pathParts is empty, treated as percentage-only
            assert(Rollout.select("yes@50%", pathOf("prod"), 25) == Right(Some("yes")))
            assert(Rollout.select("yes@50%", pathOf("prod"), 75) == Right(None))
            // Also works with empty path
            assert(Rollout.select("yes@50%", pathOf(""), 25) == Right(Some("yes")))
        }

        // ── Weight accumulation (new in kyo-config) ──

        "multi-arm percentage: weights accumulate across choices" in {
            val expr = "a@33%;b@33%;control"
            // bucket 0-32 → a (cumulWeight=0, weight=33, 0 <= bucket < 33)
            assert(Rollout.select(expr, pathOf("prod"), 0) == Right(Some("a")))
            assert(Rollout.select(expr, pathOf("prod"), 32) == Right(Some("a")))
            // bucket 33-65 → b (cumulWeight=33, weight=33, 33 <= bucket < 66)
            assert(Rollout.select(expr, pathOf("prod"), 33) == Right(Some("b")))
            assert(Rollout.select(expr, pathOf("prod"), 65) == Right(Some("b")))
            // bucket 66-99 → control (terminal)
            assert(Rollout.select(expr, pathOf("prod"), 66) == Right(Some("control")))
            assert(Rollout.select(expr, pathOf("prod"), 99) == Right(Some("control")))
        }

        "multi-arm with path and percentage: mixed weights" in {
            // fast gets 20% of prod, balanced gets next 10% (bare), safe is terminal
            // Parse-time thresholds: fast=20, balanced=30 (20+10)
            val expr = "fast@prod/20%;balanced@10%;safe"

            // prod path, bucket 0-19 → fast (path matches, 0 <= bucket < 20)
            assert(Rollout.select(expr, pathOf("prod"), 0) == Right(Some("fast")))
            assert(Rollout.select(expr, pathOf("prod"), 19) == Right(Some("fast")))

            // prod path, bucket 20-29 → balanced (cumulWeight=20, 20 <= bucket < 30)
            assert(Rollout.select(expr, pathOf("prod"), 20) == Right(Some("balanced")))
            assert(Rollout.select(expr, pathOf("prod"), 29) == Right(Some("balanced")))

            // prod path, bucket 30+ → safe (terminal)
            assert(Rollout.select(expr, pathOf("prod"), 30) == Right(Some("safe")))
            assert(Rollout.select(expr, pathOf("prod"), 99) == Right(Some("safe")))

            // staging path → fast path doesn't match (not prod), cumulWeight still advances by 20
            // balanced@10%: cumulWeight=20, bucket 20-29 → balanced
            assert(Rollout.select(expr, pathOf("staging"), 20) == Right(Some("balanced")))
            assert(Rollout.select(expr, pathOf("staging"), 29) == Right(Some("balanced")))
            // staging, bucket 0 → fast skip (path no match), balanced skip (0 < 20), safe terminal
            assert(Rollout.select(expr, pathOf("staging"), 0) == Right(Some("safe")))
            // staging, bucket 30+ → safe (terminal)
            assert(Rollout.select(expr, pathOf("staging"), 30) == Right(Some("safe")))
        }

        // ── Realistic scenarios ──

        "gradual rollout day 1-6 progression" in {
            val path   = pathOf("prod/us-east-1/canary/pod1")
            val bucket = 5

            // Day 1: only canary cluster
            val day1 = "true@prod/us-east-1/canary"
            assert(Rollout.select(day1, path, bucket) == Right(Some("true")))
            // Non-canary doesn't match
            assert(Rollout.select(day1, pathOf("prod/us-east-1/main/pod1"), bucket) == Right(None))

            // Day 2: 10% of us-east-1
            val day2 = "true@prod/us-east-1/10%"
            assert(Rollout.select(day2, path, bucket) == Right(Some("true"))) // bucket 5 < 10
            assert(Rollout.select(day2, path, 50) == Right(None))             // bucket 50 >= 10

            // Day 3: full us-east-1 + 10% eu-west
            val day3 = "true@prod/us-east-1;true@prod/eu-west-1/10%"
            assert(Rollout.select(day3, path, bucket) == Right(Some("true")))
            assert(Rollout.select(day3, pathOf("prod/eu-west-1/az1"), 5) == Right(Some("true")))
            assert(Rollout.select(day3, pathOf("prod/eu-west-1/az1"), 50) == Right(None))

            // Day 4: 50% of all prod
            val day4 = "true@prod/50%"
            assert(Rollout.select(day4, path, bucket) == Right(Some("true")))           // bucket 5 < 50
            assert(Rollout.select(day4, path, 75) == Right(None))                       // bucket 75 >= 50
            assert(Rollout.select(day4, pathOf("staging/pod1"), bucket) == Right(None)) // not prod

            // Day 5: all prod
            val day5 = "true@prod"
            assert(Rollout.select(day5, path, bucket) == Right(Some("true")))
            assert(Rollout.select(day5, pathOf("staging/pod1"), bucket) == Right(None))

            // Day 6: everywhere
            val day6 = "true"
            assert(Rollout.select(day6, path, bucket) == Right(Some("true")))
            assert(Rollout.select(day6, pathOf("staging/pod1"), bucket) == Right(Some("true")))
            assert(Rollout.select(day6, pathOf(""), bucket) == Right(Some("true")))
        }

        "multi-region DB pool sizing" in {
            val expr = "50@prod/us-east-1;30@prod/eu-west-1;20@prod;10@staging;5"
            assert(Rollout.select(expr, pathOf("prod/us-east-1/az1"), 0) == Right(Some("50")))
            assert(Rollout.select(expr, pathOf("prod/eu-west-1/az1"), 0) == Right(Some("30")))
            assert(Rollout.select(expr, pathOf("prod/ap-southeast-1"), 0) == Right(Some("20")))
            assert(Rollout.select(expr, pathOf("staging/host1"), 0) == Right(Some("10")))
            assert(Rollout.select(expr, pathOf("dev/host1"), 0) == Right(Some("5")))
        }

        "feature gate with canary + percentage" in {
            val expr = "true@staging;true@prod/*/canary;true@prod/5%;false"
            // staging matches first choice
            assert(Rollout.select(expr, pathOf("staging/host1"), 0) == Right(Some("true")))
            // prod canary cluster matches second choice
            assert(Rollout.select(expr, pathOf("prod/us-east-1/canary"), 0) == Right(Some("true")))
            // prod non-canary with low bucket matches third choice (5%)
            assert(Rollout.select(expr, pathOf("prod/us-east-1/main"), 3) == Right(Some("true")))
            // prod non-canary with high bucket falls through to terminal "false"
            assert(Rollout.select(expr, pathOf("prod/us-east-1/main"), 50) == Right(Some("false")))
        }

        "timeout configuration with percentage" in {
            val expr = "100@prod/us-east-1/25%;500@prod;1000"
            // prod/us-east-1 with bucket in range -> 100
            assert(Rollout.select(expr, pathOf("prod/us-east-1/az1"), 10) == Right(Some("100")))
            // prod/us-east-1 with bucket out of range -> falls to prod -> 500
            assert(Rollout.select(expr, pathOf("prod/us-east-1/az1"), 50) == Right(Some("500")))
            // prod other region -> 500
            assert(Rollout.select(expr, pathOf("prod/eu-west-1/az1"), 0) == Right(Some("500")))
            // non-prod -> terminal 1000
            assert(Rollout.select(expr, pathOf("staging/host1"), 0) == Right(Some("1000")))
        }

        // ── Edge cases ──

        "choice value contains spaces" in {
            assert(Rollout.select("hello world@prod", pathOf("prod"), 0) == Right(Some("hello world")))
        }

        "choice value is numeric" in {
            assert(Rollout.select("42@prod;0", pathOf("prod"), 0) == Right(Some("42")))
            assert(Rollout.select("42@prod;0", pathOf("staging"), 0) == Right(Some("0")))
        }

        "selector with trailing slash is malformed" in {
            // "prod/" splits to ["prod", ""], the empty segment won't match any path segment
            val result = Rollout.select("yes@prod/", pathOf("prod/us-east-1"), 0)
            // The empty string segment won't equal "us-east-1" since "" != "us-east-1"
            assert(result == Right(None))
        }

        "double semicolons skip empty choice" in {
            // "a@staging;;b@prod" splits to ["a@staging", "", "b@prod"]
            // empty choice is skipped, so b@prod is evaluated
            val result = Rollout.select("a@staging;;b@prod", pathOf("prod"), 0)
            assert(result == Right(Some("b")))
        }

        "rollout path is empty — only terminal and bare percentage match" in {
            // Conditional with path segment won't match empty path
            assert(Rollout.select("yes@prod", pathOf(""), 0) == Right(None))
            // Terminal matches
            assert(Rollout.select("yes@prod;fallback", pathOf(""), 0) == Right(Some("fallback")))
            // Bare percentage matches against empty path
            assert(Rollout.select("yes@50%", pathOf(""), 25) == Right(Some("yes")))
            assert(Rollout.select("yes@50%", pathOf(""), 75) == Right(None))
        }

        "rollout path has one segment" in {
            assert(Rollout.select("yes@prod", pathOf("prod"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@staging", pathOf("prod"), 0) == Right(None))
        }

        "multiple @ in one choice — first @ splits value from selector" in {
            // "a@b@c": value="a", selector="b@c"
            // selector "b@c" is a single path segment, matches if instancePath(0) == "b@c"
            assert(Rollout.select("a@b@c", pathOf("b@c"), 0) == Right(Some("a")))
            // Doesn't match normal paths
            assert(Rollout.select("a@b@c", pathOf("b"), 0) == Right(None))
        }

        "percentage > 100 is accepted (clamped by bucket range)" in {
            // With the fix, parsePercentage accepts >100% to be consistent with validate()
            val result = Rollout.select("yes@prod/200%", pathOf("prod"), 0)
            assert(result.isRight)
            // bucket 0 is in range [0, 200), so it matches
            assert(result == Right(Some("yes")))
        }

        "percentage = 0 boundary" in {
            // 0% with bucket 0 — still no match because !(0 >= 0 && 0 < 0)
            assert(Rollout.select("yes@prod/0%", pathOf("prod"), 0) == Right(None))
        }

        "very long path (10 segments)" in {
            val longPath = pathOf("a/b/c/d/e/f/g/h/i/j")
            // Short selector still prefix-matches
            assert(Rollout.select("yes@a/b", longPath, 0) == Right(Some("yes")))
            // Full path match
            assert(Rollout.select("yes@a/b/c/d/e/f/g/h/i/j", longPath, 0) == Right(Some("yes")))
            // Mismatch deep in path
            assert(Rollout.select("yes@a/b/c/d/e/f/g/h/i/z", longPath, 0) == Right(None))
        }

        "very many choices (10+)" in {
            val choices = (1 to 10).map(i => s"v$i@env$i").mkString(";") + ";default"
            // None of env1-env10 match "prod"
            assert(Rollout.select(choices, pathOf("prod"), 0) == Right(Some("default")))
            // env5 matches
            assert(Rollout.select(choices, pathOf("env5"), 0) == Right(Some("v5")))
            // env10 matches
            assert(Rollout.select(choices, pathOf("env10"), 0) == Right(Some("v10")))
        }

        // ── Section 21.8 new tests ──

        "Rollout DSL basic parsing" in {
            // Simple expression with terminal
            assert(Rollout.select("fallback", pathOf(""), 0) == Right(Some("fallback")))
            // Expression with condition and terminal
            assert(Rollout.select("a@prod;b", pathOf("prod"), 0) == Right(Some("a")))
            assert(Rollout.select("a@prod;b", pathOf("staging"), 0) == Right(Some("b")))
        }

        "path prefix matching" in {
            // Selector is prefix of longer path
            assert(Rollout.select("yes@prod", pathOf("prod/us-east-1/az1"), 0) == Right(Some("yes")))
            // Selector requires more segments than path has
            assert(Rollout.select("yes@prod/us-east-1/az1", pathOf("prod"), 0) == Right(None))
        }

        "wildcard matching" in {
            assert(Rollout.select("yes@prod/*/canary", pathOf("prod/us-east-1/canary"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@prod/*/canary", pathOf("prod/eu-west-1/canary"), 0) == Right(Some("yes")))
            assert(Rollout.select("yes@prod/*/canary", pathOf("prod/us-east-1/main"), 0) == Right(None))
        }

        "percentage evaluation with weights" in {
            // Two choices, each getting 33% of bucket space
            val expr = "a@33%;b@33%;c"
            // a: bucket in [0, 33)
            assert(Rollout.select(expr, pathOf("any"), 10) == Right(Some("a")))
            // b: bucket in [33, 66)
            assert(Rollout.select(expr, pathOf("any"), 50) == Right(Some("b")))
            // c: terminal catches the rest
            assert(Rollout.select(expr, pathOf("any"), 80) == Right(Some("c")))
        }

        "terminal fallback behavior" in {
            // Terminal as last choice is the default fallback
            assert(Rollout.select("a@staging;b@dev;default", pathOf("prod"), 0) == Right(Some("default")))
            // No terminal and no match → None
            assert(Rollout.select("a@staging;b@dev", pathOf("prod"), 0) == Right(None))
        }

        "multiple choices with first-match" in {
            // First matching choice wins
            val expr = "specific@prod/us-east-1;general@prod;fallback"
            assert(Rollout.select(expr, pathOf("prod/us-east-1/az1"), 0) == Right(Some("specific")))
            assert(Rollout.select(expr, pathOf("prod/eu-west-1"), 0) == Right(Some("general")))
            assert(Rollout.select(expr, pathOf("staging"), 0) == Right(Some("fallback")))
        }
    }

    "Rollout.validate" - {

        "validate() accepts valid expression" in {
            assert(Rollout.validate("a@prod/50%;b") == Right(Nil))
        }

        "validate() rejects empty selector" in {
            val result = Rollout.validate("a@")
            assert(result.isLeft)
            assert(result.left.getOrElse("").contains("empty selector"))
        }

        "validate() rejects double slash (empty path segment)" in {
            val result = Rollout.validate("a@prod//b")
            assert(result.isLeft)
            assert(result.left.getOrElse("").contains("empty path segment"))
        }

        "validate() warns on weight > 100" in {
            val result = Rollout.validate("a@200%")
            // Per design: individual > 100 is a warning in validate, not hard error
            assert(result.isRight)
            val warnings = result.getOrElse(Nil)
            assert(warnings.exists(_.contains("100%")))
        }

        "validate() accepts bare percentage" in {
            assert(Rollout.validate("a@50%") == Right(Nil))
        }

        "validate() accepts wildcards" in {
            assert(Rollout.validate("a@*/b/*/50%") == Right(Nil))
        }

        "validate() accepts no-selector terminal" in {
            assert(Rollout.validate("fallback") == Right(Nil))
        }

        "validate() accepts empty string" in {
            assert(Rollout.validate("") == Right(Nil))
        }

        "validate() error cases" in {
            // Empty value before @
            val emptyValue = Rollout.validate("@prod")
            assert(emptyValue.isLeft)
            assert(emptyValue.left.getOrElse("").contains("empty value"))

            // Invalid percentage
            val badPercent = Rollout.validate("a@abc%")
            assert(badPercent.isLeft)
            assert(badPercent.left.getOrElse("").contains("invalid percentage"))

            // Negative percentage
            val negPercent = Rollout.validate("a@-5%")
            assert(negPercent.isLeft)
            assert(negPercent.left.getOrElse("").contains("negative percentage"))
        }

        "validate() warning cases" in {
            // Unreachable choices after terminal
            val afterTerminal = Rollout.validate("fallback;a@prod")
            assert(afterTerminal.isRight)
            val w1 = afterTerminal.getOrElse(Nil)
            assert(w1.exists(_.contains("unreachable")))

            // Weights summing > 100%
            val overweight = Rollout.validate("a@60%;b@60%")
            assert(overweight.isRight)
            val w2 = overweight.getOrElse(Nil)
            assert(w2.exists(_.contains("exceeds 100%")))

            // Numeric path segment warning
            val numericSeg = Rollout.validate("a@50")
            assert(numericSeg.isRight)
            val w3 = numericSeg.getOrElse(Nil)
            assert(w3.exists(_.contains("did you mean")))
        }

        "validate() with weights summing to exactly 100% is ok" in {
            val result = Rollout.validate("a@50%;b@50%")
            assert(result == Right(Nil))
        }

        "validate() with weights less than 100% is ok" in {
            val result = Rollout.validate("a@30%;b@20%")
            assert(result == Right(Nil))
        }

        "validate() warns about choices after terminal even with conditions" in {
            val result = Rollout.validate("a@staging;fallback;b@prod;c@dev")
            assert(result.isRight)
            val warnings = result.getOrElse(Nil)
            assert(warnings.size == 2) // b@prod and c@dev are both unreachable
            assert(warnings.forall(_.contains("unreachable")))
        }
    }

    "Rollout validate/select consistency" - {

        "validate and select agree on percentage > 100" in {
            // validate("a@150%") should produce a warning (not error)
            val validated = Rollout.validate("a@150%")
            assert(validated.isRight, s"validate should succeed, got: $validated")
            val warnings = validated.getOrElse(Nil)
            assert(warnings.exists(_.contains("100%")), "should warn about >100%")

            // select("a@150%", ...) should also succeed (not fail with Left)
            val selected = Rollout.select("a@150%", pathOf("prod"), 0)
            assert(selected.isRight, s"select should succeed, got: $selected")
            // bucket 0 is in range [0, 150), so it matches
            assert(selected == Right(Some("a")))
        }

        "validate accumulates weight even for percentages > 100" in {
            // "a@150%;b@50%" — total weight should be 200, which triggers cumulative warning
            val validated = Rollout.validate("a@150%;b@50%")
            assert(validated.isRight)
            val warnings = validated.getOrElse(Nil)
            assert(warnings.exists(_.contains("percentage exceeds 100%")), "should warn about individual >100%")
            assert(warnings.exists(_.contains("weights sum to 200%")), s"should warn about cumulative total, got: $warnings")
        }
    }

    "Rollout.splitOn" - {

        "trailing delimiter produces empty last element" in {
            val result = Rollout.splitOn("a;b;", ';')
            assert(result.toList == List("a", "b", ""))
        }

        "no trailing delimiter" in {
            val result = Rollout.splitOn("a;b", ';')
            assert(result.toList == List("a", "b"))
        }

        "single element no delimiter" in {
            val result = Rollout.splitOn("abc", ';')
            assert(result.toList == List("abc"))
        }

        "empty string" in {
            val result = Rollout.splitOn("", ';')
            assert(result.toList == List(""))
        }

        "leading delimiter produces empty first element" in {
            val result = Rollout.splitOn(";a;b", ';')
            assert(result.toList == List("", "a", "b"))
        }

        "consecutive delimiters produce empty elements" in {
            val result = Rollout.splitOn("a;;b", ';')
            assert(result.toList == List("a", "", "b"))
        }
    }

    "Rollout.bucketFor" - {

        "hash distribution is roughly uniform over 100k paths" in {
            val counts = new Array[Int](100)
            val n      = 100000
            for (i <- 0 until n) {
                val bucket = Rollout.bucketFor(s"test/path/$i")
                counts(bucket) += 1
            }
            // Each bucket should get ~1000 hits. Allow 30% deviation.
            val expected = n / 100
            val maxDev   = (expected * 0.3).toInt
            for (b <- 0 until 100) {
                assert(
                    counts(b) >= expected - maxDev && counts(b) <= expected + maxDev,
                    s"bucket $b has ${counts(b)} hits (expected ~$expected +/- $maxDev)"
                )
            }
        }

        "empty path returns 0" in {
            assert(Rollout.bucketFor("") == 0)
        }

        "same path always returns same bucket (deterministic)" in {
            val bucket1 = Rollout.bucketFor("prod/us-east-1/pod42")
            val bucket2 = Rollout.bucketFor("prod/us-east-1/pod42")
            assert(bucket1 == bucket2)
        }

        "bucket is in range 0-99" in {
            for (i <- 0 until 1000) {
                val b = Rollout.bucketFor(s"random/path/$i")
                assert(b >= 0 && b < 100, s"bucket $b out of range for path random/path/$i")
            }
        }

        "bucketFor() is deterministic across many calls" in {
            // Compute buckets for many keys and verify reproducibility
            val results = scala.collection.mutable.HashMap.empty[String, Int]
            for (t <- 0 until 10) {
                for (i <- 0 until 100) {
                    val key    = s"key-$t-$i"
                    val bucket = Rollout.bucketFor(key)
                    results.put(key, bucket): Unit
                }
            }
            // Verify determinism: all results should be reproducible
            results.foreach { case (key, bucket) =>
                assert(Rollout.bucketFor(key) == bucket, s"non-deterministic result for $key")
            }
        }

        "bucketFor() range with 10000 random strings" in {
            for (i <- 0 until 10000) {
                val b = Rollout.bucketFor(s"random-$i-${i * 7}")
                assert(b >= 0 && b < 100, s"bucket $b out of range")
            }
        }

        "bucketFor() stability across calls" in {
            val key    = "stable-key-test"
            val first  = Rollout.bucketFor(key)
            val second = Rollout.bucketFor(key)
            val third  = Rollout.bucketFor(key)
            assert(first == second)
            assert(second == third)
        }

        "bucketFor never returns negative (Int.MinValue hash edge case)" in {
            // MurmurHash3 can return Int.MinValue, which made Math.abs overflow.
            // Test with many keys to increase chance of hitting problematic hashes.
            for (i <- 0 until 100000) {
                val b = Rollout.bucketFor(s"hashtest-$i")
                assert(b >= 0 && b < 100, s"bucket $b out of range for key hashtest-$i")
            }
        }

        "bucketFor returns non-negative for keys with known problematic hashes" in {
            // Directly test that the result is always in [0, 99] for a wide variety of inputs
            val keys = List(
                "",
                "a",
                "test",
                "Int.MinValue",
                "\u0000",
                "x" * 10000,
                scala.util.hashing.MurmurHash3.stringHash("probe").toString
            )
            keys.foreach { key =>
                val b = Rollout.bucketFor(key)
                assert(b >= 0 && b < 100, s"bucket $b out of range for key '$key'")
            }
        }
    }

    "Rollout.select trailing semicolons" - {

        "trailing semicolon is ignored by select" in {
            // "a@staging;" should behave like "a@staging" (no empty terminal)
            assert(Rollout.select("a@staging;", pathOf("prod"), 0) == Right(None))
        }

        "trailing semicolons do not create phantom terminals" in {
            assert(Rollout.select("a@staging;b@dev;", pathOf("prod"), 0) == Right(None))
        }

        "trailing semicolons with matching condition still works" in {
            assert(Rollout.select("a@prod;", pathOf("prod"), 0) == Right(Some("a")))
        }

        "select and parseChoices agree on trailing semicolons" in {
            // Neither should treat trailing semicolons as empty choices
            val selectResult = Rollout.select("a@staging;", pathOf("prod"), 0)
            assert(selectResult == Right(None))
            // parseChoices should also not throw
            val choices = Rollout.parseChoices("a@staging;", failFast = true, flagName = "test.flag")
            assert(choices.entries.length == 1)
        }
    }

    "Rollout.parseChoices" - {

        "parseChoices failFast=true with cumulative weights > 100%" in {
            val ex = intercept[FlagExpressionParseException] {
                Rollout.parseChoices("a@60%;b@60%", failFast = true, flagName = "test.flag")
            }
            assert(ex.getMessage.contains("exceed"))
        }

        "parseChoices failFast=true with single weight > 100%" in {
            val ex = intercept[FlagExpressionParseException] {
                Rollout.parseChoices("a@150%", failFast = true, flagName = "test.flag")
            }
            assert(ex.getMessage.contains("exceed"))
        }

        "parseChoices failFast=false normalizes weights > 100%" in {
            // Should not throw, just normalizes
            val choices = Rollout.parseChoices("a@150%;b@50%", failFast = false, flagName = "test.flag")
            assert(choices.entries.length == 2)
        }

        "parseChoices with trailing semicolon does not throw" in {
            // Trailing semicolon should be ignored, not produce empty choice
            val choices = Rollout.parseChoices("a@prod;", failFast = true, flagName = "test.flag")
            assert(choices.entries.length == 1)
        }

        "parseChoices with empty expression returns empty" in {
            val choices = Rollout.parseChoices("", failFast = true, flagName = "test.flag")
            assert(choices.entries.isEmpty)
        }

        "parseChoices with plain value returns single terminal" in {
            val choices = Rollout.parseChoices("hello", failFast = true, flagName = "test.flag")
            assert(choices.entries.length == 1)
            assert(choices.entries(0).value == "hello")
            assert(choices.entries(0).selector == Rollout.Terminal)
        }
    }
}
