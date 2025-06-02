package kyo.grpc.compiler.internal

private[compiler] object Types {

    def pendingGrpcResponse(t: String) = s"_root_.kyo.<[$t, $grpcResponse]"

    def pendingGrpcRequest(t: String) = s"_root_.kyo.<[$t, $grpcRequest]"

    def streamGrpcResponse(t: String) = s"_root_.kyo.Stream[$t, $grpcResponse]"

    def streamGrpcRequest(t: String) = s"_root_.kyo.Stream[$t, $grpcRequest]"

    val clientCall = "_root_.kyo.grpc.ClientCall"

    val grpcResponse = "_root_.kyo.grpc.GrpcResponse"

    val grpcRequest = "_root_.kyo.grpc.GrpcRequest"

    val serverHandler = "_root_.kyo.grpc.ServerHandler"

    val service = "_root_.kyo.grpc.Service"

    val callOptions = "_root_.io.grpc.CallOptions"

    val channel = "_root_.io.grpc.Channel"

    val serverServiceDefinition = "_root_.io.grpc.ServerServiceDefinition"

}
