package kyo.net.fixture

object HttpMain:
    def main(args: Array[String]): Unit =
        println(s"kyo-consumer http_status=${kyo.HttpStatus.OK}")
        ConsumerReport.print("kyo-http")
