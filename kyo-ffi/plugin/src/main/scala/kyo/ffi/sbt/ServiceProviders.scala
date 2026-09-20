package kyo.ffi.sbt

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import sbt._
import scala.collection.JavaConverters._

/** Reads `META-INF/services` declarations out of jars, in the form Scala Native's link-time provider allowlist wants.
  *
  * On the JVM and on JS a provider is found at run time, so declaring it in the jar is the whole story. Scala Native
  * resolves `ServiceLoader` at LINK time and drops any class nothing references, so a provider also has to be named in
  * `nativeConfig.withServiceProviders` or it is silently absent: the module links clean and never registers, with no
  * error and no warning.
  *
  * That allowlist is a linker constraint rather than a semantic choice, and the jars already carry the answer. Reading
  * it back is what lets a Native build match what `ServiceLoader` would have found on a classpath, instead of asking
  * every application to retype class names that already exist in the artifacts it depends on.
  */
object ServiceProviders {

    /** Classpath-relative directory of the declarations, as the `ServiceLoader` spec fixes it. */
    val dir: Seq[String] = Seq("META-INF", "services")

    /** Every provider the jars on `cp` declare, as interface to implementations.
      *
      * Only jars. A classpath directory belongs to a module built in this same build, which reaches the linker through
      * its own settings rather than through a published declaration.
      */
    def readJars(cp: Seq[File]): Map[String, Seq[String]] = {
        val prefix = dir.mkString("", "/", "/")
        val found = cp.filter(entry => entry.isFile && entry.getName.endsWith(".jar")).flatMap { jar =>
            val zip = new ZipFile(jar)
            try
                zip.entries().asScala.toSeq
                    .filter(e => !e.isDirectory && e.getName.startsWith(prefix))
                    .flatMap { e =>
                        val iface = e.getName.drop(prefix.length)
                        // A nested path is not a service file: the spec puts the interface name directly under the
                        // directory, and anything deeper is some other tool's resource.
                        if (iface.isEmpty || iface.contains('/')) Nil
                        else {
                            val in = zip.getInputStream(e)
                            val text =
                                try new String(IO.readBytes(in), StandardCharsets.UTF_8)
                                finally in.close()
                            parse(text).map(iface -> _)
                        }
                    }
            finally zip.close()
        }
        found.groupBy(_._1).map { case (iface, pairs) => iface -> pairs.map(_._2).distinct.sorted }
    }

    /** The implementation class names in one service file.
      *
      * The format is the `ServiceLoader` one: one class per line, `#` starts a comment, blank lines are ignored.
      */
    def parse(text: String): Seq[String] =
        text.linesIterator.map { line =>
            val hash = line.indexOf('#')
            (if (hash >= 0) line.substring(0, hash) else line).trim
        }.filter(_.nonEmpty).toSeq.distinct

    /** `declared` merged over `existing`, so a value already in the config survives rather than being replaced. */
    def merge(
        existing: Map[String, Iterable[String]],
        declared: Map[String, Seq[String]]
    ): Map[String, Iterable[String]] =
        declared.foldLeft(existing) { case (acc, (iface, impls)) =>
            acc.updated(iface, (acc.getOrElse(iface, Nil).toSeq ++ impls).distinct)
        }
}
