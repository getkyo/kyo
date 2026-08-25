package io.getkyo.compat

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._ // brings %%%
import sbt._
import sbt.Keys._
import sbt.internal.ProjectMatrix
import sbtprojectmatrix.ProjectMatrixKeys
import sbtprojectmatrix.ReflectionUtil

/** Internal helpers + per-matrix metadata for the [[CompatPlugin]] extension method. The user-facing `compatLibrary` extension is defined
  * in [[CompatPlugin.autoImport]].
  *
  * sbt-projectmatrix's `jvmPlatform` / `jsPlatform` / `nativePlatform` builders are used as the row-creation primitives — they handle
  * plugin enablement (ScalaJSPlugin / ScalaNativePlugin) for us. We pass the [[CompatBackendAxis]] as an extra `axisValues` so each row
  * carries its backend identity, then add a per-row `process` callback that injects:
  *
  *   - `moduleName := name.value + backend.directorySuffix`
  *   - `libraryDependencies +=` the backend's artifact coordinates (`organization %%% artifactName % version`, defaulting to `io.getkyo %%% s"kyo-compat-<name>" % compatKyoVersion.value`; suppressed when the backend is locally bound)
  *   - shared/per-backend `unmanagedSourceDirectories`
  *
  * `dependsOn(other: ProjectMatrix)` between two compat libraries works out-of-the-box — `WeakAxis` semantics on [[CompatBackendAxis]]
  * route each row to the matching-backend row in the dependee.
  */
