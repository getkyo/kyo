package kyo

import scala.annotation.tailrec

/** Infrastructure-level gradual rollout via a string DSL.
  *
  * Rollout lets configuration values vary by deployment topology (environment, region, cluster) and percentage sampling. A system property
  * or env var contains a semicolon-separated expression of conditional choices evaluated against the instance's rollout path, configured
  * via `-Dkyo.rollout.path=prod/us-east-1/az1/pod-abc` or env var `KYO_ROLLOUT_PATH`. When unset, Rollout auto-detects from Kubernetes,
  * AWS, or GCP environment variables.
  *
  * Grammar: `expression = choice { ";" choice }`, where `choice = value "@" selector | value` (terminal). Selector = path segments and/or a
  * trailing `N%`. Choices are evaluated left-to-right; first match wins. Terminal (bare value, no `@`) always matches.
  *
  * Key capabilities:
  *   - Path-based selectors with wildcard (`*`) and exact-match segments
  *   - Percentage-based sampling with deterministic, cross-platform bucketing
  *   - Expression validation via `validate()` with structured warnings and errors
  *   - Auto-detection of deployment topology from cloud provider env vars
  *
  * IMPORTANT: Percentages are WEIGHTS, not thresholds. `"a@33%;b@33%"` means a gets 33% and b gets 33% of bucket space. Weights accumulate
  * left-to-right into cumulative thresholds at parse time. Users never do cumulative math.
  *
  * WARNING: Increasing a percentage adds entities; decreasing can REMOVE entities. `50%` -> `75%` adds 25% new entities. `75%` -> `50%`
  * removes 25% existing entities.
  *
  * Note: The bucket is deterministic per key (MurmurHash3). Same key always gets the same bucket. Different flags share the same bucket for
  * a given key, enabling correlated rollouts.
  *
  * @see
  *   [[StaticFlag]] for static flags that use rollout expressions at class load time
  * @see
  *   [[DynamicFlag]] for per-entity flags with runtime rollout updates
  * @see
  *   [[StaticFlag]] and [[DynamicFlag]] for the flag types that use Rollout
  */
object Rollout {

    // --- Public API ---

    /** Computes the bucket for an arbitrary key string. Useful for debugging: "what bucket does key X fall into?"
      *
      * @return
      *   a value in [0, 99], or 0 for empty string
      */
    def bucketFor(key: String): Int =
        if (key.isEmpty) 0
        else Math.floorMod(scala.util.hashing.MurmurHash3.stringHash(key), 100)

    /** Validates a rollout expression without evaluating it.
      *
      * Returns `Right(warnings)` on success (may include warnings), `Left(error)` on hard failure.
      *
      * Checks:
      *   - Empty values before `@`
      *   - Bad percentages (negative, > 100, non-numeric)
      *   - Unreachable choices after a terminal
      *   - Weights summing > 100% (warning)
      *   - Numeric path segments without `%` (warning: "did you mean N%?")
      *   - Empty path segments (double slash)
      */
    def validate(expression: String): Either[String, List[String]] =
        if (expression.isEmpty) Right(Nil)
        else validateNonEmpty(expression)

    // --- Internal types ---

    /** A selector determines whether a choice matches a given path and bucket. */
    sealed private[kyo] trait Selector

    /** Terminal selector: always matches, no path or percentage constraint. */
    private[kyo] case object Terminal extends Selector

    /** Path-based selector with optional percentage constraint.
      *
      * @param segments
      *   path segments to prefix-match (empty array = match all paths)
      * @param cumulativeThreshold
      *   upper bound of the bucket range (exclusive), equals lowerBound + weight
      * @param hasPercentage
      *   true if this selector includes a percentage constraint
      * @param lowerBound
      *   lower bound of the bucket range (inclusive)
      */
    private[kyo] case class PathSelector(
        segments: Array[String],
        cumulativeThreshold: Int,
        hasPercentage: Boolean,
        lowerBound: Int
    ) extends Selector

    /** A single choice in a rollout expression, with the raw string value (not yet parsed to a typed value). */
    private[kyo] case class Choice(value: String, selector: Selector)

