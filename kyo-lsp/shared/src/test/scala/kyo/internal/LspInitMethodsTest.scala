package kyo.internal

import kyo.*

/** Verifies LspServer has exactly 10 init methods. INV-095.
  *
  * The 10 methods are: init (x3) + initWith (x2) + initUnscoped (x3) + initUnscopedWith (x2).
  * This test reflects the LspServer companion and counts the matching overloads.
  */
class LspInitMethodsTest extends Test:

    "LspServer has exactly 10 init-family methods (INV-095)" in run {
        val companionMethods = classOf[LspServer.type].getMethods.toSeq
        val initMethods      = companionMethods.filter(m => m.getName.startsWith("init"))
        // Count by name.
        val countInit             = initMethods.count(_.getName == "init")
        val countInitWith         = initMethods.count(_.getName == "initWith")
        val countInitUnscoped     = initMethods.count(_.getName == "initUnscoped")
        val countInitUnscopedWith = initMethods.count(_.getName == "initUnscopedWith")
        val total                 = countInit + countInitWith + countInitUnscoped + countInitUnscopedWith
        assert(countInit == 3, s"Expected 3 init overloads, got $countInit")
        assert(countInitWith == 2, s"Expected 2 initWith overloads, got $countInitWith")
        assert(countInitUnscoped == 3, s"Expected 3 initUnscoped overloads, got $countInitUnscoped")
        assert(countInitUnscopedWith == 2, s"Expected 2 initUnscopedWith overloads, got $countInitUnscopedWith")
        assert(total == 10, s"Expected 10 init-family methods total, got $total")
    }

    "Every LspServer init method name is in the allowed set (INV-095)" in run {
        val allowed          = Set("init", "initWith", "initUnscoped", "initUnscopedWith")
        val companionMethods = classOf[LspServer.type].getMethods.map(_.getName).toSet
        // Filter out Scala-generated default-parameter accessors (e.g. init$default$3).
        val initNames  = companionMethods.filter(n => n.startsWith("init") && !n.contains("$"))
        val unexpected = initNames -- allowed
        assert(unexpected.isEmpty, s"Unexpected init-family method names: ${unexpected.mkString(", ")}")
    }

end LspInitMethodsTest