private[compat] object CompatLibrary {

    // ---- Per-matrix metadata -------------------------------------------------
    //
    // We keep a global registry keyed by matrix id (stable across `.copy(...)`
    // calls — ProjectMatrix's `copy` preserves `id`). Every `.compatLibrary(...)`
    // call seeds this; named accessors and `bindLocally` read/mutate it.
    //
    // The registry is mutable because `bindLocally` is a setter that runs at
    // build evaluation time, BEFORE the matrix's `lazy val resolvedMappings`
    // forces row materialization. The customRow `process` callback is a
    // closure that re-reads from the registry at materialization time.
    final case class Meta(
        backends: Seq[CompatBackendAxis],
        platforms: Seq[VirtualAxis.PlatformAxis],
        scalaVersions: Seq[String],
        bindings: Map[String, ProjectReference], // backend.name -> local project ref
        jvmExtras: Seq[Setting[?]] = Nil,
        jsExtras: Seq[Setting[?]] = Nil,
        nativeExtras: Seq[Setting[?]] = Nil,
        conformance: Option[String] = None // Some(scalatestVersion) when .compatConformance is enabled
    )

    private val registry: scala.collection.mutable.Map[String, Meta] =
        scala.collection.mutable.Map.empty

    def register(id: String, meta: Meta): Unit = registry.synchronized {
        registry(id) = meta
    }

    def metaOf(id: String): Option[Meta] = registry.synchronized {
        registry.get(id)
    }

    def updateBinding(id: String, backend: CompatBackendAxis, local: ProjectReference): Unit =
        registry.synchronized {
            val cur = registry.getOrElse(
                id,
                sys.error(s"compatLibrary: matrix '$id' was not registered before bindLocally")
            )
            registry(id) = cur.copy(bindings = cur.bindings + (backend.name -> local))
        }

    def enableConformance(id: String, scalatestVersion: String): Unit =
        registry.synchronized {
            val cur = registry.getOrElse(
                id,
                sys.error(s"compatLibrary: matrix '$id' was not registered before compatConformance")
            )
            registry(id) = cur.copy(conformance = Some(scalatestVersion))
        }

    /** Append per-platform extra settings to the matrix's Meta entry. The extras are read at row-materialization time by `addRows`'s
      * process closure so calls can come AFTER `.compatLibrary(...)`.
      */
    def appendPlatformExtras(
        id: String,
        platform: VirtualAxis.PlatformAxis,
        extras: Seq[Setting[?]]
    ): Unit =
        registry.synchronized {
            val cur = registry.getOrElse(
                id,
                sys.error(s"compatLibrary: matrix '$id' was not registered before per-platform settings")
            )
            val updated = platform match {
                case VirtualAxis.jvm    => cur.copy(jvmExtras = cur.jvmExtras ++ extras)
                case VirtualAxis.js     => cur.copy(jsExtras = cur.jsExtras ++ extras)
                case VirtualAxis.native => cur.copy(nativeExtras = cur.nativeExtras ++ extras)
                case other              => sys.error(s"compatLibrary: unsupported PlatformAxis $other")
            }
            registry(id) = updated
        }

    /** Add rows for every (backend, platform, scalaVersion) triple to `m`, skipping cells the backend doesn't support. ANY user-requested
      * backend whose `supportedPlatforms` is disjoint from the requested `platforms` raises a build-time error (matching the
      * `empty-intersection-fail` scripted test). Future is exempt because it's always implicit — its presence shouldn't mask a hard
      * mismatch between an explicitly-requested backend and the requested platforms.
      */
    def addRows(m: ProjectMatrix, meta: Meta): ProjectMatrix = {
        val matrixId       = m.id
        val platformValues = meta.platforms.map(_.value).toSet

        // Per-backend empty-intersection check — skip Future (implicit anchor).
        meta.backends.foreach { b =>
            if (b.name != "future" && b.supportedPlatforms.intersect(platformValues).isEmpty)
                sys.error(
                    s"compatLibrary: backend ${b.idSuffix} supports " +
                        s"${b.supportedPlatforms.toSeq.sorted.mkString(", ")} but the " +
                        s"compatLibrary requested platforms ${meta.platforms.map(_.value).mkString(", ")}. " +
                        "Empty intersection. Either drop the backend from the compatLibrary(...) " +
                        "extras list, or widen the requested platforms list."
                )
        }

        val combos: Seq[(CompatBackendAxis, VirtualAxis.PlatformAxis)] =
            for {
                b <- meta.backends
                p <- meta.platforms
                if b.supportedPlatforms.contains(p.value)
            } yield (b, p)

        // Use ProjectMatrix's basedir (set by `(projectMatrix in file(...))`)
        // as the source-tree root so consumers can keep <base>/shared/src/...
        // and <base>/<backend>/<platform>/src/... layouts.
        val sourceBase: File = m.base

        combos.foldLeft(m) { case (acc, (backend, platform)) =>
            val backendDir      = backend.name   // e.g. "future"
            val platformDir     = platform.value // "jvm" | "js" | "native"
            val sharedMain      = sourceBase / "shared" / "src" / "main" / "scala"
            val sharedTest      = sourceBase / "shared" / "src" / "test" / "scala"
            val sharedMainR     = sourceBase / "shared" / "src" / "main" / "resources"
            val sharedTestR     = sourceBase / "shared" / "src" / "test" / "resources"
            val perBackendMain  = sourceBase / backendDir / "src" / "main" / "scala"
            val perBackendTest  = sourceBase / backendDir / "src" / "test" / "scala"
            val perBackendMainR = sourceBase / backendDir / "src" / "main" / "resources"
            val perBackendTestR = sourceBase / backendDir / "src" / "test" / "resources"
            val backendMain     = sourceBase / backendDir / platformDir / "src" / "main" / "scala"
            val backendTest     = sourceBase / backendDir / platformDir / "src" / "test" / "scala"
            val backendMainR    = sourceBase / backendDir / platformDir / "src" / "main" / "resources"
            val backendTestR    = sourceBase / backendDir / platformDir / "src" / "test" / "resources"
            val backendBase     = sourceBase / backendDir / platformDir

            // Plugin enabler. JS/Native platforms must enable the corresponding
            // sbt plugin; we do the same reflection lookup pmx itself does in
            // its private jsPlatform/nativePlatform helpers (we sidestep those
            // because they hard-code `scalaABIVersion(sv)` whose value
            // collapses 3.3.4 and 3.4.0 to "3", colliding target dirs and
            // project ids on multi-scala matrices).
            val enablePlatformPlugin: Project => Project = platform match {
                case VirtualAxis.jvm => identity
                case VirtualAxis.js =>
                    p =>
                        p.enablePlugins(
                            ReflectionUtil.getSingletonObject[AutoPlugin](
                                getClass.getClassLoader,
                                "org.scalajs.sbtplugin.ScalaJSPlugin$"
                            ).getOrElse(sys.error(
                                "Scala.js plugin was not found. Add the sbt-scalajs plugin into project/plugins.sbt:\n" +
                                    "  addSbtPlugin(\"org.scala-js\" % \"sbt-scalajs\" % \"x.y.z\")"
                            ))
                        )
                case VirtualAxis.native =>
                    p =>
                        p.enablePlugins(
                            ReflectionUtil.getSingletonObject[AutoPlugin](
                                getClass.getClassLoader,
                                "scala.scalanative.sbtplugin.ScalaNativePlugin$"
                            ).getOrElse(sys.error(
                                "Scala Native plugin was not found. Add the sbt-scala-native plugin into project/plugins.sbt:\n" +
                                    "  addSbtPlugin(\"org.scala-native\" % \"sbt-scala-native\" % \"x.y.z\")"
                            ))
                        )
                case other => sys.error(s"compatLibrary: unsupported PlatformAxis $other")
            }

            val process: Project => Project = (proj: Project) => {
                // A backend declared with CompatBackendAxis.local carries no coordinates and must be
                // bound to a vendored project. Fail clearly here (re-read at materialization, so a
                // bindLocally placed after compatLibrary is seen) rather than later on a Maven miss.
                if (backend.local && !metaOf(matrixId).exists(_.bindings.contains(backend.name)))
                    sys.error(
                        s"compatLibrary: backend '${backend.name}' was declared with CompatBackendAxis.local " +
                            "but not bound; add .bindLocally(<axis>, <project>) for it."
                    )
                val withPlugin = enablePlatformPlugin(proj)
                val withDeps = withPlugin.settings(
                    moduleName := name.value + backend.directorySuffix,
                    baseDirectory := {
                        val dir = IO.resolve((LocalRootProject / baseDirectory).value, backendBase)
                        IO.createDirectory(dir)
                        dir
                    },
                    Compile / unmanagedSourceDirectories ++= {
                        val root = (LocalRootProject / baseDirectory).value
                        Seq(sharedMain, perBackendMain, backendMain).map(IO.resolve(root, _))
                    },
                    Test / unmanagedSourceDirectories ++= {
                        val root = (LocalRootProject / baseDirectory).value
                        Seq(sharedTest, perBackendTest, backendTest).map(IO.resolve(root, _))
                    },
                    Compile / unmanagedResourceDirectories ++= {
                        val root = (LocalRootProject / baseDirectory).value
                        Seq(sharedMainR, perBackendMainR, backendMainR).map(IO.resolve(root, _))
                    },
                    Test / unmanagedResourceDirectories ++= {
                        val root = (LocalRootProject / baseDirectory).value
                        Seq(sharedTestR, perBackendTestR, backendTestR).map(IO.resolve(root, _))
                    },
                    libraryDependencies ++= {
                        val isBound = metaOf(matrixId).exists(_.bindings.contains(backend.name))
                        if (isBound) Seq.empty
                        else Seq(
                            backend.organization %%% backend.resolvedArtifactName %
                                backend.version.getOrElse(CompatPlugin.autoImport.compatKyoVersion.value)
                        )
                    }
                )
                val withLocal = metaOf(matrixId).flatMap(_.bindings.get(backend.name)) match {
                    case Some(local) => withDeps.dependsOn(ClasspathDependency(local, None))
                    case None        => withDeps
                }
                // Apply per-platform extras LAST so they win over both the
                // plugin-injected defaults above and the receiver's universal
                // `.settings(...)` (which sbt-projectmatrix applies before
                // customRow process closures run). Re-read at materialization
                // time so `.jvmSettings(...)` calls placed AFTER
                // `.compatLibrary(...)` are picked up.
                val currentMeta = metaOf(matrixId)
                val platformExtras: Seq[Setting[?]] = currentMeta.map { meta =>
                    platform match {
                        case VirtualAxis.jvm    => meta.jvmExtras
                        case VirtualAxis.js     => meta.jsExtras
                        case VirtualAxis.native => meta.nativeExtras
                        case _                  => Nil
                    }
                }.getOrElse(Nil)
                // Conformance suite wiring, re-read at materialization time so a
                // `.compatConformance(...)` call placed AFTER `.compatLibrary(...)` is
                // picked up (same pattern as bindLocally and the per-platform extras).
                val conformanceExtras: Seq[Setting[?]] =
                    currentMeta.flatMap(_.conformance) match {
                        case Some(scalatestVersion) => conformanceSettings(platform.value, scalatestVersion)
                        case None                   => Nil
                    }
                val extras = platformExtras ++ conformanceExtras
                if (extras.isEmpty) withLocal
                else withLocal.settings(extras: _*)
            }

            // Add one row per scala version, tagging the row with a
            // full-version `scalaVersionAxis(sv, sv)` instead of the default
            // `scalaABIVersion(sv)`. Full-version directorySuffix keeps target
            // dirs ("target/<backend>-<platform>-<sv>") and project ids
            // ("...3_3_4" / "...3_4_0") distinct across scala versions in a
            // multi-scala matrix.
            meta.scalaVersions.foldLeft(acc) { (m2, sv) =>
                val axes: Seq[VirtualAxis] =
                    Seq(platform, backend, VirtualAxis.scalaVersionAxis(sv, sv))
                m2.customRow(autoScalaLibrary = true, axisValues = axes, process = process)
            }
        }
    }

    /** Test-scope settings that wire the plugin-bundled conformance suite into one generated row: extract the suite for `platformValue`
      * (the `shared` buckets plus this platform's bucket) into `Test / sourceManaged`, add scalatest, and set `-Xmax-inlines` (the suite
      * relies on it). Applied per row by the `process` closure when `.compatConformance` is enabled.
      */
    private def conformanceSettings(platformValue: String, scalatestVersion: String): Seq[Setting[?]] =
        Seq(
            libraryDependencies += "org.scalatest" %%% "scalatest" % scalatestVersion % Test,
            Test / scalacOptions += "-Xmax-inlines:1024",
            Test / sourceGenerators += Def.task {
                CompatConformance.extract(
                    (Test / sourceManaged).value / "kyo-compat-testkit",
                    platformValue
                )
            }.taskValue
        )
}

