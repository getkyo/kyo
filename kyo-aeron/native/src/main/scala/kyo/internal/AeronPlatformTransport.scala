package kyo.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.ffi.FfiNullPointer

/** Native platform selector, backed by the C client through kyo-ffi's `@extern` backend.
  *
  * The Scala source is identical to the JS and Wasm selector; codegen supplies the backend
  * difference (`@extern` here, koffi there).
  */
private[kyo] object AeronPlatformTransport:

    /** Starts an embedded media driver in `dir` and connects a client to it.
      *
      * `dir` must be unique per call (callers pass `Path.tempDir`), since Aeron otherwise routes
      * every runtime through its single default directory. The `@Ffi.blocking` downcalls park the
      * carrier under the scheduler's blocking monitor rather than stranding it. The driver is
      * just-launched, so the connect returns within milliseconds; the NULL path is [[external]]'s
      * concern.
      */
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        Sync.Unsafe.defer(Ffi.load[AeronBindings]).map { bindings =>
            for
                driver <- Sync.Unsafe.defer(bindings.driverStart(dir)).flatMap(_.safe.get)
                client <- Sync.Unsafe.defer(bindings.clientConnect(dir)).flatMap(_.safe.get)
            yield new AeronRuntime:
                val transport: AeronTransport = new FfiAeronTransport(bindings, client)
                def close()(using AllowUnsafe): Unit =
                    // Close order is load-bearing: the client holds an open connection to the
                    // conductor, so closing the driver first leaves it in an invalid state. These
                    // are plain downcalls, so the conductor pthread-join parks the carrier; it is
                    // bounded and one-shot, unlike the connect @Ffi.blocking covers.
                    bindings.clientClose(client)
                    bindings.driverClose(driver)
                end close
        }
    end embedded

    /** Connects a client to a caller-owned external driver at `aeronDir`.
      *
      * No driver is started, so the returned runtime closes only the client. `clientConnect`
      * installs the C recording error handler.
      */
    def external(aeronDir: String)(using Frame): AeronRuntime < (Async & Abort[TopicTransportFailedException]) =
        Sync.Unsafe.defer(Ffi.load[AeronBindings]).map { bindings =>
            // A driver-absent connect returns NULL after the ~10s driver timeout, which the
            // generated binding raises as FfiNullPointer inside the fiber: a Panic, hence recover
            // rather than catch.
            val connect: Ffi.Handle[AeronClientHandle] < (Async & Abort[Any]) =
                Sync.Unsafe.defer(bindings.clientConnect(aeronDir)).flatMap(_.safe.get)
            Abort.recover[Any](
                onFail = mapConnectFailure,
                onPanic = mapConnectFailure
            )(connect).map { client =>
                new AeronRuntime:
                    val transport: AeronTransport        = new FfiAeronTransport(bindings, client)
                    def close()(using AllowUnsafe): Unit = bindings.clientClose(client)
                end new
            }
        }
    end external

    /** Maps a connect failure, treating the absent-driver NULL as the only expected one and
      * re-raising everything else as a panic so a genuine defect stays a defect.
      */
    private def mapConnectFailure(e: Any)(using Frame): Nothing < Abort[TopicTransportFailedException] =
        e match
            case n: FfiNullPointer =>
                Abort.fail(TopicTransportFailedException(Maybe(n.getMessage).filter(_.nonEmpty).getOrElse(n.toString)))
            case t: Throwable => Abort.panic(t)
            case other        => Abort.panic(new RuntimeException(s"unexpected Aeron connect failure: $other"))
end AeronPlatformTransport
