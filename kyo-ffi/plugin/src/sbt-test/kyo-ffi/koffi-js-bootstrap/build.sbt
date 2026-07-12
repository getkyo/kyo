import org.scalajs.sbtplugin.ScalaJSPlugin

// ffiKoffiJsBootstrap runs a real npm install on Test / compile, so the fixture asserts on the files it
// produces (package.json plus koffi under node_modules), which is what a settings-shape assertion cannot do.
lazy val root = (project in file("."))
    .enablePlugins(KyoFfiPlugin, ScalaJSPlugin)
    .settings(
        scalaVersion := "3.8.3",
        ffiLibraryId := "test_bootstrap_lib",
        ffiKoffiJsBootstrap("probe-bootstrap")
    )
