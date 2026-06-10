package kyo

/** Verifies Lsp.local reads as Absent outside a handler invocation. */
class LspLocalTest extends Test:

    "Lsp.local is Absent outside handler" in {
        Lsp.local.use { value =>
            assert(value == Absent)
        }
    }

end LspLocalTest
