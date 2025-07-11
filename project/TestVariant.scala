import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import sbt.*
import sbt.Keys.*

object TestVariant {

    private object internal {
        case class Variant(base: String, replacements: Seq[String])

        object Variant {
            def parseLine(str: String): Option[Variant] = {
                val annotation   = """.*@TestVariant\(("[^"]+"(?:,\s*"[^"]+")*)\)""".r
                val valuePattern = """"([^"]+)"""".r

                str match {
                    case annotation(args) =>
                        val values = valuePattern.findAllMatchIn(args).map(_.group(1)).toList
                        values match {
                            case base :: replacements =>
                                Some(Variant(base, replacements))
                            case _ => None

                        }
                    case _ => None
                }
            }
        }

        sealed trait Line
        object Line {
            case class Raw(line: String)            extends Line
            case class Variants(lines: Seq[String]) extends Line
        }

        sealed trait Mode
        object Mode {
            case object Continue                     extends Mode
            case class Replace(testVariant: Variant) extends Mode
        }

        case class State(lines: Vector[Line], mode: Mode) {
            def next(line: Line, mode: Mode): State = copy(lines :+ line, mode)
        }

        object State {
            val zero: State = State(Vector.empty, Mode.Continue)
        }
    }

    val generate = Def.task {

        import internal.*

        val log              = streams.value.log
        val files: Seq[File] = (Test / unmanagedSources).value
        val outDir           = (Test / sourceManaged).value / "testVariants"

        lazy val created: Boolean = {
            IO.createDirectory(outDir)
            outDir.isDirectory
        }

        var i = 0
        def newFile(name: String): File = {
            require(created)
            i = i + 1
            outDir / s"generated${i}_$name.scala"
        }

        files.flatMap(file => {
            val content = IO.read(file, StandardCharsets.UTF_8)

            if (content.contains("@TestVariant")) {
                val processed: State = content.split("\n").foldLeft(State.zero)((state, str) => {

                    val nextMod: Mode = Variant.parseLine(str) match {
                        case Some(value) => Mode.Replace(value)
                        case None        => Mode.Continue
                    }

                    val line: Line = state.mode match {
                        case Mode.Continue => Line.Raw(str)
                        case Mode.Replace(testVariant) =>
                            Line.Variants(testVariant.replacements.map(r => str.replace(testVariant.base, r)))
                    }

                    state.next(line, nextMod)
                })

                val sizes = processed.lines.collect({ case Line.Variants(xs) => xs.size }).distinct
                assert(sizes.size == 1)
                val size = sizes.head

                val name = file.name.replaceAll(".scala$", "")

                val files: Seq[File] = (0 until size).map(_ => newFile(name))

                files.foreach(file => file.createNewFile())

                val writers: Seq[PrintWriter] = files.map(file => new PrintWriter(file))

                processed.lines.foreach({
                    case Line.Raw(str) => writers.foreach(_.println(str))
                    case Line.Variants(lines) => lines.zip(writers).foreach({
                            case (str, writer) => writer.println(str)
                        })

                })

                writers.foreach(_.close())
                files
            } else
                Nil
        })
    }
}
