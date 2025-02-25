package kyo.test

/** TestPlatform provides information about the platform tests are being run on to enable platform specific test configuration. */
object TestPlatform:

    /** Returns whether the current platform is ScalaJS. */
    final val isJS = false

    /** Returns whether the current platform is the JVM. */
    final val isJVM = false

    /** Returns whether the current platform is Scala Native. */
    final val isNative = true
end TestPlatform
