import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.scalajs.linker.interface.ModuleKind
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
  *   - on JS, fails when the program as linked reads a host global some JS host does not declare (`process`, `require`, `location`, ...) as
  *     a bare identifier rather than as a property of `globalThis`, since that read throws before any guard around it runs;
  *   - fails when the output contains data the program cannot reach: the IANA time-zone database or the CLDR locale data;
  *   - fails when the output exceeds its ceiling in `kyo-link-check/ceilings.txt`.
  *
  * A JS link can split into chunks, and a host fetches only the chunks it reaches. So on JS the command also measures the initial load,
  * `main.mjs` and everything it imports statically, which is what a host fetches before any code runs. For a program that reaches a
  * backend only one kind of host can run (the network stack, which has a Node side and a page side):
  *   - fails when the initial load carries either side's backend, since the side a host does not use belongs in a chunk it never fetches;
  *   - deletes the Node-side chunks from a copy of the output and runs it with no `process` global, as a page, and deletes the page-side
  *     chunks from another copy and runs it under plain node: each must still print its line, which is what proves the deleted chunks
  *     were never fetched;
  *   - fails when the initial load exceeds its own ceiling;
  *   - builds the output with vite, webpack and rollup, with and without `external: [/^node:/]`, serves each build to the Chrome the
  *     browser test rows run, and fails when a page does not print its line, throws, or fetches a file carrying a Node backend
  *     (`kyo-link-check/bundlers/check.mjs`; the bundler versions are pinned in its `package.json`).
  *
  * A WasmGC link is one module by necessity, so it carries both sides inline and its initial load is its whole output.
  *
  * On JS each program is also linked as CommonJS and as NoModule, the kind Scala.js emits when an application configures none, and run under
  * plain node from its output file. The linker rejects a `js.dynamicImport` and an `import.meta` under NoModule, so this is what shows a
  * split point or a module-relative lookup collapsing for that link, and it holds those links to the same bare-global rule.
  *
  * Every link is made against the modules as a release builds them. A release publishes each project for each of its own
  * `crossScalaVersions`, so a project that does not list the build's Scala version, such as kyo-config, which is published for the Scala 3.3
  * LTS line and 2.13, is built for the entry of the same major version, and every module that depends on it compiles against that build.
  * An application links exactly those artifacts, so a module that relies on something only the default build of a dependency has fails
  * here, as it would in a release.
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
    final case class Program(
        name: String,
        project: String,
        mainClass: String,
        lastLine: Regex,
        withoutProcess: Regex,
        /** Strings the linked output must NOT contain: code this program has no use for and must not drag in. */
        absent: Seq[String] = Nil,
        /** Whether the program reaches the network stack, whose backends split by host into chunks loaded on demand. */
        hostChunks: Boolean = false
    )

    object Program {
        def apply(name: String, project: String, mainClass: String, lastLine: Regex): Program =
            Program(name, project, mainClass, lastLine, lastLine)
    }

    val programs: Seq[Program] = Seq(
        Program("CoreMin", "kyo-link-check-core", "linkcheck.CoreMin", "42".r),
        Program("CoreLog", "kyo-link-check-core", "linkcheck.CoreLog", """-?\+?\d{4,}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3,9})?Z""".r),
        Program("CoreApp", "kyo-link-check-core", "linkcheck.CoreApp", "app".r),
        Program("CoreReadLine", "kyo-link-check-core", "linkcheck.CoreReadLine", "failure EOFException".r, "failure IOException".r),
        Program("UiMin", "kyo-link-check-ui", "linkcheck.UiMin", """Div\(Attrs\(.*\),Chunk\.Indexed\(\)\)""".r),
        // A host with no file system answers on the channel `Path` declares, so the page row is a typed failure, not a panic.
        Program("SystemPath", "kyo-link-check-system", "linkcheck.SystemPath", "kyo".r, "failure FileSystemUnsupportedOnHostException".r),
        // A host with no usable backend gets NetBackendUnavailableException from its first operation, on the Abort channel the
        // operation declares, without fetching any backend.
        Program(
            "NetEcho",
            "kyo-link-check-net",
            "linkcheck.NetEcho",
            "echo kyo".r,
            "failure NetBackendUnavailableException".r,
            hostChunks = true
        ),
        // One request to a closed port, on a host that has sockets and on one that does not. The program also has to exit,
        // which is how a client that leaves something running behind it is caught.
        Program(
            "HttpGet",
            "kyo-link-check-http",
            "linkcheck.HttpGet",
            "failure HttpConnectException".r,
            "failure HttpConnectException".r,
            hostChunks = true
        ),
        // Names one HTTP provider and reads its completion. kyo-ai's two CLI harnesses spawn a process, which reaches
        // node:child_process; a program that names neither must not carry them, and a page could not run them at all.
        Program(
            "AiHttp",
            "kyo-link-check-ai",
            "linkcheck.AiHttp",
            "failure AITransportException".r,
            "failure AITransportException".r,
            absent = Seq("node:child_process"),
            hostChunks = true
        ),
        // The machine-stats factory registers itself at module load, and registering starts a sampler that reads the
        // machine through Node's own modules. A host with no machine to read registers nothing, which is the difference
        // between these two lines.
        Program("MachineStats", "kyo-link-check-machine", "linkcheck.MachineStats", "machine exporter true".r, "machine exporter false".r)
    )

    /** A static import of a Node built-in in linked output: `import * as x from "node:fs"`, `import "node:fs"`. */
    private val staticNodeImport: Regex = """(?:\bfrom|\bimport)\s*["']node:[\w/]+["']""".r

    /** A static import of another output file: `import * as x from "./internal-a1.mjs"`. A dynamic `import("./internal-a1.mjs")` does not
      * match, because a parenthesis stands between the keyword and the quote.
      */
    private val staticRelativeImport: Regex = """(?:\bfrom|\bimport)\s*["']\./([^"']+)["']""".r

    /** Classes only a Node-like host runs: the posix transport and its drivers, Node's own transport and the module loader under it, the
      * socket HTTP client, and the koffi layer the posix backends load natives through. Matched as the quoted class name Scala.js keeps for
      * each class, so prose that mentions one does not count.
      */
    val nodeBackend: Seq[String] = Seq(
        "kyo.net.internal.posix.PosixTransport",
        "kyo.net.internal.posix.IoUringDriver",
        "kyo.net.internal.posix.PollerIoDriver",
        "kyo.net.internal.JsTransport",
        "kyo.net.internal.JsIoDriver",
        "kyo.net.internal.NodeNetModules$",
        "kyo.internal.client.HttpClientBackend",
        "kyo.ffi.internal.Koffi$",
        "kyo.ffi.internal.NativeLoader$"
    )

    /** Classes only a host without sockets runs: the fetch client. */
    val pageBackend: Seq[String] = Seq("kyo.internal.client.FetchClientBackend")

    /** Globals some JS host does not declare: Node's (`require`, `process`, `Buffer`), a page's (`location`, `window`, `document`), the two
      * an embedded engine or an older runtime can lack (`fetch`, `WebSocket`), and the parking pair a page gets only when it is
      * cross-origin isolated (`SharedArrayBuffer`, `Atomics`). A bare read of one throws a `ReferenceError` on such a host before any guard
      * around it can run, so every read goes through a property of `globalThis` (`PlatformJs.jsGlobal`), which reads as `undefined` there
      * instead. `typeof name` is the one bare form that cannot throw.
      */
    val hostGlobals: Set[String] =
        Set("require", "process", "Buffer", "location", "window", "document", "fetch", "WebSocket", "SharedArrayBuffer", "Atomics")

    /** Strings only the data artifacts put into a link: a zone ID and the tzdb module name, and the CLDR data package. */
    val dataMarkers: Seq[String] = Seq("Africa/Abidjan", "zonedb.java.tzdb", "locales.cldr.data")

    /** Artifacts whose only content is that data. */
    val dataArtifacts: Set[String] = Set("scala-java-time-tzdb", "locales-full-currencies-db", "locales-full-db", "locales-minimal-en_us-db")

    private val platforms = Seq("JS", "Wasm", "Native")

    private def log(msg: String): Unit = println(s"[linkCheck] $msg")

    // Reads each (possibly multi-megabyte) output file once per marker; linear, and only three programs.

    def command: Command = Command.args("linkCheck", "<JS|Wasm|Native> [program...]") { (state, args) =>
        args match {
            case Seq(platform, only @ _*) if platforms.contains(platform) && only.forall(name => programs.exists(_.name == name)) =>
                // Naming programs links only those, for iterating on one; CI names none.
                val selected = if (only.isEmpty) programs else programs.filter(p => only.contains(p.name))
                // Checked against the release build without making it the session's: the state after the command is the one before it.
                val release = releaseSettings(state)
                val failures =
                    dependencyFailures(state, release, platform) ++
                        (if (platform == "Native") Nil else linkFailures(state, release, platform, selected))
                if (failures.isEmpty) {
                    log(s"$platform: all checks passed")
                    state
                } else {
                    failures.foreach(f => state.log.error(s"[linkCheck] $f"))
                    state.fail
                }
            case _ =>
                state.log.error(s"usage: linkCheck <JS|Wasm|Native> [program...], where a program is one of ${programs.map(_.name).mkString(", ")}")
                state.fail
        }
    }

    /** The settings that put every project on the Scala version a release builds it with: a project whose `crossScalaVersions` do not
      * include its `scalaVersion` takes the entry with the same binary version, which is the build a `+publish` compiles it for and every
      * module on the build's Scala version compiles against.
      *
      * They go into every state this command appends settings to, because `appendWithoutSession` starts again from the build's own settings
      * and would drop them from a state that already carried them.
      */
    private def releaseSettings(state: State): Seq[Setting[_]] = {
        val extracted = Project.extract(state)
        val moved = extracted.structure.allProjectRefs.flatMap { ref =>
            val version = extracted.get(ref / scalaVersion)
            val cross   = extracted.get(ref / crossScalaVersions)
            if (cross.isEmpty || cross.contains(version)) Nil
            else
                cross
                    .find(v => CrossVersion.binaryScalaVersion(v) == CrossVersion.binaryScalaVersion(version))
                    .map(released => (ref, released))
                    .toSeq
        }
        moved.groupBy(_._2).toSeq.sortBy(_._1).foreach { case (released, refs) =>
            log(s"linking against the release build of ${refs.map(_._1.project).sorted.mkString(", ")}: Scala $released")
        }
        moved.map { case (ref, released) => ref / scalaVersion := released }
    }

    /** The module kinds each program is also linked as on JS, besides the ES module an application ships and the sizes are measured on,
      * with the directory label of each.
      */
    private val otherModuleKinds: Seq[(String, ModuleKind)] =
        Seq("commonjs" -> ModuleKind.CommonJSModule, "nomodule" -> ModuleKind.NoModule)

    private def isDataArtifact(name: String): Boolean =
        dataArtifacts.exists(a => name == a || name.startsWith(s"${a}_"))

    /** A data artifact is kyo's choice when a kyo module declares it outside tests, and that module fails. One that reaches a kyo module
      * only through a third-party library declaring it, as zio-test declares the tzdb, is that library's choice, already made for any
      * application using the library, so it is reported and not failed.
      */
    private def dependencyFailures(original: State, release: Seq[Setting[_]], platform: String): Seq[String] = {
        val state     = Project.extract(original).appendWithoutSession(release, original)
        val extracted = Project.extract(state)
        // The Wasm row links the JS projects, so it has their dependencies.
        val suffix = if (platform == "Wasm") "JS" else platform
        val refs = extracted.structure.allProjectRefs.filter { ref =>
            ref.project.endsWith(suffix) && ref.project.startsWith("kyo-") && !ref.project.startsWith("kyo-link-check")
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

    private def linkFailures(state: State, release: Seq[Setting[_]], platform: String, programs: Seq[Program]): Seq[String] = {
        val extracted = Project.extract(state)
        val ceilings  = readCeilings(extracted.get(LocalRootProject / baseDirectory) / "kyo-link-check" / "ceilings.txt")
        // Installed and resolved once per run, and only when a program bundles.
        lazy val tools = bundlerTools(state)
        val rows = programs.map { program =>
            // Wasm links the JS project with the WasmGC linker configuration, as an application linking the one _sjs1 artifact does.
            val ref    = LocalProject(s"${program.project}JS")
            val outDir = extracted.get(ref / target) / "link-check" / platform.toLowerCase / program.name
            IO.delete(outDir)
            val wasmLink =
                if (platform == "Wasm") Seq(ref / Compile / fullLinkJS / scalaJSLinkerConfig ~= KyoJsRows.wasmLinkerConfig)
                else Nil
            val linkState = extracted.appendWithoutSession(
                release ++ Seq(
                    ref / Compile / mainClass                                   := Some(program.mainClass),
                    ref / Compile / fullLinkJS / scalaJSLinkerOutputDirectory := outDir
                ) ++ wasmLink,
                state
            )
            Project.extract(linkState).runTask(ref / Compile / fullLinkJS, linkState)
            // Source maps are a debugging aid the application does not ship.
            val files   = outputFiles(outDir)
            val size    = files.map(_.length).sum
            val initial = initialLoad(files, platform)
            val found   = dataMarkers.filter(marker => files.exists(f => contains(f, marker)))
            val linked  = program.absent.filter(marker => files.exists(f => contains(f, marker)))
            val nodeImports = files.flatMap { f =>
                staticNodeImport.findAllIn(read(f)).toSeq
            }.distinct
            val kinds = if (platform == "JS") otherModuleKinds.map { case (label, kind) => moduleKindLink(linkState, ref, program, label, kind) } else Nil
            val runs = Seq(
                ("under plain node", program.lastLine, runNode(outDir, platform, withoutProcess = false)),
                ("with no process global", program.withoutProcess, runNode(outDir, platform, withoutProcess = true))
            ) ++ hostChunkRuns(program, outDir, platform, files, initial) ++ kinds.map(_.run)
            val eager =
                if (program.hostChunks && platform == "JS")
                    (nodeBackend ++ pageBackend).filter(name => initial.exists(f => contains(f, quoted(name))))
                else Nil
            // The WasmGC row links the same IR, so the JS row answers for both.
            val bareGlobals =
                if (platform != "JS") Nil
                else checkedBareGlobals(linkState, ref) ++ kinds.flatMap(kind => kind.bareGlobals.map(read => s"$read, as ${kind.label}"))
            val bundlers    = if (program.hostChunks && platform == "JS") bundlerRuns(program, outDir, tools) else Right(Nil)
            Row(program, size, initial.map(_.length).sum, gzipSize(initial), found, linked, nodeImports, eager, bareGlobals, bundlers, runs)
        }
        log(s"$platform sizes (bytes; total is every output file, initial is what a host fetches before any code runs):")
        rows.foreach { row =>
            val ceiling = ceilings.get((platform, row.program.name)).fold("no ceiling")(c => f"ceiling $c%,d")
            val initialCeiling =
                if (platform == "JS") ceilings.get((initialKey(platform), row.program.name)).fold("no ceiling")(c => f"ceiling $c%,d")
                else "the whole output"
            log(
                f"  ${row.program.name}%-12s total ${row.size}%,12d ($ceiling)   initial ${row.initialSize}%,12d ($initialCeiling)   initial gzip ${row.initialGzip}%,10d"
            )
        }
        rows.foreach(row => row.bundlers.foreach(_.foreach(line => log(s"  ${row.program.name} bundled, $line"))))
        rows.flatMap { row =>
            val program = row.program
            val data    = row.found.map(m => s"$platform ${program.name}: the linked output contains data it cannot reach (marker '$m')")
            val reached =
                row.linked.map(m => s"$platform ${program.name}: the linked output carries code this program does not use (marker '$m')")
            val imports =
                row.nodeImports.map(i => s"$platform ${program.name}: the linked output has a static Node import a browser cannot load: $i")
            val eager = row.eager.map { name =>
                s"$platform ${program.name}: the initial load carries $name, which only one kind of host runs; it belongs in a chunk that host loads on demand"
            }
            val bareGlobals = row.bareGlobals.map { read =>
                s"$platform ${program.name}: reads the host global $read bare, which throws a ReferenceError on a host that does not declare it; read it through PlatformJs.jsGlobal"
            }
            val ceiling = ceilingFailure(ceilings, platform, platform, program.name, row.size)
            val initialCeiling =
                if (platform == "JS") ceilingFailure(ceilings, platform, initialKey(platform), program.name, row.initialSize)
                else Nil
            val output = row.runs.flatMap {
                case (how, _, Left(err)) => Seq(s"$platform ${program.name} $how: $err")
                case (how, expected, Right(lines)) =>
                    val last = lines.reverse.find(_.trim.nonEmpty).getOrElse("")
                    if (expected.pattern.matcher(last.trim).matches()) Nil
                    else Seq(s"$platform ${program.name}: unexpected output $how, last line '$last', expected '$expected'")
            }
            val bundled = row.bundlers match {
                case Left(err)    => Seq(s"$platform ${program.name}: the bundler check did not run: $err")
                case Right(lines) => lines.filter(_.startsWith("FAIL")).map(line => s"$platform ${program.name} bundled: ${line.stripPrefix("FAIL ")}")
            }
            data ++ reached ++ imports ++ eager ++ bareGlobals ++ bundled ++ ceiling ++ initialCeiling ++ output
        }
    }

    /** What linking a program as one of [[otherModuleKinds]] showed: its run under plain node, which is a failure when it did not link, and
      * its bare host-global reads.
      */
    private final case class KindLink(label: String, run: (String, Regex, Either[String, Seq[String]]), bareGlobals: Seq[String])

    /** Links `program` as `kind` into its own directory and runs `main.js` from there under plain node, the way an application that
      * configures that module kind launches it. `state` is the program's ES module link state, with the release build and its main class.
      *
      * The linker is called directly rather than through `fullLinkJS`, so a link it rejects comes back as that run's failure: a failed task
      * inside this command would end the command for an sbt client before the other checks report.
      */
    private def moduleKindLink(state: State, ref: ProjectReference, program: Program, label: String, kind: ModuleKind): KindLink = {
        val extracted = Project.extract(state)
        val outDir    = extracted.get(ref / target) / "link-check" / s"js-$label" / program.name
        IO.delete(outDir)
        IO.createDirectory(outDir)
        val config = extracted
            .get(ref / Compile / fullLinkJS / scalaJSLinkerConfig)
            .withModuleKind(kind)
            .withOutputPatterns(org.scalajs.linker.interface.OutputPatterns.Defaults)
        val how = s"as $label under plain node"
        linkIR(state, ref, config, org.scalajs.linker.PathOutputDirectory(outDir.toPath)) match {
            case Left(err) => KindLink(label, (how, program.lastLine, Left(s"did not link: $err")), Nil)
            case Right(moduleSet) =>
                KindLink(label, (how, program.lastLine, runNode(outDir, "JS", withoutProcess = false, entry = "main.js")), bareHostGlobalReads(moduleSet))
        }
    }

    /** The bare host-global reads of the program's ES module link, with a link that failed reported as a read, so it fails the check. */
    private def checkedBareGlobals(state: State, ref: ProjectReference): Seq[String] = {
        val config = Project.extract(state).get(ref / Compile / fullLinkJS / scalaJSLinkerConfig)
        try
            linkIR(state, ref, config, org.scalajs.linker.MemOutputDirectory()) match {
                case Left(err)        => Seq(s"(the IR could not be read: $err)")
                case Right(moduleSet) => bareHostGlobalReads(moduleSet)
            }
        catch {
            case e: Throwable =>
                val frames = e.getStackTrace.take(8).mkString(" <- ")
                Seq(s"(the IR could not be read: $e at $frames)")
        }
    }

    private final case class Row(
        program: Program,
        size: Long,
        initialSize: Long,
        initialGzip: Long,
        found: Seq[String],
        linked: Seq[String],
        nodeImports: Seq[String],
        eager: Seq[String],
        bareGlobals: Seq[String],
        bundlers: Either[String, Seq[String]],
        runs: Seq[(String, Regex, Either[String, Seq[String]])]
    )

    /** Links the program of `ref` with `config` into `output` through the standard linker, and returns the modules it emitted, as the
      * optimizer left them, or the errors the linker reported.
      */
    private def linkIR(
        state: State,
        ref: ProjectReference,
        config: org.scalajs.linker.interface.StandardConfig,
        output: org.scalajs.linker.interface.OutputDirectory
    ): Either[String, org.scalajs.linker.standard.ModuleSet] = {
        import org.scalajs.linker.PathIRContainer
        import org.scalajs.linker.StandardImpl
        import org.scalajs.linker.interface.IRFile
        import org.scalajs.linker.interface.OutputDirectory
        import org.scalajs.linker.interface.Report
        import org.scalajs.linker.standard.CoreSpec
        import org.scalajs.linker.standard.LinkerBackend
        import org.scalajs.linker.standard.ModuleSet
        import org.scalajs.linker.standard.StandardLinkerBackend
        import org.scalajs.linker.standard.StandardLinkerFrontend
        import org.scalajs.linker.standard.StandardLinkerImpl
        import org.scalajs.linker.standard.SymbolRequirement
        import scala.concurrent.Await
        import scala.concurrent.ExecutionContext
        import scala.concurrent.duration.Duration

        implicit val ec: ExecutionContext = ExecutionContext.global
        val extracted         = Project.extract(state)
        val (_, initializers) = extracted.runTask(ref / Compile / scalaJSModuleInitializers, state)
        val (_, classpath)    = extracted.runTask(ref / Compile / fullClasspath, state)
        // The frontend reports what it could not link through its logger, and the exception says only that it failed.
        val errors = scala.collection.mutable.ListBuffer.empty[String]
        val logger = new org.scalajs.logging.Logger {
            def log(level: org.scalajs.logging.Level, message: => String): Unit =
                if (level == org.scalajs.logging.Level.Error) errors.synchronized(errors += message)
            def trace(t: => Throwable): Unit = ()
        }
        // The standard linker, whose backend records the modules it is handed before emitting them: linking through the frontend alone
        // would leave out the linker's own runtime library, which the standard linker adds.
        @volatile var linked: Option[ModuleSet] = None
        val standardBackend                      = StandardLinkerBackend(config)
        val recordingBackend = new LinkerBackend {
            val coreSpec: CoreSpec                     = standardBackend.coreSpec
            val symbolRequirements: SymbolRequirement  = standardBackend.symbolRequirements
            def injectedIRFiles: scala.collection.Seq[IRFile] = standardBackend.injectedIRFiles
            def emit(moduleSet: ModuleSet, output: OutputDirectory, logger: org.scalajs.logging.Logger)(implicit
                ec: ExecutionContext
            ): scala.concurrent.Future[Report] = {
                linked = Some(moduleSet)
                standardBackend.emit(moduleSet, output, logger)
            }
        }
        val linker = StandardLinkerImpl(StandardLinkerFrontend(config), recordingBackend)
        val linking = for {
            (containers, _) <- PathIRContainer.fromClasspath(classpath.map(_.data.toPath))
            irFiles         <- StandardImpl.irFileCache().newCache.cached(containers)
            _               <- linker.link(irFiles, initializers, output, logger)
        } yield linked.getOrElse(sys.error("the linker emitted no modules"))
        try Right(Await.result(linking, Duration.Inf))
        catch {
            case e: org.scalajs.linker.interface.LinkingException =>
                Left((Option(e.getMessage).toSeq ++ errors.distinct).mkString(" | "))
        }
    }

    /** Every bare read of a [[hostGlobals]] name in `moduleSet`, each as `'name' in class.member`.
      *
      * Read from the linker's IR after optimization rather than from the emitted text: there a global read is a node of its own and
      * `typeof name` another, so neither a guard, nor a string, nor a local that happens to share the name is mistaken for a read. A
      * facade declared with `@JSGlobal` on one of the names is a bare read too, wherever it is loaded. A read inside the branch of an `if`
      * whose condition compares `typeof name` with a string other than `"undefined"` is not counted: the name is declared there. That is
      * the one form `require` keeps, in CommonJS and NoModule links only, where the module's own `require` is a wrapper parameter that no
      * property of `globalThis` reaches.
      */
    private def bareHostGlobalReads(moduleSet: org.scalajs.linker.standard.ModuleSet): Seq[String] = {
        import org.scalajs.ir.Traversers.Traverser
        import org.scalajs.ir.Trees

        val classes   = moduleSet.modules.flatMap(_.classDefs) ++ moduleSet.abstractClasses
        val byName    = classes.map(c => c.className -> c).toMap

        def hostGlobal(spec: Option[Trees.JSNativeLoadSpec]): Option[String] = spec.collect {
            case Trees.JSNativeLoadSpec.Global(global, _) if hostGlobals.contains(global) => global
        }

        /** The names `cond` proves declared in each branch of an `if`, as (then, else): a comparison of `typeof name` with a string. A
          * `typeof` that is not `"undefined"` means the name is declared, so a read there cannot throw.
          */
        def declaredBy(cond: Trees.Tree): (Set[String], Set[String]) = {
            // The optimizer casts `typeof name` to the string it is compared with, as the emitter's transient `Cast(expr, type)`:
            // `(typeof name).as![String] === "function"`. That class is internal to the emitter, so it is taken apart as the case class it is.
            def typeOfGlobal(tree: Trees.Tree): Option[String] = tree match {
                case Trees.JSTypeOfGlobalRef(Trees.JSGlobalRef(name)) => Some(name)
                case Trees.AsInstanceOf(expr, _)                      => typeOfGlobal(expr)
                case Trees.Transient(cast: Product) if cast.productPrefix == "Cast" && cast.productArity > 0 =>
                    cast.productElement(0) match {
                        case expr: Trees.Tree => typeOfGlobal(expr)
                        case _                => None
                    }
                case _ => None
            }
            def compare(op: Int, lhs: Trees.Tree, rhs: Trees.Tree, eq: Int, ne: Int): (Set[String], Set[String]) =
                (typeOfGlobal(lhs), rhs) match {
                    case (Some(name), Trees.StringLiteral(value)) =>
                        val declaredWhenEqual = value != "undefined"
                        if (op == eq) { if (declaredWhenEqual) (Set(name), Set.empty) else (Set.empty, Set(name)) }
                        else if (op == ne) { if (declaredWhenEqual) (Set.empty, Set(name)) else (Set(name), Set.empty) }
                        else (Set.empty, Set.empty)
                    case (None, _) if lhs.isInstanceOf[Trees.StringLiteral] && typeOfGlobal(rhs).isDefined => compare(op, rhs, lhs, eq, ne)
                    case _                                                                                  => (Set.empty, Set.empty)
                }
            cond match {
                case Trees.BinaryOp(op, lhs, rhs)   => compare(op, lhs, rhs, Trees.BinaryOp.===, Trees.BinaryOp.!==)
                case Trees.JSBinaryOp(op, lhs, rhs) => compare(op, lhs, rhs, Trees.JSBinaryOp.===, Trees.JSBinaryOp.!==)
                case _                              => (Set.empty, Set.empty)
            }
        }

        val reads = scala.collection.mutable.LinkedHashSet.empty[String]
        moduleSet.modules.flatMap(_.classDefs).foreach { cls =>
            var member   = ""
            var declared = Set.empty[String]
            def record(global: String): Unit = if (!declared.contains(global)) reads += s"'$global' in ${cls.fullName}.$member"
            val traverser = new Traverser {
                private def within(names: Set[String], tree: Trees.Tree): Unit = {
                    val outer = declared
                    declared = outer ++ names
                    try traverse(tree)
                    finally declared = outer
                }
                override def traverse(tree: Trees.Tree): Unit = tree match {
                    case _: Trees.JSTypeOfGlobalRef => ()
                    // A read under `typeof name === "function"` (the CommonJS wrapper's `require`, `PlatformJs.moduleRequire`) cannot throw.
                    case Trees.If(cond, thenp, elsep) =>
                        val (inThen, inElse) = declaredBy(cond)
                        traverse(cond)
                        within(inThen, thenp)
                        within(inElse, elsep)
                    case Trees.JSGlobalRef(name) if hostGlobals.contains(name) => record(name)
                    case Trees.LoadJSModule(className) =>
                        hostGlobal(byName.get(className).flatMap(_.jsNativeLoadSpec)).foreach(record)
                    case Trees.LoadJSConstructor(className) =>
                        hostGlobal(byName.get(className).flatMap(_.jsNativeLoadSpec)).foreach(record)
                    case Trees.SelectJSNativeMember(className, name) =>
                        val spec = byName.get(className).flatMap(_.jsNativeMembers.find(_.name.name == name.name)).map(_.jsNativeLoadSpec)
                        hostGlobal(spec).foreach(record)
                    case _ => super.traverse(tree)
                }
            }
            cls.methods.foreach { method =>
                member = method.methodName.simpleName.nameString
                traverser.traverseMethodDef(method)
            }
            member = "<constructor>"
            cls.jsConstructorDef.foreach(traverser.traverseJSConstructorDef)
            member = "<exported member>"
            cls.exportedMembers.foreach(traverser.traverseJSMethodPropDef)
        }
        reads.toSeq
    }

    /** The key the initial-load ceiling of a platform is filed under in `ceilings.txt`. */
    private def initialKey(platform: String): String = s"$platform-initial"

    private def ceilingFailure(
        ceilings: Map[(String, String), Long],
        platform: String,
        key: String,
        program: String,
        size: Long
    ): Seq[String] =
        ceilings.get((key, program)) match {
            case None                => Seq(s"$platform $program: no '$key' ceiling in kyo-link-check/ceilings.txt")
            case Some(c) if size > c => Seq(f"$platform $program: $size%,d bytes ($key) exceeds the ceiling of $c%,d")
            case Some(_)             => Nil
        }

    /** Every file an application ships from `dir`: source maps are a debugging aid it does not. */
    private def outputFiles(dir: File): Seq[File] =
        Option(dir.listFiles).toSeq.flatten.filter(f => f.isFile && !f.getName.endsWith(".map"))

    /** What a host fetches before any code runs. On JS, `main.mjs` and every output file it reaches through static imports, transitively;
      * a file reached only through a dynamic `import()` is left out, since a host fetches it only when the code importing it runs. A WasmGC
      * link is a single module, so its initial load is the whole output.
      */
    private def initialLoad(files: Seq[File], platform: String): Seq[File] =
        if (platform != "JS") files
        else {
            val byName = files.map(f => f.getName -> f).toMap
            def imports(f: File): Seq[File] =
                if (!f.getName.endsWith(".mjs")) Nil
                else staticRelativeImport.findAllMatchIn(read(f)).map(_.group(1)).toSeq.distinct.flatMap(byName.get)
            @scala.annotation.tailrec
            def walk(pending: List[File], seen: Set[File]): Set[File] = pending match {
                case Nil => seen
                case file :: rest =>
                    val next = imports(file).filterNot(seen.contains)
                    walk(next.toList ++ rest, seen ++ next)
            }
            byName.get("main.mjs").fold(Seq.empty[File])(main => walk(List(main), Set(main)).toSeq.sortBy(_.getName))
        }

    /** For a program whose network stack splits by host, the two runs that prove a host never fetches the other side's chunks: a copy of
      * the output with every Node-side chunk deleted, run as a page (no `process` global), and a copy with every page-side chunk deleted,
      * run under plain node. A chunk in the initial load is never deleted, since a host fetches that regardless; a backend found there is
      * reported by the initial-load check instead. When there is no chunk to delete there is nothing to prove, so there is no run.
      */
    private def hostChunkRuns(
        program: Program,
        outDir: File,
        platform: String,
        files: Seq[File],
        initial: Seq[File]
    ): Seq[(String, Regex, Either[String, Seq[String]])] =
        if (!program.hostChunks || platform != "JS") Nil
        else {
            def withoutChunks(host: String, markers: Seq[String], expected: Regex, withoutProcess: Boolean) = {
                val deleted = files.filterNot(initial.contains).filter(f => markers.exists(m => contains(f, quoted(m))))
                if (deleted.isEmpty) Nil
                else {
                    val copy = outDir.getParentFile / s"${outDir.getName}-$host"
                    IO.delete(copy)
                    IO.copyDirectory(outDir, copy)
                    deleted.foreach(f => IO.delete(copy / f.getName))
                    val names = deleted.map(_.getName).mkString(", ")
                    Seq((s"as a $host with $names deleted", expected, runNode(copy, platform, withoutProcess)))
                }
            }
            withoutChunks("page", nodeBackend, program.withoutProcess, withoutProcess = true) ++
                withoutChunks("node-program", pageBackend, program.lastLine, withoutProcess = false)
        }

    private def quoted(name: String): String = "\"" + name + "\""

    private def read(file: File): String = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

    /** The bytes `files` take through `gzip -9`, concatenated, which is what a wire carries for them. */
    private def gzipSize(files: Seq[File]): Long = {
        val bytes = new java.io.ByteArrayOutputStream()
        val gzip = new java.util.zip.GZIPOutputStream(bytes) {
            `def`.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
        }
        files.foreach(f => gzip.write(Files.readAllBytes(f.toPath)))
        gzip.close()
        bytes.size.toLong
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

    /** Runs `entry` (an ES module's `main.mjs` by default) with plain node from its own directory, directly or, for an ES module, through
      * [[withoutProcessLauncher]], and returns the lines it printed. The launcher is written beside the output, never into it, so it is not
      * counted in the size.
      */
    private def runNode(outDir: File, platform: String, withoutProcess: Boolean, entry: String = "main.mjs"): Either[String, Seq[String]] = {
        val main = outDir / entry
        if (!main.exists) Left(s"no $entry in $outDir")
        else {
            val suffix = if (withoutProcess) "-no-process" else ""
            val launched =
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
            val process = new ProcessBuilder((Seq("node") ++ flags :+ launched)*)
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

    /** Runs `command` from `dir` with its output in `outFile`, and returns its exit code and lines, or why it did not finish. */
    private def runProcess(command: Seq[String], dir: File, timeoutSeconds: Int, outFile: File): Either[String, (Int, Seq[String])] = {
        IO.createDirectory(outFile.getParentFile)
        val process = new ProcessBuilder(command*)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(outFile)
            .start()
        if (!process.waitFor(timeoutSeconds.toLong, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Left(s"${command.head} did not exit within $timeoutSeconds seconds")
        } else Right((process.exitValue, IO.readLines(outFile)))
    }

    /** What the bundler check needs: its directory, with the pinned bundlers installed, and the Chrome the browser test rows run. */
    private final case class BundlerTools(dir: File, chrome: String, logs: File)

    private def bundlerTools(state: State): Either[String, BundlerTools] = {
        val extracted = Project.extract(state)
        val base      = extracted.get(LocalRootProject / baseDirectory)
        val dir       = base / "kyo-link-check" / "bundlers"
        val browser   = LocalProject("kyo-link-check-browser")
        val logs      = extracted.get(browser / target) / "link-check"
        val install = runProcess(Seq("npm", "install", "--no-audit", "--no-fund", "--no-package-lock"), dir, 600, logs / "npm-install.out")
        install match {
            case Left(err)                   => Left(s"installing the bundlers: $err")
            case Right((code, lines)) if code != 0 => Left(s"installing the bundlers exited with $code: ${lines.takeRight(5).mkString(" | ")}")
            case Right(_) =>
                val (_, classpath) = extracted.runTask(browser / Compile / fullClasspath, state)
                val command = Seq(
                    "java",
                    "-cp",
                    classpath.map(_.data.getAbsolutePath).mkString(File.pathSeparator),
                    "linkcheck.ChromeExecutable",
                    KyoJsRows.chromeVersion(base)
                )
                runProcess(command, base, 900, logs / "chrome-executable.out") match {
                    case Left(err) => Left(s"resolving Chrome: $err")
                    case Right((code, lines)) =>
                        lines.reverse.find(_.trim.nonEmpty).map(_.trim).filter(p => code == 0 && new File(p).isFile) match {
                            case Some(chrome) => Right(BundlerTools(dir, chrome, logs))
                            case None         => Left(s"resolving Chrome exited with $code: ${lines.takeRight(5).mkString(" | ")}")
                        }
                }
        }
    }

    /** Builds the linked output with vite, webpack and rollup and serves each build to Chrome as a page (`kyo-link-check/bundlers/check.mjs`),
      * returning the line printed for each build. A line starting with `FAIL` is a failure: the page did not print the line expected of a
      * page, threw, or fetched an output file carrying a Node backend.
      */
    private def bundlerRuns(program: Program, outDir: File, tools: Either[String, BundlerTools]): Either[String, Seq[String]] =
        tools.flatMap { t =>
            val command = Seq("node", (t.dir / "check.mjs").getAbsolutePath, outDir.getAbsolutePath, t.chrome, program.withoutProcess.regex) ++
                nodeBackend
            runProcess(command, t.dir, 1800, t.logs / s"${program.name}-bundlers.out").flatMap { case (code, lines) =>
                val results = lines.filter(_.matches("^(ok|note|FAIL) .*"))
                if (code != 0 && !results.exists(_.startsWith("FAIL")))
                    Left(s"the bundler check exited with $code: ${lines.takeRight(5).mkString(" | ")}")
                else Right(results)
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
