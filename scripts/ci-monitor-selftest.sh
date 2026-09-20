#!/usr/bin/env bash
set -euo pipefail

# Exercise ci-monitor.sh's proc_top attribution against stubbed process listings, on both the Windows
# and the posix branch, with no real processes and no monitor loop.
#
# proc_top parses text whose shape only appears on a CI runner, so the parsing has broken where it
# cannot be seen: first by having no Windows branch at all (MSYS `ps` reports its own accounting, so a
# JVM holding gigabytes printed as single-digit MB), then by reading `tasklist` image names that carry
# spaces as separate CSV columns. Both are parse bugs in an awk pipeline that nothing else covers.
#
# The function is extracted from ci-monitor.sh rather than copied, so a change to the real pipeline is
# what this exercises. Sourcing the script whole is not an option: it is a monitor loop that runs on
# load.

script_dir=$(cd "$(dirname "$0")" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
mkdir "$test_dir/bin"

fail() {
    printf 'ci-monitor-selftest: %s\n' "$1" >&2
    exit 1
}

expect_eq() {
    [ "$2" = "$3" ] || fail "$1: expected [$3], got [$2]"
}

# Windows: image names with spaces, thousands separators, an "N/A" memory column, and two rows of one
# image that have to aggregate.
cat > "$test_dir/bin/tasklist" <<'STUB'
#!/usr/bin/env bash
cat <<'ROWS'
"java.exe","1234","Console","1","4,194,304 K"
"java.exe","1235","Console","1","2,097,152 K"
"Memory Compression","999","Services","0","1,048,576 K"
"System Idle Process","0","Services","0","8 K"
"svchost.exe","500","Services","0","N/A"
ROWS
STUB
chmod +x "$test_dir/bin/tasklist"

windows_out=$(
    PATH="$test_dir/bin:$PATH" OS=MINGW64_NT-10.0 bash -c "
        $(sed -n '/^proc_top()/,/^}/p' "$script_dir/ci-monitor.sh")
        proc_top
    "
)

# java aggregates both rows (4194304 + 2097152 KB = 6144 MB, count 2) and sorts first. The two
# space-carrying names survive as single fields. svchost's non-numeric memory drops the row rather
# than parsing as 0 and displacing a real one.
expect_eq "windows attribution" "$windows_out" \
    'top=[java:6144M/2 Memory_Compression:1024M/1 System_Idle_Process:0M/1]'

case "$windows_out" in
    *'Memory Compression'*) fail "windows: an image name with a space leaked an unjoined field" ;;
    *'","'*) fail "windows: a raw CSV separator reached the output" ;;
esac

rm "$test_dir/bin/tasklist"

# Posix: the same aggregation over `ps axo rss=,comm=`, including a path-qualified command and one
# carrying a space, which the branch joins the same way.
cat > "$test_dir/bin/ps" <<'STUB'
#!/usr/bin/env bash
cat <<'ROWS'
  4096 /usr/bin/java
  2048 /usr/bin/java
  1024 /opt/My App/helper
     0 /bin/zombie
ROWS
STUB
chmod +x "$test_dir/bin/ps"

posix_out=$(
    PATH="$test_dir/bin:$PATH" OS=Linux bash -c "
        $(sed -n '/^proc_top()/,/^}/p' "$script_dir/ci-monitor.sh")
        proc_top
    "
)

# java aggregates to 6144 KB over two rows; the leading path is stripped; the zero-RSS row is dropped.
expect_eq "posix attribution" "$posix_out" 'top=[java:6M/2 helper:1M/1]'

# A listing tool that is absent must yield nothing at all rather than a partial line or an error, since
# proc_top runs on every monitor interval and must never disrupt the build. bash is invoked by absolute
# path so emptying PATH removes only the lookup proc_top performs, not the shell running it.
bash_bin=$(command -v bash)
mkdir -p "$test_dir/empty"
absent_out=$(
    PATH="$test_dir/empty" OS=Linux "$bash_bin" -c "
        $(sed -n '/^proc_top()/,/^}/p' "$script_dir/ci-monitor.sh")
        proc_top
    " || fail "a missing ps made proc_top exit non-zero"
)
expect_eq "missing ps" "$absent_out" ""

