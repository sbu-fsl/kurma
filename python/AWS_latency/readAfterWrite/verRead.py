import os
import sys
import time
import boto
import KurmaAWSTestLib
from boto.s3.key import Key
from datetime import datetime
from boto.s3.connection import Location

# If you don't refresh k = Key(bucket) in loop,
# every second op will pass even after exception
###################### MAIN ########################

def main(argv):

    ## PARAM OVERRIDES
    KurmaAWSTestLib.GLOBAL_DEBUG = 1
    bucket_name = 'readafterwrite003kurmaeu'
    FILNAME = 'readtimes000'

    ret = KurmaAWSTestLib.fetchArgs(argv)
    if(ret == -1):
        sys.exit(2)

    userObj = boto.s3.connect_to_region(
                      'eu-west-1',
                      aws_access_key_id=KurmaAWSTestLib.user_profiles[0]['access'],
                      aws_secret_access_key=KurmaAWSTestLib.user_profiles[0]['secret'],
                      calling_format=boto.s3.connection.OrdinaryCallingFormat())

    bucket = userObj.get_bucket(bucket_name)
    j = 0
    of = open (FILNAME, 'w+')
    while (j < 10):
        j = j + 1
        k = Key(bucket)
        keystring = 'testobj' + str(j)
        k.key = keystring
        while(1):
            try:
                k.get_contents_to_filename(keystring)
                #print ("Read " + keystring + " at: "+ str(datetime.now()))
                #f = open(keystring, 'r+')
                #print f.readline()
                #f.close()
            except:
                tstamp = str(datetime.now()) + '\n'
                #print("---------- Could not find " + keystring + " at: " + str(datetime.now()))
                of.write(tstamp)
                break

    of.close()
    return

if __name__ == "__main__":
    main(sys.argv[1:])
