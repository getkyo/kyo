import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import sbt.*
import sbt.Keys.*
import sbt.internal.util.ManagedLogger
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object TestVariant {

    private object internal {
        case class Variant(base: String, replacements: Seq[String])

        object Variant {
            def extractFrom(str: String)(implicit log: ManagedLogger): Option[Try[Variant]] = {
                val annotation   = """.*@TestVariant\(("[^"]+"(?:,\s*"[^"]+")*)\)""".r
                val valuePattern = """"([^"]+)"""".r

                str match {
                    case annotation(args) =>
                        val values = valuePattern.findAllMatchIn(args).map(_.group(1)).toList
                        values match {
                            case base :: replacements =>
                                Some(Try(Variant(base, replacements)))
                            case _ =>
                                log.error(s"Invalid @TestVariant annotation: $str")
                                Some(Try(sys.error("Invalid @TestVariant annotation")))

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

        implicit val log: ManagedLogger = streams.value.log
        val files: Seq[File]            = ((Test / unmanagedSources).value).filter(_.name.endsWith(".scala"))
        val outDir                      = (Test / sourceManaged).value / "testVariants"

        lazy val created: Boolean = {
            log.debug(s"Creating directory $outDir")
            IO.createDirectory(outDir)
            outDir.isDirectory
        }

        var fileCounter = 0

        def newScalaFile(suffix: String): File = {
            require(created)
            val nn: String = if (fileCounter < 10) "0" + fileCounter else "" + fileCounter
            val file       = outDir / s"generated$nn-$suffix.scala"
            fileCounter = fileCounter + 1

            log.debug(s"Creating file $file")
            file.createNewFile()
            file
        }

        files.flatMap(file => {
            val content = IO.read(file, StandardCharsets.UTF_8)

            if (content.contains("@TestVariant")) {
                log.debug(s"Processing file $file for variants")

                var expectedSize: Option[Int] = None

                val processed: State = content.split("\n").zipWithIndex.foldLeft(State.zero)({
                    case (state, (str, n)) => {
                        val lineNumber = n + 1

                        val nextMod: Mode = Variant.extractFrom(str) match {
                            case Some(Success(variant)) if expectedSize.isEmpty =>
                                expectedSize = Some(variant.replacements.size)
                                Mode.Replace(variant)

                            case Some(Success(variant)) if expectedSize.contains(variant.replacements.size) =>
                                Mode.Replace(variant)

                            case Some(Success(variant)) =>
                                log.error(s"in $file:$lineNumber\ninvalid variant $str : ${expectedSize.map(i =>
                                        s"expected size $i, actual size ${variant.replacements.size}"
                                    ).mkString}")
                                throw sys.error(s"invalid variant $str for $file:$lineNumber")

                            case Some(Failure(exception)) =>
                                log.error(s"cannot generate variant for $file")
                                throw exception
                            case None => Mode.Continue
                        }

                        val line: Line = state.mode match {
                            case Mode.Continue => Line.Raw(str)
                            case Mode.Replace(testVariant) =>
                                if (str.contains(testVariant.base))
                                    Line.Variants(testVariant.replacements.map(r => str.replace(testVariant.base, r)))
                                else {
                                    log.error(s"in $file:$lineNumber:\ncannot find '${testVariant.base}' in this line: \n|  $str")
                                    throw sys.error(s"cannot generate variant for $file")
                                }
                        }

                        state.next(line, nextMod)
                    }
                })

                val size: Int = processed.lines.collectFirst({ case Line.Variants(xs) => xs.size }).get

                val testFilename = file.name.replaceAll(".scala$", "")

                val files: Seq[File] = (0 until size).map(_ => newScalaFile(suffix = testFilename))

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
