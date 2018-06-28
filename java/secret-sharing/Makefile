# remove -DNDEBUG to show debug message
CFLAGS = -O3 -Wall -fno-operator-names -fPIC -DNDEBUG

CC = g++
LIBS = -lcrypto -lssl -lpthread -lgf_complete
OBJS = CryptoPrimitive.o CDCodec.o secretsharing_CDCodecJNI.o
JAVAC := $(shell which javac)
JNI_HEADERS_PATH := $(shell readlink -f ${JAVAC} | sed "s:bin/javac::")/include
INCLUDES =-I/usr/local/include -I$(JNI_HEADERS_PATH) -I$(JNI_HEADERS_PATH)/linux

all: libsecretsharing.a aontrs-test libsecretsharing.jni.so


secretsharing_CDCodecJNI.h: secretsharing/CDCodecJNI.java
	javac $< && javah secretsharing.CDCodecJNI

%.o: %.cpp %.h
	$(CC) $(CFLAGS) $(INCLUDES) -c $< -o $@

libsecretsharing.a: $(OBJS)
	ar rcs $@ $^

libsecretsharing.jni.so: $(OBJS)
	$(CC) $(CFLAGS) -shared -Wl,--as-needed,-soname,libsecretsharing.jni.so -o $@ $(OBJS) $(LIBS)

aontrs-test: ./main.cc $(OBJS)
	$(CC) $(CFLAGS) $(INCLUDES) -o aontrs-test ./main.cc $(OBJS) $(LIBS)

.PHONY: install clean

install: libsecretsharing.jni.so
	@cp $< /usr/local/lib

clean:
	@rm -f libsecretsharing.a aontrs-test libsecretsharing.jni.so
	@rm -f $(OBJS)
	@rm -f secretsharing/*.class
