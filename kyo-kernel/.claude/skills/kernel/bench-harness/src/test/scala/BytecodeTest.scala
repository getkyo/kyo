import Model.*
import kyo.*
import kyo.test.*

/** Holds the bytecode reader to real compiled classes.
  *
  * Its whole value is that its numbers are exact, so the arithmetic producing them is checked rather than asserted. `size` adds one byte
  * back for the final instruction, which is only right if every method ends in a one-byte return or throw. That is checked here directly
  * instead of being left as a comment nobody re-derives.
  */
class BytecodeTest extends Test[Any]:

    // the sbt output directory, which any build produces, rather than a Metals session's bloop
    // directory whose name is unique to one editor session on one machine
    val classpath = Roots.classes

    /** Every JVM method ends in one of these, and each is a single byte. */
    val Terminators = Set("return", "areturn", "ireturn", "lreturn", "freturn", "dreturn", "athrow")

    // one leaf per suite: the artifact is read once inside it, every check records its claim, and the
    // leaf fails listing every claim that did not hold. This is the mains' counting `check` with the
    // framework holding the exit code
    private val failed = scala.collection.mutable.ListBuffer.empty[String]
    private def check(name: String, cond: Boolean, detail: => String = ""): Unit =
        if !cond then failed += (if detail.nonEmpty then s"$name  <- $detail" else name)

    "the bytecode reader agrees with the compiled classes" in {
        for
            methods <- Bytecode.of(classpath, "kyo.Arrow$AndThen")

            _ = assert(failed.isEmpty, "claims that did not hold:\n" + failed.mkString("\n"))
        yield ()
        end for
    }
end BytecodeTest
