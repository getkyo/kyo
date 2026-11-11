import errno
import array
import fcntl
import json
import os
import pathlib
import resource
import signal
import socket
import stat
import struct
import subprocess
import sys
import tempfile
import unittest


SUPERVISOR = os.environ['KYO_PROCESS_SUPERVISOR']
READY, EXITED, SIGNALED, RUNNING, FAILED, RELEASED = range(1, 7)


class Command:
    def __init__(self, arguments, cwd='', environment=None, inherited=(), pipes=0, supervisor=None):
        self.streams = {}
        self.control, peer = socket.socketpair()
        self.control_number = peer.fileno()
        try:
            self.process = subprocess.Popen(
                [supervisor or SUPERVISOR, str(peer.fileno()), str(cwd), str(pipes), *arguments],
                pass_fds=(peer.fileno(), *inherited), env=environment,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
        except BaseException:
            self.control.close()
            raise
        finally:
            peer.close()
        try:
            if pipes & 7:
                data, ancillary, flags, _ = self.control.recvmsg(8, socket.CMSG_SPACE(3 * array.array('i').itemsize))
                descriptors = array.array('i')
                for level, name, payload in ancillary:
                    if level == socket.SOL_SOCKET and name == socket.SCM_RIGHTS:
                        descriptors.frombytes(payload)
                for fd, descriptor in zip((fd for fd in range(3) if pipes & (1 << fd)), descriptors):
                    self.streams[fd] = os.fdopen(descriptor, 'wb' if fd == 0 else 'rb', buffering=0)
                while len(data) < 8:
                    part = self.control.recv(8 - len(data))
                    if not part:
                        raise AssertionError('truncated ready event')
                    data += part
                kind, value = struct.unpack('=ii', data)
                if kind == READY:
                    assert not flags & socket.MSG_CTRUNC
                    assert len(descriptors) == (pipes & 7).bit_count()
            else:
                kind, value = self.event()
            if kind != READY:
                if kind == FAILED:
                    raise OSError(value, os.strerror(value))
                raise AssertionError((kind, value))
        except BaseException:
            self.close()
            raise
        self.pid = value

    def event(self):
        data = bytearray()
        while len(data) != 8:
            part = self.control.recv(8 - len(data))
            if not part:
                raise AssertionError('supervisor closed before completing its protocol')
            data.extend(part)
        return struct.unpack('=ii', data)

    def exit(self):
        kind, value = self.event()
        if kind not in (EXITED, SIGNALED):
            raise AssertionError((kind, value))
        return kind, value

    def release(self):
        self.control.sendall(bytes([3]))
        while True:
            kind, value = self.event()
            if kind == FAILED:
                raise OSError(value, os.strerror(value))
            if kind == RELEASED:
                if value:
                    raise OSError(value, os.strerror(value))
                break
        if self.process.wait() != 0:
            raise AssertionError('supervisor failed')

    def close(self):
        self.control.close()
        for stream in self.streams.values():
            stream.close()
        self.process.stdin.close()
        self.process.wait()
        self.process.stdout.close()
        self.process.stderr.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def process_state(pid):
    result = subprocess.run(['ps', '-o', 'stat=', '-p', str(pid)], text=True, capture_output=True)
    if result.returncode == 1:
        return ''
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout.strip()


class ProcessSupervisorTest(unittest.TestCase):
    @unittest.skipUnless(sys.platform == 'linux', 'Linux /proc lifetime interleaving')
    def test_unrelated_process_reaped_between_open_and_read_does_not_fail_group_cleanup(self):
        with Command(['true'], supervisor=os.environ['KYO_PROCESS_SUPERVISOR_RACE']) as command:
            self.assertEqual(command.exit(), (EXITED, 0))
            command.release()

    def test_owned_pipes_transfer_all_three_streams(self):
        script = "import sys; data=sys.stdin.buffer.read(); sys.stdout.buffer.write(data.upper()); sys.stderr.write('diagnostic'); sys.exit(7)"
        with Command([sys.executable, '-c', script], pipes=7) as command:
            self.assertEqual(set(command.streams), {0, 1, 2})
            for stream in command.streams.values():
                self.assertTrue(stat.S_ISFIFO(os.fstat(stream.fileno()).st_mode))
            command.streams[0].write(b'hello\x00world')
            command.streams[0].close()
            self.assertEqual(command.streams[1].read(), b'HELLO\x00WORLD')
            self.assertEqual(command.streams[2].read(), b'diagnostic')
            self.assertEqual(command.exit(), (EXITED, 7))
            command.release()

    def test_redirected_stderr_shares_the_owned_stdout_pipe(self):
        with Command(['/bin/sh', '-c', 'printf out; printf err >&2'], pipes=2 | 8) as command:
            self.assertEqual(set(command.streams), {1})
            self.assertEqual(command.streams[1].read(), b'outerr')
            self.assertEqual(command.exit(), (EXITED, 0))
            command.release()

    def test_startup_failure_with_owned_pipes_keeps_errno(self):
        with self.assertRaises(OSError) as failure:
            Command(['/kyo/missing/executable'], pipes=7)
        self.assertEqual(failure.exception.errno, errno.ENOENT)

    def test_closed_inherited_stdin_remains_closed(self):
        left, right = socket.socketpair()
        script = "import os,errno;\ntry: os.fstat(0); print('open')\nexcept OSError as e: print('closed' if e.errno == errno.EBADF else str(e))"
        process = subprocess.Popen(
            [SUPERVISOR, str(right.fileno()), '', '0', sys.executable, '-c', script],
            pass_fds=(right.fileno(),), stdout=subprocess.PIPE, preexec_fn=lambda: os.close(0),
        )
        right.close()
        try:
            self.assertEqual(struct.unpack('=ii', left.recv(8))[0], READY)
            self.assertEqual(process.stdout.readline(), b'closed\n')
        finally:
            left.close()
            process.wait()
            process.stdout.close()

    def test_arguments_environment_working_directory_and_streams(self):
        script = 'import json,os,sys; print(json.dumps([sys.argv[1:],os.getcwd(),os.environ["VALUE"],input()])); print("error",file=sys.stderr)'
        with tempfile.TemporaryDirectory() as directory:
            environment = dict(os.environ, VALUE='value with spaces')
            arguments = ['a b', '$HOME', ';', "'"]
            with Command([sys.executable, '-c', script, *arguments], directory, environment) as command:
                command.process.stdin.write(b'input\n')
                command.process.stdin.flush()
                data = json.loads(command.process.stdout.readline())
                self.assertEqual(data, [arguments, str(pathlib.Path(directory).resolve()), 'value with spaces', 'input'])
                self.assertEqual(command.process.stderr.readline(), b'error\n')
                self.assertEqual(command.exit(), (EXITED, 0))
                command.release()

    def test_early_exit_keeps_group_owned_until_release(self):
        script = 'import os,subprocess; p=subprocess.Popen(["sleep","100000"],stdout=subprocess.DEVNULL); print(p.pid,flush=True); os._exit(7)'
        with Command([sys.executable, '-c', script]) as command:
            child = int(command.process.stdout.readline())
            self.assertEqual(command.exit(), (EXITED, 7))
            self.assertTrue(process_state(command.pid).startswith('Z'))
            self.assertTrue(process_state(child))
            # The command belongs to the supervisor, including on a runtime using waitpid(-1).
            self.assertEqual(os.waitpid(-1, os.WNOHANG), (0, 0))
            with self.assertRaises(ChildProcessError):
                os.waitpid(command.pid, os.WNOHANG)
            command.release()
            self.assertEqual(process_state(command.pid), '')
            self.assertIn(process_state(child)[:1], ('', 'Z'))

    def test_abandoned_acquisition_cleans_up_without_delivering_a_handle(self):
        control, peer = socket.socketpair()
        process = subprocess.Popen(
            [SUPERVISOR, str(peer.fileno()), '', '0', 'sleep', '100000'],
            pass_fds=(peer.fileno(),), stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        peer.close()
        control.close()
        self.assertEqual(process.wait(), 0)
        process.stderr.close()

    def test_stop_is_not_an_exit_and_kill_preserves_signal(self):
        with Command(['sleep', '100000']) as command:
            os.kill(command.pid, signal.SIGSTOP)
            while not process_state(command.pid).startswith('T'):
                pass
            command.control.sendall(bytes([4]))
            self.assertEqual(command.event(), (RUNNING, 0))
            command.control.sendall(bytes([2]))
            self.assertEqual(command.exit(), (SIGNALED, signal.SIGKILL))
            command.release()

    def test_terminate_preserves_signal(self):
        with Command(['sleep', '100000']) as command:
            command.control.sendall(bytes([1]))
            self.assertEqual(command.exit(), (SIGNALED, signal.SIGTERM))
            command.release()

    def test_sigpipe_is_restored_for_command(self):
        with Command(['/bin/sh', '-c', 'kill -PIPE $$; exit 7']) as command:
            self.assertEqual(command.exit(), (SIGNALED, signal.SIGPIPE))
            command.release()

    def test_startup_failures_preserve_errno(self):
        with tempfile.TemporaryDirectory() as directory:
            denied = pathlib.Path(directory) / 'denied'
            denied.write_text('exit 0\n')
            denied.chmod(0o600)
            cases = [(['kyo-no-such-program'], '', errno.ENOENT), ([str(denied)], '', errno.EACCES),
                     (['true'], str(pathlib.Path(directory) / 'missing'), errno.ENOENT)]
            for arguments, cwd, expected in cases:
                with self.assertRaises(OSError) as failure:
                    Command(arguments, cwd)
                self.assertEqual(failure.exception.errno, expected)

    def test_control_and_unrelated_descriptors_do_not_reach_command(self):
        with tempfile.TemporaryFile() as file:
            original = resource.getrlimit(resource.RLIMIT_NOFILE)
            if original[0] < 4098:
                resource.setrlimit(resource.RLIMIT_NOFILE, (4098, original[1]))
            descriptor = fcntl.fcntl(file.fileno(), fcntl.F_DUPFD, 4096)
            try:
                script = '''import os,sys
for fd in map(int, input().split()):
    try: os.fstat(fd)
    except OSError as error: print(error.errno, flush=True)
    else: print('inherited', flush=True)
'''
                with Command([sys.executable, '-c', script], inherited=(descriptor,)) as command:
                    command.process.stdin.write(f'{descriptor} {command.control_number}\n'.encode())
                    command.process.stdin.flush()
                    self.assertEqual(command.process.stdout.readline(), f'{errno.EBADF}\n'.encode())
                    self.assertEqual(command.process.stdout.readline(), f'{errno.EBADF}\n'.encode())
                    self.assertEqual(command.exit(), (EXITED, 0))
                    command.release()
            finally:
                os.close(descriptor)
                resource.setrlimit(resource.RLIMIT_NOFILE, original)

    def test_concurrent_supervisors_keep_separate_groups(self):
        with Command(['sleep', '100000']) as first, Command(['sleep', '100000']) as second:
            self.assertNotEqual(first.pid, second.pid)
            first.release()
            second.control.sendall(bytes([4]))
            self.assertEqual(second.event(), (RUNNING, 0))
            self.assertNotIn(process_state(second.pid)[:1], ('', 'Z'))
            second.release()
            self.assertEqual(process_state(first.pid), '')
            self.assertEqual(process_state(second.pid), '')

    def test_detached_pipe_holder_is_not_mistaken_for_group_membership(self):
        script = '''import os
pid = os.fork()
if pid:
    os._exit(0)
os.setsid()
print(os.getpid(), flush=True)
os.read(0, 1)
print('finished', flush=True)
'''
        with Command([sys.executable, '-c', script]) as command:
            detached = int(command.process.stdout.readline())
            self.assertEqual(command.exit(), (EXITED, 0))
            command.release()
            self.assertNotIn(process_state(detached)[:1], ('', 'Z'))
            command.process.stdin.write(b'x')
            command.process.stdin.flush()
            self.assertEqual(command.process.stdout.readline(), b'finished\n')


if __name__ == '__main__':
    unittest.main(verbosity=2)
