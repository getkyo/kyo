package kyo.internal

import kyo.*
import kyo.ffi.Ffi
import kyo.ffi.FfiNullPointer

// Native: loads the FFI-backed AeronBindings, starts the embedded C media driver, connects
// a C client, and returns an AeronRuntime whose transport is the shared FfiAeronTransport.
// This is the per-platform selection mechanism; the JVM path uses a separate file under jvm/.
// The dir parameter is the unique directory allocated by the caller (Path.tempDir); passing
// a unique dir to driverStart and clientConnect eliminates the collision where two concurrent
// embedded() calls with null would share Aeron's single default directory.
private[kyo] object AeronPlatformTransport:
    // embedded(dir): the @Ffi.blocking driverStart/clientConnect return Fiber.Unsafe;
    // bridge each via .safe.get into Async (the call parks the carrier under the scheduler's
    // blocking monitor rather than stranding it). Both calls take the same unique dir. The
    // driver is just-launched and known-live, so clientConnect returns a non-NULL handle in a
    // few ms; the failure path (NULL -> FfiNullPointer) is the external primitive's concern,
    // not embedded's.
    def embedded(dir: String)(using Frame): AeronRuntime < Async =
        Sync.Unsafe.defer(Ffi.load[AeronBindings]).map { bindings =>
            // The binding call needs AllowUnsafe (binding tier); Sync.Unsafe.defer supplies it and
            // yields the Fiber.Unsafe, then .safe.get bridges into Async.
            for
                driver <- Sync.Unsafe.defer(bindings.driverStart(dir)).flatMap(_.safe.get)
                client <- Sync.Unsafe.defer(bindings.clientConnect(dir)).flatMap(_.safe.get)
            yield new AeronRuntime:
                val transport: AeronTransport = new FfiAeronTransport(bindings, client)
                def close()(using AllowUnsafe): Unit =
                    // Client must be closed before driver: the client holds an active
                    // connection to the conductor; closing the driver first would leave
                    // the client in an invalid state.
                    // clientClose/driverClose are plain (non-@Ffi.blocking) downcalls: they run
                    // synchronously and park the carrier under the scheduler's blocking monitor
                    // during the bounded conductor pthread-join at teardown. This is the bounded
                    // one-shot scope-exit join, distinct from the unbounded ~10 s connect that
                    // @Ffi.blocking covers (see AeronBindings); leaving it synchronous is intentional.
                    bindings.clientClose(client)
                    bindings.driverClose(driver)
                end close
        }
    end embedded

    // external(aeronDir): connect a C client to an external driver (caller-owned driver
    // lifecycle) via the @Ffi.blocking clientConnect(dir) (which already installs the C
    // recording error handler). No driver start. Bridge the Fiber.Unsafe via .safe.get; on a
    // driver-absent connect clientConnect returns NULL after the ~10 s driver-timeout. The
    // generated binding raises FfiNullPointer for that NULL; routed through the @Ffi.blocking
    // fiber it surfaces as a Panic at .safe.get, so it is recovered (not merely caught) and
    // mapped to a uniform Abort.fail(TopicTransportFailedException) eagerly and in-band. close() closes
    // ONLY the client.
    def external(aeronDir: String)(using Frame): AeronRuntime < (Async & Abort[TopicTransportFailedException]) =
        Sync.Unsafe.defer(Ffi.load[AeronBindings]).map { bindings =>
            // The .safe.get bridge of the @Ffi.blocking clientConnect carries an Abort[Any] error
            // channel (Fiber.Unsafe's error type is Any). The driver-absent NULL is raised as
            // FfiNullPointer inside the fiber and surfaces as a Panic; recover it (not just catch)
            // and map to a uniform TopicTransportFailedException, re-raising any other failure/panic.
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

    // Maps the @Ffi.blocking clientConnect NULL signal (FfiNullPointer, surfaced as a Panic or
    // failure through the fiber bridge) to a uniform Abort.fail(TopicTransportFailedException). Anything
    // else is re-raised as a panic: a genuine defect stays a defect; only the absent-driver NULL
    // is the expected, typed in-band connect failure.
    private def mapConnectFailure(e: Any)(using Frame): Nothing < Abort[TopicTransportFailedException] =
        e match
            case n: FfiNullPointer =>
                Abort.fail(TopicTransportFailedException(Maybe(n.getMessage).filter(_.nonEmpty).getOrElse(n.toString)))
            case t: Throwable => Abort.panic(t)
            case other        => Abort.panic(new RuntimeException(s"unexpected Aeron connect failure: $other"))
end AeronPlatformTransport
