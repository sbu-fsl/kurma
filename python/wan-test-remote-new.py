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
gflags.DEFINE_integer('iters', 10, 'iterations', short_name='i')

#TESTDIR = '/mnt/kurma-test'
TESTDIR = '/mnt/testdir'

def _r(cmd):
  return subprocess.check_output(cmd, shell=True)


def bench():
  print('#remote: %s %d %s files' % (
      ('debugging' if gflags.FLAGS.debug else 'benchmarking'),
      gflags.FLAGS.iters,
      ('empty' if gflags.FLAGS.empty else '1M')))
  for i in xrange(gflags.FLAGS.iters):
    if i % 10 == 0:
      sys.stderr.write(('processed %d files\n' % i))
    filename = ('%06d' % i)
    # wait for file to appear
    while True:
      try:
        files = os.listdir(TESTDIR)
        if filename in files:
          if gflags.FLAGS.empty:
            break
          fsize = os.stat(os.path.join(TESTDIR, filename)).st_size
          if fsize == 1048576:
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
    print('%s\t%.2f' % (filename, time.time()))


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