/** A virtual axis representing one kyo-compat backend. Implements `VirtualAxis.WeakAxis` — at most one backend axis can be on a row, and
  * `dependsOn` between two compat-library matrices wires each row to the matching-backend row in the dependee. Equality is by `name`, so
  * any two `CompatBackendAxis("kyo", ...)` instances are interchangeable.
  *
  * Suffix order is `40` so the backend lands BEFORE the platform (`80`) and scala (`100`) in generated project ids and source-set suffixes:
  * `myLibFuture`, `myLibFutureJS`, etc.
  *
  * The `organization`, `artifactName`, and `version` fields carry the Maven coordinates of the backend's runtime artifact. The five
  * built-in backends leave them at their defaults (`io.getkyo` %%% `kyo-compat-<name>` % `compatKyoVersion.value`). A backend published
  * OUTSIDE the kyo repo sets them to its own coordinates, so its generated rows pull that artifact
  * instead of a nonexistent `io.getkyo:kyo-compat-<name>`. Prefer [[CompatBackendAxis.external]] to declare one. The backend `name` must
  * not collide with a built-in, since equality (and matrix de-duplication) is by `name`.
  */
final case class CompatBackendAxis(
    name: String,                        // e.g. "future"; default artifact name `kyo-compat-future`
    idSuffix: String,                    // e.g. "Future"; appended to project id
    directorySuffix: String,             // e.g. "-future"; appended to moduleName + source dir
    supportedPlatforms: Set[String],     // values from PlatformAxis: "jvm" | "js" | "native"
    organization: String = "io.getkyo",  // Maven org of the backend artifact
    artifactName: Option[String] = None, // artifact name; defaults to s"kyo-compat-$name"
    version: Option[String] = None,      // artifact version; defaults to compatKyoVersion.value
    local: Boolean = false               // declared via `local(...)`: vendored, must be bound with bindLocally
) extends VirtualAxis.WeakAxis {
    override val suffixOrder: Int = 40
    override def equals(obj: Any): Boolean = obj match {
        case that: CompatBackendAxis => this.name == that.name
        case _                       => false
    }
    override def hashCode(): Int = name.hashCode

    /** Artifact name of the row dependency: `artifactName` when set, else `kyo-compat-<name>`. */
    private[compat] def resolvedArtifactName: String = artifactName.getOrElse(s"kyo-compat-$name")
}

