package kyo.ffi.sbt

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.zip.ZipFile
import sbt._
import sbt.util.Logger
import scala.collection.JavaConverters._
import scala.sys.process._

/** The published half of [[FfiSystemLibrary]]: how a Native artifact carries the declaration and how a consumer's build
  * turns it into flags.
  *
  * The declaration lives at `META-INF/kyo-ffi/native-system-libraries/<module>.properties`, one per module, as flat
  * properties so a jar can be read without anything but `java.util`:
  *
  * {{{
  * libraries = kyonet_openssl, kyonet_posix_uring
  * kyonet_openssl.headers = openssl/ssl.h
  * kyonet_openssl.libs = ssl, crypto
  * kyonet_openssl.prefixes.darwin = /opt/homebrew/opt/openssl@3, /usr/local/opt/openssl@3
  * kyonet_posix_uring.headers = liburing.h
  * kyonet_posix_uring.libs.linux = uring
  * kyonet_posix_uring.static = true
  * }}}
  */
object NativeSystemLibraries {

    /** Classpath-relative directory of the declarations. */
    val dir: Seq[String] = Seq("META-INF", "kyo-ffi", "native-system-libraries")

    /** One declared library, as a consumer reads it back. */
    final case class Declared(id: String, system: FfiSystemLibrary)

    /** A declared library this machine can link, and the flags that link it. */
    final case class Resolved(id: String, compileFlags: Seq[String], linkFlags: Seq[String])

    /** The declaration text for `libs`, or None when none of them declares a system library. Hand-written in a fixed order
      * so an unchanged declaration is byte-identical and does not change the jar.
      */
    def render(libs: Seq[FfiLibrary]): Option[String] = {
        val declared = libs.flatMap(lib => lib.system.map(Declared(lib.id, _)))
        if (declared.isEmpty) None
        else {
            def list(values: Seq[String]): String = {
                values.find(v => v.exists(c => c == ',' || c == '\n' || c == '\r' || c == '\\')).foreach { bad =>
                    sys.error(s"[kyo-ffi-plugin] '$bad' cannot be written to a system-library declaration (comma, newline or backslash).")
                }
                values.mkString(", ")
            }
            val lines = Seq(s"libraries = ${list(declared.map(_.id))}") ++ declared.flatMap { case Declared(id, s) =>
                Seq(s"$id.headers = ${list(s.headers)}") ++
                    (if (s.linkLibs.nonEmpty) Seq(s"$id.libs = ${list(s.linkLibs)}") else Nil) ++
                    s.linkLibsByOs.toSeq.sortBy(_._1).map { case (os, v) => s"$id.libs.$os = ${list(v)}" } ++
                    (if (s.staticLink) Seq(s"$id.static = true") else Nil) ++
                    s.prefixesByOs.toSeq.sortBy(_._1).map { case (os, v) => s"$id.prefixes.$os = ${list(v)}" }
            }
            Some(lines.mkString("", "\n", "\n"))
        }
    }

    /** Parses a declaration written by [[render]]. */
    def parse(text: String): Seq[Declared] = {
        val props = new Properties()
        props.load(new StringReader(text))
        def list(key: String): Seq[String] =
            Option(props.getProperty(key)).toSeq.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
        def byOs(prefix: String): Map[String, Seq[String]] =
            props.stringPropertyNames().asScala.toSeq.filter(_.startsWith(prefix)).map(k => k.drop(prefix.length) -> list(k)).toMap
        list("libraries").map { id =>
            Declared(
                id,
                FfiSystemLibrary(
                    headers = list(s"$id.headers"),
                    linkLibs = list(s"$id.libs"),
                    linkLibsByOs = byOs(s"$id.libs."),
                    staticLink = Option(props.getProperty(s"$id.static")).exists(_.trim == "true"),
                    prefixesByOs = byOs(s"$id.prefixes.")
                )
            )
        }
    }

    /** Every declaration the jars on `cp` carry. Only jars: a declaration is how a PUBLISHED artifact speaks, while a module
      * built in the same build is a classpath directory and hands over its exact flags through the in-build manifests.
      */
    def readJars(cp: Seq[File]): Seq[Declared] = {
        val prefix = dir.mkString("", "/", "/")
        cp.filter(entry => entry.isFile && entry.getName.endsWith(".jar")).flatMap { jar =>
            val zip = new ZipFile(jar)
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
        }.groupBy(_.id).values.map(_.head).toSeq.sortBy(_.id)
    }

    /** The link flags for `libs` on `os`. The static window is GNU ld's; ld64 has none, so darwin links plainly. */
    def linkLibFlags(libs: Seq[String], static: Boolean, os: String): Seq[String] =
        if (static && os != "darwin") CCompiler.foldedLinkLibFlags(libs, staticLink = true)
        else libs.map(l => s"-l$l")

    /** Resolves `declared` on this machine: the first of the compiler defaults and each declared prefix under which
      * `probe` links a program including every header against every library. `probe` receives the compiler arguments
      * beyond the source and output; see [[probeWith]].
      */
    def resolve(declared: Declared, os: String, probe: Seq[String] => Boolean): Option[Resolved] = {
        val s    = declared.system
        val libs = s.resolvedLinkLibs(os)
        if (libs.isEmpty) None
        else {
            val define = s"-D${FfiLibrary.linkedDefineFor(declared.id)}"
            val link   = linkLibFlags(libs, s.staticLink, os)
            val candidates: Seq[(Seq[String], Seq[String])] =
                (Nil, Nil) +: s.prefixes(os).map(p => (Seq(s"-I$p/include"), Seq(s"-L$p/lib")))
            candidates
                .find { case (includes, libDirs) => probe(includes ++ libDirs ++ link) }
                .map { case (includes, libDirs) => Resolved(declared.id, define +: includes, libDirs ++ link) }
        }
    }

    /** A probe that compiles and links, with `cc`, a C file including `headers`, in `workDir`. */
    def probeWith(cc: String, headers: Seq[String], workDir: File, log: Logger): Seq[String] => Boolean = { args =>
        IO.createDirectory(workDir)
        val src = workDir / "probe.c"
        val out = workDir / "probe.out"
        IO.write(src, headers.map(h => s"#include <$h>\n").mkString + "int main(void) { return 0; }\n")
        val cmd    = Seq(cc, src.getAbsolutePath, "-o", out.getAbsolutePath) ++ args
        val output = new StringBuilder
        val code =
            try Process(cmd).!(ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n')))
            catch { case _: java.io.IOException => -1 }
        log.debug(s"[kyo-ffi-plugin] probe (exit=$code): ${cmd.mkString(" ")}\n$output")
        code == 0
    }
}
