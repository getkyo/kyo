package kyo.natives.sbt

import java.util.zip.ZipFile
import kyo.ffi.sbt.NativeDelivery
import kyo.ffi.sbt.NativeTargets
import sbt._
import sbt.librarymanagement.DependencyResolution
import sbt.util.Logger
import scala.collection.JavaConverters._

/** Finding and unpacking the shared libraries a kyo artifact delivers.
  *
  * Everything here is driven by the [[NativeDelivery]] declarations on the project's own classpath, so the set of
  * modules this understands is whatever the build depends on rather than a list compiled in here.
  */
private[sbt] object Delivery {

    /** One library to fetch: the artifact carrying it, and the id naming the file inside. */
    final case class Request(module: ModuleID, libId: String)

    /** A library that was fetched, the jar it came from, and the coordinate that jar was resolved from. */
    final case class Fetched(libId: String, library: File, jar: File, module: ModuleID)

    /** The classpath-relative path a JVM artifact packages a library at. Both halves come from the packaging side, so
      * a consumer cannot look for a name the producer does not write.
      */
    def entryPath(libId: String, osArch: String, os: String): String =
        s"META-INF/native/$osArch/${NativeTargets.libraryFileName(libId, os)}"

    /** What `classpath` asks for on `platform`, for target `osArch`.
      *
      * A Native or JS artifact declares the delivery but carries no library, so each request names the JVM artifact of
      * the same module and version: the declaration supplies the classifier, and [[NativeDelivery.jvmArtifactName]]
      * maps the platform-suffixed name back. `intransitive` because only that one jar is wanted, never the module's
      * dependency closure, which the project already has.
      *
      * A declaration that does not deliver to `platform` is skipped, which is how a library whose C already compiles
      * into a Native binary stays out of that link.
      *
      * Two modules declaring the same library id is an error rather than a choice. The id names the file that both the
      * Native `-L` directory and the Node package hold, so the second would overwrite the first and the build would
      * link or open whichever was unpacked last, with nothing said.
      */
    def requests(classpath: Seq[(ModuleID, File)], osArch: String, platform: String): Seq[Request] = {
        val found = classpath.flatMap { case (module, file) =>
            if (!file.isFile || !file.getName.endsWith(".jar")) Nil
            else
                NativeDelivery.readJar(file).filter(_.deliversTo(platform)).map { declared =>
                    val carrier = module.organization % NativeDelivery.jvmArtifactName(module.name) % module.revision
                    val withClassifier =
                        declared.classifier(osArch).fold(carrier)(c => carrier.classifier(c))
                    Request(withClassifier.withCrossVersion(CrossVersion.disabled).intransitive(), declared.id)
                }
        }.distinct
        found.groupBy(_.libId).find(_._2.size > 1).foreach { case (libId, clashing) =>
            sys.error(
                s"[kyo-natives] $libId is declared by more than one module (${clashing.map(_.module.name).sorted.mkString(", ")}), " +
                    "and both would deliver to the same file name."
            )
        }
        found
    }

    /** Resolutions already made in this sbt session, keyed on the exact coordinate.
      *
      * Every task that needs a library resolves it: `nativeConfig`, the link, `fastLinkJS`, `run`, `test`, the
      * report. sbt caches a task's value within one command, not across them, so without this a session spends a
      * resolution on each. Keyed on the full coordinate including the classifier, so a republished version cannot
      * be answered from here: kyo's snapshots carry a timestamp, and any version string that changes on republish
      * changes the key. A mutable `-SNAPSHOT` republished DURING one sbt session is the case this would hold
      * stale, and reloading the build clears it.
      *
      * Successes only. A resolution that failed may have failed on the network, and holding that answer for the
      * session would turn one bad moment into "this release carries no library" for every later task, which under
      * `Auto` is a warning and a binary without the capability.
      */
    private val resolved = new java.util.concurrent.ConcurrentHashMap[String, File]()

    /** Resolves `module` to its single jar, or a message saying why not.
      *
      * A classifier jar a release does not carry for this target is an ordinary outcome, not a build failure: the
      * caller decides, because whether a missing library is fatal depends on the source the application pinned.
      */
    def resolve(depRes: DependencyResolution, module: ModuleID, log: Logger): Either[String, File] = {
        val key = s"${module.organization}:${module.name}:${module.revision}:${module.explicitArtifacts.flatMap(_.classifier).mkString(",")}"
        Option(resolved.get(key)).filter(_.isFile) match {
            case Some(jar) => Right(jar)
            case None =>
                val answer = resolveUncached(depRes, module, log)
                answer.right.foreach(jar => resolved.put(key, jar))
                answer
        }
    }

    private def resolveUncached(depRes: DependencyResolution, module: ModuleID, log: Logger): Either[String, File] = {
        val descriptor = depRes.moduleDescriptor(
            sbt.librarymanagement.ModuleDescriptorConfiguration(
                "io.getkyo" % "kyo-natives-resolver" % "0",
                sbt.librarymanagement.ModuleInfo("kyo-natives-resolver")
            ).withDependencies(Vector(module))
                .withConfigurations(Vector(sbt.librarymanagement.Configurations.Compile))
                .withScalaModuleInfo(None)
        )
        depRes.update(
            descriptor,
            sbt.librarymanagement.UpdateConfiguration().withLogging(sbt.librarymanagement.UpdateLogging.Quiet),
            sbt.librarymanagement.UnresolvedWarningConfiguration(),
            log
        ) match {
            case Right(report) =>
                // Intransitive and single-artifact, so anything but one jar means the request did not say what it
                // meant and picking one would deliver a library nobody asked for.
                report.allFiles.distinct.filter(_.getName.endsWith(".jar")) match {
                    case Seq(jar) => Right(jar)
                    case Seq()    => Left(s"$module resolved no jar")
                    case several  => Left(s"$module resolved ${several.size} jars: ${several.map(_.getName).mkString(", ")}")
                }
            case Left(warning) => Left(s"$module did not resolve: ${warning.resolveException.getMessage}")
        }
    }

    /** Copies `libId`'s library for `osArch` out of `jar` into `out`, flat, or None when the jar carries none.
      *
      * Flat because a `-L` search directory names one directory and expects `lib<id>.<ext>` directly in it, and
      * because the same directory is what travels beside a linked binary.
      */
    def unpack(jar: File, libId: String, osArch: String, os: String, out: File): Option[File] = {
        val path = entryPath(libId, osArch, os)
        val zip  = new ZipFile(jar)
        try
            zip.entries().asScala.find(_.getName == path).map { entry =>
                val dest = out / NativeTargets.libraryFileName(libId, os)
                IO.createDirectory(out)
                val in = zip.getInputStream(entry)
                try IO.transfer(in, dest)
                finally in.close()
                dest.setExecutable(true, false)
                dest
            }
        finally zip.close()
    }
}
