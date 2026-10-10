package kyo.ffi.sbt

/** A platform a shared library can be delivered to, which is one of the platforms kyo publishes for.
  *
  * A type rather than a string because the set reaches a consumer's build through a properties file and comes back as
  * text. Naming the platform wrong in a module's `ffiNativeDelivery` is then a compile error in that build rather than
  * a resource-generator failure, and [[NativeDelivery.render]] cannot emit a name [[NativeDelivery.parse]] would not
  * recognize.
  */
sealed abstract class DeliveryPlatform(val name: String)

object DeliveryPlatform {

    /** The classpath carries the library and the loader extracts it, so nothing has to be delivered beside the jar. */
    case object Jvm extends DeliveryPlatform("jvm")

    /** koffi opens a file by path and never sees a classpath, so the library is written where Node resolves it. */
    case object Js extends DeliveryPlatform("js")

    /** Scala Native has no runtime loader, so the library is linked into the binary and staged beside it. */
    case object Native extends DeliveryPlatform("native")

    val all: Set[DeliveryPlatform] = Set(Jvm, Js, Native)

    /** The platform `name` names, or None when it names none.
      *
      * A declaration written by a newer kyo than the plugin reading it can carry a platform this one does not know.
      * None lets the reader drop it and deliver the rest, rather than failing a build over a platform it is not.
      */
    def of(name: String): Option[DeliveryPlatform] = all.find(_.name == name)
}
