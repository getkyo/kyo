package kyo.internal

import kyo.*
import kyo.ffi.*

sealed private[kyo] trait ProcessLaunchHandle

/** Native preparation for a supervisor started and reaped by the host runtime's process API.
  *
  * The caller owns the pending init before awaiting it, retains its error buffer until completion, and joins every native call before
  * release. Endpoint and executable are copied from borrowed native strings while the handle is live.
  */
private[kyo] trait ProcessLaunchBindings extends Ffi:
    @Ffi.blocking
    def init(temporaryDirectory: String, error: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Maybe[Ffi.Handle[ProcessLaunchHandle]], Any]

    def executable(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Ffi.Borrowed[String]

    def endpoint(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Ffi.Borrowed[String]

    def take_listener(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Int

    def accept(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Int

    @Ffi.blocking
    def await(launch: Ffi.Handle[ProcessLaunchHandle], streamsMask: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    def cancel(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Int

    def helper_exited(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Int

    def disconnect(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Int

    def take_control(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Int

    def take_stream(launch: Ffi.Handle[ProcessLaunchHandle], stream: Int)(using AllowUnsafe): Int

    @Ffi.blocking
    def take_pipe(launch: Ffi.Handle[ProcessLaunchHandle], slot: Int, error: Buffer[Int])(using
        AllowUnsafe
    ): Fiber.Unsafe[Maybe[Ffi.Handle[ProcessPipeHandle]], Any]

    @Ffi.blocking
    def release(launch: Ffi.Handle[ProcessLaunchHandle])(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end ProcessLaunchBindings

private[kyo] object ProcessLaunchBindings
    extends Ffi.Config(library = "kyo_process", symbolPrefix = "kyo_process_launch_", nativeBundled = true)
