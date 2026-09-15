package linkcheck

// Outside the kyo package, as an application is.
import kyo.*

/** The smallest kyo-core program: one suspended computation, evaluated. */
object CoreMin:
    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        println(Sync.Unsafe.evalOrThrow(Sync.defer(42)))
end CoreMin

/** A program that reads the clock, logs, and prints a timestamp: the path from `Clock` and `Log` through `Instant.show`. */
object CoreLog:
    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val shown = Sync.Unsafe.evalOrThrow(Clock.now.map(now => Log.info(s"now ${now.show}").andThen(now.show)))
        println(shown)
    end main
end CoreLog

/** The smallest kyo-ui program: one element, rendered to its description. */
object UiMin:
    def main(args: Array[String]): Unit =
        println(UI.div.toString)
end UiMin
