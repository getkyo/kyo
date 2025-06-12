package kyo.grpc.compiler.internal

final private[compiler] case class Parameter(name: String, typeName: String, default: Option[String]) {

    def :=(default: String): Parameter = copy(default = Some(default))
}