    /** Pre-parsed rollout expression: an array of choices ready for evaluation. */
    private[kyo] case class Choices(entries: Array[Choice])

    // --- Internal API ---

    /** The instance's topology path, auto-detected or from `kyo.rollout.path` / `KYO_ROLLOUT_PATH`. */
    private[kyo] val path: Array[String] = {
        val explicit = readProperty("kyo.rollout.path", "KYO_ROLLOUT_PATH")
        val raw      = if (explicit.nonEmpty) explicit else detectPath()
        if (raw.isEmpty) Array.empty[String] else raw.split("/")
    }

    /** Deterministic bucket (0-99) derived from MurmurHash3 of the full rollout path string. */
    private[kyo] val bucket: Int = {
        val explicit = readProperty("kyo.rollout.path", "KYO_ROLLOUT_PATH")
        val raw      = if (explicit.nonEmpty) explicit else detectPath()
        if (raw.isEmpty) 0
        else Math.floorMod(scala.util.hashing.MurmurHash3.stringHash(raw), 100)
    }

    /** Parses a rollout expression and returns the selected value string.
      *
      * Used by StaticFlag for one-shot parse+evaluate.
      *
      * @return
      *   `Right(Some(value))` if a choice matched, `Right(None)` if no choice matched (use flag default), `Left(error)` for parse errors.
      */
    private[kyo] def select(
        expression: String,
        instancePath: Array[String] = path,
        instanceBucket: Int = bucket
    ): Either[String, Option[String]] = {
        if (expression.isEmpty) Right(None)
        else {
            val choices = splitOn(expression, ';')
            @tailrec
            def loop(i: Int, cumulWeight: Int): Either[String, Option[String]] =
                if (i >= choices.length) Right(None)
                else {
                    val choice = choices(i)
                    if (choice.isEmpty) {
                        // Skip empty choices (e.g. from trailing semicolons)
                        loop(i + 1, cumulWeight)
                    } else {
                        val atIdx = choice.indexOf('@')
                        if (atIdx < 0) {
                            // Terminal choice — always matches
                            Right(Some(choice))
                        } else {
                            val value    = choice.substring(0, atIdx)
                            val selector = choice.substring(atIdx + 1)
                            matchSelector(selector, instancePath, instanceBucket, cumulWeight) match {
                                case Right((true, _))       => Right(Some(value))
                                case Right((false, weight)) => loop(i + 1, cumulWeight + weight)
                                case Left(error)            => Left(error)
                            }
                        }
                    }
                }
            loop(0, 0)
        }
    }

    /** Parses a rollout expression into pre-parsed Choices for efficient repeated evaluation.
      *
      * Used by DynamicFlag at init/update time so the hot path does no string parsing.
      *
      * @param expression
      *   the rollout expression string
      * @param failFast
      *   if true, throws on weights > 100% (init time); if false, normalizes (update time)
      * @param flagName
      *   flag name for error messages
      * @param valueValidator
      *   optional callback invoked for each choice's raw value string during parsing. Called in order so that value parse errors are raised
      *   before structural errors (e.g. unreachable-after-terminal). The callback receives (rawValue, choiceNum, totalChoices,
      *   fullExpression). May throw to report value parse errors.
      * @return
      *   pre-parsed Choices
      * @throws FlagExpressionParseException
      *   on structural parse errors
      */
    private[kyo] def parseChoices(
        expression: String,
        failFast: Boolean,
        flagName: String,
        valueValidator: (String, Int, Int, String) => Unit = (_, _, _, _) => ()
    ): Choices = {
        val expr = expression.trim
        if (expr.isEmpty) Choices(Array.empty[Choice])
        else if (expr.indexOf('@') < 0 && expr.indexOf(';') < 0) {
            // Plain value — single terminal choice
            valueValidator(expr, 1, 1, expression)
            Choices(Array(Choice(expr, Terminal)))
        } else {
            parseRolloutChoices(expr, expression, failFast, flagName, valueValidator)
        }
    }

