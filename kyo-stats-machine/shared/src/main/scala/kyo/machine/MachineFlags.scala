package kyo.machine

import kyo.*

/** The host sampler's levers.
  *
  * They live in `kyo.machine` rather than beside the sampler in `kyo.stats.machine` because a flag's key is
  * its fully-qualified object name: this package is what makes them `kyo.machine.disabled` and friends,
  * which is the spelling the module has always documented. Each resolves once at class load from
  * `-D<key>` or the matching env var (dots to underscores, uppercased), so a change needs a restart.
  */

/** Suppresses the sampler entirely, for a host that collects its metrics another way, or a test process
  * that must not race a live sampler. Read once at classpath-presence activation.
  */
object disabled extends StaticFlag[Boolean](false)

/** How often the sampler reads the host.
  *
  * Everything downstream inherits this cadence: a consumer polling faster sees the same value repeated,
  * because the signal only changes when the sampler ticks, so a dashboard wanting sub-second host
  * behaviour has to move the producer rather than poll harder. One sample a second is the default because
  * one shared sampler at 1 Hz costs the host far less than N consumers reading `/proc` themselves.
  *
  * Takes a duration, so `100ms`, `2s` and `1 minute` all work.
  */
object interval extends StaticFlag[Duration](1.second)

/** How long one disk read may take before its cycle is abandoned.
  *
  * Bounds only the disk fiber, which reads the one genuinely blockable family; the fast fiber never waits
  * on it. Keep it above `interval`, since a bound at or below the cadence abandons every slow mount
  * before it can answer.
  */
object diskReadTimeout extends StaticFlag[Duration](4.seconds)
