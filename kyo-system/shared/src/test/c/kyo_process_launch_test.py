import ctypes
import errno
import os
import pathlib
import resource
import select
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import unittest

from kyo_process_supervisor_test import Command, EXITED, READY, process_state


library = ctypes.CDLL(os.environ['KYO_PROCESS_LIBRARY'])
library.kyo_process_launch_init.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
library.kyo_process_launch_init.restype = ctypes.c_void_p
for name in ('executable', 'endpoint'):
    function = getattr(library, 'kyo_process_launch_' + name)
    function.argtypes = [ctypes.c_void_p]
    function.restype = ctypes.c_char_p
for name in ('take_listener', 'accept', 'release'):
    function = getattr(library, 'kyo_process_launch_' + name)
    function.argtypes = [ctypes.c_void_p]
    function.restype = ctypes.c_int
for name in ('await', 'take_stream'):
    function = getattr(library, 'kyo_process_launch_' + name)
    function.argtypes = [ctypes.c_void_p, ctypes.c_int]
    function.restype = ctypes.c_int
for name in ('cancel', 'helper_exited', 'disconnect', 'take_control'):
    function = getattr(library, 'kyo_process_launch_' + name)
    function.argtypes = [ctypes.c_void_p]
    function.restype = ctypes.c_int
library.kyo_process_launch_take_pipe.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
library.kyo_process_launch_take_pipe.restype = ctypes.c_void_p
library.kyo_process_pipe_read.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int]
library.kyo_process_pipe_read.restype = ctypes.c_int
library.kyo_process_pipe_release.argtypes = [ctypes.c_void_p]
library.kyo_process_pipe_release.restype = ctypes.c_int


class Launch:
    def __init__(self, temporary=''):
        error = ctypes.c_int()
        self.owner = library.kyo_process_launch_init(os.fsencode(temporary), ctypes.byref(error))
        if not self.owner:
            raise OSError(error.value, os.strerror(error.value))
        self.executable = os.fsdecode(library.kyo_process_launch_executable(self.owner))
        self.endpoint = os.fsdecode(library.kyo_process_launch_endpoint(self.owner))

    def close(self):
        if self.owner:
            result = library.kyo_process_launch_release(self.owner)
            self.owner = None
            if result:
                raise OSError(-result, os.strerror(-result))

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class RuntimeCommand(Command):
    def __init__(self, launch, arguments):
        self.streams = {}
        descriptor = library.kyo_process_launch_take_listener(launch.owner)
        if descriptor < 0:
            raise OSError(-descriptor, os.strerror(-descriptor))
        with socket.socket(fileno=descriptor) as listener:
            self.process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '0', *arguments],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            try:
                # Readiness checks also observe a helper that failed before connecting. The poll interval
                # is not a test deadline or pass condition; completion follows connection or process exit.
                while not select.select([listener], [], [], 0.01)[0]:
                    if self.process.poll() is not None:
                        raise AssertionError(self.process.stderr.read().decode())
                self.control, _ = listener.accept()
                self.control.setblocking(True)
                kind, self.pid = self.event()
                if kind != READY:
                    raise AssertionError((kind, self.pid))
            except BaseException:
                if hasattr(self, 'control'):
                    self.control.close()
                listener.close()
                self.process.stdin.close()
                self.process.wait()
                self.process.stdout.close()
                self.process.stderr.close()
                raise


def descriptors():
    directory = '/dev/fd' if os.uname().sysname == 'Darwin' else '/proc/self/fd'
    return len(os.listdir(directory))


