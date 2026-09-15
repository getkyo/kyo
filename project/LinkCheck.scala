import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import sbt.*
import sbt.Keys.*
import scala.util.matching.Regex

/** Links representative programs the way an application links kyo, and checks what the application gets.
  *
  * Usage:
  *   - `linkCheck JS` links every program as a minified ES module
  *   - `linkCheck Wasm` links every program as WasmGC
  *   - `linkCheck Native` checks dependencies only (a Native link per program is what the Native test rows already pay for)
  *
  * For each program on JS and Wasm the command:
  *   - runs the linked output with plain `node`, nothing injected, and matches its last line of output (a launch that only the sbt test
  *     harness makes work fails here);
  *   - fails when the output contains data the program cannot reach: the IANA time-zone database or the CLDR locale data;
  *   - fails when the output exceeds its ceiling in `kyo-link-check/ceilings.txt`.
  *
  * On every platform it fails when a kyo module declares, outside tests, a dependency on an artifact that only carries that data. Linking a
  * data artifact is the application's choice, never kyo's.
  *
  * The sizes are printed on every run, so a ceiling moves by editing one line when a program legitimately grows.
  */
object LinkCheck {

    final case class Program(name: String, mainClass: String, lastLine: Regex)

    val programs: Seq[Program] = Seq(
        Program("CoreMin", "linkcheck.CoreMin", "42".r),
        Program("CoreLog", "linkcheck.CoreLog", """-?\+?\d{4,}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3,9})?Z""".r),
        Program("UiMin", "linkcheck.UiMin", """Div\(Attrs\(.*\),Chunk\.Indexed\(\)\)""".r)
    )

    /** Strings only the data artifacts put into a link: a zone ID and the tzdb module name, and the CLDR data package. */
    val dataMarkers: Seq[String] = Seq("Africa/Abidjan", "zonedb.java.tzdb", "locales.cldr.data")

    /** Artifacts whose only content is that data. */
    val dataArtifacts: Set[String] = Set("scala-java-time-tzdb", "locales-full-currencies-db", "locales-full-db", "locales-minimal-en_us-db")

    private val platforms = Seq("JS", "Wasm", "Native")

    private def log(msg: String): Unit = println(s"[linkCheck] $msg")

    // Reads each (possibly multi-megabyte) output file once per marker; linear, and only three programs.

    def command: Command = Command.args("linkCheck", "<JS|Wasm|Native>") { (state, args) =>
        args match {
            case Seq(platform) if platforms.contains(platform) =>
                val failures = dependencyFailures(state, platform) ++ (if (platform == "Native") Nil else linkFailures(state, platform))
                if (failures.isEmpty) {
                    log(s"$platform: all checks passed")
                    state
                } else {
                    failures.foreach(f => state.log.error(s"[linkCheck] $f"))
                    state.fail
                }
            case _ =>
                state.log.error("usage: linkCheck <JS|Wasm|Native>")
                state.fail
        }
    }

    private def isDataArtifact(name: String): Boolean =
        dataArtifacts.exists(a => name == a || name.startsWith(s"${a}_"))

    /** A data artifact is kyo's choice when a kyo module declares it outside tests, and that module fails. One that reaches a kyo module
      * only through a third-party library declaring it, as zio-test declares the tzdb, is that library's choice, already made for any
      * application using the library, so it is reported and not failed.
      */
    private def dependencyFailures(state: State, platform: String): Seq[String] = {
        val extracted = Project.extract(state)
        val refs = extracted.structure.allProjectRefs.filter { ref =>
            ref.project.endsWith(platform) && ref.project.startsWith("kyo-") && !ref.project.startsWith("kyo-link-check")
        }
        val declared = refs.flatMap { ref =>
            extracted.get(ref / libraryDependencies).collect {
                case m if isDataArtifact(m.name) && m.configurations.forall(_.split(";").exists(_.startsWith("compile"))) =>
                    (ref.project, s"${m.organization}:${m.name}:${m.revision}")
            }
        }
        val declaredNames = declared.map(_._2.split(":")(1)).toSet
        refs.foreach { ref =>
            val (_, report) = extracted.runTask(ref / Compile / update, state)
            report.configuration(Compile).toSeq.flatMap(_.allModules).foreach { m =>
                if (isDataArtifact(m.name) && !declaredNames.exists(n => m.name == n || m.name.startsWith(s"${n}_")))
                    log(s"${ref.project} gets ${m.organization}:${m.name}:${m.revision} from a third-party library that declares it")
            }
        }
        declared.distinct.map { case (project, id) => s"$project declares $id outside tests" }
    }

