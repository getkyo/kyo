package kyo.natives.sbt

/** Where an application's shared libraries come from, and what happens when one is not there. */
sealed trait NativesSource

object NativesSource {

    /** Deliver what the artifacts carry for the target, and leave the build untouched for anything they do not.
      *
      * The default, because a library absent for a target is an ordinary outcome: the application still builds, and at
      * run time the capability reports itself unavailable rather than the build failing on a machine kyo has no
      * artifact for.
      */
    case object Auto extends NativesSource

    /** Deliver from the artifacts, and fail the build when one carries no library for the target.
      *
      * For an application whose target is a pole kyo publishes for and which would rather hear about a missing library
      * at build time than at run time.
      */
    case object Jar extends NativesSource

    /** Contribute nothing, so the build is exactly as it would be without this plugin. */
    case object Disabled extends NativesSource
}
