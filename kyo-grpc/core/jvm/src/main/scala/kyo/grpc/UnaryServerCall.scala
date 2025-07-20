package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler

class UnaryServerCallHandler[Request, Response] extends ServerCallHandler[Request, Response]:
    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] =
        new UnaryServerCallListener

class UnaryServerCallListener[Request, Response] extends ServerCall.Listener[Request]:
    override def onMessage(message: Request): Unit = ???

    override def onHalfClose(): Unit = ???

    override def onCancel(): Unit = ???

    override def onComplete(): Unit = ???

    override def onReady(): Unit = ???
end UnaryServerCallListener
