package kyo

import scala.annotation.implicitNotFound
import scala.jdk.CollectionConverters.*

/** Shared foundation for type-safe configuration flags backed by system properties and environment variables.
  *
  * Flag provides the machinery that both [[StaticFlag]] (static, resolve-once) and [[DynamicFlag]] (per-call, runtime-mutable) share: name
  * derivation from the JVM class path, config source resolution, validation, and self-registration into the global flag registry.
  *
  * Resolution order: system property -> environment variable -> constructor default. The first source found wins.
  *
  * Key capabilities:
  *   - Fully-qualified name derived from the Scala object's JVM class name
  *   - Environment variable name computed automatically (dots -> underscores, uppercased)
  *   - Optional validation function applied to resolved values, with flag-name-enriched error messages
  *   - Near-miss detection warns on stderr when a system property has a similar (case-insensitive) name
  *   - Self-registration into [[Flag]] companion registry for introspection and admin
  *
  * IMPORTANT: Flags MUST be Scala objects (top-level or nested in other objects). Classes, traits, and lambdas produce mangled JVM names
  * and are rejected at construction time with a [[FlagNameException]].
  *
  * IMPORTANT: Values are resolved via system property -> env var -> default. System property takes precedence over environment variable.
  *
  * Note: The env var name is derived automatically: dots become underscores, then uppercased. `myapp.db.poolSize` -> `MYAPP_DB_POOLSIZE`.
  *
  * @tparam A
  *   The configuration value type (must have an implicit [[Flag.Reader]])
  *
  * @see
  *   [[StaticFlag]] for static, resolve-once flags
  * @see
  *   [[DynamicFlag]] for runtime-mutable, per-entity flags
  * @see
  *   [[Rollout]] for the expression DSL used in conditional rollouts
  */
abstract class Flag[A] private[kyo] (val default: A, val validate: A => Either[Throwable, A] = (a: A) => Right(a))(implicit
    val reader: Flag.Reader[A]
) {

    // --- Public API ---

    /** Fully-qualified flag name, derived from the JVM class name. */
    val name: String = {
        val className = getClass.getName.stripSuffix("$")
        // Validate before replacement: check original JVM name for suspicious patterns
        if (className.matches(".*\\$\\d+.*") || className.contains("$anon$") || className.contains("$$Lambda")) {
            throw FlagNameException(
                className.replace('$', '.'),
                "appears to be declared inside a class, trait, or method. " +
                    "Flags must be declared as top-level objects or objects nested in other objects."
            )
        }
        className.replace('$', '.')
    }

    /** Environment variable name: flag name with dots replaced by underscores, uppercased. */
    val envName: String = name.replace('.', '_').toUpperCase

    /** The source that provided the resolved value. */
    val source: Flag.Source =
        if (java.lang.System.getProperty(name) ne null) Flag.Source.SystemProperty
        else if (java.lang.System.getenv(envName) ne null) Flag.Source.EnvironmentVariable
        else Flag.Source.Default

    /** Whether this flag supports runtime updates. */
    def isDynamic: Boolean

    // --- Internal ---

    /** Raw expression string from system property or environment variable. Empty string when source is Default. */
    private[kyo] val initialExpression: String =
        if (source.eq(Flag.Source.SystemProperty)) java.lang.System.getProperty(name)
        else if (source.eq(Flag.Source.EnvironmentVariable)) java.lang.System.getenv(envName)
        else ""

    /** Returns true if the expression contains rollout DSL syntax (@ or ;). */
    private[kyo] def isRollout(expr: String): Boolean =
        expr.indexOf('@') >= 0 || expr.indexOf(';') >= 0

    /** Pre-validated default value, computed once at construction time. */
    private[kyo] val validatedDefault: A =
        try validate(default) match {
                case Right(a) => a
                case Left(e)  => throw FlagValidationFailedException(name, String.valueOf(default), "default", e)
            }
        catch {
            case e: FlagException => throw e
            case e: Throwable     => throw FlagValidationFailedException(name, String.valueOf(default), "default", e)
        }
}

object Flag {

    // --- Nested Types ---

    /** The source from which a flag's value was resolved. */
    sealed abstract class Source
    object Source {
        case object SystemProperty      extends Source
        case object EnvironmentVariable extends Source
        case object Default             extends Source
    }

    /** Result of a [[DynamicFlag.reload]] call. */
    sealed abstract class ReloadResult
    object ReloadResult {
        case class Updated(expression: String) extends ReloadResult
        case object Unchanged                  extends ReloadResult
        case object NoSource                   extends ReloadResult
    }

