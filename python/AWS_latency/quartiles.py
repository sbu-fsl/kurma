import os
import sys
import time
import boto
import numpy as np
import KurmaAWSTestLib
from boto.s3.key import Key
from datetime import datetime

###################### ARGS ########################
LISTFILE = 'diffdata'
OUTFILE  = 'cstick.dat'
DATASIZE = 8192
###################### MAIN ########################
def main(argv):
    difflist = readList()
    processQuart(difflist)
    return

######### Write candlestick record to outfile ######
def processQuart(difflist):
    var = np.array(difflist)
    qarr = np.percentile(var, np.arange(0, 100, 25))
    wstr = ''
    wstr += str(DATASIZE)
    wstr += '    '
    wstr += str(qarr[0])
    wstr += '    '
    wstr += str(qarr[1])
    wstr += '    '
    wstr += str(qarr[2])
    wstr += '    '
    wstr += str(qarr[3])
    wstr += '    '
    wstr += str(max(difflist))
    wstr += '\n'
    of = open(OUTFILE, 'a')
    if of is not None:
        of.write(wstr)
    of.close()
    return

############ Read list of diff to memory ###########
def readList():
    difflist = []
    f = open(LISTFILE, 'r')
    if f is not None:
        for line in f:
            difflist.append(float(line))
    f.close()
    return difflist

if __name__ == "__main__":
    main(sys.argv[1:])
