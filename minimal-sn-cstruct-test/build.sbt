scalaVersion := "3.4.2" // Downgraded from 3.7.0
enablePlugins(ScalaNativePlugin)

name := "minimal-sn-cstruct-test"
version := "0.1.0"

// Optional: Add nativeConfig
// nativeConfig ~= { config =>
//   config
//     .withGC(scala.scalanative.build.GC.immix) // Immix is default in SN 0.5.x
//     .withMode(scala.scalanative.build.Mode.debug)
// }