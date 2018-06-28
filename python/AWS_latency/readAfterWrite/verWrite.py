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
    k.key = 'testobj'
    for i in range(1, 2):
        k.set_contents_from_filename('q1')
        print ("Wrote testobj at: " + str(datetime.now()))
        time.sleep(10)
        k.set_contents_from_filename('q2')
        print ("Wrote testobj at: " + str(datetime.now()))
        time.sleep(60)

    print ("Deleting all objects...")
    for k in bucket.list():
        k.delete()

    return

if __name__ == "__main__":
    main(sys.argv[1:])
