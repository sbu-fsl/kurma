#! /usr/bin/sh
# This script extracts write only ops from each trace
# and generate the sorted output file(key = fh) which is used for 
# next processing.
# "extract-snia-nfs-write" is a binary which needs to be generated
# before running the script. This can be generated from "trace2model" repository
# and should be copied in current working directory.
#
src=$1
if [ ! -d "$src" ]
then
  echo "Usage: ./extract_write_only_txt.sh <trace_dir_path>"
else
  export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:/usr/local/dataseries/lib
  rm -rf $src-write-only-txt
  mkdir $src-write-only-txt
  touch $src-write-only-txt/preprocessed.txt
  for filename in $src/* 
    do
      basename=$(basename $filename)
      file_new=${basename%.*}.txt
      echo $filename
      echo "$src-write-only-txt/${file_new}"
      rm -f $src-write-only-txt/${file_new} && ./extract-snia-nfs-write \
          ${filename} >  $src-write-only-txt/${file_new}
      sed '/fname\|write/d' $src-write-only-txt/${file_new}  >> $src-write-only-txt/preprocessed.txt 
  done
  ## generate preprocess file
  echo "Generating preprocessing fille"
  sort -n -k 1 $src-write-only-txt/preprocessed.txt > $src-write-only-txt/nsorted.txt
  rm $src-write-only-txt/preprocessed.txt
  echo "Done"
fi
