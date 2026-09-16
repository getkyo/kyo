package kyo.ffi.sbt

import java.io.File
import java.util.zip.ZipFile
import sbt.IO
import sbt.util.Logger
import scala.collection.JavaConverters._

/** Puts the natives a Scala.js program's classpath carries beside the program's linked output, where the JS loader looks for them.
  *
  * A Scala.js artifact carries its natives as resources under `kyo-ffi/native/<os>-<arch>/lib<id>.<ext>` ([[Packager.copyForJs]]), and
  * the linker writes only JavaScript, so nothing in a linked program would reach them. The JS loader resolves
  * `./kyo-ffi/native/<os>-<arch>/lib<id>.<ext>` against the linked program's own location, the way the program's imports resolve, so this
  * copies the same tree, entry for entry, into the output directory. Every platform on the classpath is staged, not only the build host's:
  * a program is often linked on one machine and run on another, and the loader picks its own platform's directory at run time.
  *
  * The staged tree mirrors the classpath: a file that no classpath entry carries any more is removed, so dropping a dependency drops its
  * natives. An entry already present with the same content is left untouched.
  */
private[sbt] object JsNativeStaging {

    /** The resource prefix every Scala.js artifact files its natives under, and the directory they are staged into. */
    val Prefix: String = "kyo-ffi/native/"

    /** Stages every native on `classpath` into `outputDirectory/kyo-ffi/native/` and returns the staged files. Earlier classpath entries win
      * when two carry the same path, as they do for class loading.
      */
    def stage(classpath: Seq[File], outputDirectory: File, log: Logger): Seq[File] = {
        val stagingRoot = new File(outputDirectory, Prefix)
        val seen        = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
        classpath.foreach { entry =>
            natives(entry).foreach { case (path, bytes) => if (!seen.contains(path)) seen.update(path, bytes()) }
        }
        val staged = seen.toSeq.map { case (path, bytes) =>
            val dest = new File(outputDirectory, path)
            if (!dest.isFile || !java.util.Arrays.equals(IO.readBytes(dest), bytes)) {
                IO.createDirectory(dest.getParentFile)
                IO.write(dest, bytes)
            }
            dest
        }
        val keep  = staged.map(_.getCanonicalFile).toSet
        val stale = if (stagingRoot.isDirectory) filesUnder(stagingRoot).filterNot(f => keep.contains(f.getCanonicalFile)) else Nil
        stale.foreach(IO.delete)
        if (staged.nonEmpty || stale.nonEmpty)
            log.info(
                s"[kyo-ffi-plugin] staged ${staged.size} native(s) beside the linked output in ${stagingRoot.getAbsolutePath}" +
                    (if (stale.isEmpty) "" else s", removed ${stale.size} no classpath entry carries")
            )
        staged
    }

    /** The natives one classpath entry carries, as (path under the output directory, contents), read lazily. */
    private[sbt] def natives(entry: File): Seq[(String, () => Array[Byte])] =
        if (entry.isDirectory) {
            val root = new File(entry, Prefix)
            if (!root.isDirectory) Nil
            else
                filesUnder(root).map(f => Prefix + relative(root, f) -> f).collect {
                    case (path, f) if isNative(path) => (path, () => IO.readBytes(f))
                }
        } else if (entry.isFile && entry.getName.endsWith(".jar")) {
            val zip = new ZipFile(entry)
            try
                zip.entries().asScala.toList.filter(e => !e.isDirectory && e.getName.startsWith(Prefix) && isNative(e.getName)).map { e =>
                    val bytes = {
                        val in = zip.getInputStream(e)
                        try IO.readBytes(in)
                        finally in.close()
                    }
                    (e.getName, () => bytes)
                }
            finally zip.close()
        } else Nil

    /** Only `<os>-<arch>/<file>` two levels under the prefix is a native; anything else there is not something the loader reads. */
    private def isNative(path: String): Boolean = path.stripPrefix(Prefix).split('/').length == 2

    private def filesUnder(dir: File): Seq[File] =
        Option(dir.listFiles()).toSeq.flatten.flatMap(f => if (f.isDirectory) filesUnder(f) else Seq(f))

    private def relative(root: File, file: File): String =
        root.toPath.relativize(file.toPath).toString.replace(File.separatorChar, '/')
}
