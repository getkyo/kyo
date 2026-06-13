package kyo

abstract class BaseWhatsAppTest extends kyo.test.Test[Any]:

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

end BaseWhatsAppTest
