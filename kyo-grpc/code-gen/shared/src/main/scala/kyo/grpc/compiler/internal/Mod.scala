package kyo.grpc.compiler.internal

private[compiler] object Mod extends Choice {
    override type A = String

    val Case     = "case"
    val Override = "override"
    val Private  = "private"
}
