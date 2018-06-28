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

#include "secretsharing_CDCodecJNI.h"

#include <stdio.h>
#include <errno.h>
#include <stdio.h>
#include "CDCodec.h"
#include "CryptoPrimitive.h"

// TODO: add detection of race conditions
static const int MAX_CODECS = 4096;
CDCodec *codecs[MAX_CODECS];
static int ncodec = 0;

/* NOTE that create() and destroy() must not be interleaved.
 *
 * Class:     secretsharing_CDCodecJNI
 * Method:    create
 * Signature: (IIII)I
 */
JNIEXPORT jint JNICALL Java_secretsharing_CDCodecJNI_create(JNIEnv *evn,
                                                            jobject clazz,
                                                            jint type, jint n,
                                                            jint m, jint r) {
  int cid = __sync_fetch_and_add(&ncodec, 1);
  if (cid == 0) { // the first codec
    if (!CryptoPrimitive::opensslLockSetup()) {
      fprintf(stderr, "failed to set up OpenSSL");
      __sync_sub_and_fetch(&ncodec, 1);
      return -EINVAL;
    }
  }

  codecs[cid] = new CDCodec(AONT_RS_TYPE, n, m, r);
  if (!codecs[cid]) {
      fprintf(stderr, "failed to set up OpenSSL");
      __sync_sub_and_fetch(&ncodec, 1);
      return -ENOMEM;
  }

  return cid;
}

/*
 * Class:     secretsharing_CDCodecJNI
 * Method:    getShareSize
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL
    Java_secretsharing_CDCodecJNI_getShareSize(JNIEnv *env, jobject clazz,
                                               jint cid, jint secretSize) {
  if (cid < 0 || cid >= MAX_CODECS) {
    fprintf(stderr, "invalid cid: %d", cid);
    return -EINVAL;
  }
  CDCodec* codec = codecs[cid];
  return codec->getShareSize(secretSize);
}

#ifndef NDEBUG
static void print_buf(const char *name, jbyte *buf) {
  fprintf(stderr, "%s: ", name);
  for (int i = 0; i < 16; ++i) {
    fprintf(stderr, "%02x ", (unsigned char)buf[i]);
  }
  fprintf(stderr, "...\n");
}
#endif

/*
 * Class:     secretsharing_CDCodecJNI
 * Method:    getAlignedSecretSize
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_secretsharing_CDCodecJNI_getAlignedSecretSize(
        JNIEnv *env, jobject clazz, jint cid, jint secretSize) {
  if (cid < 0 || cid >= MAX_CODECS) {
    fprintf(stderr, "invalid cid: %d", cid);
    return -EINVAL;
  }
  CDCodec* codec = codecs[cid];
  return codec->getAlignedSecretSize(secretSize);
}

/*
 * Class:     secretsharing_CDCodecJNI
 * Method:    encode
 * Signature: (I[BI[BI)Z
 */
JNIEXPORT jint JNICALL Java_secretsharing_CDCodecJNI_encode(
    JNIEnv *env, jobject clazz, jint cid, jbyteArray jsecret, jint secretSize,
    jbyteArray jshares) {
  if (cid < 0 || cid >= MAX_CODECS) {
    fprintf(stderr, "invalid cid: %d", cid);
    return -EINVAL;
  }
  jbyte *secret = env->GetByteArrayElements(jsecret, NULL);
  jbyte *shares = env->GetByteArrayElements(jshares, NULL);
  jint secretBufferSize = env->GetArrayLength(jsecret);
  jint sharesBufferSize = env->GetArrayLength(jshares);

#ifndef NDEBUG
  fprintf(stderr, "encoding...\n");
  print_buf("secret", secret);
#endif

  int shareSize = -1;
  CDCodec* codec = codecs[cid];
  codec->encodingEx(reinterpret_cast<unsigned char *>(secret), secretBufferSize,
                    secretSize, reinterpret_cast<unsigned char *>(shares),
                    sharesBufferSize, &shareSize);

#ifndef NDEBUG
  print_buf("shares", shares);
#endif

  env->ReleaseByteArrayElements(jshares, shares, 0);
  env->ReleaseByteArrayElements(jsecret, secret, 0);
  return shareSize;
}

/*
 * Class:     secretsharing_CDCodecJNI
 * Method:    decode
 * Signature: (I[B[B)I
 */
JNIEXPORT jboolean JNICALL Java_secretsharing_CDCodecJNI_decode(
    JNIEnv *env, jobject clazz, jint cid, jbyteArray jshares, jint shareSize,
    jintArray jerasures, jbyteArray jsecret, jint secretSize) {
  if (cid < 0 || cid >= MAX_CODECS) {
    fprintf(stderr, "invalid cid: %d", cid);
    return -EINVAL;
  }
  jbyte *secret = env->GetByteArrayElements(jsecret, NULL);
  jbyte *shares = env->GetByteArrayElements(jshares, NULL);
  jint *erasures = env->GetIntArrayElements(jerasures, NULL);

#ifndef NDEBUG
  fprintf(stderr, "encoding...\n");
  print_buf("shares", shares);
#endif

  CDCodec* codec = codecs[cid];
  bool res = codec->decoding(reinterpret_cast<unsigned char *>(shares),
                             erasures, shareSize, secretSize,
                             reinterpret_cast<unsigned char *>(secret));

#ifndef NDEBUG
  print_buf("secret", secret);
#endif

  env->ReleaseIntArrayElements(jerasures, erasures, 0);
  env->ReleaseByteArrayElements(jshares, shares, 0);
  env->ReleaseByteArrayElements(jsecret, secret, 0);
  return res;
}

/*
 * Class:     secretsharing_CDCodecJNI
 * Method:    destroy
 * Signature: (I)Z
 */
JNIEXPORT jboolean JNICALL Java_secretsharing_CDCodecJNI_destroy(JNIEnv *env,
                                                                 jobject clazz,
                                                                 jint cid) {

  if (codecs[cid] == NULL) {
    fprintf(stderr, "invalid Codec ID: %d", cid);
    return false;
  }

  delete codecs[cid];
  codecs[cid] = NULL;

  if (__sync_sub_and_fetch(&ncodec, 1) == 0) {
    if (!CryptoPrimitive::opensslLockCleanup()) {
      fprintf(stderr, "cannot clean up OpenSSL locks");
      return false;
    }
  }

  return true;
}
