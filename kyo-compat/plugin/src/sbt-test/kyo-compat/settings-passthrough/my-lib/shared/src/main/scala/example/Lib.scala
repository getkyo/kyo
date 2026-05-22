package example

// Trivial dep-free stand-in. The settings-passthrough scripted test
// inspects setting values and runs a forked test; it does not exercise
// any runtime API on this object.
object Lib {
    def name: String = "my-lib"
}