# Sockets: `netstat -ano -p tcp` rows are indented and carry a state column only for TCP; the count that
# matters is TIME_WAIT against the dynamic range `netsh` reports. Same parse-bug exposure as proc_top,
# and it decides whether a WSAENOBUFS leg ran out of ports or out of something else.
cat > "$test_dir/bin/netstat" <<'STUB'
#!/usr/bin/env bash
cat <<'ROWS'

Active Connections

  Proto  Local Address          Foreign Address        State           PID
  TCP    127.0.0.1:49152        127.0.0.1:9222         ESTABLISHED     1234
  TCP    127.0.0.1:49153        127.0.0.1:9222         TIME_WAIT       0
  TCP    127.0.0.1:49154        127.0.0.1:9222         TIME_WAIT       0
  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       900
ROWS
STUB
chmod +x "$test_dir/bin/netstat"

cat > "$test_dir/bin/netsh" <<'STUB'
#!/usr/bin/env bash
cat <<'ROWS'

Protocol tcp Dynamic Port Range
---------------------------------
Start Port      : 49152
Number of Ports : 16384
ROWS
STUB
chmod +x "$test_dir/bin/netsh"

sockets_out=$(
    PATH="$test_dir/bin:$PATH" OS=MINGW64_NT-10.0 bash -c "
        $(sed -n '/^sockets_headline()/,/^}/p' "$script_dir/ci-monitor.sh")
        sockets_headline
    "
)

# All four TCP rows count; only the two TIME_WAIT ones count again. The banner lines carry no leading
# TCP token and must not inflate the total.
expect_eq "windows sockets" "$sockets_out" 'tcp=4 timeWait=2 ephemeral=16384'

# The posix branch has no port range small enough to exhaust, so the headline is absent entirely rather
# than reporting a partial line.
posix_sockets_out=$(
    PATH="$test_dir/bin:$PATH" OS=Linux bash -c "
        $(sed -n '/^sockets_headline()/,/^}/p' "$script_dir/ci-monitor.sh")
        sockets_headline
    "
)
expect_eq "posix sockets" "$posix_sockets_out" ""

# A netstat that runs but yields no TCP rows (it errored, or printed only a banner) must read as
# unsampled. Counting it as zero would report healthy sockets on exactly the leg that cannot be sampled.
cat > "$test_dir/bin/netstat" <<'STUB'
#!/usr/bin/env bash
echo "netstat: something went wrong" >&2
exit 1
STUB
chmod +x "$test_dir/bin/netstat"

unsampled_out=$(
    PATH="$test_dir/bin:$PATH" OS=MINGW64_NT-10.0 bash -c "
        $(sed -n '/^sockets_headline()/,/^}/p' "$script_dir/ci-monitor.sh")
        sockets_headline
    " || fail "a failing netstat made sockets_headline exit non-zero"
)
expect_eq "unsampled sockets" "$unsampled_out" 'tcp=? timeWait=? ephemeral=16384'

case "$unsampled_out" in
    *'tcp=0'*) fail "a failing netstat reported zero sockets instead of an unsampled field" ;;
esac

# Absent netstat must print nothing and exit zero, for the same reason proc_top must.
absent_sockets_out=$(
    PATH="$test_dir/empty" OS=MINGW64_NT-10.0 "$bash_bin" -c "
        $(sed -n '/^sockets_headline()/,/^}/p' "$script_dir/ci-monitor.sh")
        sockets_headline
    " || fail "a missing netstat made sockets_headline exit non-zero"
)
expect_eq "missing netstat" "$absent_sockets_out" ""

printf 'ci-monitor-selftest: ok\n'
