#!/usr/bin/env python

import gflags
import os
import errno
import sys
import subprocess
import time
import unittest

from google.apputils import basetest

gflags.DEFINE_boolean('debug', True, 'debugging', short_name='d')
gflags.DEFINE_boolean('empty', False, 'empty file or not', short_name='e')
gflags.DEFINE_string('size', '1M', 'file size such as 64K, 1M, or 64M', short_name='s')
gflags.DEFINE_integer('iters', 10, 'iterations', short_name='i')

#TESTDIR = '/mnt/kurma-test'
TESTDIR = '/mnt/testdir'

def _r(cmd):
  return subprocess.check_output(cmd, shell=True)


def bytes():
  if gflags.FLAGS.empty:
    return 0
  size = int(gflags.FLAGS.size[0:-1])
  if gflags.FLAGS.size[-1].upper() == 'K':
    size *= 1024
  elif gflags.FLAGS.size[-1].upper() == 'M':
    size *= (1024 * 1024)
  return size


def bench():
  print('#remote: %s %d %s files' % (
      ('debugging' if gflags.FLAGS.debug else 'benchmarking'),
      gflags.FLAGS.iters,
      ('empty' if gflags.FLAGS.empty else gflags.FLAGS.size)))
  for i in xrange(gflags.FLAGS.iters):
    if i % 10 == 0:
      sys.stderr.write(('processed %d files\n' % i))
    filename = ('%s/%06d' % (TESTDIR, i))
    # wait for file to appear
    while True:
      try:
        st = os.stat(filename)
        if gflags.FLAGS.empty or st.st_size == bytes():
          break
      except OSError as e:
        if e.errno != errno.ENOENT:
          print(e)
          raise
      time.sleep(0.1)  # retry after 0.1 sec

    timestamp = time.time()
    if gflags.FLAGS.empty:
      prefix = filename + '\t0'
    else:
      prefix = _r('md5sum ' + filename).strip()
    print('%s\t%.2f' % (prefix, time.time()))


def main(argv):
  try:
    argv = gflags.FLAGS(argv)  # parse flags
  except gflags.FlagsError, e:
    print '%s\\nUsage: %s ARGS\\n%s' % (e, sys.argv[0],
                                        gflags.FLAGS)
    sys.exit(1)
  if gflags.FLAGS.debug: print 'non-flag arguments:', argv
  bench()


if __name__ == '__main__':
  main(sys.argv)