class ProcessLaunchTest(unittest.TestCase):
    def test_pipe_owner_transfers_only_after_successful_allocation(self):
        before = descriptors()
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '2', '/bin/sh', '-c', 'printf payload'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            owner = None
            try:
                self.assertGreater(library.kyo_process_launch_await(launch.owner, 2), 0)
                error = ctypes.c_int()
                limits = resource.getrlimit(resource.RLIMIT_NOFILE)
                try:
                    resource.setrlimit(resource.RLIMIT_NOFILE, (0, limits[1]))
                    failed = library.kyo_process_launch_take_pipe(launch.owner, 1, ctypes.byref(error))
                finally:
                    resource.setrlimit(resource.RLIMIT_NOFILE, limits)
                self.assertIsNone(failed)
                self.assertEqual(error.value, errno.EMFILE)
                owner = library.kyo_process_launch_take_pipe(launch.owner, 1, ctypes.byref(error))
                self.assertTrue(owner)
                self.assertEqual(error.value, 0)
                self.assertEqual(library.kyo_process_launch_take_stream(launch.owner, 1), -errno.EBADF)
                buffer = ctypes.create_string_buffer(32)
                self.assertEqual(library.kyo_process_pipe_read(owner, buffer, 32), 7)
                self.assertEqual(buffer.raw[:7], b'payload')
                self.assertEqual(library.kyo_process_pipe_read(owner, buffer, 32), 0)
            finally:
                if owner:
                    self.assertEqual(library.kyo_process_pipe_release(owner), 0)
                launch.close()
                self.assertEqual(process.wait(), 0)
        self.assertEqual(descriptors(), before)

    def test_native_acquisition_transfers_exclusive_control_and_stream_ownership(self):
        before = descriptors()
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '7', '/bin/sh', '-c', 'cat; printf diagnostic >&2; exit 7'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            streams = []
            control = None
            try:
                pid = library.kyo_process_launch_await(launch.owner, 7)
                self.assertGreater(pid, 0)
                self.assertNotEqual(pid, process.pid)
                control = socket.socket(fileno=library.kyo_process_launch_take_control(launch.owner))
                control.setblocking(True)
                self.assertEqual(library.kyo_process_launch_take_control(launch.owner), -errno.EBADF)
                for index in range(3):
                    descriptor = library.kyo_process_launch_take_stream(launch.owner, index)
                    self.assertGreaterEqual(descriptor, 3)
                    self.assertFalse(os.get_inheritable(descriptor))
                    self.assertEqual(library.kyo_process_launch_take_stream(launch.owner, index), -errno.EBADF)
                    os.set_blocking(descriptor, True)
                    streams.append(os.fdopen(descriptor, 'wb' if index == 0 else 'rb', buffering=0))
                launch.close()
                streams[0].write(b'owned output')
                streams[0].close()
                self.assertEqual(streams[1].read(), b'owned output')
                self.assertEqual(streams[2].read(), b'diagnostic')
                command = Command.__new__(Command)
                command.control = control
                self.assertEqual(command.exit(), (EXITED, 7))
            finally:
                for stream in streams:
                    stream.close()
                if control:
                    control.close()
                launch.close()
                self.assertEqual(process.wait(), 0)
        self.assertEqual(descriptors(), before)

    def test_cancel_wakes_acquisition_without_a_connected_helper(self):
        before = descriptors()
        with Launch() as launch:
            result = []
            waiter = threading.Thread(target=lambda: result.append(library.kyo_process_launch_await(launch.owner, 7)))
            waiter.start()
            self.assertEqual(library.kyo_process_launch_cancel(launch.owner), 0)
            waiter.join()
            self.assertEqual(result, [-errno.ECANCELED])
            self.assertEqual(library.kyo_process_launch_take_control(launch.owner), -errno.EBADF)
        self.assertEqual(descriptors(), before)

    def test_abandonment_after_native_acquisition_closes_untransferred_descriptors(self):
        before = descriptors()
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '7', 'sleep', '100000'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            try:
                pid = library.kyo_process_launch_await(launch.owner, 7)
                self.assertGreater(pid, 0)
            finally:
                launch.close()
                self.assertEqual(process.wait(), 0)
            self.assertEqual(process_state(pid), '')
        self.assertEqual(descriptors(), before)

    def test_native_acquisition_reports_spawn_errno(self):
        before = descriptors()
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '7', '/kyo/missing/executable'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            try:
                self.assertEqual(library.kyo_process_launch_await(launch.owner, 7), -errno.ENOENT)
                self.assertEqual(library.kyo_process_launch_take_stream(launch.owner, 0), -errno.EBADF)
            finally:
                launch.close()
                self.assertEqual(process.wait(), 2)
        self.assertEqual(descriptors(), before)

    def test_helper_exit_before_connection_wakes_acquisition(self):
        before = descriptors()
        with Launch() as launch:
            result = []
            waiter = threading.Thread(target=lambda: result.append(library.kyo_process_launch_await(launch.owner, 7)))
            waiter.start()
            self.assertEqual(library.kyo_process_launch_helper_exited(launch.owner), 0)
            waiter.join()
            self.assertEqual(result, [-errno.ECHILD])
            # Cancellation remains authoritative even if helper exit was observed first.
            self.assertEqual(library.kyo_process_launch_cancel(launch.owner), 0)
            self.assertEqual(library.kyo_process_launch_await(launch.owner, 7), -errno.ECANCELED)
        self.assertEqual(descriptors(), before)

    def test_helper_exit_notification_preserves_a_queued_exec_failure(self):
        before = descriptors()
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '7', '/kyo/missing/executable'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            self.assertEqual(process.wait(), 2)
            self.assertEqual(library.kyo_process_launch_helper_exited(launch.owner), 0)
            self.assertEqual(library.kyo_process_launch_await(launch.owner, 7), -errno.ENOENT)
        self.assertEqual(descriptors(), before)

    def test_disconnect_ends_helper_before_releasing_its_watchers_context(self):
        before = descriptors()
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '7', 'sleep', '100000'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            try:
                pid = library.kyo_process_launch_await(launch.owner, 7)
                self.assertGreater(pid, 0)
                self.assertEqual(library.kyo_process_launch_disconnect(launch.owner), 0)
                self.assertEqual(process.wait(), 0)
                self.assertEqual(process_state(pid), '')
                self.assertEqual(library.kyo_process_launch_helper_exited(launch.owner), 0)
                self.assertEqual(library.kyo_process_launch_cancel(launch.owner), 0)
                self.assertEqual(library.kyo_process_launch_disconnect(launch.owner), 0)
            finally:
                launch.close()
                process.wait()
        self.assertEqual(descriptors(), before)

    def test_private_executable_and_socket_are_removed_on_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            with Launch(temporary) as launch:
                executable = pathlib.Path(launch.executable)
                endpoint = pathlib.Path(launch.endpoint)
                self.assertEqual(stat.S_IMODE(executable.stat().st_mode), 0o500)
                self.assertEqual(stat.S_IMODE(executable.parent.stat().st_mode), 0o700)
                self.assertEqual(stat.S_IMODE(endpoint.parent.stat().st_mode), 0o700)
                self.assertTrue(stat.S_ISSOCK(endpoint.stat().st_mode))
                self.assertEqual(executable.parent.parent, pathlib.Path(temporary))
            self.assertFalse(executable.parent.exists())
            self.assertFalse(endpoint.parent.exists())

    def test_runtime_owns_supervisor_and_supervisor_owns_command(self):
        with Launch() as launch, RuntimeCommand(launch, ['/bin/sh', '-c', 'printf payload; exit 7']) as command:
            self.assertEqual(command.process.stdout.read(7), b'payload')
            self.assertEqual(command.exit(), (EXITED, 7))
            with self.assertRaises(ChildProcessError):
                os.waitpid(command.pid, os.WNOHANG)
            self.assertEqual(os.waitpid(-1, os.WNOHANG), (0, 0))
            self.assertTrue(process_state(command.pid).startswith('Z'))
            command.release()
            self.assertEqual(process_state(command.pid), '')

    def test_acquisition_abandonment_closes_connection_before_reading_ready(self):
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '0', 'sleep', '100000'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            )
            try:
                while True:
                    descriptor = library.kyo_process_launch_accept(launch.owner)
                    if descriptor >= 0:
                        break
                    if descriptor not in (-errno.EAGAIN, -errno.EWOULDBLOCK, -errno.EINTR):
                        self.fail(str(descriptor))
                    self.assertIsNone(process.poll())
                # A connected helper may already have spawned. EOF owns cleanup even before READY is read.
                os.close(descriptor)
                self.assertEqual(process.wait(), 0)
            finally:
                process.stderr.close()

    def test_abandonment_before_accept_cleans_up_an_already_started_command(self):
        with Launch() as launch:
            process = subprocess.Popen(
                [launch.executable, launch.endpoint, '', '0', '/bin/sh', '-c', 'echo $$; exec sleep 100000'],
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            try:
                pid = int(process.stdout.readline())
                launch.close()
                self.assertEqual(process.wait(), 0)
                self.assertEqual(process_state(pid), '')
            finally:
                process.stdout.close()
                process.stderr.close()

    def test_missing_image_directory_preserves_errno_without_descriptor_leak(self):
        before = descriptors()
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(OSError) as failure:
                Launch(pathlib.Path(temporary) / 'missing')
            self.assertEqual(failure.exception.errno, errno.ENOENT)
        self.assertEqual(descriptors(), before)

    def test_listener_transfer_is_exclusive_and_release_leaves_transferred_owner_alive(self):
        before = descriptors()
        with Launch() as launch:
            descriptor = library.kyo_process_launch_take_listener(launch.owner)
            self.assertGreaterEqual(descriptor, 0)
            self.assertEqual(library.kyo_process_launch_take_listener(launch.owner), -errno.EBADF)
            self.assertEqual(library.kyo_process_launch_accept(launch.owner), -errno.EBADF)
            with socket.socket(fileno=descriptor) as listener:
                launch.close()
                self.assertTrue(stat.S_ISSOCK(os.fstat(listener.fileno()).st_mode))
                self.assertEqual(listener.getsockname(), launch.endpoint)
        self.assertEqual(descriptors(), before)

    def test_private_listener_does_not_reopen_closed_standard_input(self):
        script = '''import ctypes,errno,os
lib = ctypes.CDLL(os.environ['KYO_PROCESS_LIBRARY'])
lib.kyo_process_launch_init.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)]
lib.kyo_process_launch_init.restype = ctypes.c_void_p
lib.kyo_process_launch_release.argtypes = [ctypes.c_void_p]
os.close(0)
error = ctypes.c_int()
owner = lib.kyo_process_launch_init(b'', ctypes.byref(error))
assert owner, error.value
try:
    try: os.fstat(0)
    except OSError as failure:
        assert failure.errno == errno.EBADF
        print('closed')
    else: print('open')
finally: assert lib.kyo_process_launch_release(owner) == 0
'''
        result = subprocess.run([sys.executable, '-c', script], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'closed\n')

    def test_long_image_directory_does_not_limit_control_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary) / ('long-' * 30)
            root.mkdir()
            with Launch(root) as launch, RuntimeCommand(launch, ['true']) as command:
                self.assertEqual(command.exit(), (EXITED, 0))
                command.release()


if __name__ == '__main__':
    unittest.main(verbosity=2)
