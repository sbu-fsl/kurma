#!/usr/bin/env python
'''Test file system operations on Kurma.

This is for basic correctness test at initial stage, more tests should be done
using xfstests.
'''

import gflags
import os
import sys
import subprocess
import unittest

from google.apputils import basetest

gflags.DEFINE_boolean('debug', True, 'debugging', short_name='d')
gflags.DEFINE_string('dumpfile', None, 'TCP dump output file', short_name='f')

_ganesha_server = '130.245.177.111'
_mount_opt = 'nofsc,soft,ac,nfsvers=4,minorversion=1,rsize=131072,wsize=131072'

def _r(cmd):
  return subprocess.check_output(cmd, shell=True)

def is_mounted():
  target = "{0}:/vfs0 /mnt".format(_ganesha_server)
  for line in open('/proc/mounts'):
    if line.startswith(target):
      return True
  return False


def mount(remount_if_already_mounted=False):
  if remount_if_already_mounted and is_mounted():
    umount(True)
  _r("mount -t nfs -o {0} {1}:/vfs0 /mnt".format(_mount_opt, _ganesha_server))


def umount(force=False):
  cmd = "umount -f /mnt" if force else "umount /mnt"
  _r(cmd)


def start_tcpdump(filename):
  cmd = '/usr/sbin/tcpdump -w %s -i lo -s 0 port 2049' % filename
  print(cmd)
  return subprocess.Popen(cmd.split())


def stop_tcpdump(pipe):
  pipe.terminate()


def is_same_file(filea, fileb):
  return subprocess.call("diff -q %s %s" % (filea, fileb), shell=True) == 0


