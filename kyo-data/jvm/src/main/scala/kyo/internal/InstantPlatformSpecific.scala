package kyo.internal

import kyo.Instant

/** The platform clock for [[kyo.Instant]] on the JVM. */
private[kyo] object InstantPlatformSpecific:

    /** `java.time.Instant.now()`, which reads the clock at the resolution the JVM offers (microseconds on common hosts). The JDK carries
      * java.time, so reading it here costs nothing in linked size.
      */
    def now(): Instant = Instant.fromJava(java.time.Instant.now())
end InstantPlatformSpecific
