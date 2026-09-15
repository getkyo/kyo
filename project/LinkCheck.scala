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
  * Each program lives in its own project under `kyo-link-check/`, depending only on the modules it uses, as a separate application would.
  * For each program on JS and Wasm the command:
  *   - runs the linked output with plain `node`, nothing injected, from an empty standard input, and matches its last line of output (a
  *     launch that only the sbt test harness makes work fails here);
  *   - runs it again with the `process` global deleted before the program loads, the state of a browser, and matches the line expected
  *     there: the same one for a program that needs no Node, a typed or explicit failure for one that does (a bare `process` read or a
  *     missing guard fails here);
  *   - fails when the output contains a static `node:*` import, which a browser cannot load (a dynamic `import("node:x")` is fine);
  *   - fails when the output contains data the program cannot reach: the IANA time-zone database or the CLDR locale data;
  *   - fails when the output exceeds its ceiling in `kyo-link-check/ceilings.txt`.
  *
  * On every platform it fails when a kyo module declares, outside tests, a dependency on an artifact that only carries that data. Linking a
  * data artifact is the application's choice, never kyo's.
  *
  * The sizes are printed on every run, so a ceiling moves by editing one line when a program legitimately grows.
  */
object LinkCheck {

    /** A program, the link-check project it lives in (without the platform suffix), and the last line it prints under plain node and with no
      * `process` global.
      */
    final case class Program(name: String, project: String, mainClass: String, lastLine: Regex, withoutProcess: Regex)

    object Program {
        def apply(name: String, project: String, mainClass: String, lastLine: Regex): Program =
            Program(name, project, mainClass, lastLine, lastLine)
    }

    val programs: Seq[Program] = Seq(
        Program("CoreMin", "kyo-link-check-core", "linkcheck.CoreMin", "42".r),
        Program("CoreLog", "kyo-link-check-core", "linkcheck.CoreLog", """-?\+?\d{4,}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3,9})?Z""".r),
        Program("CoreReadLine", "kyo-link-check-core", "linkcheck.CoreReadLine", "failure EOFException".r, "failure IOException".r),
        Program("UiMin", "kyo-link-check-ui", "linkcheck.UiMin", """Div\(Attrs\(.*\),Chunk\.Indexed\(\)\)""".r),
        Program("SystemPath", "kyo-link-check-system", "linkcheck.SystemPath", "kyo".r, "panic UnsupportedOperationException".r),
        // NetPlatform.transport is a plain lazy val, so a host with no usable backend gets its NetBackendUnavailableException as a throw.
        Program("NetEcho", "kyo-link-check-net", "linkcheck.NetEcho", "echo kyo".r, "panic NetBackendUnavailableException".r)
    )

    /** A static import of a Node built-in in linked output: `import * as x from "node:fs"`, `import "node:fs"`. */
    private val staticNodeImport: Regex = """(?:\bfrom|\bimport)\s*["']node:[\w/]+["']""".r

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
        val ceilings  = readCeilings(extracted.get(LocalRootProject / baseDirectory) / "kyo-link-check" / "ceilings.txt")
        val rows = programs.map { program =>
            val ref    = LocalProject(s"${program.project}$platform")
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
            val nodeImports = files.flatMap { f =>
                staticNodeImport.findAllIn(new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)).toSeq
            }.distinct
            val runs = Seq(
                ("under plain node", program.lastLine, runNode(outDir, platform, withoutProcess = false)),
                ("with no process global", program.withoutProcess, runNode(outDir, platform, withoutProcess = true))
            )
            (program, size, found, nodeImports, runs)
        }
        log(s"$platform sizes (bytes, all output files):")
        rows.foreach { case (program, size, _, _, _) =>
            val ceiling = ceilings.get((platform, program.name)).fold("no ceiling")(c => f"ceiling $c%,d")
            log(f"  ${program.name}%-12s $size%,12d   $ceiling")
        }
        rows.flatMap { case (program, size, found, nodeImports, runs) =>
            val data = found.map(m => s"$platform ${program.name}: the linked output contains data it cannot reach (marker '$m')")
            val imports = nodeImports.map(i => s"$platform ${program.name}: the linked output has a static Node import a browser cannot load: $i")
            val ceiling = ceilings.get((platform, program.name)) match {
                case None                    => Seq(s"$platform ${program.name}: no ceiling in kyo-link-check/ceilings.txt")
                case Some(c) if size > c     => Seq(f"$platform ${program.name}: $size%,d bytes exceeds the ceiling of $c%,d")
                case Some(_)                 => Nil
            }
            val output = runs.flatMap {
                case (how, _, Left(err)) => Seq(s"$platform ${program.name} $how: $err")
                case (how, expected, Right(lines)) =>
                    val last = lines.reverse.find(_.trim.nonEmpty).getOrElse("")
                    if (expected.pattern.matcher(last.trim).matches()) Nil
                    else Seq(s"$platform ${program.name}: unexpected output $how, last line '$last', expected '$expected'")
            }
            data ++ imports ++ ceiling ++ output
        }
    }

    /** Deletes `process` from the global object, then loads the program: the program sees what a browser page shows it, while node itself,
      * which holds its own reference, keeps printing and exiting normally.
      */
    private val withoutProcessLauncher =
        """delete globalThis.process;
          |await import("./main.mjs");
          |""".stripMargin

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

    /** Runs `main.mjs` with plain node from its own directory, directly or through [[withoutProcessLauncher]], and returns the lines it
      * printed. The launcher is written beside the output, never into it, so it is not counted in the size.
      */
    private def runNode(outDir: File, platform: String, withoutProcess: Boolean): Either[String, Seq[String]] = {
        val main = outDir / "main.mjs"
        if (!main.exists) Left(s"no main.mjs in $outDir")
        else {
            val suffix = if (withoutProcess) "-no-process" else ""
            val entry =
                if (!withoutProcess) main.getAbsolutePath
                else {
                    val launcher = outDir.getParentFile / s"${outDir.getName}-no-process.mjs"
                    IO.write(launcher, withoutProcessLauncher.replace("./main.mjs", s"./${outDir.getName}/main.mjs"))
                    launcher.getAbsolutePath
                }
            val flags   = if (platform == "Wasm") Seq("--experimental-wasm-exnref") else Nil
            val outFile = outDir.getParentFile / s"${outDir.getName}$suffix.out"
            // An empty file, not the build's own stdin, so a program that reads standard input sees its end instead of waiting.
            val stdin = outDir.getParentFile / "empty-stdin"
            IO.write(stdin, "")
            val process = new ProcessBuilder((Seq("node") ++ flags :+ entry)*)
                .directory(outDir)
                .redirectInput(stdin)
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