    /** Evaluates pre-parsed Choices against a path and bucket, returning the index of the matched choice.
      *
      * Hot path: @tailrec loop over pre-parsed choices, no string parsing.
      *
      * @param choices
      *   pre-parsed choices from parseChoices
      * @param targetPath
      *   path segments to match against (attributes for DynamicFlag, instance path for StaticFlag)
      * @param bucket
      *   deterministic bucket (0-99)
      * @return
      *   index of matched choice, or -1 if no choice matched
      */
    private[kyo] def evaluateIndex(choices: Choices, targetPath: Seq[String], bucket: Int): Int = {
        val entries = choices.entries
        @tailrec
        def loop(i: Int): Int =
            if (i >= entries.length) -1
            else {
                val choice = entries(i)
                choice.selector match {
                    case Terminal => i
                    case ps: PathSelector =>
                        if (matchesPath(ps.segments, targetPath) && matchesPercent(ps, bucket))
                            i
                        else
                            loop(i + 1)
                }
            }
        loop(0)
    }

    // --- Private helpers ---

    /** Checks whether selector path segments prefix-match the target path. */
    private def matchesPath(selectorSegments: Array[String], targetPath: Seq[String]): Boolean = {
        if (selectorSegments.length == 0) true
        else if (selectorSegments.length > targetPath.length) false
        else {
            @tailrec
            def loop(j: Int): Boolean =
                if (j >= selectorSegments.length) true
                else {
                    val seg = selectorSegments(j)
                    if (seg != "*" && seg != targetPath(j)) false
                    else loop(j + 1)
                }
            loop(0)
        }
    }

    /** Checks whether a bucket falls within the selector's percentage range. */
    private def matchesPercent(ps: PathSelector, bucket: Int): Boolean =
        if (!ps.hasPercentage) true
        else bucket >= ps.lowerBound && bucket < ps.cumulativeThreshold

