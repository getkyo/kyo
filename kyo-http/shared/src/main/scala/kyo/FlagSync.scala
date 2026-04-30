package kyo

import kyo.AllowUnsafe.embrace.danger

/** Background polling to keep [[DynamicFlag]] instances in sync with external config sources.
  *
  * FlagSync runs a periodic background fiber that iterates over all registered flags and updates dynamic ones. Two strategies are provided:
  * `startReloader` re-reads each DynamicFlag from its original config source (system property or env var), while `startSync` fetches
  * expressions from a caller-supplied function (e.g., Consul, etcd, a database).
  *
  * Key capabilities:
  *   - `startReloader(interval)` -- polls system properties and environment variables
  *   - `startSync(interval, source)` -- polls a custom source function per flag name
  *   - Error backoff to prevent log spam from persistently broken config sources
  *   - Automatic success reset clears the failure counter
  *
  * IMPORTANT: Error backoff -- first 5 consecutive failures per flag are logged at WARN, then one ERROR escalation on the 6th, then
  * silence. A successful sync resets the counter. This prevents log spam from persistently broken config sources.
  *
  * Note: Static flags ([[StaticFlag]]) are skipped -- only [[DynamicFlag]] instances are processed during each polling cycle.
  *
  * @see
  *   [[DynamicFlag]] for the flags that FlagSync updates
  * @see
  *   [[FlagAdmin]] for HTTP endpoints to update flags manually
  * @see
  *   [[Flag]] for the global flag registry
  */
object FlagSync:

    // --- Public API ---

    /** Starts a background fiber that periodically reloads all dynamic flags from their config sources (system properties / env vars).
      *
      * Each DynamicFlag is reloaded from its original config source. Consecutive failures per flag are tracked with backoff: first 5
      * failures log at WARN level, the 6th triggers one ERROR escalation, and subsequent failures are suppressed until a success resets the
      * counter.
      *
      * @param interval
      *   Time between polling cycles
      */
    def startReloader(interval: Duration)(using Frame): Unit < (Async & Abort[Closed]) =
        val failures = new java.util.concurrent.ConcurrentHashMap[String, Int]()
        Loop.forever {
            Async.sleep(interval).andThen {
                Kyo.foreachDiscard(Flag.all) { flag =>
                    flag match
                        case d: DynamicFlag[?] =>
                            handleWithBackoff(d, failures, "reload") {
                                discard(d.reload())
                                discard(failures.remove(d.name))
                            }
                        case _ => ()
                    end match
                }
            }
        }
    end startReloader

    /** Starts a background fiber that periodically fetches expressions from a custom source and updates matching dynamic flags.
      *
      * The source function is called for each DynamicFlag name. When it returns `Present(expr)`, the flag is updated with the new
      * expression. When it returns `Absent`, the flag is skipped. Same backoff behavior as `startReloader` for consecutive failures.
      *
      * @param interval
      *   Time between polling cycles
      * @param source
      *   Function that takes a flag name and returns `Maybe[String]` -- `Present(expr)` to update, `Absent` to skip
      */
    def startSync(interval: Duration, source: String => Maybe[String])(using Frame): Unit < (Async & Abort[Closed]) =
        val failures = new java.util.concurrent.ConcurrentHashMap[String, Int]()
        Loop.forever {
            Async.sleep(interval).andThen {
                Kyo.foreachDiscard(Flag.all) { flag =>
                    flag match
                        case d: DynamicFlag[?] =>
                            handleWithBackoff(d, failures, "sync") {
                                source(d.name) match
                                    case Maybe.Present(expr) =>
                                        d.update(expr)
                                        discard(failures.remove(d.name))
                                    case _ =>
                                        discard(failures.remove(d.name))
                                end match
                            }
                        case _ => ()
                    end match
                }
            }
        }
    end startSync

    // --- Internal ---

    private val maxConsecutiveFailures = 5

    /** Wraps an action in Abort.catching + Abort.recover with backoff logging. */
    private def handleWithBackoff(
        d: DynamicFlag[?],
        failures: java.util.concurrent.ConcurrentHashMap[String, Int],
        label: String
    )(
        action: => Unit
    )(using Frame): Unit < Async =
        Abort.recover[Throwable] { (e: Throwable) =>
            val count = failures.merge(d.name, 1, Integer.sum)
            if count <= maxConsecutiveFailures then
                Log.warn(s"Failed to $label ${d.name} ($count consecutive): ${e.getMessage}")
            else if count == maxConsecutiveFailures + 1 then
                Log.error(s"Stopped logging $label failures for ${d.name} after $maxConsecutiveFailures attempts")
            else ()
            end if
        } {
            Abort.catching[Throwable] {
                action
            }
        }
    end handleWithBackoff

end FlagSync
