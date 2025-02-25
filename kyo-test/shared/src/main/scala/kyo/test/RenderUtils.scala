package kyo.test

import scala.Console as SConsole

private[test] object ConsoleUtils:
    def underlined(s: String): String =
        SConsole.UNDERLINED + s + SConsole.RESET

    def green(s: String): String =
        SConsole.GREEN + s + SConsole.RESET

    def yellow(s: String): String =
        SConsole.YELLOW + s + SConsole.RESET

    def red(s: String): String =
        SConsole.RED + s + SConsole.RESET

    def blue(s: String): String =
        SConsole.BLUE + s + SConsole.RESET

    def magenta(s: String): String =
        SConsole.MAGENTA + s + SConsole.RESET

    def cyan(s: String): String =
        SConsole.CYAN + s + SConsole.RESET

    def dim(s: String): String =
        "\u001b[2m" + s + SConsole.RESET

    def bold(s: String): String =
        SConsole.BOLD + s + SConsole.RESET

    def ansi(ansiColor: String, s: String): String =
        ansiColor + s + SConsole.RESET
end ConsoleUtils
