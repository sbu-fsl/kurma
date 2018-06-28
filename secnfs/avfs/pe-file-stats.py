#!/bin/env python
'''Statistics of Portable Executable (PE) files - file size, section size,
number of sections.

by Arun Olappamanna Vasudevan, arunov1986@gmail.com,
                               aolappamanna@cs.stonybrook.edu
'''

pe_parse_mod = 'pefile'
mymods = [pe_parse_mod, 'fnmatch', 'os', 'argparse', 'string', 'numpy']

for module_name in mymods:
	try:
		__import__(module_name)
	except ImportError:
		print "Module %s not found." %(module_name)
		if(module_name == pe_parse_mod):
			print('')
			print('Download pefile: svn checkout'
				' http://pefile.googlecode.com/svn/trunk/'
							' pefile-read-only')
			print('Add to PYTHONPATH')
			print('Ref: https://code.google.com/p/pefile/')
		exit(1)

import pefile
import fnmatch
import os
import argparse
import numpy as np

# Ref: http://en.wikipedia.org/wiki/Portable_Executable
patterns = ['*.exe', '*.cpl', '*.dll', '*.ocx', '*.sys', '*.scr', '*.drv',
		'*.efi']

num_all_files = 0
file_size_arr = []
sec_size_arr = []
sec_cnt_arr = []

def print_res():
	print('')
	file_size_arr_np = np.array(file_size_arr)
	sec_size_arr_np = np.array(sec_size_arr)
	sec_cnt_arr_np = np.array(sec_cnt_arr)
	print('Number of PE files parsed: %d' % file_size_arr_np.size)
	print('Number of files parsed: %d' % num_all_files)
	if(num_all_files != 0):
		print('Percent of PE files: %f' % (float(file_size_arr_np.size)
				    			/ num_all_files*100.0))
	if(file_size_arr_np.size != 0):
		print('Average file size: %f' % file_size_arr_np.mean())
		print('Max and min file size: %d, %d' % (file_size_arr_np.max(),
							file_size_arr_np.min()))
		print('Number of sections parsed: %d' % sec_size_arr_np.size)
		print('Average section size: %f' % sec_size_arr_np.mean())
		print('Max and min section size: %d, %d' %
				(sec_size_arr_np.max(), sec_size_arr_np.min()))
		print('Average sections per file: %f' % sec_cnt_arr_np.mean())
		print('Max and min number of sections per file: %d, %d' %
				(sec_cnt_arr_np.max(), sec_cnt_arr_np.min()))
	return

parser = argparse.ArgumentParser(description='Statistics of Portable Executable'
' (PE) files - file size, section size, number of sections.', prog=__file__)
parser.add_argument('rootPath', metavar='PATH', help='Directory to parse PE'
							' files recursively')
args = parser.parse_args()

rootPath = args.rootPath

if not (os.path.isdir(rootPath)):
	parser.print_usage()
	print('%s: error: cannot find directory %s' % (__file__, rootPath))
	exit(1)

try:
	for root, dirs, files in os.walk(rootPath):
		num_all_files += len(files)
		for pattern in patterns:
			for filename in fnmatch.filter(files, pattern):
				path = os.path.join(root, filename)
				try:
					pe = pefile.PE(path)
				except:
					print('%s: Not PE file!' % path)
					continue

				print(path)

				size = os.path.getsize(path)
				file_size_arr.append(size)

				file_section_cnt = 0
				for section in pe.sections:
					file_section_cnt += 1
					ssize = section.SizeOfRawData
					sec_size_arr.append(ssize)
				sec_cnt_arr.append(file_section_cnt)

except Exception as e:
	print e.message

finally:
	print_res()

