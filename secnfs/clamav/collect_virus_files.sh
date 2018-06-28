#!/bin/bash - 
#=============================================================================
# Collect files with virus that can be detected by clamav
# 
# by Ming Chen, v.mingchen@gmail.com
#=============================================================================

set -o nounset                          # treat unset variables as an error
set -o errexit                          # stop script if command fail
export PATH="/bin:/usr/bin:/sbin"             
IFS=$' \t\n'                            # reset IFS
unset -f unalias                        # make sure unalias is not a function
\unalias -a                             # unset all aliases
ulimit -H -c 0 --                       # disable core dump
hash -r                                 # clear the command path hash

# Download the Malware DB
#wget -O ytif-theZoo-malware-db.tar.gz https://github.com/ytisf/theZoo/tarball/master
#tar xjf ytif-theZoo-malware-db.tar.gz

MALWARE_DIR=/home/mchen/Downloads/malware-binaries/
MALWARE_DST=/home/mchen/Downloads/detectable-malware

#for i in */*.zip; do
  #echo $i
  #unzip -P infected $i -d $MALWARE_DIR
#done

#find $MALWARE_DIR -type f -exec ./buff_scan.sh {} + >scan_results.txt

mkdir -p $MALWARE_DST

python - <<-END
import sys
from subprocess import call

for line in open('scan_results.txt'):
  if line.endswith(': clean\n'): pass
  parts = line.split(': infected by ')
  if len(parts) == 2:
    file, virus = parts
    cmd = 'cp "%s" $MALWARE_DST' % file
    ret = call(cmd, shell=True)
    if ret != 0:
      sys.stderr.write('command failed: %s\n' % cmd)
END

echo "All detectable malware binaries written to $MALWARE_DST"
