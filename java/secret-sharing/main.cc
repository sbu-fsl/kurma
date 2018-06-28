/**
 * Copyright 2016-2017 Ming Chen
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * main test program
 */
#include <stdio.h>
#include <stdlib.h>
#include <iostream>
#include <sys/time.h>

#include <algorithm>

#include "CDCodec.h"
#include "CryptoPrimitive.h"

#define MAIN_CHUNK

using namespace std;

void randmem(unsigned char *buf, int bufsize) {
	for (int i = 0; i < bufsize; ++i) {
		buf[i] = rand() & 255;
	}
}

int main(int argc, char *argv[]){
	const int kSecretSize = 1024 * 1024;
	unsigned char secret[kSecretSize] = {'h', 'e', 'l', 'l', 'o'};
	unsigned char share[2 * kSecretSize];
	int shareSize = 0;
	unsigned char recover[kSecretSize];
	int *kShareIdList;
	const int n = 4;
	const int m = 1;
        const int k = n - m;
	const int r = 2;

	if (!CryptoPrimitive::opensslLockSetup()) {
		fprintf(stderr, "failed to set up OpenSSL");
		return 1;
	}
	CDCodec codec(CAONT_RS_TYPE, n, m, r);

	randmem(secret, kSecretSize);
	if (!codec.encoding(secret, kSecretSize, share, 2 * kSecretSize, &shareSize)) {
		fprintf(stderr, "failed to encode");
		return 1;
	}
	fprintf(stderr, "===== shareSize is %d =====\n", shareSize);

	kShareIdList = (int *)malloc(k * sizeof(int));
	for (int i = 0; i < k; ++i) kShareIdList[i] = i;
	// swap share-0 and share-1
	memmove(recover, share, shareSize);
	memmove(share, share + shareSize, shareSize);
	memmove(share + shareSize, recover, shareSize);
	std::swap(kShareIdList[0], kShareIdList[1]);

	if (!codec.decoding(share, kShareIdList, shareSize, kSecretSize, recover)) {
		fprintf(stderr, "failed to decode");
	}
	if (memcmp(secret, recover, kSecretSize) != 0) {
		fprintf(stderr, "recovered is not correct\n");
	} else {
		fprintf(stderr, "SUCCEED!!!!!\n");
	}
	free(kShareIdList);
	if (!CryptoPrimitive::opensslLockCleanup()) {
		fprintf(stderr, "failed to clean up OpenSSL");
		return 1;
	}
	return 0;
}
