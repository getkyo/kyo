package kyo.internal

import scala.scalajs.js.timers

/** An explicit reason for a JS host to stay running, for as long as an application's own work is pending.
  *
  * A JS host exits when nothing is left holding its event loop, and kyo's timers deliberately hold nothing: the clock arms them with
  * `unref`, so a background cadence (a connection pool's reaper, a stats sampler, a retry that is waiting to fire) is no more a reason for
  * the host to stay running than a JVM daemon thread is a reason for the JVM to stay running.
  *
  * That leaves the application itself to say it is not finished, which on the JVM is `KyoApp` blocking the main thread until its run block
  * completes. There is no thread to block here, so the same statement is made with a timer that is not unref'd, held from the moment the
  * run block starts until it completes, fails, or is interrupted.
  */
private[kyo] object HostKeepAlive:

    /** How often the held timer fires, in milliseconds.
      *
      * Nothing happens on a fire. The period only has to stay inside the host's timer range, and long enough that an application waiting on
      * something else does no measurable work while it waits.
      */
    private val PeriodMillis = 3600000.0

    /** A hold on the host, released once and only by whoever took it. */
    final class Hold private[HostKeepAlive] (private val handle: timers.SetIntervalHandle):
        def release(): Unit = timers.clearInterval(handle)

    def acquire(): Hold = new Hold(timers.setInterval(PeriodMillis)(()))

end HostKeepAlive
