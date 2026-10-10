# The fixture's assertions, kept in a file because this fixture runs on Windows, where the JVM joins a process's
# arguments into one command line without escaping the double quotes inside them, and an inline `sh -c '...'`
# reaches the shell cut at its first one.
set -eu
case "$1" in
    classpath)
        # The host's transport jar is delivered everywhere, and its BoringSSL jar everywhere but Windows, where none is
        # published.
        host=$(cat host.txt)
        cat runtime-classpath.txt
        grep -q -- "-$host\.jar$" runtime-classpath.txt
        case "$host" in
            windows-*)
                if grep -q -- "-boringssl\.jar$" runtime-classpath.txt; then
                    echo "a BoringSSL jar on Windows, where none is published" >&2
                    exit 1
                fi
                ;;
            *) grep -q -- "-$host-boringssl\.jar$" runtime-classpath.txt ;;
        esac
        ;;
    run)
        cat out.txt
        grep -q "CONSUMER tls=hello" out.txt
        ;;
    pom)
        # The natives must not reach this application's own POM: a classifier there would pin the architecture of the
        # machine that built it onto everyone who depends on it.
        pom=$(find target -name "*.pom" | head -n1)
        cat "$pom"
        if grep -q "classifier" "$pom"; then
            echo "the POM names a classifier" >&2
            exit 1
        fi
        grep -q "kyo-net" "$pom"
        ;;
    *)
        echo "unknown check: $1" >&2
        exit 1
        ;;
esac
