// Wire the in-tree `kyo-compat-plugin` plugin (artifact `kyo-compat-plugin`) into the
// meta-build so that `build.sbt` can call `compatLibrary("...")...` and
// reference `CompatBackend.{Future, Kyo, Zio, Ce, Ox}` directly.
//
// `kyo-compat-plugin` is also declared as a regular project in the root
// `build.sbt`, but that project is only built when the outer build
// asks for it (`kyo-compat-plugin/compile`, `kyo-compat-plugin/scripted`, etc.). To
// make the plugin's classes available DURING outer-build evaluation
// (so that `compatLibrary(...)` parses), the plugin's source directory
// is added to the meta-build's own compile sources.
//
// Plugin compile-time deps (sbt-scalajs-crossproject,
// sbt-scala-native-crossproject) come from `project/plugins.sbt` —
// they're already there because the outer build itself uses
// crossProject in build.sbt.
Compile / unmanagedSourceDirectories +=
    baseDirectory.value.getParentFile / "kyo-compat" / "plugin" / "src" / "main" / "scala"

// Wire the in-tree `kyo-doctest-plugin` plugin into the meta-build so build.sbt
// can reference `KyoDoctestPlugin` + its setting keys without resolving the
// plugin from ivy. Same pattern as kyo-compat-plugin above. Eliminates the
// chicken-and-egg of "plugin must be published to load the build that
// publishes the plugin."
Compile / unmanagedSourceDirectories +=
    baseDirectory.value.getParentFile / "kyo-doctest" / "plugin" / "src" / "main" / "scala"
