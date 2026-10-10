# Whether an ELF library reaches its own sqlite3_* functions and data directly, or leaves them to the dynamic loader.
# A Node built against the system libsqlite3 has sqlite3_* in the process's global scope before koffi loads the
# library, and a reference left to the loader resolves there first, so one connection spans two engines.
#
#   control    builds the same shape of library with and without -Bsymbolic and requires this check to refuse the
#              one without and accept the one with, so a check that cannot see the difference fails here.
#   check ID   requires the delivered lib<ID>.so to carry no dynamic relocation against its own sqlite3_*.
set -eu

if [ "$(uname -s)" != Linux ]; then
    echo "symbol interposition is an ELF concern; skipped on $(uname -s)"
    exit 0
fi

# The relocations of $1 against its own sqlite3_*, printed, or nothing. A failing readelf fails the script under -e,
# and a library with no JUMP_SLOT at all means the listing is not the one this reads, since every shared library
# calls into libc through its PLT.
own_relocations() {
    relocs=$(readelf -rW "$1")
    if ! printf '%s\n' "$relocs" | grep -q "JUMP_SLOT"; then
        echo "readelf listed no JUMP_SLOT relocation for $1, so this reading cannot tell a bound library from an unbound one" >&2
        exit 1
    fi
    printf '%s\n' "$relocs" | grep " sqlite3_" || true
}

case "$1" in
    control)
        dir=$(mktemp -d)
        # The libc call gives both libraries a JUMP_SLOT whatever the linker binds, as every delivered library has one.
        printf '%s\n' \
            '#include <unistd.h>' \
            'int sqlite3_control_data = 1;' \
            'int sqlite3_control(void) { return sqlite3_control_data; }' \
            'int kyo_control(void) { return sqlite3_control() + (int) getpid(); }' > "$dir/control.c"
        # -fsemantic-interposition keeps the compiler from binding the call itself, so the linker is what decides.
        cc -shared -fPIC -O0 -fsemantic-interposition "$dir/control.c" -o "$dir/libunbound.so"
        cc -shared -fPIC -O0 -fsemantic-interposition "$dir/control.c" -o "$dir/libbound.so" -Wl,-Bsymbolic
        # Assigned rather than tested inline: a failing substitution stops the script only as an assignment.
        unbound=$(own_relocations "$dir/libunbound.so")
        bound=$(own_relocations "$dir/libbound.so")
        if [ -z "$unbound" ]; then
            echo "the check accepted a library linked without -Bsymbolic, so it cannot refuse one" >&2
            exit 1
        fi
        echo "control: a library linked without -Bsymbolic is refused"
        if [ -n "$bound" ]; then
            echo "the check refused a library linked with -Bsymbolic" >&2
            exit 1
        fi
        echo "control: the same library linked with -Bsymbolic is accepted"
        ;;
    check)
        lib=$(ls target/node_modules/@kyo/ffi-native/native/*/"lib$2.so")
        own=$(own_relocations "$lib")
        if [ -n "$own" ]; then
            printf '%s\n' "$own"
            echo "$lib reaches its own sqlite3_* through the dynamic loader, where a host SQLite interposes" >&2
            exit 1
        fi
        echo "$lib binds its own sqlite3_* to itself"
        ;;
    *)
        echo "usage: elf-self-binding.sh control | check ID" >&2
        exit 1
        ;;
esac
