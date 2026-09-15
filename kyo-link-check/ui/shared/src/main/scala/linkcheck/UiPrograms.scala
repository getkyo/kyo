package linkcheck

// Outside the kyo package, as an application is.
import kyo.*

/** The smallest kyo-ui program: one element, rendered to its description. */
object UiMin:
    def main(args: Array[String]): Unit =
        println(UI.div.toString)
end UiMin
