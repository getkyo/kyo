package kyo

import System.Parser
import java.lang.System as JSystem
import kyo.kernel.Reducible

/** Represents a system environment with various operations.
  *
  * This abstract class provides methods to interact with system properties, environment variables, and other system-related information.
  */
abstract class System:
    def env[E, A](name: String)(using Parser[E, A], Frame): Maybe[A] < (Abort[E] & IO)
    def property[E, A](name: String)(using Parser[E, A], Frame): Maybe[A] < (Abort[E] & IO)
    def lineSeparator(using Frame): String < IO
    def userName(using Frame): String < IO
    def operatingSystem(using Frame): System.OS < IO
end System

/** Companion object for System, containing utility methods and type classes. */
object System:

    /** Enumeration of supported operating systems. */
    enum OS derives CanEqual:
        case Linux, MacOS, Windows, BSD, Solaris, IBMI, AIX, Unknown

    private val local = Local.init(live)

    /** The default live System implementation. */
    val live: System =
        new System:
            def env[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & IO) =
                IO {
                    val value = JSystem.getenv(name)
                    if value == null then Maybe.empty
                    else Abort.get(p(value).map(Maybe(_)))
                }

            def property[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & IO) =
                IO {
                    val value = JSystem.getProperty(name)
                    if value == null then Maybe.empty
                    else Abort.get(p(value).map(Maybe(_)))
                }

            def lineSeparator(using Frame): String < IO = IO(JSystem.lineSeparator())

            def userName(using Frame): String < IO = IO(JSystem.getProperty("user.name"))

            def operatingSystem(using Frame): OS < IO =
                IO {
                    Maybe(JSystem.getProperty("os.name")).map { prop =>
                        val osName = prop.toLowerCase
                        if osName.contains("linux") then OS.Linux
                        else if osName.contains("mac") then OS.MacOS
                        else if osName.contains("windows") then OS.Windows
                        else if osName.contains("bsd") then OS.BSD
                        else if osName.contains("sunos") then OS.Solaris
                        else if osName.contains("os/400") || osName.contains("os400") then OS.IBMI
                        else if osName.contains("aix") then OS.AIX
                        else OS.Unknown
                        end if
                    }.getOrElse(OS.Unknown)
                }

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

    class EnvOps[A](dummy: Unit) extends AnyVal:
        /** Retrieves an environment variable.
          *
          * @param name
          *   The name of the environment variable.
          * @tparam E
          *   The error type for parsing.
          * @return
          *   A `Maybe` containing the parsed value if it exists, or `Maybe.empty` otherwise.
          */
        def apply[E](name: String)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): Maybe[A] < (reduce.SReduced & IO) =
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
        def apply[E](name: String, default: => A)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): A < (reduce.SReduced & IO) =
            reduce(local.use(_.env[E, A](name).map(_.getOrElse(default))))

    end EnvOps

    def env[A]: EnvOps[A] = EnvOps(())

    class PropertyOps[A](dummy: Unit) extends AnyVal:

        /** Retrieves a system property.
          *
          * @param name
          *   The name of the system property.
          * @tparam E
          *   The error type for parsing.
          * @return
          *   A `Maybe` containing the parsed value if it exists, or `Maybe.empty` otherwise.
          */
        def apply[E](name: String)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): Maybe[A] < (reduce.SReduced & IO) =
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
        def apply[E](name: String, default: => A)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): A < (reduce.SReduced & IO) =
            reduce(local.use(_.property[E, A](name).map(_.getOrElse(default))))
    end PropertyOps

    def property[A]: PropertyOps[A] = PropertyOps(())

    /** Retrieves the system-dependent line separator string. */
    def lineSeparator(using Frame): String < IO = local.use(_.lineSeparator)

    /** Retrieves the user name of the current user. */
    def userName(using Frame): String < IO = local.use(_.userName)

    /** Retrieves the current operating system. */
    def operatingSystem(using Frame): OS < IO = local.use(_.operatingSystem)

    /** Abstract class for parsing string values into specific types. */
    abstract class Parser[E, A]:
        /** Parses a string value into type A.
          *
          * @param s
          *   The string to parse.
          * @return
          *   A Result containing either the parsed value or an error.
          */
        def apply(s: String): Result[E, A]
    end Parser

    /** Companion object for Parser, containing default implementations. */
    object Parser:
        given Parser[Nothing, String]                    = v => Result.succeed(v)
        given Parser[NumberFormatException, Int]         = v => Result.catching(v.toInt)
        given Parser[NumberFormatException, Long]        = v => Result.catching(v.toLong)
        given Parser[NumberFormatException, Float]       = v => Result.catching(v.toFloat)
        given Parser[NumberFormatException, Double]      = v => Result.catching(v.toDouble)
        given Parser[IllegalArgumentException, Boolean]  = v => Result.catching(v.toBoolean)
        given Parser[NumberFormatException, Byte]        = v => Result.catching(v.toByte)
        given Parser[NumberFormatException, Short]       = v => Result.catching(v.toShort)
        given Parser[Duration.InvalidDuration, Duration] = v => Duration.parse(v)

        given Parser[IllegalArgumentException, java.util.UUID] =
            v => Result.catching(java.util.UUID.fromString(v))

        given Parser[java.time.format.DateTimeParseException, java.time.LocalDate] =
            v => Result.catching(java.time.LocalDate.parse(v))

        given Parser[java.time.format.DateTimeParseException, java.time.LocalTime] =
            v => Result.catching(java.time.LocalTime.parse(v))

        given Parser[java.time.format.DateTimeParseException, java.time.LocalDateTime] =
            v => Result.catching(java.time.LocalDateTime.parse(v))

        given Parser[java.net.URISyntaxException, java.net.URI] =
            v => Result.catching(new java.net.URI(v))

        given Parser[java.net.MalformedURLException, java.net.URL] =
            v => Result.catching(new java.net.URL(v))

        given [E, A](using p: Parser[E, A], frame: Frame): Parser[E, Seq[A]] =
            v => Result.collect(Chunk.from(v.split(",")).map(v => p(v.trim())))

        given Parser[IllegalArgumentException, Char] =
            v =>
                if v.length() == 1 then Result.succeed(v(0))
                else Result.error(new IllegalArgumentException("String must have exactly one character"))

    end Parser

end System
