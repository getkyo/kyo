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
  * The binary records `@rpath/lib<id>.dylib` (`\$ORIGIN` on linux) and the libraries are staged beside it, so a linked
  * binary runs from anywhere and deploys as a directory. That directory has to travel with it: a Native application
  * using kyo's natives is not a single file, in the same way a JVM application is not a single file.
  */
object KyoNativesNativePlugin extends AutoPlugin {

    override def trigger  = allRequirements
    override def requires = KyoNativesPlugin && ScalaNativePlugin

    import KyoNativesPlugin.autoImport._
    import KyoNativesPlugin.kyoNativesFetched
    import KyoNativesPlugin.kyoNativesRequests

    override def projectSettings: Seq[Setting[_]] = Seq(
        // A binary is built for one target, so more than one is a contradiction rather than a wider delivery.
        //
        // The target comes from the compiler rather than from `nativeConfig`, which would be the more direct source
        // and is not available: this plugin contributes TO `nativeConfig`, so reading it here would make the setting
        // depend on itself. A build that cross-compiles by setting `targetTriple` names `kyoNativesTargets` too, and
        // `crossTargetCheck` fails the link when the two disagree.
        kyoNativesResolvedTargets := {
            val log      = streams.value.log
            val explicit = kyoNativesTargets.value
            if (explicit.size > 1)
                sys.error(s"[kyo-natives] a Native binary has one target; kyoNativesTargets names ${explicit.mkString(", ")}.")
            val target = explicit.headOption match {
                case some @ Some(_) => some
                case None =>
                    val triple = Discover.targetTriple(Discover.clang())
                    val derived = NativeTargets.ofTriple(triple)
                    if (derived.isEmpty)
                        log.info(s"[kyo-natives] no target kyo publishes for matches $triple; delivering nothing")
                    derived
            }
            target.flatMap { t =>
                undeliverable(t) match {
                    case Some(why) =>
                        log.info(s"[kyo-natives] $why; delivering nothing")
                        None
                    case None => Some(t)
                }
            }.toSeq
        },
        nativeConfig := {
            val base    = nativeConfig.value
            val fetched = kyoNativesFetched.value
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
        Compile / nativeLink := stageBeside((Compile / nativeLink).dependsOn(crossTargetCheck)).value,
        Test / nativeLink    := stageBeside((Test / nativeLink).dependsOn(crossTargetCheck)).value
    )

    /** Why `target`'s libraries cannot reach a Scala Native link, or None when they can.
      *
      * A Windows release carries `<id>.dll` and no import library, and `-l<id>` against a bare DLL resolves nothing,
      * so the delivery would end in a linker error naming a library the release does not publish in a linkable form.
      * Saying so and delivering nothing leaves the binary in the state it is in on every other platform where a
      * library is missing: it links, and the capability reports itself unavailable when it is used.
      *
      * A target kyo publishes nothing at all for passes through rather than being reported here, so the message it
      * gets is the one that names the supported set.
      */
    private[sbt] def undeliverable(target: String): Option[String] =
        if (NativeTargets.supported.contains(target) && NativeTargets.osOf(target) == "windows")
            Some(s"$target publishes a DLL and no import library, which a Native link cannot use")
        else None

    /** Fails the build before the link when the target the binary is built for and the libraries about to be linked
      * into it disagree.
      *
      * This runs at link time, not while building `nativeConfig`, because an auto-plugin's settings are applied before
      * the project's own: inside this plugin's `nativeConfig :=`, `nativeConfig.value` is sbt-scala-native's default
      * and an application's `withTargetTriple` has not been applied yet, so the triple read there is always empty. A
      * task reads the finished setting instead, and `dependsOn` puts it ahead of the link, so the error arrives before
      * the linker's own. Reading the finished config also covers a `withClang`, which the clang the target was derived
      * from would otherwise ignore.
      */
    private def crossTargetCheck: Def.Initialize[Task[Unit]] = Def.task {
        val config   = nativeConfig.value
        val targets  = kyoNativesResolvedTargets.value
        val requests = kyoNativesRequests.value
        // The compiler the finished config names, which is the one that will run, rather than the one the delivery
        // derived its target from.
        val compilerTarget = NativeTargets.ofTriple(Discover.targetTriple(config.clang))
        crossTargetError(config.targetTriple, compilerTarget, targets.headOption, requests.nonEmpty, kyoNativesTargets.value.nonEmpty)
            .foreach(sys.error)
    }

    /** The error a build gets when the target its binary is built for and the libraries it asks to have linked into it
      * disagree, or None when they agree or the build asks for no libraries at all.
      *
      * A build states its target in two places, `targetTriple` and `kyoNativesTargets`, and the delivery reads only the
      * second. Either one naming a pole the other does not is a binary linked against another pole's libraries, which
      * fails in the linker with a file-format error at best and loads and crashes at worst when the poles share an
      * architecture, as glibc and musl do.
      *
      * `requested` is whether the dependencies declare any library, not whether one was found. A release that carries
      * nothing for the named pole produces the same empty delivery as a correct build with nothing to deliver, and it
      * is precisely the build that named an impossible pole which needs to be told so rather than quietly handed a
      * binary missing the capability.
      *
      * `named` distinguishes a target the build wrote from one derived from the clang on the PATH, which is what a
      * `withClang` pointing at another toolchain produces: telling that build to change a setting it never wrote sends
      * it looking in the wrong place.
      */
    private[sbt] def crossTargetError(
        triple: Option[String],
        compilerTarget: Option[String],
        wanted: Option[String],
        requested: Boolean,
        named: Boolean
    ): Option[String] =
        if (!requested) None
        else
            wanted.flatMap { target =>
                triple match {
                    case Some(t) =>
                        NativeTargets.ofTriple(t) match {
                            case Some(fromTriple) if fromTriple != target =>
                                Some(
                                    s"[kyo-natives] the target triple $t is $fromTriple, but the libraries are " +
                                        s"wanted for $target. Set kyoNativesTargets to $fromTriple."
                                )
                            case None =>
                                Some(
                                    s"[kyo-natives] the target triple $t is not a target kyo publishes natives for, " +
                                        s"so the libraries wanted are $target's and would be linked into a binary " +
                                        "for another. Set kyoNativesSource to NativesSource.Disabled to build without them."
                                )
                            case _ => None
                        }
                    case None =>
                        compilerTarget.filter(_ != target).map { host =>
                            val source =
                                if (named) s"kyoNativesTargets names $target"
                                else s"the libraries were resolved for $target, derived from the clang on the PATH"
                            s"[kyo-natives] $source, but the compiler this build uses targets $host and no " +
                                s"targetTriple says otherwise, so $target's libraries would be linked into a $host binary."
                        }
                }
            }

    /** Copies the libraries next to the binary `link` produced, so the directory it sits in is runnable as it stands.
      *
      * Without this the rpath resolves nothing and the first run fails in `dyld`, which is a poor way to learn that a
      * Native binary using kyo's natives travels with them.
      */
    private def stageBeside(link: Def.Initialize[Task[File]]): Def.Initialize[Task[File]] = Def.task {
        val binary  = link.value
        val fetched = kyoNativesFetched.value
        fetched.foreach { case (_, f) =>
            IO.copyFile(f.library, binary.getParentFile / f.library.getName, preserveLastModified = true)
        }
        binary
    }
}
