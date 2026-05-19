package example

// Minimal source so the shared-source-discovery has something at the
// path the plugin wires into Compile/unmanagedSourceDirectories. The
// scripted test does not invoke `compile` (kyo-compat-X is a stub
// version); this file just makes the directory non-empty.
object Lib {
    def name: String = "my-lib"
}
