package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.*

/** Struct with pointer-typed fields: opaque handle, string, function pointer.
  *
  * All three pointer field types must marshal correctly across all platforms.
  */
case class TaggedResource(handle: Ffi.Handle[ItHandle], label: String, callback: Int => Unit)

trait ItStructPtrBindings extends Ffi:
    /** Read the opaque handle from the struct, dereference as int*, return the stored int. */
    def kyo_it_struct_read_handle(res: TaggedResource)(using AllowUnsafe): Int

    /** Read the label string from the struct and return its length. */
    def kyo_it_struct_label_len(res: TaggedResource)(using AllowUnsafe): Int

    /** Fire the callback stored in the struct with the given value. */
    def kyo_it_struct_fire_callback(res: TaggedResource, value: Int)(using AllowUnsafe): Unit
end ItStructPtrBindings

object ItStructPtrBindings extends Ffi.Config(library = "kyo_it_bundled")