    /** Parses a rollout expression with selectors into pre-parsed Choices. */
    private def parseRolloutChoices(
        expr: String,
        expression: String,
        failFast: Boolean,
        flagName: String,
        valueValidator: (String, Int, Int, String) => Unit
    ): Choices = {
        val rawSplit = splitOn(expr, ';')
        // Strip trailing empty elements (from trailing semicolons)
        val rawChoices = if (rawSplit.length > 0 && rawSplit(rawSplit.length - 1).trim.isEmpty) rawSplit.dropRight(1) else rawSplit
        val total      = rawChoices.length
        val result     = new Array[Choice](total)

        // First pass: compute total raw weight (for normalization at update time)
        val totalRawWeight =
            if (failFast) 0
            else rawChoices.foldLeft(0) { (acc, raw) =>
                val trimmed = raw.trim
                val atIdx   = trimmed.indexOf('@')
                if (atIdx >= 0) {
                    val rawSelector = trimmed.substring(atIdx + 1)
                    val parts       = splitOn(rawSelector, '/')
                    if (parts.length > 0) {
                        val lastPart = parts(parts.length - 1)
                        if (lastPart.endsWith("%")) {
                            val digits = lastPart.substring(0, lastPart.length - 1)
                            try {
                                val n = Integer.parseInt(digits)
                                if (n > 0) acc + n else acc
                            } catch { case _: NumberFormatException => acc }
                        } else acc
                    } else acc
                } else acc
            }

        val needsNormalization = !failFast && totalRawWeight > 100

        // Main pass: parse each choice, accumulating (cumulWeight, foundTerminal)
        val (_, _) = rawChoices.zipWithIndex.foldLeft((0, false)) { case ((cumulWeight, foundTerminal), (raw, i)) =>
            val trimmed   = raw.trim
            val choiceNum = i + 1

            if (trimmed.isEmpty) {
                throw FlagExpressionParseException(
                    flagName,
                    expression,
                    choiceNum,
                    s"empty choice $choiceNum of $total"
                )
            }

            if (foundTerminal) {
                if (failFast) {
                    throw FlagExpressionParseException(
                        flagName,
                        expression,
                        choiceNum,
                        s"unreachable choice $choiceNum of $total after terminal"
                    )
                } else {
                    java.lang.System.err.println(
                        s"[kyo-config] Warning: DynamicFlag '$flagName': unreachable choice '$trimmed' after terminal in expression '$expression'"
                    )
                    // Still need to parse to validate structure
                    val atIdx = trimmed.indexOf('@')
                    if (atIdx < 0) {
                        valueValidator(trimmed, choiceNum, total, expression)
                        result(i) = Choice(trimmed, Terminal)
                    } else {
                        val rawValue    = trimmed.substring(0, atIdx)
                        val rawSelector = trimmed.substring(atIdx + 1)
                        validateSelectorSyntax(rawSelector, choiceNum, total, expression, flagName)
                        valueValidator(rawValue, choiceNum, total, expression)
                        val selector = parseSelectorFromRaw(
                            rawSelector,
                            0,
                            0,
                            needsNormalization = false,
                            totalRawWeight,
                            failFast,
                            flagName,
                            expression,
                            choiceNum,
                            total
                        )
                        result(i) = Choice(rawValue, selector)
                    }
                    (cumulWeight, true)
                }
            } else {
                val atIdx = trimmed.indexOf('@')
                if (atIdx < 0) {
                    // Terminal
                    if (valueValidator ne null) valueValidator(trimmed, choiceNum, total, expression)
                    result(i) = Choice(trimmed, Terminal)
                    (cumulWeight, true)
                } else {
                    val rawValue = trimmed.substring(0, atIdx)
                    if (rawValue.isEmpty) {
                        throw FlagExpressionParseException(
                            flagName,
                            expression,
                            choiceNum,
                            s"empty value before '@' in choice $choiceNum of $total"
                        )
                    }

                    val rawSelector = trimmed.substring(atIdx + 1)
                    validateSelectorSyntax(rawSelector, choiceNum, total, expression, flagName)
                    if (valueValidator ne null) valueValidator(rawValue, choiceNum, total, expression)

                    // Parse selector: extract path segments and optional percentage
                    val parts      = splitOn(rawSelector, '/')
                    val lastPart   = parts(parts.length - 1)
                    val hasPercent = lastPart.endsWith("%")

                    val pathParts = if (hasPercent) {
                        val pp = new Array[String](parts.length - 1)
                        java.lang.System.arraycopy(parts, 0, pp, 0, parts.length - 1)
                        pp
                    } else parts

                    if (hasPercent) {
                        val digits = lastPart.substring(0, lastPart.length - 1)
                        val rawPct = Integer.parseInt(digits) // already validated by validateSelectorSyntax
                        val effectivePct =
                            if (needsNormalization) {
                                val normalized = Math.round(rawPct.toDouble * 100.0 / totalRawWeight.toDouble).toInt
                                java.lang.System.err.println(
                                    s"[kyo-config] Warning: DynamicFlag '$flagName': weights sum to $totalRawWeight% (> 100%), " +
                                        s"normalizing $rawPct% to $normalized%"
                                )
                                normalized
                            } else if (rawPct > 100) {
                                if (failFast) {
                                    throw FlagExpressionParseException(
                                        flagName,
                                        expression,
                                        choiceNum,
                                        s"percentage exceeds 100%: $rawPct% in choice $choiceNum of $total"
                                    )
                                } else {
                                    java.lang.System.err.println(
                                        s"[kyo-config] Warning: DynamicFlag '$flagName': percentage $rawPct% exceeds 100%, clamping to ${100 - cumulWeight}%"
                                    )
                                    100 - cumulWeight
                                }
                            } else {
                                rawPct
                            }
                        val lowerBound     = cumulWeight
                        val newCumulWeight = cumulWeight + effectivePct
                        if (failFast && newCumulWeight > 100) {
                            throw FlagExpressionParseException(
                                flagName,
                                expression,
                                choiceNum,
                                s"cumulative weights exceed 100% ($newCumulWeight%) at choice $choiceNum of $total"
                            )
                        }
                        result(i) = Choice(rawValue, PathSelector(pathParts, newCumulWeight, hasPercentage = true, lowerBound = lowerBound))
                        (newCumulWeight, false)
                    } else {
                        // No percentage — path-only selector
                        // Warn on numeric path segments
                        pathParts.foreach { seg =>
                            if (seg.nonEmpty && seg != "*") {
                                try {
                                    Integer.parseInt(seg)
                                    java.lang.System.err.println(
                                        s"[kyo-config] Warning: DynamicFlag '$flagName': numeric path segment '$seg' \u2014 did you mean '$seg%'?"
                                    )
                                } catch { case _: NumberFormatException => () }
                            }
                        }
                        result(i) = Choice(rawValue, PathSelector(pathParts, cumulWeight, hasPercentage = false, lowerBound = cumulWeight))
                        (cumulWeight, false)
                    }
                }
            }
        }

        Choices(result)
    }

