package kyo.grpc.compiler.internal

private[compiler] final case class Parameter(name: String, typeName: String, default: Option[String]) {

    def :=(default: String): Parameter = copy(default = Some(default))
}
