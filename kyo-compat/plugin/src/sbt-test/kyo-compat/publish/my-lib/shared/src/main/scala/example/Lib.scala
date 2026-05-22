package example

// Trivial stand-in: the publish scripted test only inspects POM metadata,
// never the runtime API. Keeping this file dependency-free lets every
// (backend, platform) cell compile with no code-level coupling to the
// auto-injected `io.getkyo:kyo-compat-<backend>` jar (which is satisfied
// at update-time by the in-test fake-compat stub modules).
object Lib {
    def name: String = "my-lib"
}
