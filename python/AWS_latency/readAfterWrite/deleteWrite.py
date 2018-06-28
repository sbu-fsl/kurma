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
    OUTFILE = 'deltimes000'

    ret = KurmaAWSTestLib.fetchArgs(argv)
    if(ret == -1):
        sys.exit(2)

    userObj = boto.s3.connect_to_region(
                      'eu-west-1',
                      aws_access_key_id=KurmaAWSTestLib.user_profiles[0]['access'],
                      aws_secret_access_key=KurmaAWSTestLib.user_profiles[0]['secret'],
                      calling_format=boto.s3.connection.OrdinaryCallingFormat())

    bucket = userObj.get_bucket(bucket_name)
    k = Key(bucket)
    of = open(OUTFILE, 'w+')

    # Deletion loop
    for i in range(1, 11):
        k.key = 'testobj' + str(i)
        k.delete()
        tstamp = str(datetime.now()) + '\n'
        of.write(tstamp)
        print ("Deleted " + str(i))
        time.sleep(5)

    print ("Deleting all objects...")
    for k in bucket.list():
        k.delete()

    of.close()
    return

if __name__ == "__main__":
    main(sys.argv[1:])
