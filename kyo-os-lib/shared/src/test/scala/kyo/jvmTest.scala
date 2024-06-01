package kyo

import kyo.*

class jvmTest extends KyoTest:

    "execute new jvm and throw error" in run {
        assert {
            IOs.run(jvm.process(classOf[MainClass.type], "some-arg" :: Nil).waitFor).pure == 1
        }
    }

    "execute new jvm and end without error" in run {
        assert {
            IOs.run(jvm.process(classOf[MainClass.type]).waitFor).pure == 0
        }
    }

end jvmTest

object MainClass:
    def main(args: Array[String]) =
        if args.isEmpty then System.exit(0)
        else System.exit(1)
end MainClass
