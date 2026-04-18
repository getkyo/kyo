package kyo

/** A runtime-mutable, per-entity configuration flag.
  *
  * Flags are declared as Scala objects in a package -- the fully-qualified object name becomes the system property key:
  * {{{
  * package myapp.features
  * object newCheckout extends DynamicFlag[Boolean](false)
  * // -Dmyapp.features.newCheckout="true@premium/50%"
  * // newCheckout(userId, "premium") => true for 50% of premium users
  * }}}
  *
  * ==Choosing between StaticFlag and DynamicFlag==
  *
  * Both types share the same declaration style, [[Rollout]] DSL, typed parsing, and validation. They differ in lifecycle:
  *   - '''[[StaticFlag]]''': resolves once at class load into a `val`. Use for infrastructure config that must not change at runtime (pool
  *     sizes, timeouts, codecs, feature kill switches). Just a field read on access.
  *   - '''DynamicFlag''': evaluates per call with a caller-provided key. Use when the value must vary per request or change without
  *     restart (feature gates, A/B tests, per-tenant rate limits). Supports `update()`/`reload()` for live changes.
  *
  * IMPORTANT: Two-tier validation. At init: strict -- weights > 100% throw. At `update()`: lenient -- weights > 100% are normalized. Init
  * = deploy time (fix and redeploy), update = runtime (normalize and keep serving).
  *
  * WARNING: `apply(key, attrs*)` tracks evaluation counters. `evaluate(key, attrs*)` does NOT. Use `evaluate` in hot loops where counter
  * overhead matters.
  *
  * IMPORTANT: `update()` is all-or-nothing. Parse failure preserves the old expression (safe rollback). New state written via single
  * volatile write -- no torn reads.
  *
  * Note: Eval counters are approximate (volatile var Map, not atomic). Bounded at 100 distinct keys -- overflow counted under "other".
  *
  * @tparam A
  *   The configuration value type (must have an implicit [[Flag.Reader]])
  *
  * @see
  *   [[StaticFlag]] for static, resolve-once flags
  * @see
  *   [[Rollout]] for the expression DSL grammar and bucketing
  * @see
  *   [[FlagAdmin]] for HTTP endpoints to update dynamic flags at runtime
  */