class BasicTests(basetest.TestCase):
  def test_hello(self):
    mount()
    _r("echo hello > /mnt/testfs/hello.txt")
    umount()
    mount()
    msg = _r("cat /mnt/testfs/hello.txt")
    umount()
    print(msg)
    self.assertEqual(msg, "hello\n")

  def test_rename_file(self):
    mount()
    _r("echo aaa > /mnt/testfs/aaa.txt")
    _r("mv /mnt/testfs/aaa.txt /mnt/testfs/bbb.txt")
    self.assertFalse(os.path.exists("/mnt/testfs/aaa.txt"))
    self.assertEqual("aaa\n", _r("cat /mnt/testfs/bbb.txt"))
    umount()
    mount()
    self.assertFalse(os.path.exists("/mnt/testfs/aaa.txt"))
    self.assertTrue(os.path.exists("/mnt/testfs/bbb.txt"))
    self.assertEqual("aaa\n", _r("cat /mnt/testfs/bbb.txt"))
    umount()

  def test_move_file(self):
    mount()
    _r("mkdir /mnt/testfs/move_file_dir1")
    _r("mkdir /mnt/testfs/move_file_dir2")
    _r("echo foo > /mnt/testfs/move_file_dir1/foo")
    _r("mv /mnt/testfs/move_file_dir1/foo /mnt/testfs/move_file_dir2/bar")
    self.assertFalse(os.path.exists("/mnt/testfs/move_file_dir1/foo"))
    self.assertTrue(os.path.exists("/mnt/testfs/move_file_dir2/bar"))
    self.assertEqual("foo\n", _r("cat /mnt/testfs/move_file_dir2/bar"))
    umount()
    mount()
    self.assertFalse(os.path.exists("/mnt/testfs/move_file_dir1/foo"))
    self.assertTrue(os.path.exists("/mnt/testfs/move_file_dir2/bar"))
    self.assertEqual("foo\n", _r("cat /mnt/testfs/move_file_dir2/bar"))
    umount()

  def test_move_dir(self):
    mount()
    _r("mkdir -p /mnt/testfs/move_dir1/foo")
    _r("echo bar > /mnt/testfs/move_dir1/foo/bar.txt")
    _r("mv /mnt/testfs/move_dir1 /mnt/testfs/move_dir2")
    self.assertFalse(os.path.exists("/mnt/testfs/move_dir1/foo/bar.txt"))
    self.assertTrue(os.path.exists("/mnt/testfs/move_dir2/foo/bar.txt"))
    self.assertEqual("bar\n", _r("cat /mnt/testfs/move_dir2/foo/bar.txt"))
    umount()
    mount()
    self.assertFalse(os.path.exists("/mnt/testfs/move_dir1/foo/bar.txt"))
    self.assertTrue(os.path.exists("/mnt/testfs/move_dir2/foo/bar.txt"))
    self.assertEqual("bar\n", _r("cat /mnt/testfs/move_dir2/foo/bar.txt"))
    umount()

  def test_write_random(self):
    _r("dd if=/dev/urandom of=/tmp/1m-random-file bs=64k count=16")
    mount()
    _r("cp /tmp/1m-random-file /mnt/testfs/1m-random-file")
    self.assertTrue(is_same_file("/tmp/1m-random-file",
                                 "/mnt/testfs/1m-random-file"))
    umount()
    mount()
    self.assertTrue(is_same_file("/tmp/1m-random-file",
                                 "/mnt/testfs/1m-random-file"))
    umount()

  def test_shrink_file(self):
    _r("dd if=/dev/urandom of=/tmp/test-shrink-file bs=4k count=2")
    _r("head -c 4K /tmp/test-shrink-file > /tmp/shrinked-to-4k")
    _r("head -c 100 /tmp/test-shrink-file > /tmp/shrinked-to-100b")
    mount()
    _r("cp /tmp/test-shrink-file /mnt/testfs/shrinked-to-4k")
    _r("truncate -s 4K /mnt/testfs/shrinked-to-4k")
    self.assertTrue(is_same_file("/tmp/shrinked-to-4k",
                                 "/mnt/testfs/shrinked-to-4k"))
    _r("cp /tmp/test-shrink-file /mnt/testfs/shrinked-to-100b")
    _r("truncate -s 100 /mnt/testfs/shrinked-to-100b")
    self.assertTrue(is_same_file("/tmp/shrinked-to-100b",
                                 "/mnt/testfs/shrinked-to-100b"))
    umount()
    mount()
    self.assertTrue(is_same_file("/tmp/shrinked-to-4k",
                                 "/mnt/testfs/shrinked-to-4k"))
    self.assertTrue(is_same_file("/tmp/shrinked-to-100b",
                                 "/mnt/testfs/shrinked-to-100b"))
    umount()

  def test_enlarge_file(self):
    _r("dd if=/dev/urandom of=/tmp/test-enlarge-file bs=4k count=2")
    _r("cp /tmp/test-enlarge-file /tmp/enlarged-to-10k")
    _r("cp /tmp/test-enlarge-file /tmp/enlarged-to-12k")
    _r("truncate -s 10K /tmp/enlarged-to-10k")
    _r("truncate -s 12K /tmp/enlarged-to-12k")
    mount()
    _r("cp /tmp/test-enlarge-file /mnt/testfs/enlarged-to-10k")
    _r("cp /tmp/test-enlarge-file /mnt/testfs/enlarged-to-12k")
    _r("truncate -s 10K /mnt/testfs/enlarged-to-10k")
    _r("truncate -s 12K /mnt/testfs/enlarged-to-12k")
    self.assertTrue(is_same_file("/tmp/enlarged-to-10k",
                                 "/mnt/testfs/enlarged-to-10k"))
    self.assertTrue(is_same_file("/tmp/enlarged-to-12k",
                                 "/mnt/testfs/enlarged-to-12k"))
    umount()
    mount()
    self.assertTrue(is_same_file("/tmp/enlarged-to-10k",
                                 "/mnt/testfs/enlarged-to-10k"))
    self.assertTrue(is_same_file("/tmp/enlarged-to-12k",
                                 "/mnt/testfs/enlarged-to-12k"))
    umount()

  def test_touch_and_delete(self):
    mount()
    _r("touch /mnt/testfs/empty-file")
    self.assertTrue(os.path.exists("/mnt/testfs/empty-file"))
    umount()
    mount()
    self.assertTrue(os.path.exists("/mnt/testfs/empty-file"))
    _r("rm /mnt/testfs/empty-file")
    self.assertFalse(os.path.exists("/mnt/testfs/empty-file"))
    umount()


def initialize():
  mount(True)
  if os.path.isdir("/mnt/testfs"):
    _r("rm -rf /mnt/testfs")
  _r("mkdir -p /mnt/testfs")
  umount()


def main(argv):
  try:
    argv = gflags.FLAGS(argv)  # parse flags
  except gflags.FlagsError, e:
    print '%s\\nUsage: %s ARGS\\n%s' % (e, sys.argv[0],
                                        gflags.FLAGS)
    sys.exit(1)
  initialize()
  if gflags.FLAGS.debug: print 'non-flag arguments:', argv
  if gflags.FLAGS.dumpfile:
    pipe = start_tcpdump(gflags.FLAGS.dumpfile)
    basetest.main()
    stop_tcpdump(pipe)
  else:
    basetest.main()


if __name__ == '__main__':
  main(sys.argv)