    private def linkFailures(state: State, platform: String): Seq[String] = {
        val extracted = Project.extract(state)
        val ref       = LocalProject(s"kyo-link-check$platform")
        val base      = extracted.get(ref / baseDirectory).getParentFile
        val ceilings  = readCeilings(base / "ceilings.txt")
        val rows = programs.map { program =>
            val outDir = extracted.get(ref / target) / "link-check" / program.name
            IO.delete(outDir)
            val linkState = extracted.appendWithoutSession(
                Seq(
                    ref / Compile / mainClass                                   := Some(program.mainClass),
                    ref / Compile / fullLinkJS / scalaJSLinkerOutputDirectory := outDir
                ),
                state
            )
            Project.extract(linkState).runTask(ref / Compile / fullLinkJS, linkState)
            // Source maps are a debugging aid the application does not ship.
            val files = Option(outDir.listFiles).toSeq.flatten.filter(f => f.isFile && !f.getName.endsWith(".map"))
            val size  = files.map(_.length).sum
            val found = dataMarkers.filter(marker => files.exists(f => contains(f, marker)))
            val run   = runNode(outDir, platform)
            (program, size, found, run)
        }
        log(s"$platform sizes (bytes, all output files):")
        rows.foreach { case (program, size, _, _) =>
            val ceiling = ceilings.get((platform, program.name)).fold("no ceiling")(c => f"ceiling $c%,d")
            log(f"  ${program.name}%-10s $size%,12d   $ceiling")
        }
        rows.flatMap { case (program, size, found, run) =>
            val data = found.map(m => s"$platform ${program.name}: the linked output contains data it cannot reach (marker '$m')")
            val ceiling = ceilings.get((platform, program.name)) match {
                case None                    => Seq(s"$platform ${program.name}: no ceiling in kyo-link-check/ceilings.txt")
                case Some(c) if size > c     => Seq(f"$platform ${program.name}: $size%,d bytes exceeds the ceiling of $c%,d")
                case Some(_)                 => Nil
            }
            val output = run match {
                case Left(err) => Seq(s"$platform ${program.name}: $err")
                case Right(lines) =>
                    val last = lines.reverse.find(_.trim.nonEmpty).getOrElse("")
                    if (program.lastLine.pattern.matcher(last.trim).matches()) Nil
                    else Seq(s"$platform ${program.name}: unexpected output under plain node, last line '$last'")
            }
            data ++ ceiling ++ output
        }
    }

    private def contains(file: File, marker: String): Boolean = {
        val bytes = Files.readAllBytes(file.toPath)
        indexOf(bytes, marker.getBytes(StandardCharsets.UTF_8)) >= 0 || indexOf(bytes, marker.getBytes(StandardCharsets.UTF_16LE)) >= 0
    }

    private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int = {
        val last = haystack.length - needle.length
        var i    = 0
        var at   = -1
        while (at < 0 && i <= last) {
            var j = 0
            while (j < needle.length && haystack(i + j) == needle(j)) j += 1
            if (j == needle.length) at = i
            i += 1
        }
        at
    }

    /** Runs `main.mjs` with plain node from its own directory and returns the lines it printed. */
    private def runNode(outDir: File, platform: String): Either[String, Seq[String]] = {
        val entry = outDir / "main.mjs"
        if (!entry.exists) Left(s"no main.mjs in $outDir")
        else {
            val flags   = if (platform == "Wasm") Seq("--experimental-wasm-exnref") else Nil
            val outFile = outDir.getParentFile / s"${outDir.getName}.out"
            val process = new ProcessBuilder((Seq("node") ++ flags :+ entry.getName)*)
                .directory(outDir)
                .redirectErrorStream(true)
                .redirectOutput(outFile)
                .start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Left("node did not exit within 120 seconds")
            } else {
                val lines = IO.readLines(outFile)
                if (process.exitValue != 0) Left(s"node exited with ${process.exitValue}: ${lines.takeRight(5).mkString(" | ")}")
                else Right(lines)
            }
        }
    }

    private def readCeilings(file: File): Map[(String, String), Long] =
        if (!file.exists) Map.empty
        else
            IO.readLines(file).map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map { line =>
                line.split("\\s+") match {
                    case Array(platform, program, bytes) => (platform, program) -> bytes.replace(",", "").replace("_", "").toLong
                    case _                               => sys.error(s"malformed ceiling line in $file: '$line'")
                }
            }.toMap
}
