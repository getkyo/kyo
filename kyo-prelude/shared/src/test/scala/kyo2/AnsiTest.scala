package kyo2

class AnsiTest extends Test:

    import Ansi.*

    "black" in {
        assert("test".black == "\u001b[30mtest\u001b[0m")
    }

    "red" in {
        assert("test".red == "\u001b[31mtest\u001b[0m")
    }

    "green" in {
        assert("test".green == "\u001b[32mtest\u001b[0m")
    }

    "yellow" in {
        assert("test".yellow == "\u001b[33mtest\u001b[0m")
    }

    "blue" in {
        assert("test".blue == "\u001b[34mtest\u001b[0m")
    }

    "magenta" in {
        assert("test".magenta == "\u001b[35mtest\u001b[0m")
    }

    "cyan" in {
        assert("test".cyan == "\u001b[36mtest\u001b[0m")
    }

    "white" in {
        assert("test".white == "\u001b[37mtest\u001b[0m")
    }

    "grey" in {
        assert("test".grey == "\u001b[90mtest\u001b[0m")
    }

    "bold" in {
        assert("test".bold == "\u001b[1mtest\u001b[0m")
    }

    "dim" in {
        assert("test".dim == "\u001b[2mtest\u001b[0m")
    }

    "italic" in {
        assert("test".italic == "\u001b[3mtest\u001b[0m")
    }

    "underline" in {
        assert("test".underline == "\u001b[4mtest\u001b[0m")
    }

    "stripAnsi" in {
        val coloredString = "test".red.bold.underline
        assert(coloredString.stripAnsi == "test")
    }

end AnsiTest
