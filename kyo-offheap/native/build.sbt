   // kyo/kyo-offheap/native/build.sbt
   name := "KyoOffheapNative"

   version := "0.1.0"

   scalaVersion := "2.13.10" // Use a stable Scala version

   // Enable Scala Native plugin
   enablePlugins(ScalaNativePlugin)

   // Add Scala Native dependency
   libraryDependencies += "org.scala-native" %% "scala-native" % "0.4.13" // Use the latest stable version

   // Add additional resolvers
   resolvers += "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases/"