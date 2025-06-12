package kyo.grpc.compiler.internal

import scala.language.implicitConversions

final private[compiler] case class Parameter(typeName: String, name: Option[String] = None, default: Option[String] = None) {

    def :=(default: String): Parameter = copy(default = Some(default))
}

private[compiler] object Parameter {

    def apply(name: String, typeName: String, default: Option[String]): Parameter =
        Parameter(typeName, Some(name), default)

    implicit def typeNameToParameter(typeName: String): Parameter = Parameter(typeName)
}
