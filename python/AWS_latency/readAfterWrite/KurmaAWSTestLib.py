import re
import sys
import time
import boto
import getopt
from boto.s3.key import Key
from boto.s3.connection import S3Connection

###################### INIT ########################

# PARAMS
GLOBAL_DEBUG = 1
MULTIPART_LARGE_FILE = ''

RADOSHOST    = ''
RADOSPORT    = ''
isSecure     = True
CLI_USER     = ''
CLI_COMMAND  = ''
COMMAND_NUM  = ''
COMMAND_TARG = ''

# ACCESS PARAMS
access_key = ''
secret_key = ''
user_profiles = None
isAwsConn  = True
has_incore_params = False

####################################################

################## CREATE CONNECTION ###############

def getConnection(user = None):
    global has_incore_params
    global access_key
    global secret_key
    global user_profiles

    if (user is not None) and (user_profiles is not None):
        try:
            access_key = user_profiles[user]['access']
            secret_key = user_profiles[user]['secret']
        except:
            print "Must have awsKeys file for multiuser access!"
            return -1

    conn_obj = None
    if (has_incore_params and isAwsConn):
        conn_obj = S3Connection(access_key, secret_key)
    else:
        return -1
    return conn_obj


############### INFO PRESENTATION ##################

def whisper(mystr):
    if GLOBAL_DEBUG == 1:
        print "DEBUG: " + mystr
    return

############### DATA POPULATION ####################

def createMaxBuckets(num, buckpref, user = None):
    obj =  getConnection(user)
    listBucketNum(obj, "User")
    whisper("Creating new buckets")
    for i in range(1, num + 1):
        name = buckpref + str(i)
        buck = obj.create_bucket(name)
        whisper("Creating bucket " + name)
        for j in range(1, 11):
            k = Key(buck)
            k.key = name + '_OBJ_' + str(j)
            whisper("Creating object " + k.key)
            k.set_contents_from_string('Data for obj ' + str(j))
    return;

############## CLEANUP AND LISTING #################

def cleanupUser(userObj, patstr = None):
    whisper("Cleaning up...")
    if (patstr):
        pstring = patstr + '*'
    else:
        pstring = '*'
    pattern = re.compile(pstring)
    for bkt in userObj.get_all_buckets():
        if (pattern.match(bkt.name)) or (not patstr):
            for k in bkt.list():
                whisper("Deleting " + str(k))
                k.delete()
            #whisper("Deleting bucket " + str(bkt))
            #userObj.delete_bucket(bkt.name)

    listBucket(userObj, "User")
    return

def listBucketNum(userObj, uname):
    print "Total number of buckets for " + uname + ": " + str(len(userObj.get_all_buckets()))
    return

def listBucket(userObj, uname):
    print "Listing buckets for " + uname
    for bucket in userObj.get_all_buckets():
        print "{name}\t{created}".format(name = bucket.name, created = bucket.creation_date)
    return

############## KEEP CHANGING ACLS ##################

def keepChangingAcls(bucket):
    obj = getConnection()
    acl_list = ('public-read', 'private')
    for i in range(1, 11):
        b1 = obj.get_bucket(bucket)
        whisper("Changing ACL to " + str(acl_list[i % 2]))
        b1.set_acl(acl_list[i % 2])
        time.sleep(1)
    return

################ BUCKET NAME GEN ###################

def getsNewBucketName(pref = None):
    ts = time.time()
    str_ts = str(ts)
    index = str_ts.find('.')
    str_ts = str_ts[0:index]
    if pref is None:
        bucketpref = 'kurmabucket' + str_ts
    else:
        bucketpref = str(pref) + str_ts
    return bucketpref

################# CLI ARGUMENTS ####################

def fetchArgs(argv):
    global has_incore_params
    global access_key
    global secret_key
    global user_profiles
    global CLI_USER
    global CLI_COMMAND
    global COMMAND_NUM
    global COMMAND_TARG

    try:
        opts, args = getopt.getopt(argv,"a:s:h:i",["access-key=","secret-key=","ifile="])
    except getopt.GetoptError:
        printsHelp()
        sys.exit(2)
    for opt, arg in opts:
        if opt == '-h':
            printsHelp()
            sys.exit()
        elif opt in ("-i", "--ifile"):
            try:
                import awsKeys
                has_incore_params = True
                access_key        = awsKeys.access_key
                secret_key        = awsKeys.secret_key
                user_profiles     = awsKeys.user_profiles
            except ImportError:
                print "Error: Failed to import awsKeys from file. Make sure that the file is present."
        elif opt in ("-a", "--access-key"):
            has_incore_params = True
            access_key = arg
        elif opt in ("-s", "--secret-key"):
            print "Sending secret key using command line is unsafe. Use awsKeys file."
            print "Sleeping for 5 seconds before continuing..."
            time.sleep(5)
            secret_key = arg

    if (not has_incore_params):
        printsHelp()
        return -1

    return 0

def printsHelp():
    print "HELP"
    print "===="
    print '<Test script> {-a <Access Key> -s <Secret Key>} or {-i True (To read from awsKeys)}'
    print '<Test script> {--access-key <Access Key> --secret-key <Secret Key>} or { --ifile True (To read from awsKeys)}'
    print "\nAWSKEYS FILE"
    print "============"
    print "Make a file called awsKeys.py and put inside it:"
    print "    access_key = \'<value>\'"
    print "    secret_key = \'<value>\'"
    return

