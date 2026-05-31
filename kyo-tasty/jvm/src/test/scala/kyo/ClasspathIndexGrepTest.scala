package kyo

import scala.collection.mutable

/** JVM-only grep tests for ClasspathIndexImmutabilityTest Leaves 3-5.
  *
  * These tests verify that deleted types (SingleAssign, ClasspathRef, UnresolvedRef) no longer appear in production sources. Uses
  * java.io.File for file-system traversal; JVM-only.
  *
  * Pins: INV-012 (deletion progress check).
  */
class ClasspathIndexGrepTest extends Test:

    // Leaf 3: no SingleAssign reference survives in main sources.
    "Leaf 3: no SingleAssign reference in kyo-tasty production sources" in {
        val srcs = Seq(
            "kyo-tasty/shared/src/main/scala",
            "kyo-tasty/jvm/src/main/scala",
            "kyo-tasty/js/src/main/scala",
            "kyo-tasty/native/src/main/scala"
        )
        val matches = srcs.flatMap: dir =>
            val d = new java.io.File(dir)
            if d.exists then grepDir(d, "SingleAssign") else Seq.empty
        assert(matches.isEmpty, s"SingleAssign should not appear in production sources; found in: ${matches.mkString(", ")}")
    }

    // Leaf 4: no ClasspathRef reference survives in main sources.
    "Leaf 4: no ClasspathRef reference in kyo-tasty production sources" in {
        val srcs = Seq(
            "kyo-tasty/shared/src/main/scala",
            "kyo-tasty/jvm/src/main/scala",
            "kyo-tasty/js/src/main/scala",
            "kyo-tasty/native/src/main/scala"
        )
        val matches = srcs.flatMap: dir =>
            val d = new java.io.File(dir)
            if d.exists then grepDir(d, "ClasspathRef") else Seq.empty
        assert(matches.isEmpty, s"ClasspathRef should not appear in production sources; found in: ${matches.mkString(", ")}")
    }

    // Leaf 5: no UnresolvedRef reference survives in main sources.
    "Leaf 5: no UnresolvedRef reference in kyo-tasty production sources" in {
        val srcs = Seq(
            "kyo-tasty/shared/src/main/scala",
            "kyo-tasty/jvm/src/main/scala",
            "kyo-tasty/js/src/main/scala",
            "kyo-tasty/native/src/main/scala"
        )
        val matches = srcs.flatMap: dir =>
            val d = new java.io.File(dir)
            if d.exists then grepDir(d, "UnresolvedRef") else Seq.empty
        assert(matches.isEmpty, s"UnresolvedRef should not appear in production sources; found in: ${matches.mkString(", ")}")
    }

    private def grepDir(dir: java.io.File, pattern: String): Seq[String] =
        val results = new mutable.ArrayBuffer[String]()
        def walk(f: java.io.File): Unit =
            if f.isDirectory then
                val children = f.listFiles()
                if children != null then children.foreach(walk)
            else if f.getName.endsWith(".scala") then
                try
                    val lines     = scala.io.Source.fromFile(f).getLines().toSeq
                    val codeLines = lines.filterNot(l => l.trim.startsWith("//") || l.trim.startsWith("*"))
                    if codeLines.exists(_.contains(pattern)) then results += f.getPath
                catch case _: Exception => ()
        walk(dir)
        results.toSeq
    end grepDir

end ClasspathIndexGrepTest