    /** Validates selector syntax, throwing on structural errors. */
    private def validateSelectorSyntax(sel: String, choiceNum: Int, total: Int, fullExpr: String, flagName: String): Unit = {
        if (sel.isEmpty) {
            throw FlagExpressionParseException(
                flagName,
                fullExpr,
                choiceNum,
                s"empty selector in choice $choiceNum of $total"
            )
        }
        val parts = splitOn(sel, '/')
        parts.zipWithIndex.foreach { case (part, idx) =>
            if (part.trim.isEmpty) {
                throw FlagExpressionParseException(
                    flagName,
                    fullExpr,
                    choiceNum,
                    s"empty path segment at position ${idx + 1} in selector '$sel' (choice $choiceNum of $total)"
                )
            }
            // Validate percentage syntax if this is the last part and ends with %
            if (idx == parts.length - 1 && part.endsWith("%")) {
                val digits = part.substring(0, part.length - 1)
                try {
                    val n = Integer.parseInt(digits)
                    if (n < 0) {
                        throw FlagExpressionParseException(
                            flagName,
                            fullExpr,
                            choiceNum,
                            s"negative percentage: $n% in choice $choiceNum of $total"
                        )
                    }
                } catch {
                    case _: NumberFormatException =>
                        throw FlagExpressionParseException(
                            flagName,
                            fullExpr,
                            choiceNum,
                            s"invalid percentage: '$part' in choice $choiceNum of $total"
                        )
                }
            }
        }
    }

    /** Parses a raw selector string into a PathSelector. Used only for unreachable choices after terminal. */
    private def parseSelectorFromRaw(
        rawSelector: String,
        cumulWeight: Int,
        lowerBound: Int,
        needsNormalization: Boolean,
        totalRawWeight: Int,
        failFast: Boolean,
        flagName: String,
        expression: String,
        choiceNum: Int,
        total: Int
    ): PathSelector = {
        val parts      = splitOn(rawSelector, '/')
        val lastPart   = parts(parts.length - 1)
        val hasPercent = lastPart.endsWith("%")
        val pathParts = if (hasPercent) {
            val pp = new Array[String](parts.length - 1)
            java.lang.System.arraycopy(parts, 0, pp, 0, parts.length - 1)
            pp
        } else parts
        PathSelector(pathParts, cumulWeight, hasPercentage = hasPercent, lowerBound = lowerBound)
    }

