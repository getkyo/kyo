package kyo

/** A static, resolve-once configuration flag.
  *
  * Flags are declared as Scala objects in a package -- the fully-qualified object name becomes the system property key:
  * {{{
  * package myapp.db
  * object poolSize   extends StaticFlag[Int](10)         // -Dmyapp.db.poolSize=20
  * object maxRetries extends StaticFlag[Int](3, n => Right(n max 0)) // -Dmyapp.db.maxRetries=5 (clamped >= 0)
  * }}}
  *
  * ==Choosing between StaticFlag and DynamicFlag==
  *
  * Both types share the same declaration style, [[Rollout]] DSL, typed parsing, and validation. They differ in lifecycle:
  *   - '''StaticFlag''': resolves once at class load into a `val`. Use for infrastructure config that must not change at runtime (pool
  *     sizes, timeouts, codecs, feature kill switches). Just a field read on access.
  *   - '''[[DynamicFlag]]''': evaluates per call with a caller-provided key. Use when the value must vary per request or change without
  *     restart (feature gates, A/B tests, per-tenant rate limits). Supports `update()`/`reload()` for live changes.
  *
  * IMPORTANT: Values are read ONCE at class load. Changes to system properties or rollout expressions after initialization are NOT picked
  * up until the process restarts. Use [[DynamicFlag]] if you need runtime changes.
  *
  * WARNING: Parse or validation errors throw at class load time -- bad config crashes the process before serving traffic.
  *
  * Note: Near-miss detection warns on stderr when a system property has a similar (case-insensitive) name -- catches typos.
  *
  * @tparam A
  *   The configuration value type (must have an implicit [[Flag.Reader]])
  *
  * @see
  *   [[DynamicFlag]] for runtime-mutable, per-entity flags
  * @see
  *   [[Rollout]] for the expression DSL grammar and semantics
  * @see
  *   [[Flag.Reader]] for custom type parsing
  */
abstract class StaticFlag[A](default: A, validate: A => Either[Throwable, A] = (a: A) => Right(a))(implicit reader: Flag.Reader[A])
    extends Flag[A](default, validate) {

    // --- Public API ---

    /** The resolved value, computed once at class load time. Fails fast on parse or validation errors. */
    val value: A = resolveValue()

    // Self-register after all vals are initialized: checks for name clashes, warns on near-miss properties.
    // Must be after `value` to avoid zombie entries if resolveValue() throws.
    Flag.register(this)

    /** Returns the resolved value. Zero overhead — just a field read. */
    def apply(): A = value

    /** Static flags do not support runtime updates. */
    def isDynamic: Boolean = false

    override def toString(): String = s"StaticFlag($name, $value, source=$source)"

    // --- Internal ---

    private def resolveValue(): A = {
        if (initialExpression.isEmpty) validatedDefault
        else {
            val parsed =
                if (isRollout(initialExpression)) {
                    Rollout.select(initialExpression) match {
                        case Right(Some(s)) =>
                            try reader(s) match {
                                    case Right(a) => Some(a)
                                    case Left(e)  => throw FlagValueParseException(name, s, reader.typeName, e)
                                }
                            catch {
                                case e: FlagException => throw e
                                case e: Exception =>
                                    throw FlagValueParseException(name, s, reader.typeName, e)
                            }
                        case Right(None) => None
                        case Left(err) =>
                            throw FlagRolloutParseException(name, initialExpression, err)
                    }
                } else {
                    try reader(initialExpression) match {
                            case Right(a) => Some(a)
                            case Left(e)  => throw FlagValueParseException(name, initialExpression, reader.typeName, e)
                        }
                    catch {
                        case e: FlagException => throw e
                        case e: Exception =>
                            throw FlagValueParseException(name, initialExpression, reader.typeName, e)
                    }
                }
            parsed match {
                case None => validatedDefault
                case Some(raw) =>
                    try validate(raw) match {
                            case Right(a) => a
                            case Left(e) =>
                                throw FlagValidationFailedException(name, String.valueOf(raw), source.toString, e)
                        }
                    catch {
                        case e: FlagException => throw e
                        case e: Throwable =>
                            throw FlagValidationFailedException(name, String.valueOf(raw), source.toString, e)
                    }
            }
        }
    }

}