abstract class DynamicFlag[A](default: A, validate: A => Either[Throwable, A] = (a: A) => Right(a))(implicit reader: Flag.Reader[A])
    extends Flag[A](default, validate) {

    import DynamicFlag.*

    // --- Public API ---

    /** Evaluates the flag for the given key and optional attributes, and tracks the evaluation counter.
      *
      * @param key
      *   Entity identifier (userId, tenantId, etc.) used for deterministic bucketing
      * @param attributes
      *   Optional path segments for selector matching (e.g., "premium", "us-east-1")
      * @return
      *   The resolved value
      */
    def apply(key: String, attributes: String*): A = {
        val result = doEvaluate(key, attributes)
        trackEvaluation(result)
        result
    }

    /** Evaluates the flag without tracking counters. For testing and dry-run scenarios.
      *
      * @param key
      *   Entity identifier used for deterministic bucketing
      * @param attributes
      *   Optional path segments for selector matching
      * @return
      *   The resolved value (same as apply() would return)
      */
    def evaluate(key: String, attributes: String*): A = doEvaluate(key, attributes)

    /** The current expression string. */
    def expression: String = state.expression

    /** Updates the flag's expression at runtime.
      *
      * Parses and validates all values first; if any value fails parsing or validation, throws and preserves the old state (safe rollback).
      *
      * @param newExpression
      *   The new rollout expression
      * @throws FlagException
      *   if any value fails Reader parsing or validation
      */
    def update(newExpression: String)(implicit allow: AllowUnsafe): Unit = {
        val newState = buildState(newExpression, failFast = false)
        val prev     = state.expression
        state = newState
        history = HistoryEntry(java.lang.System.currentTimeMillis(), prev, newExpression, source.toString) ::
            history.take(maxHistory - 1)
    }

    /** Re-reads the expression from the original config source (system property or environment variable).
      *
      * @return
      *   `Updated(expr)` if the expression changed, `Unchanged` if it's the same, `NoSource` if no config source exists
      */
    def reload()(implicit allow: AllowUnsafe): Flag.ReloadResult = {
        val expr: Option[String] = source match {
            case Flag.Source.SystemProperty =>
                Option(java.lang.System.getProperty(name))
            case Flag.Source.EnvironmentVariable =>
                Option(java.lang.System.getenv(envName))
            case Flag.Source.Default =>
                None
        }
        expr match {
            case None                             => Flag.ReloadResult.NoSource
            case Some(e) if e == state.expression => Flag.ReloadResult.Unchanged
            case Some(e) =>
                update(e)
                Flag.ReloadResult.Updated(e)
        }
    }

    /** Dynamic flags support runtime updates. */
    def isDynamic: Boolean = true

    override def toString(): String =
        s"DynamicFlag($name, expression=${state.expression}, source=$source)"

    // --- Internal ---

    private[kyo] def evaluationCounts: Map[String, Long] = {
        val m = evalCounts
        if (overflowCount > 0) m + ("other" -> overflowCount)
        else m
    }

    private[kyo] def updateHistory: List[DynamicFlag.HistoryEntry] = history

    // --- Private state ---

    /** Parallel arrays: Rollout.Choices for matching, Array[A] for pre-parsed typed values. */
    private case class State(expression: String, choices: Rollout.Choices, typedValues: IndexedSeq[A])

    @volatile private var state: State = buildState(initialExpression, failFast = true)

    private val maxCounterKeys                          = 100
    @volatile private var evalCounts: Map[String, Long] = Map.empty
    @volatile private var overflowCount: Long           = 0L

    private val maxHistory: Int                                   = 10
    @volatile private var history: List[DynamicFlag.HistoryEntry] = Nil

    // Self-register after all state is initialized: checks for name clashes, warns on near-miss properties.
    // Must be after `state` to avoid zombie entries if parsing throws.
    Flag.register(this)

    // --- Private evaluation engine ---

    /** Hot path: single volatile read, bucket computation, @tailrec index lookup, typed array access. */
    private def doEvaluate(key: String, attributes: Seq[String]): A = {
        val s   = state // single volatile read
        val idx = Rollout.evaluateIndex(s.choices, attributes, Rollout.bucketFor(key))
        if (idx < 0) validatedDefault
        else s.typedValues(idx)
    }

    private def trackEvaluation(value: A): Unit = {
        val key    = value.toString
        val counts = evalCounts // single volatile read
        if (counts.contains(key)) {
            evalCounts = counts.updated(key, counts(key) + 1)
        } else if (counts.size >= maxCounterKeys) {
            overflowCount += 1
        } else {
            evalCounts = counts.updated(key, 1L)
        }
    }

    // --- Private expression parsing ---

    /** Builds a new State by delegating expression parsing to Rollout, then indexing typed values.
      *
      * Value parsing is interleaved with structural parsing via the valueValidator callback, so that value parse errors are raised in
      * choice order (before subsequent structural errors like unreachable-after-terminal).
      */
    private def buildState(expression: String, failFast: Boolean): State = {
        // Accumulate typed values during parsing via the callback
        val typedBuf = new scala.collection.mutable.ArrayBuffer[A](4)

        val validator: (String, Int, Int, String) => Unit = { (rawValue, choiceNum, totalChoices, fullExpr) =>
            val parsed = parseValue(rawValue, choiceNum, totalChoices, fullExpr)
            typedBuf += validateValue(parsed, fullExpr)
        }

        val choices     = Rollout.parseChoices(expression, failFast, name, validator)
        val typedValues = typedBuf.toIndexedSeq
        State(expression, choices, typedValues)
    }

    private def validateValue(value: A, fullExpr: String): A = {
        try validate(value) match {
                case Right(a) => a
                case Left(e) =>
                    throw FlagValidationFailedException(name, String.valueOf(value), s"expression '$fullExpr'", e)
            }
        catch {
            case e: FlagException => throw e
            case e: Throwable =>
                throw FlagValidationFailedException(name, String.valueOf(value), s"expression '$fullExpr'", e)
        }
    }

    private def parseValue(raw: String, choiceNum: Int, total: Int, fullExpr: String): A = {
        try reader(raw) match {
                case Right(a) => a
                case Left(e)  => throw FlagChoiceParseException(name, fullExpr, choiceNum, total, raw, reader.typeName, e)
            }
        catch {
            case e: FlagException => throw e
            case e: Exception =>
                throw FlagChoiceParseException(name, fullExpr, choiceNum, total, raw, reader.typeName, e)
        }
    }

}

object DynamicFlag {

    // --- Internal types ---

    /** Record of a single expression update, for audit trail purposes. */
    private[kyo] case class HistoryEntry(
        timestamp: Long,
        previousExpression: String,
        newExpression: String,
        source: String
    )

}
