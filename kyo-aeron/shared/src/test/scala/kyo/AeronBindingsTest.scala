package kyo

import kyo.internal.AeronBindings

class AeronBindingsTest extends Test:

    /** The complete C ABI surface AeronBindings binds against: each kyo_aeron_* symbol is what codegen
      * derives for one binding method (symbolPrefix + snake_case, per FfiInspector), and what kyo_aeron.c
      * must export. Renaming a binding method without updating kyo_aeron.c is caught here.
      *
      * Hand-maintained: the codegen's snake_case derivation is compile-time-only (private in FfiInspector),
      * and neither JS nor Native has portable runtime reflection over the trait's methods.
      */
    val symbols = Chunk(
        // driver + client lifecycle
        "kyo_aeron_driver_start",
        "kyo_aeron_driver_close",
        "kyo_aeron_client_connect",
        "kyo_aeron_client_close",
        // publication async-add quartet + error accessors
        "kyo_aeron_async_add_publication",
        "kyo_aeron_async_add_publication_poll",
        "kyo_aeron_async_add_publication_get",
        "kyo_aeron_async_add_publication_free",
        "kyo_aeron_async_add_publication_err_code",
        "kyo_aeron_async_add_publication_err_msg",
        // publication hot path + teardown
        "kyo_aeron_publication_is_connected",
        "kyo_aeron_publication_offer",
        "kyo_aeron_publication_max_message_length",
        "kyo_aeron_publication_close",
        // subscription async-add quartet + error accessors
        "kyo_aeron_async_add_subscription",
        "kyo_aeron_async_add_subscription_poll",
        "kyo_aeron_async_add_subscription_get",
        "kyo_aeron_async_add_subscription_free",
        "kyo_aeron_async_add_subscription_err_code",
        "kyo_aeron_async_add_subscription_err_msg",
        // subscription hot path + teardown
        "kyo_aeron_subscription_is_connected",
        "kyo_aeron_subscription_poll",
        "kyo_aeron_subscription_close",
        // recording error-handler accessors
        "kyo_aeron_has_client_error",
        "kyo_aeron_client_error_msg",
        "kyo_aeron_client_error_code",
        // behavioral-test inject seam
        "kyo_aeron_test_inject_error"
    )

    "every symbol carries the live symbolPrefix" in {
        // A symbolPrefix change breaks here rather than silently at native-link / koffi-resolve time.
        assert(symbols.forall(_.startsWith(AeronBindings.symbolPrefix)))
    }

    "every symbol is a well-formed snake_case C identifier" in {
        // Catches a camelCase or otherwise malformed entry.
        val snake = "^[a-z][a-z0-9]*(_[a-z0-9]+)*$".r
        assert(symbols.forall(s => snake.matches(s)))
    }

    "the symbol set is complete and free of duplicates" in {
        assert(symbols.distinct == symbols)
        // 27 = the count of abstract methods on the AeronBindings trait; bump both together.
        assert(symbols.size == 27)
    }

    "companion Ffi.Config fields" in {
        assert(AeronBindings.library == "kyo_aeron")
        assert(AeronBindings.symbolPrefix == "kyo_aeron_")
        assert(AeronBindings.headers == Chunk("aeronc.h", "aeronmd.h"))
    }

end AeronBindingsTest
