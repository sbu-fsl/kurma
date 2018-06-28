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
#ifndef _JAVAUTILITY_H
#define _JAVAUTILITY_H

#include <jni.h>
#include <vector>

jint throwNoClassDefError(JNIEnv *env, char *message);
jint throwOutOfMemoryError(JNIEnv *env, char *message);
jint throwIllegalArgumentException(JNIEnv *env, char* message);

/*
* arrayOfArrays and resultData do not have to be initialized.
*/
bool getArrayOfByteArrays(JNIEnv *env, jobjectArray *arrays, std::vector<jbyteArray> *arrayOfArrays, std::vector<jbyte*> *resultData, int numArrays);
void freeArrayOfByteArrays(JNIEnv *env, std::vector<jbyteArray>* arrayOfArrays, std::vector<jbyte*> *resultData, int numArrays);
#endif