#!/usr/bin/env python
import sys
import os

THRIFT_PATH='/usr/local/Cellar/thrift/0.9.2/lib/python2.7/site-packages'
if os.path.exists(THRIFT_PATH):
  sys.path.append('/usr/local/Cellar/thrift/0.9.2/lib/python2.7/site-packages')

from Kurma import KurmaService
from Namespace.ttypes import *

from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol

try:
  # Make socket
  transport = TSocket.TSocket('localhost', 9091)

  # Buffering is critical. Raw sockets are very slow
  transport = TTransport.TBufferedTransport(transport)

  # Wrap in a protocol
  protocol = TBinaryProtocol.TBinaryProtocol(transport)

  # Create a client to use the protocol encoder
  client = KurmaService.Client(protocol)

  # Connect!
  transport.open()

  volumeid = "volume_id-test"
  kr = client.format_volume(volumeid, 0);
  print(kr)

  kr = client.create_session("test-clientid1", volumeid);
  sessionid = kr.sessionid

  root_oid = ObjectID(Int128(0, 0), 0, ObjectType.DIRECTORY, 0)
  kr = client.mkdir(sessionid, root_oid, "nfsdata", ObjectAttributes(0, 0, 0, 0755, 1))
  dir_oid = kr.oid
  print(dir_oid)

  # Close!
  transport.close()

except Thrift.TException, tx:
  print '%s' % (tx.message)
