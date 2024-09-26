package kyo.grpc.compiler

object Types {

    val unit = "_root_.scala.Unit"

    def future(t: String) = s"_root_.scala.concurrent.Future[$t]"

    def pending(t: String, s: String) = s"_root_.kyo.<[$t, $s]"

    def pendingGrpcResponse(t: String) = s"_root_.kyo.<[$t, $grpcResponse]"

    def pendingGrpcRequest(t: String) = s"_root_.kyo.<[$t, $grpcRequest]"

    val frame = "_root_.kyo.Frame"

    def streamGrpcResponse(t: String) = s"_root_.kyo.Stream[$t, $grpcResponse]"

    def streamGrpcRequest(t: String) = s"_root_.kyo.Stream[$t, $grpcRequest]"

    val clientCall = "_root_.kyo.grpc.ClientCall"

    val grpcResponse = "_root_.kyo.grpc.GrpcResponse"

    val grpcRequest = "_root_.kyo.grpc.GrpcRequest"

    val serverHandler = "_root_.kyo.grpc.ServerHandler"

    val service = "_root_.kyo.grpc.Service"

    val javaServiceDescriptor = "_root_.com.google.protobuf.Descriptors.ServiceDescriptor"

    val callOptions = "_root_.io.grpc.CallOptions"

    val channel = "_root_.io.grpc.Channel"

    val serverServiceDefinition = "_root_.io.grpc.ServerServiceDefinition"

    def streamObserver(v: String) = s"_root_.io.grpc.stub.StreamObserver[$v]"

    val serverCalls = "_root_.io.grpc.stub.ServerCalls"

    val serviceDescriptor = "_root_.scalapb.descriptors.ServiceDescriptor"

    val abstractService = "_root_.scalapb.grpc.AbstractService"

    def serviceCompanion(a: String) = s"_root_.scalapb.grpc.ServiceCompanion[$a]"

}
