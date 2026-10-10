package kyo.ffi.sbt

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import sbt._
import scala.collection.JavaConverters._

/** Reads `META-INF/services` declarations off a classpath, in the form Scala Native's link-time provider allowlist
  * wants.
  *
  * On the JVM and on JS a provider is found at run time, so declaring it in the jar is the whole story. Scala Native
  * resolves `ServiceLoader` at LINK time and drops any class nothing references, so a provider also has to be named in
  * `nativeConfig.withServiceProviders` or it is silently absent: the module links clean and never registers, with no
  * error and no warning.
  *
  * That allowlist is a linker constraint rather than a semantic choice, and the classpath already carries the answer.
  * Reading it back is what lets a Native build match what `ServiceLoader` would have found, instead of asking every
  * application to retype class names that already exist in what it depends on.
  */
object ServiceProviders {

    /** Classpath-relative directory of the declarations, as the `ServiceLoader` spec fixes it. */
    val dir: Seq[String] = Seq("META-INF", "services")

    /** Every provider the entries on `cp` declare, as interface to implementations.
      *
      * Jars and classpath directories both, unlike the declaration readers beside this one. Those read a statement a
      * PUBLISHED artifact makes about itself, so a directory, being a module built in this same build, speaks through
      * its own settings instead. A services file is not that: it is the same file `ServiceLoader` would read at run
      * time wherever it sits, so a sibling project in the same build declares a provider exactly as a jar does, and
      * skipping it would drop that provider from the link with nothing to say so.
      */
    def read(cp: Seq[File]): Map[String, Seq[String]] =
        merge(readDirs(cp.filter(_.isDirectory)), readJars(cp.filter(entry => entry.isFile && entry.getName.endsWith(".jar"))))
            .map { case (iface, impls) => iface -> impls.toSeq.distinct.sorted }

    /** The providers declared under each classpath directory's `META-INF/services`. */
    private def readDirs(dirs: Seq[File]): Map[String, Seq[String]] = {
        val found = dirs.flatMap { root =>
            val servicesDir = dir.foldLeft(root)(_ / _)
            if (!servicesDir.isDirectory) Nil
            else
                IO.listFiles(servicesDir).toSeq.filter(_.isFile).flatMap { f =>
                    parse(IO.read(f)).map(f.getName -> _)
                }
        }
        found.groupBy(_._1).map { case (iface, pairs) => iface -> pairs.map(_._2).distinct.sorted }
    }

    /** The providers declared inside each jar's `META-INF/services`. */
    private def readJars(jars: Seq[File]): Map[String, Seq[String]] = {
        val prefix = dir.mkString("", "/", "/")
        val found  = jars.flatMap { jar =>
            val zip = new ZipFile(jar)
            // toList, not toSeq: an Iterator's toSeq is a lazy Stream here, and every entry below is read through
            // the ZipFile that `finally` closes. A lazy chain escapes the try and reads from a closed file.
            try
                zip.entries().asScala.toList
                    .filter(e => !e.isDirectory && e.getName.startsWith(prefix))
                    .flatMap { e =>
                        val iface = e.getName.drop(prefix.length)
                        // A nested path is not a service file: the spec puts the interface name directly under the
                        // directory, and anything deeper is some other tool's resource.
                        if (iface.isEmpty || iface.contains('/')) Nil
                        else {
                            val in   = zip.getInputStream(e)
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
        // toList, not toSeq: an Iterator's toSeq is a lazy Stream on 2.12, and these values end up in a config map
        // that is read long after this call.
        text.linesIterator.map { line =>
            val hash = line.indexOf('#')
            (if (hash >= 0) line.substring(0, hash) else line).trim
        }.filter(_.nonEmpty).toList.distinct

    /** `declared` merged over `existing`, so a value already in the config survives rather than being replaced. */
    def merge(
        existing: Map[String, Iterable[String]],
        declared: Map[String, Seq[String]]
    ): Map[String, Iterable[String]] =
        declared.foldLeft(existing) { case (acc, (iface, impls)) =>
            acc.updated(iface, (acc.getOrElse(iface, Nil).toSeq ++ impls).distinct)
        }
}
