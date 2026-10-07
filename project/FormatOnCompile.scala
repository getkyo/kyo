import org.scalafmt.sbt.ConcurrentRestrictionTags
import org.scalafmt.sbt.ScalafmtPlugin
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.*
import sbt.*
import sbt.Keys.*

/** Formats every project's sources before they compile, outside CI, and fails the compile on a source scalafmt rejects.
  *
  * sbt-scalafmt's own `scalafmtOnCompile` formats without failing: a source scalafmt cannot format only logs that it
  * failed for N sources, the compile goes on, and the plugin's tracker records the file as processed, so neither a
  * later compile nor `scalafmtCheck` looks at it again until it changes. A source the configured dialect cannot parse (Scala 3
  * syntax in a module `.scalafmt.conf` formats as Scala 2) then compiles and passes the local check, and fails only CI's
  * `scalafmtAll`. Formatting through `scalafmt` throws instead, and a run that throws records nothing.
  *
  * One scalafmt task runs at a time ([[restriction]]): a module's platform projects share source directories, so two of them
  * formatting the same file at once let one read the other's half-written output and reject it.
  *
  * CI formats nothing on compile: its scalafmt workflow (`scalafmtAll` and a dirty-tree check) is the enforcement there.
  */
object FormatOnCompile extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = ScalafmtPlugin

    /** The build sets `Global / concurrentRestrictions` wholesale, after every plugin's `globalSettings`, so it lists this rather than
      * the plugin appending it.
      */
    val restriction: Tags.Rule = Tags.limit(ConcurrentRestrictionTags.Scalafmt, 1)

    override def buildSettings: Seq[Setting[?]] = Seq(
        scalafmtLogOnEachError := true
    )

    override def projectSettings: Seq[Setting[?]] = Seq(
        scalafmtOnCompile := false,
        Compile / sources := (Compile / sources).dependsOn(formatLocally(Compile)).value,
        Test / sources    := (Test / sources).dependsOn(formatLocally(Test)).value
    )

    private def formatLocally(config: Configuration): Def.Initialize[Task[Unit]] = Def.taskDyn {
        if (insideCI.value) Def.task(()) else Def.task((config / scalafmt).value)
    }
}
