package kyo.internal

import kyo.*
import kyo.ffi.*

sealed private[kyo] trait ProcessPipeHandle

/** Owns a descriptor independently of the runtime's child-process reaper. A caller joins every read, write and cancellation before release.
  * Node uses libuv for stream readiness; these blocking operations are for the JVM and Native carrier boundary.
  */
private[kyo] trait ProcessPipeBindings extends Ffi:
    def pair(descriptors: Buffer[Int])(using AllowUnsafe): Int

    def available_descriptor(descriptor: Int)(using AllowUnsafe): Int

    @Ffi.blocking
    def init(descriptor: Int, error: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Maybe[Ffi.Handle[ProcessPipeHandle]], Any]

    @Ffi.blocking
    def read(pipe: Ffi.Handle[ProcessPipeHandle], bytes: Buffer[Byte], capacity: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    @Ffi.blocking
    def write(pipe: Ffi.Handle[ProcessPipeHandle], bytes: Buffer[Byte], offset: Int, length: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

    def cancel(pipe: Ffi.Handle[ProcessPipeHandle])(using AllowUnsafe): Int

    def stop(pipe: Ffi.Handle[ProcessPipeHandle])(using AllowUnsafe): Int

    @Ffi.blocking
    def release(pipe: Ffi.Handle[ProcessPipeHandle])(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end ProcessPipeBindings

private[kyo] object ProcessPipeBindings
    extends Ffi.Config(library = "kyo_process", symbolPrefix = "kyo_process_pipe_", nativeBundled = true)
