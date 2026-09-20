package kyo.natives.sbt

import kyo.ffi.sbt.FfiLibrary
import kyo.ffi.sbt.NativeTargets
import sbt.Keys._
import sbt._
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** The Scala Native half of [[KyoNativesPlugin]]: link the binary against the libraries the artifacts carry.
  *
  * Scala Native has no runtime loader, so a library cannot be extracted and opened the way the JVM does it. Instead the
  * artifact's C shim, which Scala Native compiles into the binary from the sources the Native jar ships, is compiled to
  * nothing, and the binary is linked against the prebuilt library directly. Without that the link holds two
  * definitions of every entry point, the shim's stubs and the library's real ones.
  *
  * The binary records `@rpath/lib<id>.dylib` (`$ORIGIN` on linux) and the libraries are staged beside it, so a linked
  * binary runs from anywhere and deploys as a directory. That directory has to travel with it: a Native application
  * that uses kyo's natives is not a single file, in the same way a JVM application is not a single file.
  */
object KyoNativesNativePlugin extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = KyoNativesPlugin && ScalaNativePlugin

    import KyoNativesPlugin.autoImport._
    import KyoNativesPlugin.kyoNativesFetched

    override def projectSettings: Seq[Setting[_]] = Seq(
        // A binary is built for one target, so the triple decides it and an explicit setting has to agree. Deriving it
        // from the triple rather than the host is what makes a cross-compiled build resolve the right pole.
        kyoNativesResolvedTargets := {
            val explicit = kyoNativesTargets.value
            if (explicit.size > 1)
                sys.error(s"[kyo-natives] a Native binary has one target; kyoNativesTargets names ${explicit.mkString(", ")}.")
            val config = nativeConfig.value
            val triple = config.targetTriple.getOrElse(scala.scalanative.build.Discover.targetTriple(config))
            val fromTriple = NativeTargets.ofTriple(triple)
            (explicit.headOption, fromTriple) match {
                case (Some(named), Some(derived)) if named != derived =>
                    sys.error(s"[kyo-natives] kyoNativesTargets says $named but the target triple $triple is $derived.")
                case (Some(named), _) => Seq(named)
                case (None, Some(derived)) => Seq(derived)
                case (None, None) =>
                    streams.value.log.info(s"[kyo-natives] no target kyo publishes for matches the triple $triple; delivering nothing")
                    Nil
            }
        },
        nativeConfig := {
            val base    = nativeConfig.value
            val fetched = kyoNativesFetched.value
            if (fetched.isEmpty) base
            else {
                val dirs    = fetched.map(_._2.library.getParentFile).distinct
                val targets = fetched.map(_._1).distinct
                // Both mean "beside the binary", so the link records no path from this machine.
                val rpaths = targets.map(t => if (NativeTargets.osOf(t) == "darwin") "-Wl,-rpath,@loader_path" else "-Wl,-rpath,$ORIGIN")
                val defines = fetched.map { case (_, f) => "-D" + FfiLibrary.externalDefineFor(f.libId) }.distinct
                val links   = fetched.map { case (_, f) => "-l" + f.libId }.distinct
                base
                    .withCompileOptions(base.compileOptions ++ defines)
                    .withLinkingOptions(base.linkingOptions ++ dirs.map("-L" + _.getAbsolutePath) ++ links ++ rpaths.distinct)
            }
        },
        Compile / nativeLink := stageBeside(Compile / nativeLink).value,
        Test / nativeLink    := stageBeside(Test / nativeLink).value
    )

    /** Copies the libraries next to the binary `link` produced, so the directory it sits in is runnable as it stands.
      *
      * Without this the rpath resolves nothing and the first run fails in `dyld`, which is a poor way to learn that a
      * Native binary using kyo's natives travels with them.
      */
    private def stageBeside(link: TaskKey[File]): Def.Initialize[Task[File]] = Def.task {
        val binary  = link.value
        val fetched = kyoNativesFetched.value
        fetched.foreach { case (_, f) => IO.copyFile(f.library, binary.getParentFile / f.library.getName, preserveLastModified = true) }
        binary
    }
}