object CompatBackendAxis {

    // Names of the five built-in backends (mirrors the axes in CompatPlugin.autoImport).
    // An added backend must not reuse one, because equality and the named accessors
    // (.future / .kyo / ...) match by name and would otherwise resolve to the added rows.
    private[compat] val builtinNames: Set[String] =
        Set("future", "kyo", "zio", "ox", "twitter-future")

    private def requireDistinctName(name: String): Unit =
        require(
            !builtinNames.contains(name),
            s"compatLibrary: backend name '$name' collides with a built-in backend " +
                s"(${builtinNames.toSeq.sorted.mkString(", ")}); pick a distinct name, since equality and the " +
                "named accessors match by name."
        )

    /** Declares a backend whose runtime artifact is published outside the kyo repo, with explicit Maven coordinates. The generated rows
      * for this backend pull `organization %%% artifactName % version` instead of the default `io.getkyo:kyo-compat-<name>`. Use this when
      * the binding is published as an artifact; use [[local]] to vendor the binding as a project in your own build instead.
      *
      * @param name
      *   backend identity (also the source-dir segment `my-lib/<name>/...`); must not collide with a built-in backend
      * @param idSuffix
      *   appended to generated project ids (e.g. `Acme` yields `myLibAcme`)
      * @param directorySuffix
      *   appended to `moduleName` and the module source dir (e.g. `-acme`)
      * @param supportedPlatforms
      *   platform values the artifact is cross-published for (`"jvm"` / `"js"` / `"native"`)
      * @param organization
      *   Maven organization of the published artifact
      * @param artifactName
      *   Maven artifact name of the published artifact
      * @param version
      *   Maven version of the published artifact
      */
    def external(
        name: String,
        idSuffix: String,
        directorySuffix: String,
        supportedPlatforms: Set[String],
        organization: String,
        artifactName: String,
        version: String
    ): CompatBackendAxis = {
        requireDistinctName(name)
        CompatBackendAxis(
            name,
            idSuffix,
            directorySuffix,
            supportedPlatforms,
            organization,
            Some(artifactName),
            Some(version)
        )
    }

