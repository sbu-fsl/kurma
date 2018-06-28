# Copyright (C) 2013-2018 Ming Chen
# Copyright (C) 2016-2016 Praveen Kumar Morampudi
# Copyright (C) 2016-2016 Harshkumar Patel
# Copyright (C) 2017-2017 Rushabh Shah
# Copyright (C) 2013-2018 Erez Zadok
# Copyright (c) 2013-2018 Stony Brook University
# Copyright (c) 2013-2018 The Research Foundation for SUNY
# This file is released under the GPL.
#!/bin/bash -

mvn install:install-file -Dfile=lib/jerasure-jni-1.2.jar \
   -DgroupId=eu.vandertil.jerasure -DartifactId=jerasure-jni \
   -Dversion=1.2 -Dpackaging=jar -DgeneratePom=true
