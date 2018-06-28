all: extract-snia-nfs-write

LDFLAGS =  -lboost_program_options -laio -lDataSeries \
	   -lLintel -L$(HOME)/build/lib -L/usr/local/dataseries/lib

CXXFLAGS = $(OPTFLAGS) -D_GNU_SOURCE -D_LARGEFILE_SOURCE \
	   -I/usr/include/libxml2/ -I/usr/local/dataseries/include \
	   -D_FILE_OFFSET_BITS=64 $(LDFLAGS) $(CPPFLAGS) -std=c++11

extract-snia-nfs-write: extract-snia-nfs-write.cpp
	g++ -I$(HOME)/build/include -lDataSeries -o $@ $^  $(CXXFLAGS)
clean:
	rm -f extract-snia-nfs-write
