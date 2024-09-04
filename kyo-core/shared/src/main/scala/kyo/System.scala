package kyo

import System.Parser
import java.lang.System as JSystem
import kyo.kernel.Reducible

abstract class System:
    def env[E, A](name: String)(using Parser[E, A], Frame): Maybe[A] < (Abort[E] & IO)
    def property[E, A](name: String)(using Parser[E, A], Frame): Maybe[A] < (Abort[E] & IO)
    def lineSeparator(using Frame): String < IO
    def userName(using Frame): String < IO
    def operatingSystem(using Frame): System.OS < IO

end System

object System:

    enum OS derives CanEqual:
        case Linux, MacOS, Windows, BSD, Solaris, IBMI, AIX, Unknown

    val local = Local.init(live)

    val live: System =
        new System:
            def env[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & IO) =
                IO {
                    val value = JSystem.getenv(name)
                    if value == null then Maybe.empty
                    else p(value).map(Maybe(_))
                }

            def property[E, A](name: String)(using p: Parser[E, A], frame: Frame): Maybe[A] < (Abort[E] & IO) =
                IO {
                    val value = JSystem.getProperty(name)
                    if value == null then Maybe.empty
                    else p(value).map(Maybe(_))
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

    def let[A, S](system: System)(f: => A < S)(using Frame): A < S =
        local.let(system)(f)

    class EnvOps[A](dummy: Unit) extends AnyVal:

        def apply[E](name: String)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): Maybe[A] < (reduce.SReduced & IO) =
            reduce(local.use(_.env[E, A](name)))

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
        def apply[E](name: String)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): Maybe[A] < (reduce.SReduced & IO) =
            reduce(local.use(_.property[E, A](name)))

        def apply[E](name: String, default: => A)(
            using
            parser: Parser[E, A],
            frame: Frame,
            reduce: Reducible[Abort[E]]
        ): A < (reduce.SReduced & IO) =
            reduce(local.use(_.property[E, A](name).map(_.getOrElse(default))))
    end PropertyOps

    def property[A]: PropertyOps[A] = PropertyOps(())

    def lineSeparator(using Frame): String < IO = local.use(_.lineSeparator)
    def userName(using Frame): String < IO      = local.use(_.userName)
    def operatingSystem(using Frame): OS < IO   = local.use(_.operatingSystem)

    abstract class Parser[E, A]:
        def apply(s: String): A < Abort[E]

    object Parser:
        given (using Frame): Parser[Nothing, String]                    = v => v
        given (using Frame): Parser[NumberFormatException, Int]         = v => Abort.catching(v.toInt)
        given (using Frame): Parser[NumberFormatException, Long]        = v => Abort.catching(v.toLong)
        given (using Frame): Parser[NumberFormatException, Float]       = v => Abort.catching(v.toFloat)
        given (using Frame): Parser[NumberFormatException, Double]      = v => Abort.catching(v.toDouble)
        given (using Frame): Parser[IllegalArgumentException, Boolean]  = v => Abort.catching(v.toBoolean)
        given (using Frame): Parser[NumberFormatException, Byte]        = v => Abort.catching(v.toByte)
        given (using Frame): Parser[NumberFormatException, Short]       = v => Abort.catching(v.toShort)
        given (using Frame): Parser[Duration.InvalidDuration, Duration] = v => Abort.get(Duration.parse(v))

        given (using Frame): Parser[IllegalArgumentException, java.util.UUID] =
            v => Abort.catching(java.util.UUID.fromString(v))

        given (using Frame): Parser[java.time.format.DateTimeParseException, java.time.LocalDate] =
            v => Abort.catching(java.time.LocalDate.parse(v))

        given (using Frame): Parser[java.time.format.DateTimeParseException, java.time.LocalTime] =
            v => Abort.catching(java.time.LocalTime.parse(v))

        given (using Frame): Parser[java.time.format.DateTimeParseException, java.time.LocalDateTime] =
            v => Abort.catching(java.time.LocalDateTime.parse(v))

        given (using Frame): Parser[java.net.URISyntaxException, java.net.URI] =
            v => Abort.catching(new java.net.URI(v))

        given (using Frame): Parser[java.net.MalformedURLException, java.net.URL] =
            v => Abort.catching(new java.net.URL(v))

        given [E, A](using p: Parser[E, A], frame: Frame): Parser[E, Seq[A]] =
            v => Kyo.foreach(Chunk.from(v.split(",")))(s => p(s.trim()))

        given (using Frame): Parser[IllegalArgumentException, Char] =
            v => Abort.ensuring(v.length == 1, v.charAt(0))(new IllegalArgumentException("String must have exactly one character"))

    end Parser

end System