    /** Declares a backend whose binding is vendored as a project in the consumer's own build rather than resolved from Maven. The axis
      * carries no coordinates; it MUST be paired with `bindLocally(axis, <project>)`, which routes each row to that project via
      * `dependsOn`. A row for a `local` backend that was not bound fails at build load with a clear error. Use [[external]] instead when
      * the binding is a published artifact.
      *
      * @param name
      *   backend identity (also the source-dir segment `my-lib/<name>/...`); must not collide with a built-in backend
      * @param idSuffix
      *   appended to generated project ids (e.g. `Acme` yields `myLibAcme`)
      * @param directorySuffix
      *   appended to `moduleName` and the module source dir (e.g. `-acme`)
      * @param supportedPlatforms
      *   platform values the vendored binding supports (`"jvm"` / `"js"` / `"native"`)
      */
    def local(
        name: String,
        idSuffix: String,
        directorySuffix: String,
        supportedPlatforms: Set[String]
    ): CompatBackendAxis = {
        requireDistinctName(name)
        CompatBackendAxis(name, idSuffix, directorySuffix, supportedPlatforms, local = true)
    }
}

/** Raised by named accessors when the user accesses a backend that wasn't passed to `compatLibrary(...)`.
  */
final class NoSuchBackendException(
    val requested: CompatBackendAxis,
    val available: Set[CompatBackendAxis]
) extends RuntimeException(
        s"compatLibrary: backend ${requested.idSuffix} was not opted in. " +
            s"Available backends: ${available.toSeq.map(_.idSuffix).sorted.mkString(", ")}. " +
            "Add it to the compatLibrary(...) extras list, or use .get(backend) for a safe lookup."
    )

/** Per-backend view returned by named accessors (`.future`, `.kyo`, ...). Mimics sbt-crossproject's `.jvm` / `.js` / `.native` shape so
  * consumers can reach a specific (backend, platform) cell via `myLib.future.jvm: Project`.
  */
final class CompatBackendProjects private[compat] (
    private val matrix: ProjectMatrix,
    private val backend: CompatBackendAxis
) {
    private def find(platform: VirtualAxis.PlatformAxis): Project =
        matrix.filterProjects(Seq(backend, platform)).headOption.getOrElse(
            sys.error(
                s"compatLibrary: backend ${backend.idSuffix} has no row for platform ${platform.value}. " +
                    "Either widen the requested platforms or pick a different backend."
            )
        )
    def jvm: Project    = find(VirtualAxis.jvm)
    def js: Project     = find(VirtualAxis.js)
    def native: Project = find(VirtualAxis.native)
}
