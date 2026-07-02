package kyo

import scala.jdk.CollectionConverters.*

private[kyo] object FlagPlatform {

    def property(name: String): String =
        java.lang.System.getProperty(name)

    def properties: Iterable[String] =
        java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList

    def env(name: String): String =
        java.lang.System.getenv(name)

    def envNames: Iterable[String] =
        java.lang.System.getenv().keySet().asScala.toList

}
