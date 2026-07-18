package kyo.doctest.sbt

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import sbt._

/** Compiles a module's README ```scala blocks by emitting them as a generated test source, for
  * modules that kyo-doctest's JVM fork cannot reach.
  *
  * The JVM doctest task (see [[Runner]]) forks a JVM and cannot compile Scala.js or Scala Native
  * output, so the aggregate `doctest` command skips those platforms. A JS/Wasm-only module (no JVM
  * variant) whose README examples use platform-only APIs therefore has no JVM host that can type-check
  * them. `KyoDoctestPlugin.doctestReadmeCompileCheck := true` wires this generator into that module's
  * `Test / sourceGenerators`, so `Test/compile` type-checks every documented example against the real
  * module surface under the module's own compiler.
  *
  * Each extracted block becomes the body of its own object so blocks do not share a scope and a `val`
  * or `import` in one block cannot collide with another. A README with no ```scala blocks yields a
  * valid source carrying only the package and a placeholder object, so the generator never produces a
  * non-compiling or empty file.
  *
  * The generated objects live under `package kyo.readme`, where the `Frame` macro aborts unless
  * `FindEnclosing` treats the file as test code: it must be emitted to a `src_managed/test/` path
  * (`Test / sourceManaged`) AND named `*Test.scala`, not either alone. Both conditions hold for the
  * `KyoDoctestReadmeTest.scala` output [[KyoDoctestPlugin]] wires up.
  */
private[sbt] object ReadmeCompileCheck {

    private val FenceOpen  = "```scala"
    private val FenceClose = "```"

    /** Returns the bodies of every fenced ```scala block in `content`, in document order. */
    private def extract(content: String): Seq[String] = {
        val lines  = content.split("\\r?\\n", -1).toVector
        val blocks = Vector.newBuilder[String]
        var i      = 0
        while (i < lines.length) {
            if (lines(i).trim == FenceOpen) {
                val body = Vector.newBuilder[String]
                i += 1
                while (i < lines.length && lines(i).trim != FenceClose) {
                    body += lines(i)
                    i += 1
                }
                blocks += body.result().mkString("\n")
                // skip the closing fence (or stop at EOF if the block was unterminated)
                if (i < lines.length) i += 1
            } else {
                i += 1
            }
        }
        blocks.result()
    }

    /** Wraps each extracted block in its own object under package `kyo.readme`. */
    private def render(blocks: Seq[String]): String = {
        val header =
            """// generated from README.md by kyo-doctest (KyoDoctestPlugin readme compile-check); do not edit.
              |package kyo.readme
              |""".stripMargin
        if (blocks.isEmpty) {
            header + "\nobject ReadmeExamples\n"
        } else {
            val objects = blocks.zipWithIndex.map { case (block, idx) =>
                val indented = block.split("\n", -1).map(line => if (line.isEmpty) line else "    " + line).mkString("\n")
                s"object ReadmeExample$idx {\n$indented\n}"
            }
            header + "\n" + objects.mkString("\n\n") + "\n"
        }
    }

    /** Reads every markdown file in `sources`, extracts their ```scala blocks in order, writes the
      * generated source to `out`, and returns `out`. Missing files contribute no blocks (the generator
      * still emits a valid file).
      */
    def generate(sources: Seq[File], out: File): File = {
        val content = sources.filter(_.exists()).map(f => IO.read(f, StandardCharsets.UTF_8)).mkString("\n")
        val source  = render(extract(content))
        IO.createDirectory(out.getParentFile)
        val writer = new PrintWriter(out, StandardCharsets.UTF_8.name())
        try writer.write(source)
        finally writer.close()
        out
    }
}
