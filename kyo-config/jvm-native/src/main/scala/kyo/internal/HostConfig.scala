package kyo.internal

import scala.jdk.CollectionConverters.*

/** The process-wide environment variables and system properties that flags, rollouts, and `System.live` read.
  *
  * On the JVM and Scala Native these are the process's own, through `java.lang.System`: variables from the environment the process was
  * started with, properties from `-D` flags and `System.setProperty`.
  */
object HostConfig {

    /** The environment variable `name`, or `null` when it is not set. */
    def env(name: String): String = java.lang.System.getenv(name)

    /** The names of the environment variables that are set. */
    def envNames: Iterable[String] = java.lang.System.getenv().keySet().asScala.toList

    /** The system property `name`, or `null` when it is not set. */
    def property(name: String): String = java.lang.System.getProperty(name)

    /** The names of the system properties that are set. */
    def propertyNames: Iterable[String] = java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList
}
