package io.aeron.driver

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
// scala.compiletime.uninitialized is not needed for type aliases or externs

// Define AeronDriverContextFields as a type alias to CStructN
// IMPORTANT: The types and order CString, Ptr[Byte] (5 times), CBool
// MUST match the first 7 fields of the actual aeron_driver_context_s C struct.
// This is a placeholder based on previous guesses.
type AeronDriverContextFields = CStruct7[
    CString,   // _1: aeron_dir (const char*)
    Ptr[Byte], // _2: Placeholder for whatever is the 2nd field
    Ptr[Byte], // _3: Placeholder
    Ptr[Byte], // _4: Placeholder
    Ptr[Byte], // _5: Placeholder
    Ptr[Byte], // _6: Placeholder
    CBool      // _7: dirs_delete_on_start (bool)
]

// Optional: Extension methods for named field access
extension (ctxPtr: Ptr[AeronDriverContextFields])
    @inline def aeron_dir: CString = ctxPtr._1
    // No setter for aeron_dir as it's typically const char* and set by aeron_driver_context_init

    @inline def dirs_delete_on_start: CBool                  = ctxPtr._7
    @inline def `dirs_delete_on_start_=`(value: CBool): Unit = ctxPtr._7 = value
end extension

@extern
@link("aeronc") // This will be needed for linking later
object CApi:
    type aeron_driver_t // Opaque struct for the driver instance

    // Signatures now use the type alias AeronDriverContextFields
    def aeron_driver_context_init(context_ptr_ptr: Ptr[Ptr[AeronDriverContextFields]]): CInt                      = extern
    def aeron_driver_init(driver_ptr_ptr: Ptr[Ptr[aeron_driver_t]], context: Ptr[AeronDriverContextFields]): CInt = extern
    def aeron_driver_start(driver: Ptr[aeron_driver_t], as_daemon: CBool): CInt                                   = extern
    def aeron_driver_close(driver: Ptr[aeron_driver_t]): CInt                                                     = extern
    def aeron_driver_context_close(context: Ptr[AeronDriverContextFields]): CInt                                  = extern

    def aeron_errmsg(): CString = extern
    def aeron_errcode(): CInt   = extern
end CApi
