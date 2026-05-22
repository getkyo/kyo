package kyo.compat

// Stand-in for a published kyo-compat-future. The bind-locally scripted
// test verifies that when the user binds Future to this local CrossProject
// via .copy(localBindings = ...), the generated myLibFuture depends on it
// directly (no maven coords for io.getkyo:kyo-compat-future).
object FakeMarker {
    val tag: String = "fake-compat-future"
}
