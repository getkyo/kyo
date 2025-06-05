package io.aeron.driver

// AeronDriverContextFields is now top-level in this package,
// so direct usage or a direct import (if needed across different files in same package for clarity)
// is correct. The CStructs object was removed.
// import io.aeron.driver.CStructs.AeronDriverContextFields; // This is no longer valid
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.util.control.NonFatal

class MediaDriver private[driver] (
    private var driver_ptr: Ptr[CApi.aeron_driver_t],
    private var context_ptr: Ptr[AeronDriverContextFields], // Refers to top-level AeronDriverContextFields in this package
    private val aeronDirNameStr: String
) extends AutoCloseable:

    def aeronDirectoryName(): String = aeronDirNameStr

    def close(): Unit =
        var result = 0
        if driver_ptr != null then
            result = CApi.aeron_driver_close(driver_ptr)
            if result != 0 then
                System.err.println(
                    s"Kyo-Aeron: Error closing Aeron driver: ${fromCString(CApi.aeron_errmsg())} (code: ${CApi.aeron_errcode()})"
                )
            end if
            driver_ptr = null // prevent double close
        end if

        if context_ptr != null then
            result = CApi.aeron_driver_context_close(context_ptr)
            if result != 0 then
                System.err.println(
                    s"Kyo-Aeron: Error closing Aeron driver context: ${fromCString(CApi.aeron_errmsg())} (code: ${CApi.aeron_errcode()})"
                )
            end if
            context_ptr = null // prevent double close
        end if
    end close

    // Ensure finalizer calls close to attempt resource cleanup if user forgets
    override def finalize(): Unit =
        try
            if driver_ptr != null || context_ptr != null then
                System.err.println(s"Kyo-Aeron: MediaDriver for $aeronDirNameStr not closed explicitly. Attempting cleanup.")
                close()
        finally
            super.finalize()
        end try
    end finalize
end MediaDriver

object MediaDriver:
    def launchEmbedded(): MediaDriver =
        Zone.acquire { implicit z =>
            val context_ptr_s_ptr = alloc[Ptr[AeronDriverContextFields]]() // Refers to top-level AeronDriverContextFields
            var driver_context_s_ptr: Ptr[AeronDriverContextFields] = null
            var driver_ptr: Ptr[CApi.aeron_driver_t]                = null
            var aeronDirName: String                                = "unknown-aeron-dir" // Default

            try
                var result = CApi.aeron_driver_context_init(context_ptr_s_ptr)
                if result != 0 then
                    throw new RuntimeException(
                        s"Failed to init Aeron driver context: ${fromCString(CApi.aeron_errmsg())} (code: ${CApi.aeron_errcode()})"
                    )
                end if

                driver_context_s_ptr = !context_ptr_s_ptr
                val context_fields_ptr = driver_context_s_ptr

                if context_fields_ptr.aeron_dir != null then
                    aeronDirName = fromCString(context_fields_ptr.aeron_dir)
                else
                    System.err.println("Kyo-Aeron: Warning - aeron_dir from native context is null.")
                end if

                context_fields_ptr.dirs_delete_on_start = true

                val current_driver_ptr_ptr = alloc[Ptr[CApi.aeron_driver_t]]()
                result = CApi.aeron_driver_init(current_driver_ptr_ptr, driver_context_s_ptr)
                if result != 0 then
                    val errMsg = fromCString(CApi.aeron_errmsg())
                    if driver_context_s_ptr != null then
                        val _ = CApi.aeron_driver_context_close(driver_context_s_ptr)
                    throw new RuntimeException(s"Failed to init Aeron driver: $errMsg (code: ${CApi.aeron_errcode()})")
                end if

                driver_ptr = !current_driver_ptr_ptr

                result = CApi.aeron_driver_start(driver_ptr, true) // true for as_daemon
                if result != 0 then
                    val errMsg = fromCString(CApi.aeron_errmsg())
                    if driver_ptr != null then
                        val _ = CApi.aeron_driver_close(driver_ptr)
                    if driver_context_s_ptr != null then
                        val _ = CApi.aeron_driver_context_close(driver_context_s_ptr)
                    throw new RuntimeException(s"Failed to start Aeron driver: $errMsg (code: ${CApi.aeron_errcode()})")
                end if

                val mediaDriver = new MediaDriver(driver_ptr, driver_context_s_ptr, aeronDirName)
                // Ownership transferred to MediaDriver instance
                driver_ptr = null
                driver_context_s_ptr = null
                mediaDriver
            catch
                case NonFatal(e) =>
                    // Ensure resources are cleaned up if an exception occurs before MediaDriver instance is created
                    // or before ownership is transferred.
                    if driver_ptr != null then
                        val _ = CApi.aeron_driver_close(driver_ptr)
                    if driver_context_s_ptr != null then
                        val _ = CApi.aeron_driver_context_close(driver_context_s_ptr)
                    throw e
            end try
        }
    end launchEmbedded
end MediaDriver
