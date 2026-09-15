package kyo

import System.Parser
import java.net.MalformedURLException
import java.net.URISyntaxException
import java.time.format.DateTimeParseException
import kyo.internal.HostConfig
import kyo.internal.Reducible
import kyo.internal.SystemPlatformSpecific

/** A platform-independent accessor for system environment and properties.
  *
  * System provides an effect-based API for interacting with the host environment, offering structured access to environment variables,
  * system properties, and platform-specific information.
  *
  * The API follows Kyo's effect model with Sync-wrapped operations and features strongly-typed parsing capabilities through the Parser type
  * class. This allows for safe, composable interactions with the system environment while maintaining proper effect tracking.
  *
  * Key features:
  *
  *   - Type-safe access to environment variables via `env[Type](name)`
  *   - Strongly-typed system property retrieval with `property[Type](name)`
  *   - Platform information including line separators, user names, and OS detection
  *   - Built-in parsers for common types with support for custom parsers
  *   - Customizable through the `let` method for testing and isolation
  *
  * The Parser type class provides automatic conversion from string values to specific types, with built-in support for primitives (Int,
  * Long, Boolean), standard JVM types (URI, URL, UUID), temporal types (LocalDate, LocalTime), and collection types. Users can implement
  * custom parsers for domain-specific types by providing a Parser instance. Parsing failures are tracked in the effect type, ensuring
  * errors are properly propagated and handled.
  *
  * The companion object provides both instance-bound methods requiring a System reference and context-bound methods that use the ambient
  * System instance, similar to other Kyo service patterns. For testing scenarios, the `let` method allows temporarily replacing the default
  * System implementation with custom mocks that provide predefined environment variables and properties.
  *
  * @see
  *   [[kyo.System.Parser]] For extending parsing capabilities to custom types
  * @see
  *   [[kyo.System.OS]] For supported operating system detection values
  */
abstract class System extends Serializable:
    def unsafe: System.Unsafe
    def env[E, A](name: String)(using Parser[E, A], Frame): Maybe[A] < (Abort[E] & Sync)
    def property[E, A](name: String)(using Parser[E, A], Frame): Maybe[A] < (Abort[E] & Sync)
    def lineSeparator(using Frame): String < Sync
    def userName(using Frame): String < Sync
    def operatingSystem(using Frame): System.OS < Sync
    def architecture(using Frame): System.Arch < Sync
    def availableProcessors(using Frame): Int < Sync
end System

