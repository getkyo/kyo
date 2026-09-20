package kyo.natives.sbt

import kyo.ffi.sbt.FfiLibrary
import kyo.ffi.sbt.NativeTargets
import sbt.Keys._
import sbt._
import scala.scalanative.build.Discover
import scala.scalanative.sbtplugin.ScalaNativePlugin
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._

/** The Scala Native half of [[KyoNativesPlugin]]: link the binary against the libraries the artifacts carry.
  *
  * Scala Native has no runtime loader, so a library cannot be extracted and opened the way the JVM does it. Instead
  * the artifact's C shim, which Scala Native compiles into the binary from the sources the Native jar ships, is
  * compiled to nothing, and the binary is linked against the prebuilt library directly. Without that the link holds
  * two definitions of every entry point, the shim's stubs and the library's real ones.
  *
  * The binary records `@rpath/lib<id>.dylib` (`$ORIGIN` on linux) and the libraries are staged beside it, so a linked
  * binary runs from anywhere and deploys as a directory. That directory has to travel with it: a Native application
  * using kyo's natives is not a single file, in the same way a JVM application is not a single file.
  */
object KyoNativesNativePlugin extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = KyoNativesPlugin && ScalaNativePlugin

    import KyoNativesPlugin.autoImport._
    import KyoNativesPlugin.kyoNativesFetched

    override def projectSettings: Seq[Setting[_]] = Seq(
        // A binary is built for one target, so more than one is a contradiction rather than a wider delivery.
        //
        // The target comes from the compiler rather than from `nativeConfig`, which would be the more direct source
        // and is not available: this plugin contributes TO `nativeConfig`, so reading it here would make the setting
        // depend on itself. A build that cross-compiles by setting `targetTriple` names `kyoNativesTargets` too, and
        // the `nativeConfig` contribution below fails the build when the two disagree.
        kyoNativesResolvedTargets := {
            val explicit = kyoNativesTargets.value
            if (explicit.size > 1)
                sys.error(s"[kyo-natives] a Native binary has one target; kyoNativesTargets names ${explicit.mkString(", ")}.")
            explicit.headOption match {
                case Some(named) => Seq(named)
                case None =>
                    val triple = Discover.targetTriple(Discover.clang())
                    NativeTargets.ofTriple(triple) match {
                        case Some(derived) => Seq(derived)
                        case None =>
                            streams.value.log.info(s"[kyo-natives] no target kyo publishes for matches $triple; delivering nothing")
                            Nil
                    }
                }
        },
        nativeConfig := {
            val base    = nativeConfig.value
            val fetched = kyoNativesFetched.value
            val targets = kyoNativesResolvedTargets.value
            // An explicit triple is what a cross-compiling build sets, and the libraries were chosen without reading
            // it. Linking one pole's libraries into another pole's binary fails in the linker at best and produces an
            // unloadable binary at worst, so the disagreement is named here instead.
            base.targetTriple.foreach { triple =>
                val fromTriple = NativeTargets.ofTriple(triple)
                if (fromTriple.nonEmpty && targets.nonEmpty && fromTriple.get != targets.head)
                    sys.error(
                        s"[kyo-natives] the target triple $triple is ${fromTriple.get}, but the libraries were " +
                            s"resolved for ${targets.head}. Set kyoNativesTargets to ${fromTriple.get}."
                    )
            }
            if (fetched.isEmpty) base
            else {
                val dirs    = fetched.map(_._2.library.getParentFile).distinct
                val defines = fetched.map { case (_, f) => "-D" + FfiLibrary.externalDefineFor(f.libId) }.distinct
                val links   = fetched.map { case (_, f) => "-l" + f.libId }.distinct
                // Both forms mean "beside the binary", so the link records no path from this machine.
                val rpaths = fetched.map(_._1).distinct.map { t =>
                    if (NativeTargets.osOf(t) == "darwin") "-Wl,-rpath,@loader_path" else "-Wl,-rpath,$ORIGIN"
                }.distinct
                base
                    .withCompileOptions(base.compileOptions ++ defines)
                    .withLinkingOptions(base.linkingOptions ++ dirs.map("-L" + _.getAbsolutePath) ++ links ++ rpaths)
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
        fetched.foreach { case (_, f) =>
            IO.copyFile(f.library, binary.getParentFile / f.library.getName, preserveLastModified = true)
        }
        binary
    }
}
