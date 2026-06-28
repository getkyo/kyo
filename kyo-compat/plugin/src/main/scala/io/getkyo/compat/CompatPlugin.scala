package io.getkyo.compat

import sbt._
import sbt.Keys._
import sbt.internal.ProjectMatrix
import sbtprojectmatrix.ProjectMatrixPlugin

/** kyo-compat — generate per-backend cross-platform rows on a `ProjectMatrix` so a single library declaration ships to every kyo-compat
  * backend.
  *
  * The library author writes:
  * {{{
  * import _root_.io.getkyo.compat.given
  * import _root_.io.getkyo.compat.{ KyoLib, ZioLib, CeLib, OxLib }
  * import sbt.VirtualAxis
  *
  * lazy val myLib = (projectMatrix in file("my-lib"))
  *     .compatLibrary(KyoLib, ZioLib, CeLib, OxLib)(
  *         VirtualAxis.jvm, VirtualAxis.js, VirtualAxis.native
  *     )(Seq("3.3.4"))
  *     .settings(/* common settings */)
  * }}}
  *
  * `compatLibrary` adds one `customRow` per (backend × platform-supported-by-backend × scalaVersion). `Future` is always implicit. Cells
  * the backend cannot support (Ce/Native, Ox/JS, Ox/Native) are silently skipped; an empty intersection errors at build-load.
  */
object CompatPlugin extends AutoPlugin {

    // allRequirements so the plugin's compatKyoVersion setting is injected
    // on every project that already has JvmPlugin enabled (which covers
    // every project a compatLibrary matrix generates). Required because
    // the per-row libraryDependencies setting reads `compatKyoVersion.value`.
    override def trigger  = allRequirements
    override def requires = sbt.plugins.JvmPlugin && ProjectMatrixPlugin

    object autoImport {

        /** Version of the `io.getkyo:kyo-compat-<backend>` artifacts that generated rows pull in. Defaults to the plugin's own published
          * version — override per-build to pin a different snapshot.
          */
        val compatKyoVersion = settingKey[String](
            "Version of io.getkyo:kyo-compat-<backend> artifacts pulled into generated rows."
        )

        // Re-export so `CompatBackendAxis` can be referenced as an autoImport
        // type (e.g. from `bindLocally(b: CompatBackendAxis, ...)` signatures
        // in user build.sbt without a full `_root_.io.getkyo.compat.*` import).
        type CompatBackendAxis      = _root_.io.getkyo.compat.CompatBackendAxis
        type NoSuchBackendException = _root_.io.getkyo.compat.NoSuchBackendException

        // Backend axis instances. The `Lib` suffix avoids collisions with
        // `scala.concurrent.Future` and the `kyo` package object.
        val FutureLib: CompatBackendAxis =
            _root_.io.getkyo.compat.CompatBackendAxis("future", "Future", "-future", Set("jvm"))
        val KyoLib: CompatBackendAxis =
            _root_.io.getkyo.compat.CompatBackendAxis("kyo", "Kyo", "-kyo", Set("jvm", "js", "native"))
        val ZioLib: CompatBackendAxis =
            _root_.io.getkyo.compat.CompatBackendAxis("zio", "Zio", "-zio", Set("jvm", "js", "native"))
        val CeLib: CompatBackendAxis =
            _root_.io.getkyo.compat.CompatBackendAxis("ce", "Ce", "-ce", Set("jvm", "js", "native"))
        val OxLib: CompatBackendAxis =
            _root_.io.getkyo.compat.CompatBackendAxis("ox", "Ox", "-ox", Set("jvm"))
        val TwitterFutureLib: CompatBackendAxis =
            _root_.io.getkyo.compat.CompatBackendAxis(
                "twitter-future",
                "TwitterFuture",
                "-twitter-future",
                Set("jvm")
            )

