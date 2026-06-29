package kyo

import kyo.schema.doc

class DocTest extends kyo.test.Test[Any]:

    "doc annotation carries its text" in {
        val d = doc("hello world")
        assert(d.text == "hello world")
        assert(d.isInstanceOf[scala.annotation.StaticAnnotation])
    }

end DocTest
