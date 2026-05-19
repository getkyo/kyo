package example

// Trivial stand-in: the aggregator-runs scripted test only verifies that
// every cell's `Test/test` actually executed (sentinel-file check).
// No runtime API surface is asserted.
object Lib {
    def name: String = "my-lib"
}