        /** Terminal extension on a `ProjectMatrix`: adds one `customRow` per (backend × platform-supported-by-backend × scalaVersion).
          * Future is always implicit; the `extras` varargs are the *additional* backends to cross-publish to.
          */
        implicit class CompatLibraryOps(val m: ProjectMatrix) extends AnyVal {
            def compatLibrary(
                extras: CompatBackendAxis*
            )(
                platforms: VirtualAxis.PlatformAxis*
            )(
                scalaVersions: Seq[String]
            ): ProjectMatrix = {
                val backends = (FutureLib +: extras).distinct
                val meta = CompatLibrary.Meta(
                    backends = backends,
                    platforms = platforms,
                    scalaVersions = scalaVersions,
                    bindings = Map.empty
                )
                CompatLibrary.register(m.id, meta)
                // Pin defaultAxes only for single-scala matrices so the canonical
                // row drops the JVM + Scala suffixes (the plugin's own choice;
                // the root build's cross-projects carry explicit JVM suffixes). Multi-scala
                // matrices intentionally keep the version suffix on every cell
                // so rows like 3.3.4 and 3.4.0 stay distinguishable.
                // We use the full-version `scalaVersionAxis(sv, sv)` (not
                // `scalaABIVersion`, whose value collapses 3.x to "3" and would
                // make 3.3.4/3.4.0 share the same target dir + project id).
                val pinned = scalaVersions match {
                    case Seq(sv) =>
                        m.defaultAxes(VirtualAxis.jvm, VirtualAxis.scalaVersionAxis(sv, sv))
                    case _ => m
                }
                CompatLibrary.addRows(pinned, meta)
            }

            /** Bind the given backend to a local Project (typically a CrossProject's `.jvm`/`.js`/`.native` accessor) instead of pulling
              * the published `kyo-compat-<id>` artifact. Suppresses the auto-injected `libraryDependencies +=` line for that backend and
              * adds a project-level `dependsOn(local)` to every row of the matrix that carries the bound backend.
              *
              * Must be called BEFORE first access to the matrix's `componentProjects` / `projectRefs` (i.e. before sbt forces row
              * materialization).
              */
            def bindLocally(b: CompatBackendAxis, local: ProjectReference): ProjectMatrix = {
                CompatLibrary.updateBinding(m.id, b, local)
                m
            }

            /** Convenience for binding multiple backends in one call. */
            def bindAllLocally(locals: Map[CompatBackendAxis, ProjectReference]): ProjectMatrix = {
                locals.foreach { case (b, local) => CompatLibrary.updateBinding(m.id, b, local) }
                m
            }

            /** Apply settings to JVM cells only. Mirrors sbt-crossproject's `.jvmSettings(...)`. Per-platform settings are applied AFTER
              * the receiver's universal `.settings(...)`, so per-platform overrides win on conflicts.
              */
            def jvmSettings(extras: Setting[?]*): ProjectMatrix = {
                CompatLibrary.appendPlatformExtras(m.id, VirtualAxis.jvm, extras)
                m
            }

            /** Apply settings to JS cells only. Mirrors sbt-crossproject's `.jsSettings(...)`.
              */
            def jsSettings(extras: Setting[?]*): ProjectMatrix = {
                CompatLibrary.appendPlatformExtras(m.id, VirtualAxis.js, extras)
                m
            }

            /** Apply settings to Native cells only. Mirrors sbt-crossproject's `.nativeSettings(...)`.
              */
            def nativeSettings(extras: Setting[?]*): ProjectMatrix = {
                CompatLibrary.appendPlatformExtras(m.id, VirtualAxis.native, extras)
                m
            }

            /** Cross-backend aggregator. Returns a plain `Project` whose `.aggregate(...)` fans every per-(backend, platform) row of the
              * matrix into one task target. `publish / skip := true` keeps the aggregator out of maven-local.
              */
            def aggregate(id: String): Project = {
                val refs = m.projectRefs
                Project(id, file(s".${id}-aggregate"))
                    .settings(publish / skip := true)
                    .aggregate(refs: _*)
            }

            /** Named accessor: returns a view onto every (Future, *) row. */
            def future: CompatBackendProjects = lookup(FutureLib)

            /** Named accessor: returns a view onto every (Kyo, *) row. */
            def kyo: CompatBackendProjects = lookup(KyoLib)

            /** Named accessor: returns a view onto every (Zio, *) row. */
            def zio: CompatBackendProjects = lookup(ZioLib)

            /** Named accessor: returns a view onto every (Ce, *) row. */
            def ce: CompatBackendProjects = lookup(CeLib)

            /** Named accessor: returns a view onto every (Ox, *) row. */
            def ox: CompatBackendProjects = lookup(OxLib)

            /** Named accessor: returns a view onto every (TwitterFuture, *) row. */
            def twitterFuture: CompatBackendProjects = lookup(TwitterFutureLib)

            /** Safe lookup — `None` if the backend was not opted in. */
            def get(b: CompatBackendAxis): Option[CompatBackendProjects] = {
                val opted = CompatLibrary.metaOf(m.id).map(_.backends.toSet).getOrElse(Set.empty)
                if (opted.contains(b)) Some(new CompatBackendProjects(m, b))
                else None
            }

            private def lookup(b: CompatBackendAxis): CompatBackendProjects = {
                val opted = CompatLibrary.metaOf(m.id).map(_.backends.toSet).getOrElse(Set.empty)
                if (!opted.contains(b)) throw new NoSuchBackendException(b, opted)
                new CompatBackendProjects(m, b)
            }
        }
    }

    import autoImport._

    override lazy val globalSettings: Seq[Setting[?]] = Seq(
        compatKyoVersion := defaultCompatKyoVersion
    )

    // Inject the compatKyoVersion default per-project too, so that a user
    // who scopes `ThisBuild / compatKyoVersion := "..."` overrides it
    // build-wide while individual matrix rows fall back to the global default.
    override lazy val projectSettings: Seq[Setting[?]] = Seq()

    /** Default version pulled from the plugin's own published build version. Library authors targeting a different snapshot can override
      * `compatKyoVersion` in their build.
      */
    private[compat] def defaultCompatKyoVersion: String =
        Option(getClass.getPackage.getImplementationVersion).getOrElse("0.0.0+SNAPSHOT")
}
