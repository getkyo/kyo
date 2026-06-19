import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import sbt.*

/** Extracts fenced ```scala blocks from a module README and emits them as a single generated Scala
  * source so that `Test/compile` type-checks every documented example against the real module surface.
  *
  * kyo-threejs is JS+Wasm only and therefore has no JVM doctest host (KyoDoctestPlugin requires
  * JvmPlugin), so the README examples are verified by compiling them under the Scala.js compiler
  * through a `Test / sourceGenerators` hook instead of the JVM doctest task.
  *
  * Each extracted block becomes the body of its own object so blocks do not share a scope and a `val`
  * or `import` in one block cannot collide with another. A README with no ```scala blocks yields a
  * valid source carrying only the package and a placeholder object, so the generator never produces a
  * non-compiling or empty file.
  */
object ReadmeBlocks {

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
            """// generated from README.md by ReadmeBlocks; do not edit.
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

    /** Reads `readme`, extracts its ```scala blocks, writes the generated source to `out`, and returns
      * `out`. A missing README is treated as having no blocks (the generator still emits a valid file).
      */
    def generate(readme: File, out: File): File = {
        val content = if (readme.exists()) IO.read(readme, StandardCharsets.UTF_8) else ""
        val source  = render(extract(content))
        IO.createDirectory(out.getParentFile)
        val writer = new PrintWriter(out, StandardCharsets.UTF_8.name())
        try writer.write(source)
        finally writer.close()
        out
    }
}
