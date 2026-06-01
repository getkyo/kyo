package kyo

/** Verifies Lsp.local reads as Absent outside a handler invocation (INV-094). */
class LspLocalTest extends Test:

    "Lsp.local is Absent outside handler" in run {
        Lsp.local.use { value =>
            assert(value == Absent)
        }
    }

end LspLocalTest