    /** Type class for parsing flag values from strings.
      *
      * Built-in readers exist for Int, Long, Double, Boolean, String, and List[A].
      */
    @implicitNotFound(
        "No Flag.Reader found for type ${A}. " +
            "Built-in readers exist for Int, Long, Double, Boolean, String, and List[A]. " +
            "For custom types, implement Flag.Reader[${A}]."
    )
    abstract class Reader[A] {

        /** Parse a string value into type A. */
        def apply(s: String): Either[Throwable, A]

        /** Human-readable type name for error messages. */
        def typeName: String
    }

    object Reader {

        /** Marker subclass of [[Reader]] for types whose string representation does not contain commas.
          *
          * Record fields require `Scalar` evidence so that comma-separated `key=value` pairs can be unambiguously parsed. Types like `Seq`,
          * `Chunk`, `Span`, `Dict`, and nested `Record` use commas in their serialized format and therefore must NOT have a `Scalar`
          * instance.
          */
        @implicitNotFound(
            "No Flag.Reader.Scalar instance found for type ${A}. " +
                "Collection elements (Seq, Chunk, Span) and Record/Dict fields must use scalar types " +
                "whose string format does not contain commas. " +
                "Seq, Chunk, Span, Dict, and Record themselves are NOT scalar because they use commas internally. " +
                "Nesting these types (e.g., Chunk[Chunk[Int]], Record with a Seq field) is not supported."
        )
        abstract class Scalar[A] extends Reader[A]

        implicit val int: Scalar[Int] = new Scalar[Int] {
            def apply(s: String): Either[Throwable, Int] =
                try Right(Integer.parseInt(s.trim))
                catch { case e: NumberFormatException => Left(e) }
            def typeName: String = "Int"
        }
        implicit val string: Scalar[String] = new Scalar[String] {
            def apply(s: String): Either[Throwable, String] = Right(s)
            def typeName: String                            = "String"
        }
        implicit val long: Scalar[Long] = new Scalar[Long] {
            def apply(s: String): Either[Throwable, Long] =
                try Right(java.lang.Long.parseLong(s.trim))
                catch { case e: NumberFormatException => Left(e) }
            def typeName: String = "Long"
        }
        implicit val double: Scalar[Double] = new Scalar[Double] {
            def apply(s: String): Either[Throwable, Double] =
                try Right(java.lang.Double.parseDouble(s.trim))
                catch { case e: NumberFormatException => Left(e) }
            def typeName: String = "Double"
        }
        implicit val boolean: Scalar[Boolean] = new Scalar[Boolean] {
            def apply(s: String): Either[Throwable, Boolean] = Right(java.lang.Boolean.parseBoolean(s.trim))
            def typeName: String                             = "Boolean"
        }
        implicit def seq[A](implicit r: Scalar[A]): Reader[Seq[A]] = new Reader[Seq[A]] {
            def apply(s: String): Either[Throwable, Seq[A]] =
                if (s.trim.isEmpty) Right(Seq.empty)
                else {
                    val elements = s.split(",").toSeq.map(_.trim)
                    elements.foldLeft[Either[Throwable, Seq[A]]](Right(Seq.empty)) { (acc, elem) =>
                        acc match {
                            case Left(e) => Left(e)
                            case Right(seq) =>
                                r(elem) match {
                                    case Left(e)  => Left(e)
                                    case Right(a) => Right(seq :+ a)
                                }
                        }
                    }
                }
            def typeName: String = s"Seq[${r.typeName}]"
        }
    }

    // --- Public API ---

    /** Returns all registered flags. */
    def all: List[Flag[?]] = synchronized {
        registry.values.toList
    }

    /** Looks up a registered flag by name. */
    def get(name: String): Option[Flag[?]] = synchronized {
        registry.get(name)
    }

    /** Reads a flag value by name without creating a Flag object.
      *
      * Useful for bootstrapping configuration that Flag itself depends on. Resolution: system property → environment variable (uppercased,
      * dots → underscores) → default. No rollout evaluation.
      */
    def apply[A](name: String, default: A)(implicit reader: Reader[A]): A = {
        val prop = java.lang.System.getProperty(name)
        if (prop ne null) {
            try reader(prop) match {
                    case Right(a) => a
                    case Left(e)  => throw FlagValueParseException(name, prop, reader.typeName, e)
                }
            catch {
                case e: FlagException => throw e
                case e: Throwable     => throw FlagValueParseException(name, prop, reader.typeName, e)
            }
        } else {
            val envName = name.replace('.', '_').toUpperCase
            val env     = java.lang.System.getenv(envName)
            if (env ne null) {
                try reader(env) match {
                        case Right(a) => a
                        case Left(e)  => throw FlagValueParseException(name, env, reader.typeName, e)
                    }
                catch {
                    case e: FlagException => throw e
                    case e: Throwable     => throw FlagValueParseException(name, env, reader.typeName, e)
                }
            } else default
        }
    }