    /** Validates a non-empty rollout expression. Extracted to avoid return statements in the public validate method. */
    private def validateNonEmpty(expression: String): Either[String, List[String]] = {
        val rawChoices = splitOn(expression, ';')
        // Strip trailing empty elements (from trailing semicolons)
        val choices = if (rawChoices.length > 0 && rawChoices(rawChoices.length - 1).isEmpty) rawChoices.dropRight(1) else rawChoices

        // Accumulator: (warnings, cumulativeWeight, foundTerminal, errorOrNull)
        val (warnings, cumulWeight, _, error) =
            choices.foldLeft((List.empty[String], 0, false, null: String)) {
                case (acc @ (_, _, _, err), _) if err ne null =>
                    // Short-circuit: error already found
                    acc
                case ((ws, cw, true, _), choice) =>
                    // Already past a terminal — everything is unreachable
                    (s"unreachable choice after terminal: '$choice'" :: ws, cw, true, null)
                case ((ws, cw, false, _), choice) =>
                    val atIdx = choice.indexOf('@')
                    if (atIdx < 0) {
                        // Terminal
                        (ws, cw, true, null)
                    } else {
                        val value    = choice.substring(0, atIdx)
                        val selector = choice.substring(atIdx + 1)

                        if (value.isEmpty)
                            (ws, cw, false, s"empty value before '@' in choice: '$choice'")
                        else if (selector.isEmpty)
                            (ws, cw, false, s"empty selector in choice: '$choice'")
                        else {
                            val parts = splitOn(selector, '/')

                            // Check for empty path segments (double slash)
                            val emptySegment = parts.exists(_.isEmpty)
                            if (emptySegment)
                                (ws, cw, false, s"empty path segment in selector: '$selector'")
                            else {
                                val lastPart   = parts(parts.length - 1)
                                val hasPercent = lastPart.endsWith("%")
                                val pathParts  = if (hasPercent) parts.dropRight(1) else parts

                                // Process percentage
                                val (pctWarnings, newCw, pctError) =
                                    if (hasPercent) {
                                        val digits = lastPart.substring(0, lastPart.length - 1)
                                        try {
                                            val n = Integer.parseInt(digits)
                                            if (n < 0)
                                                (Nil, cw, s"negative percentage: $n%")
                                            else {
                                                val pw = if (n > 100) List(s"percentage exceeds 100%: $n%") else Nil
                                                (pw, cw + n, null)
                                            }
                                        } catch {
                                            case _: NumberFormatException =>
                                                (Nil, cw, s"invalid percentage: $lastPart")
                                        }
                                    } else {
                                        (Nil, cw, null)
                                    }

                                if (pctError ne null)
                                    (ws, cw, false, pctError)
                                else {
                                    // Check for numeric path segments (warn "did you mean N%?")
                                    val numericWarnings = pathParts.iterator
                                        .filter(seg => seg.nonEmpty && seg != "*")
                                        .flatMap { seg =>
                                            try {
                                                Integer.parseInt(seg)
                                                Some(s"numeric path segment '$seg' \u2014 did you mean '$seg%'?")
                                            } catch {
                                                case _: NumberFormatException => None
                                            }
                                        }.toList

                                    (numericWarnings.reverse ++ pctWarnings.reverse ++ ws, newCw, false, null)
                                }
                            }
                        }
                    }
            }

        if (error ne null) Left(error)
        else {
            val finalWarnings =
                if (cumulWeight > 100) s"weights sum to $cumulWeight%, exceeds 100%" :: warnings
                else warnings
            Right(finalWarnings.reverse)
        }
    }

    /** Matches a selector against the instance path and bucket, with cumulative weight tracking.
      *
      * Selector format: `segment/segment/.../N%` where the trailing `N%` is optional.
      *
      * Used by select() for one-shot StaticFlag evaluation.
      *
      * @return
      *   `Right((matched, weight))` where weight is the percentage weight of this choice (0 if no percentage), or `Left(error)`.
      */
    private def matchSelector(
        selector: String,
        instancePath: Array[String],
        instanceBucket: Int,
        cumulWeight: Int
    ): Either[String, (Boolean, Int)] = {
        val parts = splitOn(selector, '/')
        if (parts.isEmpty) Left("empty selector")
        else {
            // Check if the last part is a percentage
            val lastPart   = parts(parts.length - 1)
            val hasPercent = lastPart.endsWith("%")

            val pathParts  = if (hasPercent) parts.dropRight(1) else parts
            val percentage = if (hasPercent) parsePercentage(lastPart) else Right(-1)

            percentage match {
                case Left(error) => Left(error)
                case Right(pct) =>
                    val weight = if (pct >= 0) pct else 0

                    // If there are no path segments (just a percentage), path always matches
                    if (pathParts.isEmpty) {
                        if (pct >= 0) {
                            val inRange = instanceBucket >= cumulWeight && instanceBucket < cumulWeight + pct
                            Right((inRange, weight))
                        } else {
                            Left("empty selector")
                        }
                    } else {
                        // Check that instance path is long enough
                        if (instancePath.length < pathParts.length) Right((false, weight))
                        else {
                            // Prefix match
                            @tailrec
                            def prefixMatch(j: Int): Boolean =
                                if (j >= pathParts.length) true
                                else {
                                    val seg = pathParts(j)
                                    if (seg != "*" && seg != instancePath(j)) false
                                    else prefixMatch(j + 1)
                                }

                            if (!prefixMatch(0)) Right((false, weight))
                            else if (pct >= 0) {
                                val inRange = instanceBucket >= cumulWeight && instanceBucket < cumulWeight + pct
                                Right((inRange, weight))
                            } else {
                                Right((true, weight))
                            }
                        }
                    }
            }
        }
    }

