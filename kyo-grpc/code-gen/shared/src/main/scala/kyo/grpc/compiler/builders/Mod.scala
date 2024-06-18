package kyo.grpc.compiler.builders

object Mod extends Choice {
    override type A = String

    val Case = "case"
    val Final = "case"
    val Override = "override"
    val Private = "private"
}
