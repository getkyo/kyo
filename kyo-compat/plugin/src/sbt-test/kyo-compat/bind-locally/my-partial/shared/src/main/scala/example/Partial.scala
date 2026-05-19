package example

// Stand-in source file for the partial-binding scenario in the
// bind-locally scripted test. The check inspects libraryDependencies
// + project dependencies via state extraction only — this file just
// needs to compile in every cell.
object Partial {
    def name: String = "my-partial"
}
