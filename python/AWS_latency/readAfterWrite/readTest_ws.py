import os
import sys
import time
import boto
import KurmaAWSTestLib
from boto.s3.key import Key
from datetime import datetime
from boto.s3.connection import Location

#boto.s3.connect_to_region
###################### MAIN ########################

def main(argv):

    ## PARAM OVERRIDES
    KurmaAWSTestLib.GLOBAL_DEBUG = 1
    bucket_name = 'readafterwrite003kurmaeu'

    ret = KurmaAWSTestLib.fetchArgs(argv)
    if(ret == -1):
        sys.exit(2)

    userObj = boto.s3.connect_to_region(
                      'eu-west-1',
                      aws_access_key_id=KurmaAWSTestLib.user_profiles[0]['access'],
                      aws_secret_access_key=KurmaAWSTestLib.user_profiles[0]['secret'],
                      calling_format=boto.s3.connection.OrdinaryCallingFormat())

    bucket = userObj.get_bucket(bucket_name)
    i = 1
    j = 0
    while (j < 100):
        j = j + 1
        print ("====================")
        for i in range(1, 11):
            k = Key(bucket)
            keystring = 'testobj' + str(i)
            k.key = keystring
            try:
                k.get_contents_to_filename(keystring)
                print ("Read " + keystring + " at: "+ str(datetime.now()))
            except:
                print("---------- Could not find " + keystring + " at: " + str(datetime.now()))
                continue
        print ("====================")
        print ("\n\n\n")
        time.sleep(5)
    return

if __name__ == "__main__":
    main(sys.argv[1:])
