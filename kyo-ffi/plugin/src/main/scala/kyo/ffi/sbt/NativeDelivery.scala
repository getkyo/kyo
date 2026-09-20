package kyo.ffi.sbt

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.zip.ZipFile
import sbt._
import scala.collection.JavaConverters._

/** Which published artifact carries the shared library for one of a module's FFI libraries.
  *
  * A Scala Native or Node application cannot take the library from its own classpath: the Native artifact ships C
  * sources and no library, and koffi loads from the filesystem rather than from a jar. The library both need is the one
  * the JVM artifact of the same module and version carries, in the main jar or under a classifier. This declaration is
  * how the artifact states that, so a consumer's build resolves it without a table of kyo modules built into the
  * reader.
  *
  * The declaration lives at `META-INF/kyo-ffi/native-delivery/<module>.properties` on every platform, because the leg
  * that needs it is the Native one, which packages no natives of its own:
  *
  * {{{
  * libraries = kyonet_boringssl, kyonet_posix_uring
  * kyonet_boringssl.classifier = <os-arch>-boringssl
  * kyonet_posix_uring.classifier = <os-arch>
  * }}}
  *
  * `<os-arch>` stands for the target tag, so one line covers every pole. An empty classifier names the module's main
  * artifact, which is where a module that does not slice its natives keeps them.
  */
object NativeDelivery {

    /** Classpath-relative directory of the declarations. */
    val dir: Seq[String] = Seq("META-INF", "kyo-ffi", "native-delivery")

    /** The placeholder a classifier pattern uses for the target os-arch tag. */
    val targetToken: String = "<os-arch>"

    /** The platform names a delivery can be scoped to, which are the platforms kyo publishes for. */
    val allPlatforms: Set[String] = Set("jvm", "js", "native")

    /** What a module delivers for one library: the classifier pattern of the artifact carrying its shared library, and
      * the platforms that should take it.
      *
      * The platform scope exists because a library is not always something a consumer needs delivered. Scala Native
      * compiles a module's C into the binary from the sources the artifact ships, so where that C is the whole
      * implementation rather than a shim over a vendored library, the binary already has it. Delivering it there would
      * link a second copy the compiled-in one shadows, and saddle the binary with a file it has to carry and does not
      * use.
      */
    final case class Entry(classifierPattern: String, platforms: Set[String] = allPlatforms)

    /** One library id and the [[Entry]] a declaration carries for it. */
    final case class Declared(id: String, classifierPattern: String, platforms: Set[String] = allPlatforms) {

        /** The classifier for `osArch`, or None when the library ships in the module's main artifact. */
        def classifier(osArch: String): Option[String] = {
            val resolved = classifierPattern.replace(targetToken, osArch)
            if (resolved.isEmpty) None else Some(resolved)
        }

        /** Whether `platform` (`jvm`, `js` or `native`) should take this library. */
        def deliversTo(platform: String): Boolean = platforms.contains(platform)
    }

    /** The declaration lines for `delivery`, keyed by library id. Written in a fixed order so an unchanged declaration
      * is byte-identical and does not change the jar.
      */
    def render(delivery: Map[String, Entry]): Seq[String] =
        if (delivery.isEmpty) Nil
        else {
            val ids = delivery.keys.toSeq.sorted
            ids.find(id => id.exists(c => c == ',' || c == '=' || c == '\n' || c == '\r')).foreach { bad =>
                sys.error(s"[kyo-ffi-plugin] library id '$bad' cannot be written to a native-delivery declaration.")
            }
            delivery.values.flatMap(_.platforms).find(!allPlatforms.contains(_)).foreach { bad =>
                sys.error(s"[kyo-ffi-plugin] '$bad' is not a platform; use ${allPlatforms.toSeq.sorted.mkString(", ")}.")
            }
            (s"libraries = ${ids.mkString(", ")}" +: ids.map(id => s"$id.classifier = ${delivery(id).classifierPattern}")) ++
                // Only written where it narrows, so a module delivering to every platform keeps the shorter declaration.
                ids.filter(id => delivery(id).platforms != allPlatforms)
                    .map(id => s"$id.platforms = ${delivery(id).platforms.toSeq.sorted.mkString(", ")}")
        }

    /** Parses a declaration written by [[render]]. */
    def parse(text: String): Seq[Declared] = {
        val props = new Properties()
        props.load(new StringReader(text))
        def list(key: String): Seq[String] =
            Option(props.getProperty(key)).toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
        list("libraries").map { id =>
            val platforms = list(s"$id.platforms").toSet
            Declared(
                id,
                Option(props.getProperty(s"$id.classifier")).map(_.trim).getOrElse(""),
                if (platforms.isEmpty) allPlatforms else platforms
            )
        }
    }

    /** The declarations `jar` carries, empty when it carries none. */
    def readJar(jar: File): Seq[Declared] = {
        val prefix = dir.mkString("", "/", "/")
        val zip    = new ZipFile(jar)
        try
            zip.entries().asScala.toSeq
                .filter(e => e.getName.startsWith(prefix) && e.getName.endsWith(".properties"))
                .sortBy(_.getName)
                .flatMap { e =>
                    val in = zip.getInputStream(e)
                    try parse(new String(IO.readBytes(in), StandardCharsets.UTF_8))
                    finally in.close()
                }
        finally zip.close()
    }

    /** The JVM artifact name for a cross-published one: `kyo-net_native0.5_3` and `kyo-net_sjs1_3` both give
      * `kyo-net_3`, and a name with no platform infix is returned unchanged.
      *
      * sbt writes the platform suffix directly before the Scala suffix, so stripping it is what maps a Native or JS
      * dependency to the JVM artifact holding its libraries. A consumer's build asks this rather than being told the
      * coordinate, which keeps the declaration free of a version that would then have to match the dependency's.
      */
    def jvmArtifactName(artifactName: String): String =
        platformInfix.replaceFirstIn(artifactName, "")

    private val platformInfix = """_(?:sjs|native)[0-9][0-9.]*(?=_)""".r
}
