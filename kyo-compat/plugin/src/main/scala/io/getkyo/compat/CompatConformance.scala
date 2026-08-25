package io.getkyo.compat

import java.io.File
import sbt._

/** Extracts the cross-binding conformance suite that [[CompatPlugin]] bundles into its own jar (see the `resourceGenerators` step in the
  * plugin's build).
  *
  * The bundle is the canonical `kyo-compat/test` + `kyo-compat/test-streams` sources copied verbatim, plus an `INDEX`. Each INDEX line is
  * `"<scope>\t<relpath>"`, where `<scope>` is `"<suite>-<bucket>"` (suite `test` / `streams`; bucket `shared` / `jvm` / `js` / `native`).
  *
  * [[extract]] writes the sources for one platform (the `shared` buckets always, plus the row's own platform bucket) into the row's
  * `Test / sourceManaged`, so the suite compiles against whichever binding the row provides. `.compatConformance` wires this per row.
  */
private[compat] object CompatConformance {

    private val ResourceRoot = "kyo-compat-testkit"

    /** INDEX entries as `(scope, relpath)`, read from the plugin jar on the classpath. */
    private def index(): Seq[(String, String)] = {
        val stream = getClass.getClassLoader.getResourceAsStream(s"$ResourceRoot/INDEX")
        if (stream == null)
            sys.error(
                s"kyo-compat: conformance sources are not on the plugin classpath (missing $ResourceRoot/INDEX). " +
                    "This is a packaging bug in kyo-compat-plugin."
            )
        try
            scala.io.Source.fromInputStream(stream, "UTF-8").getLines()
                .filter(_.nonEmpty)
                .map { line =>
                    line.split("\t", 2) match {
                        case Array(scope, rel) => (scope, rel)
                        case _                 => sys.error(s"kyo-compat: malformed conformance INDEX line: '$line'")
                    }
                }
                .toVector
        finally stream.close()
    }

    /** A scope `"<suite>-<bucket>"` is in scope for `platform` when its bucket is `shared` or equals the platform. */
    private def inScope(scope: String, platform: String): Boolean =
        scope.split("-", 2) match {
            case Array(_, bucket) => bucket == "shared" || bucket == platform
            case _                => false
        }

    /** Writes the conformance sources for `platform` under `outDir` (scope prefix dropped, the `kyo/compat/...` path preserved) and returns
      * the written files, for a `Test / sourceGenerators` entry.
      */
    def extract(outDir: File, platform: String): Seq[File] = {
        val inScopeEntries = index().filter { case (scope, _) => inScope(scope, platform) }
        // The scope prefix is dropped in the destination path, so two in-scope buckets
        // contributing the same relpath would silently clobber. No collision exists today;
        // fail loudly if one is ever introduced.
        val collisions = inScopeEntries.groupBy(_._2).filter(_._2.size > 1)
        if (collisions.nonEmpty)
            sys.error(
                "kyo-compat: conformance sources collide on " +
                    collisions.map { case (rel, es) => s"'$rel' (scopes ${es.map(_._1).sorted.mkString(", ")})" }
                        .mkString("; ")
            )
        inScopeEntries.map {
            case (scope, rel) =>
                val resourcePath = s"$ResourceRoot/$scope/$rel"
                val stream       = getClass.getClassLoader.getResourceAsStream(resourcePath)
                if (stream == null) sys.error(s"kyo-compat: missing bundled conformance resource '$resourcePath'")
                val dest = outDir / rel
                try IO.transfer(stream, dest)
                finally stream.close()
                dest
        }
    }
}