    private def parsePercentage(s: String): Either[String, Int] = {
        val digits = s.substring(0, s.length - 1)
        try {
            val n = Integer.parseInt(digits)
            if (n < 0) Left(s"percentage out of range: $n")
            else Right(n)
        } catch {
            case _: NumberFormatException => Left(s"invalid percentage: $s")
        }
    }

    /** Splits a string on a delimiter character. Produces empty strings for consecutive and trailing delimiters. */
    private[kyo] def splitOn(s: String, delim: Char): Array[String] = {
        val buf = new scala.collection.mutable.ArrayBuffer[String](4)
        @tailrec
        def loop(start: Int, i: Int): Unit =
            if (i >= s.length) {
                if (start <= s.length) buf += s.substring(start)
            } else if (s.charAt(i) == delim) {
                buf += s.substring(start, i)
                loop(i + 1, i + 1)
            } else {
                loop(start, i + 1)
            }
        loop(0, 0)
        buf.toArray
    }

    /** Reads a system property or environment variable, returning empty string if neither is set. */
    private def readProperty(sysProp: String, envVar: String): String = {
        val prop = java.lang.System.getProperty(sysProp)
        if (prop != null) prop
        else {
            val env =
                try java.lang.System.getenv(envVar)
                catch { case _: SecurityException => null }
            if (env != null) env
            else ""
        }
    }

    /** Auto-detects a rollout path from cloud provider environment variables.
      *
      * Detection order: Kubernetes > AWS > GCP > Generic fallback. Each segment is only included if its env var is present. Missing
      * segments are skipped.
      *
      * Kubernetes: triggered by KUBERNETES_SERVICE_HOST. Segments from POD_NAMESPACE (or KUBE_NAMESPACE), NODE_NAME, HOSTNAME. AWS:
      * triggered by AWS_REGION. Segments from AWS_REGION, ECS_CLUSTER, HOSTNAME. GCP: triggered by GOOGLE_CLOUD_PROJECT. Segments from
      * GOOGLE_CLOUD_PROJECT, GOOGLE_CLOUD_REGION, K_SERVICE, HOSTNAME. Generic: segments from ENV (or ENVIRONMENT), REGION, HOSTNAME.
      */
    private def detectPath(): String = {
        val segments = new scala.collection.mutable.ArrayBuffer[String]()

        if (env("KUBERNETES_SERVICE_HOST").nonEmpty) {
            // Kubernetes — region/AZ not available via env vars;
            // use Downward API injections (POD_NAMESPACE, NODE_NAME) and HOSTNAME
            addIfPresent(segments, "POD_NAMESPACE", "KUBE_NAMESPACE")
            addIfPresent(segments, "NODE_NAME")
            addIfPresent(segments, "HOSTNAME")
        } else if (env("AWS_REGION").nonEmpty) {
            // AWS
            segments += env("AWS_REGION")
            addIfPresent(segments, "ECS_CLUSTER")
            addIfPresent(segments, "HOSTNAME")
        } else if (env("GOOGLE_CLOUD_PROJECT").nonEmpty) {
            // GCP
            segments += env("GOOGLE_CLOUD_PROJECT")
            addIfPresent(segments, "GOOGLE_CLOUD_REGION")
            addIfPresent(segments, "K_SERVICE")
            addIfPresent(segments, "HOSTNAME")
        } else {
            // Generic fallback
            addIfPresent(segments, "ENV", "ENVIRONMENT")
            addIfPresent(segments, "REGION")
            addIfPresent(segments, "HOSTNAME")
        }

        segments.mkString("/")
    }

    private def env(name: String): String = {
        val v =
            try java.lang.System.getenv(name)
            catch { case _: SecurityException => null }
        if (v eq null) "" else v
    }

    private def addIfPresent(buf: scala.collection.mutable.ArrayBuffer[String], names: String*): Unit =
        names.find(n => env(n).nonEmpty).foreach(n => buf += env(n))
}
