# The fixture's assertion, kept in a file because this fixture runs on Windows, where the JVM joins a process's
# arguments into one command line without escaping the double quotes inside them, and an inline `sh -c '...'`
# reaches the shell cut at its first one.
set -eu
cat out.txt
grep -q "CONSUMER sqlite=1024" out.txt
