package kyo

class DocTest extends kyo.test.Test[Any]:

    "doc annotation carries its value" in {
        val d = doc("hello world")
        assert(d.value == "hello world")
        assert(d.isInstanceOf[scala.annotation.StaticAnnotation])
    }

end DocTest
