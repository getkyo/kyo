import sbt.*
import sbt.Keys.*

/** `publishLocal` without the doc jar, for the scripted suites that publish part of this build to Ivy local before their
  * sub-builds resolve it.
  *
  * A Scala 3 module's doc jar forks scaladoc on every publish, and no sub-build reads it. Dropping the doc artifact from
  * `publishLocal / packagedArtifacts` would still fork it, since computing that map runs every packaging task in it, so the
  * artifacts are rebuilt from the default packaging tasks minus `packageDoc`. The published ivy.xml still declares the
  * doc jar, in the `docs` configuration, which no compile or runtime resolution fetches. Artifacts a project appends to
  * `packagedArtifacts` itself (kyo-net's native classifiers) are therefore not published here: a closure reaching such a
  * project publishes it with `publishLocal`.
  */
object ScriptedPublish extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = plugins.IvyPlugin

    object autoImport {
        val publishLocalWithoutDoc = taskKey[Unit]("publishLocal without the doc jar")
    }
    import autoImport.*

    override def projectSettings: Seq[Setting[?]] = Seq(
        publishLocalWithoutDoc / publishLocalConfiguration := {
            // Under coursier the publish delivers an ivy.xml that sbt writes only ahead of this unscoped key and
            // `publishLocalConfiguration`; without it Ivy fails with "Ivy file not found in cache".
            val _ = makeIvyXmlLocalConfiguration.value
            Classpaths.publishConfig(
                publishMavenStyle = false,
                deliverIvyPattern = Classpaths.deliverPattern(crossTarget.value),
                status = if (isSnapshot.value) "integration" else "release",
                configurations = ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
                artifacts = Classpaths.packaged(Seq(makePom, Compile / packageBin, Compile / packageSrc)).value.toVector,
                checksums = (publishLocal / checksums).value.toVector,
                logging = ivyLoggingLevel.value,
                overwrite = isSnapshot.value
            )
        },
        publishLocalWithoutDoc := Classpaths.publishOrSkip(publishLocalWithoutDoc / publishLocalConfiguration, publishLocal / skip).value
    )
}
