package kyo

import scala.jdk.CollectionConverters.*
import scala.scalajs.js

private[kyo] object FlagPlatform {

    def property(name: String): String =
        java.lang.System.getProperty(name)

    def properties: Iterable[String] =
        java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList

    def env(name: String): String = {
        val proc = js.Dynamic.global.process
        if (js.typeOf(proc) == "undefined" || js.typeOf(proc.env) == "undefined") null
        else {
            val value = proc.env.selectDynamic(name)
            if (js.isUndefined(value) || value == null) null
            else value.asInstanceOf[String]
        }
    }

    def envNames: Iterable[String] = {
        val proc = js.Dynamic.global.process
        if (js.typeOf(proc) == "undefined" || js.typeOf(proc.env) == "undefined") Iterable.empty
        else js.Object.keys(proc.env.asInstanceOf[js.Object]).toSeq
    }

}
