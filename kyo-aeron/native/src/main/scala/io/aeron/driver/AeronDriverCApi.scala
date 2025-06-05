package io.aeron.driver

import scala.compiletime.uninitialized
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

// AeronDriverContextFields is a top-level class extending CStruct
class AeronDriverContextFields extends CStruct:
    var aeron_dir: CString          = uninitialized
    var padding_ptr_1: Ptr[Byte]    = uninitialized
    var padding_ptr_2: Ptr[Byte]    = uninitialized
    var padding_ptr_3: Ptr[Byte]    = uninitialized
    var padding_ptr_4: Ptr[Byte]    = uninitialized
    var padding_ptr_5: Ptr[Byte]    = uninitialized
    var dirs_delete_on_start: CBool = uninitialized
end AeronDriverContextFields

@extern
@link("aeronc") // Common name for Aeron C library, might be aeronmd
object CApi:
    // In C, aeron_driver_context_t is typically `typedef struct aeron_driver_context_s aeron_driver_context_t;`
    // So, Ptr[aeron_driver_context_t] in C is Ptr[Ptr[aeron_driver_context_s]].
    // Our `aeron_driver_context_t` here should represent `struct aeron_driver_context_s`.
    // And the functions will take Ptr[aeron_driver_context_s].
    // aeron_driver_context_s is no longer an alias here, we use CStructs.AeronDriverContextFields directly.
    // type aeron_driver_context_s = CStructs.AeronDriverContextFields // Removed this alias
    type aeron_driver_t // Opaque struct for the driver instance

    def aeron_driver_context_init(context_ptr_ptr: Ptr[Ptr[AeronDriverContextFields]]): CInt = extern

    // aeron_dir is accessed via context_ptr->aeron_dir
    // dirs_delete_on_start is accessed via context_ptr->dirs_delete_on_start

    def aeron_driver_init(driver_ptr_ptr: Ptr[Ptr[aeron_driver_t]], context: Ptr[AeronDriverContextFields]): CInt = extern
    def aeron_driver_start(driver: Ptr[aeron_driver_t], as_daemon: CBool): CInt                                   = extern
    def aeron_driver_close(driver: Ptr[aeron_driver_t]): CInt                                                     = extern
    def aeron_driver_context_close(context: Ptr[AeronDriverContextFields]): CInt                                  = extern

    def aeron_errmsg(): CString = extern
    def aeron_errcode(): CInt   = extern
end CApi