/** Companion object for System, containing utility methods and type classes. */
object System:

    /** Enumeration of supported operating systems. */
    enum OS derives CanEqual:
        case Linux, MacOS, Windows, BSD, Solaris, IBMI, AIX, Unknown

    /** Enumeration of supported CPU architectures. */
    enum Arch derives CanEqual:
        case X86, X86_64, Arm, Aarch64, Unknown

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:
        def env(name: String)(using AllowUnsafe): Maybe[String]
        def property(name: String)(using AllowUnsafe): Maybe[String]
        def lineSeparator()(using AllowUnsafe): String
        def userName()(using AllowUnsafe): String
        def operatingSystem()(using AllowUnsafe): OS
        def architecture()(using AllowUnsafe): Arch
        def availableProcessors()(using AllowUnsafe): Int
        def safe: System = System(this)
    end Unsafe

    def apply(u: Unsafe): System =
        new System:
            def env[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & Sync) =
                Sync.Unsafe.defer {
                    u.env(name) match
                        case Absent     => Absent
                        case Present(v) => Abort.get(p(v).map(Maybe(_)))
                }
            def property[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & Sync) =
                Sync.Unsafe.defer {
                    u.property(name) match
                        case Absent     => Absent
                        case Present(v) => Abort.get(p(v).map(Maybe(_)))
                }
            def lineSeparator(using Frame): String < Sync    = Sync.Unsafe.defer(u.lineSeparator())
            def userName(using Frame): String < Sync         = Sync.Unsafe.defer(u.userName())
            def operatingSystem(using Frame): OS < Sync      = Sync.Unsafe.defer(u.operatingSystem())
            def architecture(using Frame): Arch < Sync       = Sync.Unsafe.defer(u.architecture())
            def availableProcessors(using Frame): Int < Sync = Sync.Unsafe.defer(u.availableProcessors())
            def unsafe: Unsafe                               = u

    private val local = Local.init(live)

    /** The default live System implementation.
      *
      * Environment variables and system properties are the process-wide ones flags also read. On the JVM and Native they come from
      * `java.lang.System`. On JS the host answers first (`process.env` on Node, Bun and Deno; properties set with `java.lang.System.setProperty`)
      * and the `globalThis.KYO_CONFIG` seed fills what it leaves unset, which is how a browser page gets any configuration. A read the host
      * refuses, such as Deno's `process.env` without `--allow-env`, is `Absent`.
      *
      * The user name is the `user.name` property where one is set; on JS without it, the OS user on a Node-like host and `""` elsewhere.
      */
    val live: System =
        System(
            new Unsafe:
                def env(name: String)(using AllowUnsafe): Maybe[String] =
                    Maybe(HostConfig.env(name))
                def property(name: String)(using AllowUnsafe): Maybe[String] =
                    Maybe(HostConfig.property(name))
                def lineSeparator()(using AllowUnsafe): String = kyo.internal.Platform.lineSeparator
                def userName()(using AllowUnsafe): String      = SystemPlatformSpecific.userName()
                def operatingSystem()(using AllowUnsafe): OS =
                    import kyo.internal.Platform.Os
                    kyo.internal.Platform.os match
                        case Os.Linux   => OS.Linux
                        case Os.MacOS   => OS.MacOS
                        case Os.Windows => OS.Windows
                        case Os.BSD     => OS.BSD
                        case Os.Solaris => OS.Solaris
                        case Os.IBMI    => OS.IBMI
                        case Os.AIX     => OS.AIX
                        case Os.Unknown => OS.Unknown
                    end match
                end operatingSystem

                def architecture()(using AllowUnsafe): Arch =
                    import kyo.internal.Platform.Arch as PlatformArch
                    kyo.internal.Platform.arch match
                        case PlatformArch.X86     => Arch.X86
                        case PlatformArch.X86_64  => Arch.X86_64
                        case PlatformArch.Arm     => Arch.Arm
                        case PlatformArch.Aarch64 => Arch.Aarch64
                        case PlatformArch.Unknown => Arch.Unknown
                    end match
                end architecture

                def availableProcessors()(using AllowUnsafe): Int = Runtime.getRuntime.availableProcessors()
        )

    /** A System whose environment variables and system properties are exactly `env` and `properties`, whatever the host holds, and whose
      * other members (line separator, user name, operating system, architecture, processors) are [[live]]'s.
      *
      * With [[let]] it gives a computation a fixed configuration on every platform:
      * {{{
      * System.let(System.fromMap(env = Map("APP_ENV" -> "test"))) {
      *     System.env[String]("APP_ENV") // Present("test")
      * }
      * }}}
      * It scopes what `System` answers inside the computation; flags resolve from the process-wide sources and do not see it.
      *
      * @param env
      *   The environment variables, by name.
      * @param properties
      *   The system properties, by name.
      */
    def fromMap(env: Map[String, String] = Map.empty, properties: Map[String, String] = Map.empty): System =
        val envByName      = env
        val propertyByName = properties
        val host           = live.unsafe
        System(
            new Unsafe:
                def env(name: String)(using AllowUnsafe): Maybe[String]      = Maybe.fromOption(envByName.get(name))
                def property(name: String)(using AllowUnsafe): Maybe[String] = Maybe.fromOption(propertyByName.get(name))
                def lineSeparator()(using AllowUnsafe): String               = host.lineSeparator()
                def userName()(using AllowUnsafe): String                    = host.userName()
                def operatingSystem()(using AllowUnsafe): OS                 = host.operatingSystem()
                def architecture()(using AllowUnsafe): Arch                  = host.architecture()
                def availableProcessors()(using AllowUnsafe): Int            = host.availableProcessors()
        )
    end fromMap

    /** Executes a computation with a custom System implementation.
      *
      * @param system
      *   The custom System implementation to use.
      * @param f
      *   The computation to execute.
      * @tparam A
      *   The return type of the computation.
      * @tparam S
      *   The effect type of the computation.
      * @return
      *   The result of the computation.
      */
    def let[A, S](system: System)(f: => A < S)(using Frame): A < S =
        local.let(system)(f)

    /** Retrieves an environment variable.
      *
      * @param name
      *   The name of the environment variable.
      * @tparam E
      *   The error type for parsing.
      * @return
      *   A `Maybe` containing the parsed value if it exists, or `Maybe.empty` otherwise.
      */
    def env[A](using Frame)[E](name: String)(using parser: Parser[E, A], reduce: Reducible[Abort[E]]): Maybe[A] < (reduce.SReduced & Sync) =
        reduce(local.use(_.env[E, A](name)))

    /** Retrieves an environment variable with a default value.
      *
      * @param name
      *   The name of the environment variable.
      * @param default
      *   The default value to use if the variable is not found.
      * @tparam E
      *   The error type for parsing.
      * @return
      *   The parsed value if it exists, or the default value otherwise.
      */
    def env[A](using
        Frame
    )[E](name: String, default: => A)(
        using
        parser: Parser[E, A],
        reduce: Reducible[Abort[E]]
    ): A < (reduce.SReduced & Sync) =
        reduce(local.use(_.env[E, A](name).map(_.getOrElse(default))))

    /** Retrieves a system property.
      *
      * @param name
      *   The name of the system property.
      * @tparam E
      *   The error type for parsing.
      * @return
      *   A `Maybe` containing the parsed value if it exists, or `Maybe.empty` otherwise.
      */
    def property[A](using
        Frame
    )[E](name: String)(
        using
        parser: Parser[E, A],
        reduce: Reducible[Abort[E]]
    ): Maybe[A] < (reduce.SReduced & Sync) =
        reduce(local.use(_.property[E, A](name)))

    /** Retrieves a system property with a default value.
      *
      * @param name
      *   The name of the system property.
      * @param default
      *   The default value to use if the property is not found.
      * @tparam E
      *   The error type for parsing.
      * @return
      *   The parsed value if it exists, or the default value otherwise.
      */
    def property[A](using
        Frame
    )[E](name: String, default: => A)(
        using
        parser: Parser[E, A],
        reduce: Reducible[Abort[E]]
    ): A < (reduce.SReduced & Sync) =
        reduce(local.use(_.property[E, A](name).map(_.getOrElse(default))))

    /** Retrieves the system-dependent line separator string. */
    def lineSeparator(using Frame): String < Sync = local.use(_.lineSeparator)

    /** Retrieves the user name of the current user. */
    def userName(using Frame): String < Sync = local.use(_.userName)

    /** Retrieves the current operating system. */
    def operatingSystem(using Frame): OS < Sync = local.use(_.operatingSystem)

    /** Retrieves the current CPU architecture. */
    def architecture(using Frame): Arch < Sync = local.use(_.architecture)

    /** Retrieves the number of processors available to the JVM. */
    def availableProcessors(using Frame): Int < Sync = local.use(_.availableProcessors)

    /** Abstract class for parsing string values into specific types. */
    sealed abstract class Parser[E, A] extends Serializable:
        /** Parses a string value into type A.
          *
          * @param s
          *   The string to parse.
          * @return
          *   A Result containing either the parsed value or an error.
          */
        def apply(s: String)(using Frame): Result[E, A]
    end Parser

    /** Companion object for Parser, containing default implementations. */
    object Parser:
        def apply[E, A](f: Frame ?=> String => Result[E, A]) =
            new Parser[E, A]:
                def apply(s: String)(using Frame) = f(s)

        given Parser[Nothing, String]                    = Parser(v => Result.succeed(v))
        given Parser[NumberFormatException, Int]         = Parser(v => Result.catching[NumberFormatException](v.toInt))
        given Parser[NumberFormatException, Long]        = Parser(v => Result.catching[NumberFormatException](v.toLong))
        given Parser[NumberFormatException, Float]       = Parser(v => Result.catching[NumberFormatException](v.toFloat))
        given Parser[NumberFormatException, Double]      = Parser(v => Result.catching[NumberFormatException](v.toDouble))
        given Parser[IllegalArgumentException, Boolean]  = Parser(v => Result.catching[IllegalArgumentException](v.toBoolean))
        given Parser[NumberFormatException, Byte]        = Parser(v => Result.catching[NumberFormatException](v.toByte))
        given Parser[NumberFormatException, Short]       = Parser(v => Result.catching[NumberFormatException](v.toShort))
        given Parser[Duration.InvalidDuration, Duration] = Parser(v => Duration.parse(v))

        given Parser[UUID.InvalidUUID, UUID] =
            Parser(v => UUID.parse(v))

        given Parser[IllegalArgumentException, java.util.UUID] =
            Parser(v => Result.catching[IllegalArgumentException](java.util.UUID.fromString(v)))

        given Parser[DateTimeParseException, java.time.LocalDate] =
            Parser(v => Result.catching[DateTimeParseException](java.time.LocalDate.parse(v)))

        given Parser[DateTimeParseException, java.time.LocalTime] =
            Parser(v => Result.catching[DateTimeParseException](java.time.LocalTime.parse(v)))

        given Parser[DateTimeParseException, java.time.LocalDateTime] =
            Parser(v => Result.catching[DateTimeParseException](java.time.LocalDateTime.parse(v)))

        given Parser[URISyntaxException, java.net.URI] =
            Parser(v => Result.catching[URISyntaxException](new java.net.URI(v)))

        // java.net.URL(String) is deprecated since JDK 20. Its URI-based replacement throws
        // URISyntaxException or IllegalArgumentException rather than MalformedURLException, which would
        // change this Parser's failure type; the constructor is deprecated, not removed, so it is kept.
        given Parser[MalformedURLException, java.net.URL] =
            Parser(v => Result.catching[MalformedURLException]((new java.net.URL(v)): @scala.annotation.nowarn("cat=deprecation")))

        given [E, A](using p: Parser[E, A], frame: Frame): Parser[E, Seq[A]] =
            Parser(v => Result.collect(Chunk.from(v.split(",")).map(v => p(v.trim()))))

        given Parser[IllegalArgumentException, Char] =
            Parser { v =>
                if v.length() == 1 then Result.succeed(v(0))
                else Result.fail(new IllegalArgumentException("String must have exactly one character"))
            }

    end Parser

end System