    /** Returns a formatted table string with columns: Name, Type, Value/Expression, Default, Source. */
    def dump(): String = {
        val flags = synchronized { registry.values.toList }.sortBy(_.name)
        if (flags.isEmpty) "(no flags registered)"
        else {
            val nameHeader    = "Name"
            val typeHeader    = "Type"
            val valueHeader   = "Value"
            val defaultHeader = "Default"
            val sourceHeader  = "Source"

            val rows = flags.map { flag =>
                val typ = if (flag.isDynamic) "dynamic" else "static"
                val value = flag match {
                    case f: StaticFlag[?] => String.valueOf(f.value)
                    case _                => "\u2014" // em dash for dynamic flags
                }
                val default = String.valueOf(flag.default)
                val source  = flag.source.toString
                (flag.name, typ, value, default, source)
            }

            val nameW    = math.max(nameHeader.length, rows.map(_._1.length).max)
            val typeW    = math.max(typeHeader.length, rows.map(_._2.length).max)
            val valueW   = math.max(valueHeader.length, rows.map(_._3.length).max)
            val defaultW = math.max(defaultHeader.length, rows.map(_._4.length).max)
            val sourceW  = math.max(sourceHeader.length, rows.map(_._5.length).max)

            def pad(s: String, w: Int): String = s + " " * (w - s.length)
            def line(l: String, m: String, r: String, fill: String): String =
                s"$l${fill * (nameW + 2)}$m${fill * (typeW + 2)}$m${fill * (valueW + 2)}$m${fill * (defaultW + 2)}$m${fill * (sourceW + 2)}$r"

            val sb = new StringBuilder
            sb ++= line("\u250c", "\u252c", "\u2510", "\u2500")
            sb += '\n'
            sb ++= s"\u2502 ${pad(nameHeader, nameW)} \u2502 ${pad(typeHeader, typeW)} \u2502 ${pad(valueHeader, valueW)} \u2502 ${pad(defaultHeader, defaultW)} \u2502 ${pad(sourceHeader, sourceW)} \u2502"
            sb += '\n'
            sb ++= line("\u251c", "\u253c", "\u2524", "\u2500")
            sb += '\n'
            rows.foreach { case (name, typ, value, default, source) =>
                sb ++= s"\u2502 ${pad(name, nameW)} \u2502 ${pad(typ, typeW)} \u2502 ${pad(value, valueW)} \u2502 ${pad(default, defaultW)} \u2502 ${pad(source, sourceW)} \u2502"
                sb += '\n'
            }
            sb ++= line("\u2514", "\u2534", "\u2518", "\u2500")
            sb.toString()
        }
    }

    // --- Internal ---

    private var registry = Map.empty[String, Flag[?]]

    /** Registers a flag. Throws on duplicate name. Warns on near-miss system properties. */
    private[kyo] def register(flag: Flag[?]): Unit = synchronized {
        registry.get(flag.name) match {
            case Some(existing) =>
                throw FlagDuplicateNameException(flag.name, flag.getClass.getName, existing.getClass.getName)
            case None =>
                registry = registry.updated(flag.name, flag)
        }
        detectNearMiss(flag)
    }

    /** Scans system properties for case-insensitive near-misses against the flag's name and envName. */
    private def detectNearMiss(flag: Flag[?]): Unit = {
        if (flag.source == Source.Default) {
            val flagNameLower = flag.name.toLowerCase
            val envNameLower  = flag.envName.toLowerCase

            try {
                val props = java.lang.System.getProperties
                scala.jdk.CollectionConverters.EnumerationHasAsScala(props.propertyNames()).asScala.foreach { elem =>
                    val propName = elem.toString
                    if (propName.toLowerCase == flagNameLower && propName != flag.name) {
                        java.lang.System.err.println(
                            s"[kyo-config] Warning: Flag '${flag.name}' resolved to default \u2014 did you mean system property '$propName'?"
                        )
                    }
                }
            } catch {
                case _: SecurityException => // ignore
            }
            // Also check env vars for near-miss
            try {
                val envMap = java.lang.System.getenv()
                envMap.keySet().asScala.foreach { envKey =>
                    if (envKey.toLowerCase == envNameLower && envKey != flag.envName) {
                        java.lang.System.err.println(
                            s"[kyo-config] Warning: Flag '${flag.name}' resolved to default \u2014 did you mean environment variable '$envKey'?"
                        )
                    }
                }
            } catch {
                case _: SecurityException => // ignore
            }
        }
    }
}
