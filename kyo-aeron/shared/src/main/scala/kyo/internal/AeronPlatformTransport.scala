package kyo.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.ffi.FfiNullPointer

/** Platform selector, backed by the C client through kyo-ffi over the `kyo_aeron.c` shim.
  *
  * One shared source serves every platform; codegen supplies the backend difference (Panama
  * downcalls on the JVM, `@extern` on Native, koffi on JS and Wasm). The C client and its embedded
  * driver are the same pinned Aeron across all four, so the transport behaves identically everywhere.
  */
private[kyo] object AeronPlatformTransport:

    /** Starts an embedded media driver in `dir` and connects a client to it.
      *
      * `dir` must be unique per call (callers pass `Path.tempDir`), since Aeron otherwise routes
      * every runtime through its single default directory. The `@Ffi.blocking` downcalls bridge
      * through the platform's blocking mechanism (the JVM and Native park the carrier under the
      * scheduler's blocking monitor; JS and Wasm dispatch to a libuv worker, leaving the event loop
      * free) rather than stranding the caller during the ~10s connect. The driver is just-launched,
      * so the connect returns within milliseconds; the NULL path is [[external]]'s concern.
      */
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        Sync.Unsafe.defer(Ffi.load[AeronBindings]).map { bindings =>
            for
                driver <- Sync.Unsafe.defer(bindings.driverStart(dir)).flatMap(_.safe.get)
                client <- Sync.Unsafe.defer(bindings.clientConnect(dir)).flatMap(_.safe.get)
                runtime <- Sync.Unsafe.defer {
                    val ffiTransport = new FfiAeronTransport(bindings, client)
                    new AeronRuntime:
                        val transport: AeronTransport = ffiTransport
                        def close()(using AllowUnsafe): Unit =
                            // Close order is load-bearing: the client holds an open connection to the
                            // conductor, so closing the driver first leaves it in an invalid state. These
                            // are plain downcalls, so the conductor pthread-join runs inline; it is bounded
                            // and one-shot (it parks the carrier on the JVM and Native, briefly freezes the
                            // event loop on JS and Wasm), unlike the connect that @Ffi.blocking covers.
                            ffiTransport.closeClient()
                            bindings.driverClose(driver)
                        end close
                    end new
                }
            yield runtime
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
            // rather than catch. A `@Ffi.blocking` binding returns `Fiber.Unsafe[A, Any]`, whose
            // second parameter is the effect row, not an error type: `Any` is the empty row, so
            // `.safe.get` is `A < Async` and carries no typed failure. Only the panic branch can
            // fire, which is why onFail is uninhabited here.
            val connect: Ffi.Handle[AeronClientHandle] < Async =
                Sync.Unsafe.defer(bindings.clientConnect(aeronDir)).flatMap(_.safe.get)
            Abort.recover[Nothing](
                onFail = (never: Nothing) => never,
                onPanic = mapConnectPanic
            )(connect).map { client =>
                Sync.Unsafe.defer {
                    val ffiTransport = new FfiAeronTransport(bindings, client)
                    new AeronRuntime:
                        val transport: AeronTransport        = ffiTransport
                        def close()(using AllowUnsafe): Unit = ffiTransport.closeClient()
                    end new
                }
            }
        }
    end external

    /** Maps a connect panic, treating the absent-driver NULL as the only expected one and
      * re-raising everything else so a genuine defect stays a defect.
      */
    private def mapConnectPanic(t: Throwable)(using Frame): Nothing < Abort[TopicTransportFailedException] =
        t match
            case n: FfiNullPointer =>
                Abort.fail(TopicTransportFailedException(Maybe(n.getMessage).filter(_.nonEmpty).getOrElse(n.toString), n))
            case other => Abort.panic(other)
end AeronPlatformTransport
