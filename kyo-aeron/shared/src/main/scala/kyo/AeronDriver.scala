package kyo

import kyo.internal.AeronDriverRuntime
import kyo.internal.AeronPlatform

/** A `Scope`-managed embedded Aeron media driver that several clients can share.
  *
  * `Topic.run(v)` already launches a driver per scope, which is the right shape when one scope owns
  * the messaging. Reach for this instead when many clients must meet on one medium: launch the
  * driver once, then hand [[directory]] to [[AeronClient.connect]] or `Topic.run(aeronDir)` as often
  * as needed. A pool of worker processes talking over `aeron:ipc` is the motivating case.
  *
  * The driver owns a directory it allocates itself, and the enclosing `Scope` owns the driver: on
  * scope exit it stops the driver, joins its conductor threads, and removes the directory, whether
  * that exit is normal, an error, or a cancellation. Clients connected to [[directory]] are the
  * caller's to close first, exactly as they are with any externally-running driver.
  *
  * @see [[AeronDriver.launch]] to start one
  * @see [[AeronClient.connect]] to connect a client to [[directory]]
  */
opaque type AeronDriver = AeronDriver.State

object AeronDriver:

    extension (self: AeronDriver)
        /** The directory this driver runs in, to connect clients to.
          *
          * Allocated per driver, so concurrent drivers never collide on a CnC file.
          */
        def directory: Path = self.dir

        /** Exposes the low-level [[AeronDriver.Unsafe]] view of this driver. */
        def unsafe: Unsafe = self.unsafeView
    end extension

    /** Driver timeouts worth raising above Aeron's defaults when one driver is shared.
      *
      * Aeron's defaults assume a client conductor that gets CPU promptly. A host running many
      * clients (or a loaded CI machine) can stall one past `clientLivenessTimeout`, which the driver
      * treats as client death and tears the client's publications down. Raising both keeps a
      * momentarily starved client alive.
      *
      * @param clientLivenessTimeout
      *   how long a client may go unheard before the driver declares it dead
      * @param publicationUnblockTimeout
      *   how long a stalled publication may block others before the driver unblocks it; Aeron
      *   requires it to exceed `clientLivenessTimeout`
      */
    final case class Settings(
        clientLivenessTimeout: Duration = Duration.Zero,
        publicationUnblockTimeout: Duration = Duration.Zero
    ) derives CanEqual

    object Settings:
        /** Aeron's own defaults, which [[Duration.Zero]] selects for both timeouts. */
        val default: Settings = Settings()
    end Settings

    /** Launches an embedded media driver and binds its lifetime to the current `Scope`.
      *
      * @param settings
      *   driver timeouts; [[Settings.default]] keeps Aeron's own
      * @return
      *   a `Scope`-managed [[AeronDriver]], aborting [[TopicTransportFailedException]] when the
      *   driver cannot start or `settings` are inconsistent
      */
    def launch(settings: Settings = Settings.default)(using
        Frame
    ): AeronDriver < (Scope & Async & Abort[TopicTransportFailedException]) =
        Scope.acquireRelease(launchUnscoped(settings))(driver => Sync.Unsafe.defer(driver.unsafe.close()))

    /** Launches a driver whose lifetime the caller owns (mirrors [[AeronClient.connectUnscoped]]).
      *
      * Reach for this only when the driver must outlive every enclosing `Scope`. The caller MUST
      * close it exactly once via `driver.unsafe.close()`; nothing else will, and until it does the
      * driver's conductor threads and its directory both stay live. Prefer [[launch]] whenever a
      * `Scope` can own the lifetime.
      *
      * @param settings
      *   driver timeouts; [[Settings.default]] keeps Aeron's own
      * @return
      *   an unscoped [[AeronDriver]] the caller closes, aborting [[TopicTransportFailedException]]
      *   when the driver cannot start or `settings` are inconsistent
      */
    def launchUnscoped(settings: Settings = Settings.default)(using
        Frame
    ): AeronDriver < (Async & Abort[TopicTransportFailedException]) =
        // Checked here rather than left to the driver: Aeron rejects the combination at startup with
        // a message that does not name the caller's setting.
        if settings.publicationUnblockTimeout != Duration.Zero &&
            settings.publicationUnblockTimeout <= settings.clientLivenessTimeout
        then
            Abort.fail(TopicTransportFailedException(
                s"publicationUnblockTimeout (${settings.publicationUnblockTimeout}) must exceed " +
                    s"clientLivenessTimeout (${settings.clientLivenessTimeout})"
            ))
        else
            // tempDirUnscoped, not the `Scope`-managed Path.tempDir: this driver's directory belongs to
            // the caller for exactly as long as the driver does, and no enclosing scope may remove it.
            Abort.run[FileStructureException](Path.tempDirUnscoped("kyo-aeron-driver")).map {
                case Result.Success(dir) =>
                    // The directory outlives the start call, so a failed start removes it here rather
                    // than leaving an empty directory behind for the caller to notice.
                    Abort.recover[Nothing](
                        onFail = (never: Nothing) => never,
                        onPanic = (t: Throwable) =>
                            // Unsafe: removes host-tier, matching the tier tempDirUnscoped created it in,
                            // and discards the outcome so cleanup cannot replace the panic it follows.
                            Sync.Unsafe.defer(discard(dir.unsafe.removeAll())).andThen(Abort.panic(t))
                    ) {
                        AeronPlatform.driver(
                            dir.unsafe.show,
                            settings.clientLivenessTimeout.toNanos,
                            settings.publicationUnblockTimeout.toNanos
                        ).map(runtime => Sync.Unsafe.defer(State(dir, runtime).asInstanceOf[AeronDriver]))
                    }
                case Result.Failure(e) => Abort.panic(e)
                case Result.Panic(t)   => Abort.panic(t)
            }
        end if
    end launchUnscoped

    /** Pairs the allocated directory with the running driver, so closing can stop the driver and
      * remove the directory in that order.
      */
    final private[kyo] class State private[kyo] (
        private[kyo] val dir: Path,
        private[kyo] val runtime: AeronDriverRuntime
    )(using Frame):
        private[kyo] def unsafeView: Unsafe =
            new Unsafe:
                def close()(using AllowUnsafe): Unit =
                    // Stop the driver before removing the directory: the driver writes into it until
                    // its conductor threads are joined.
                    runtime.close()
                    discard(dir.unsafe.removeAll())
                end close
                def safe: AeronDriver = State.this.asInstanceOf[AeronDriver]
    end State

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See
      * AllowUnsafe for more details.
      */
    sealed abstract class Unsafe extends Serializable:
        def close()(using AllowUnsafe): Unit
        def safe: AeronDriver
    end Unsafe
end AeronDriver
