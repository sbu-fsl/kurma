/*
 * Copyright (C) 2013-2018 Ming Chen
 * Copyright (C) 2016-2016 Praveen Kumar Morampudi
 * Copyright (C) 2016-2016 Harshkumar Patel
 * Copyright (C) 2017-2017 Rushabh Shah
 * Copyright (C) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class eu_vandertil_jerasure_jni_Galois */

#ifndef _Included_eu_vandertil_jerasure_jni_Galois
#define _Included_eu_vandertil_jerasure_jni_Galois
#ifdef __cplusplus
extern "C" {
#endif
/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_single_multiply
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1single_1multiply
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_single_divide
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1single_1divide
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_log
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1log
  (JNIEnv *, jclass, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_ilog
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1ilog
  (JNIEnv *, jclass, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_create_log_tables
 * Signature: (I)I
 */
JNIEXPORT jboolean JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1create_1log_1tables
  (JNIEnv *, jclass, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_logtable_multiply
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1logtable_1multiply
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_logtable_divide
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1logtable_1divide
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_create_mult_tables
 * Signature: (I)I
 */
JNIEXPORT jboolean JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1create_1mult_1tables
  (JNIEnv *, jclass, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_multtable_multiply
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1multtable_1multiply
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_multtable_divide
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1multtable_1divide
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_shift_multiply
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1shift_1multiply
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_shift_divide
 * Signature: (III)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1shift_1divide
  (JNIEnv *, jclass, jint, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_create_split_w8_tables
 * Signature: ()I
 */
JNIEXPORT jboolean JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1create_1split_1w8_1tables
  (JNIEnv *, jclass);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_split_w8_multiply
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1split_1w8_1multiply
  (JNIEnv *, jclass, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_inverse
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1inverse
  (JNIEnv *, jclass, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_shift_inverse
 * Signature: (II)I
 */
JNIEXPORT jint JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1shift_1inverse
  (JNIEnv *, jclass, jint, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_get_mult_table
 * Signature: (I)[I
 */
JNIEXPORT jintArray JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1get_1mult_1table
  (JNIEnv *, jclass, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_get_div_table
 * Signature: (I)[I
 */
JNIEXPORT jintArray JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1get_1div_1table
  (JNIEnv *, jclass, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_get_log_table
 * Signature: (I)[I
 */
JNIEXPORT jintArray JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1get_1log_1table
  (JNIEnv *, jclass, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_get_ilog_table
 * Signature: (I)[I
 */
JNIEXPORT jintArray JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1get_1ilog_1table
  (JNIEnv *, jclass, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_region_xor
 * Signature: ([B[B[BI)V
 */
JNIEXPORT void JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1region_1xor
  (JNIEnv *, jclass, jbyteArray, jbyteArray, jbyteArray, jint);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_w08_region_multiply
 * Signature: ([BII[BZ)V
 */
JNIEXPORT void JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1w08_1region_1multiply
  (JNIEnv *, jclass, jbyteArray, jint, jint, jbyteArray, jboolean);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_w16_region_multiply
 * Signature: ([BII[BZ)V
 */
JNIEXPORT void JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1w16_1region_1multiply
  (JNIEnv *, jclass, jbyteArray, jint, jint, jbyteArray, jboolean);

/*
 * Class:     eu_vandertil_jerasure_jni_Galois
 * Method:    galois_w32_region_multiply
 * Signature: ([BII[BZ)V
 */
JNIEXPORT void JNICALL Java_eu_vandertil_jerasure_jni_Galois_galois_1w32_1region_1multiply
  (JNIEnv *, jclass, jbyteArray, jint, jint, jbyteArray, jboolean);

#ifdef __cplusplus
}
#endif
#endif
