#!/bin/bash
perl -e '@list = (1,2,3,4,5,6,7,8,9);foreach $k (@list) {print `ntpstat`; print `date`;sleep 5;}'
