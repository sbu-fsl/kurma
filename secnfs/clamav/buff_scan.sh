export LD_LIBRARY_PATH=/usr/local/lib64/

if [ "$#" -lt 1 ]
then
    echo Usage: $0 \<files\>
    exit
fi

./buff_scan.o "$@"
