package kyo.grpc.compiler.builders

final case class Parameter(name: String, typeName: String, default: Option[String]) {

    def :=(default: String): Parameter = copy(default = Some(default))
}
