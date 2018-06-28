import azure
from datetime import datetime
from azure.storage.blob import PublicAccess
from azure.storage.blob import BlockBlobService

CONTAINER_NAME = 'kurma001'
DATAFILE = 'azreadobj'
OBJPREF  = '8mobj'
OUTFILE  = 'read_azurelog_8m'
ITERS = 31

#block_blob_service = BlockBlobService(account_name='shivanshu',
#                     account_key='Ki8ulrraNraJ/bw6GqdXXqMKMy8fIQseXoa5CHmc8Fu2QqcQARwkMQzY26A4wQ61y6MWjEcE8vKFEmsaZYJK9Q==')
block_blob_service = BlockBlobService(account_name='kurma',
                     account_key='7D+s2E9/nKaBxOS3+ZweEnz+T4fzKhuDiFB3Xm3aXubqZtijOyTyr98c+pMeSiTPNHPVFjHQUJeitSq9GyuomQ==')


#Create container
#block_blob_service.create_container(CONTAINER_NAME)
#block_blob_service.set_container_acl(CONTAINER_NAME, public_access=PublicAccess.Container)
block_blob_service.create_container(CONTAINER_NAME, public_access=PublicAccess.Container)

#Create object

#of = open(OUTFILE, 'w+')
#for i in range (1, ITERS + 1):
#    objname = OBJPREF + str(i)
#    block_blob_service.create_blob_from_path(
#            CONTAINER_NAME,
#            objname,
#            DATAFILE,
#            )
#    print "Wrote object" + str(i) + " at: " + str(datetime.now())
#    of.write(str(datetime.now()) + '\n')
#of.close()

#List object
#generator = block_blob_service.list_blobs(CONTAINER_NAME)
#for blob in generator:
#        print(blob.name)

#Download object
#block_blob_service.get_blob_to_path(CONTAINER_NAME, 'myblockblob', 'out-sunset.png')
of = open(OUTFILE, 'w+')
for i in range (1, ITERS + 1):
    objname = OBJPREF + str(i)
    block_blob_service.get_blob_to_path(
            CONTAINER_NAME,
            objname,
            DATAFILE,
            )
    print "Read object" + str(i) + " at: " + str(datetime.now())
    of.write(str(datetime.now()) + '\n')
of.close()

#Delete blob
#block_blob_service.delete_blob(CONTAINER_NAME, '4mobj1')

