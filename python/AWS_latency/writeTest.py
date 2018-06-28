import os
import sys
import time
import boto
import KurmaAWSTestLib
from boto.s3.key import Key
from datetime import datetime

###################### MAIN ########################

def main(argv):

    ## PARAM OVERRIDES
    KurmaAWSTestLib.GLOBAL_DEBUG = 1
    bucket_name = 'readafterwrite003kurmaeu'
    ITERS = 31
    OUTFILE = 'readtimes000_8mb'
    DATAFILE = 'data/dwld'
    OBJPREF = '8mobj'
    ################################################


    ret = KurmaAWSTestLib.fetchArgs(argv)
    if(ret == -1):
        sys.exit(2)

    userObj = boto.s3.connect_to_region(
                      'eu-west-1',
                      aws_access_key_id=KurmaAWSTestLib.user_profiles[0]['access'],
                      aws_secret_access_key=KurmaAWSTestLib.user_profiles[0]['secret'],
                      calling_format=boto.s3.connection.OrdinaryCallingFormat())

    bucket = userObj.get_bucket(bucket_name)
    of = open(OUTFILE, 'w+')
    k = Key(bucket)
#    for i in range(1, ITERS + 1):
#        k.key = OBJPREF + str(i)
#        k.set_contents_from_filename(DATAFILE)
#        print ("Wrote testobj" + str(i) + " at: "+ str(datetime.now()))
#        of.write(str(datetime.now()) + '\n')

    for i in range(1, ITERS + 1):
        k.key = OBJPREF + str(i)
        k.get_contents_to_filename(DATAFILE)
        print ("Read testobj" + str(i) + " at: "+ str(datetime.now()))
        of.write(str(datetime.now()) + '\n')

    of.close()
    #print ("Deleting all objects...")
    #for k in bucket.list():
    #    k.delete()

    return

if __name__ == "__main__":
    main(sys.argv[1:])
