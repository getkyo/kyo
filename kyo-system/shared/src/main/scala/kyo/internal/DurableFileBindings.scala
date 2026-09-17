package kyo.internal

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Native descriptors used exclusively by the durable replacement protocol.
  *
  * The native session owns the temporary descriptor and the destination's access-control
  * snapshot. For an existing destination it creates the temporary with restrictive permissions, then restores and
  * verifies the snapshot before synchronizing it. Native handles never cross through
  * another runtime's descriptor table.
  */
private[kyo] trait DurableFileBindings extends Ffi:
    def kyo_durable_mkdir(path: String, error: Buffer[Int])(using AllowUnsafe): Int
    def kyo_durable_verify_directory(path: String, error: Buffer[Int])(using AllowUnsafe): Int
    def kyo_durable_open(target: String, temporary: String, error: Buffer[Int])(using AllowUnsafe): Long
    def kyo_durable_write(handle: Long, position: Long, bytes: Buffer[Byte], length: Int)(using AllowUnsafe): Int
    def kyo_durable_truncate(handle: Long, size: Long)(using AllowUnsafe): Int
    def kyo_durable_sync(handle: Long)(using AllowUnsafe): Int
    def kyo_durable_close(handle: Long)(using AllowUnsafe): Int
end DurableFileBindings

private[kyo] object DurableFileBindings extends Ffi.Config(library = "kyo_system_durable", nativeBundled = true)
