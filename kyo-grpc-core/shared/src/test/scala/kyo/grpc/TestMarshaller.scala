package kyo.grpc

import io.grpc.MethodDescriptor.Marshaller
import java.io.*

class TestMarshaller[T] extends Marshaller[T]:
    override def stream(value: T): InputStream =
        ByteArrayInputStream(value.toString.getBytes)
    override def parse(stream: InputStream): T =
        null.asInstanceOf[T]
end TestMarshaller
