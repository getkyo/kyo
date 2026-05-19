package example

// Stand-in source file for the compatKyoVersion-override scenario in the
// publish scripted test. The check inspects POM metadata only, so this
// file just needs to compile in every cell.
object Override {
    def name: String = "my-override"
}
