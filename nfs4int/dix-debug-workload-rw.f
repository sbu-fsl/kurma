#
# CDDL HEADER START
#
# The contents of this file are subject to the terms of the
# Common Development and Distribution License (the "License").
# You may not use this file except in compliance with the License.
#
# You can obtain a copy of the license at usr/src/OPENSOLARIS.LICENSE
# or http://www.opensolaris.org/os/licensing.
# See the License for the specific language governing permissions
# and limitations under the License.
#
# When distributing Covered Code, include this CDDL HEADER in each
# file and include the License file at usr/src/OPENSOLARIS.LICENSE.
# If applicable, add the following below this CDDL HEADER, with the
# fields enclosed by brackets "[]" replaced with your own identifying
# information: Portions Copyright [yyyy] [name of copyright owner]
#
# CDDL HEADER END
#
#
# Copyright 2007 Sun Microsystems, Inc.  All rights reserved.
# Use is subject to license terms.
#

set $dir=/tmp
set $nfiles=100
set $filesize=1m
set $nthreads=16
set $iosize=4k
set $dsync=true
set $riters=1
set $witers=1

define fileset name=fileset1,path=$dir,size=$filesize,entries=$nfiles,dirwidth=10000,prealloc=100,filesizegamma=0

define process name=fileoperator,instances=1
{
  thread name=fileoperatorthd,memsize=10m,instances=$nthreads
  {
    flowop openfile name=openfile1,filesetname=fileset1,fd=1,dsync=$dsync,directio
    flowop read name=fileread,fd=1,iosize=$iosize,random,dsync=$dsync,iters=$riters
    flowop write name=filewrite,fd=1,iosize=$iosize,random,dsync=$dsync,iters=$witers
    flowop closefile name=fileclose,fd=1
  }
}

echo  "File RW Version 1.0 personality successfully loaded"
usage "Usage: set \$dir=<dir>"
usage "       set \$nfiles=<value>    defaults to $nfiles"
usage "       set \$filesize=<value>  defaults to $filesize"
usage "       set \$nthreads=<value>  defaults to $nthreads"
usage "       set \$iosize=<size>     defaults to $iosize"
usage "       set \$riters=<value>    defaults to $riters"
usage "       set \$witers=<value>    defaults to $witers"
usage "       set \$dsync=<value>     defaults to $dsync"
usage "       run runtime (e.g. run 60)"

set $dsync=true
set $iosize=4k
set $nthreads=32
set $numactivevids=30
set $eventrate=90
set $witers=16
set $riters=1
set $numpassivevids=190
set $fixediosize=1m
set $filesize=10m
set $nfiles=100
set $dir=/mnt/rw/test
#system("set_delay 0")
create fileset
#system("set_delay 0")
debug 2
psrun -5 300
