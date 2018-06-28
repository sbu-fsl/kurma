import datetime

file1 = 'shiv_times/readtimes000_1mb'
file2 = 'del_times/deltimes000_1mb'
outfile = 'diffdata'

times_read = []
times_del = []

f1 = open(file1, 'r')
for line in f1:
    line = line.strip('\n')
    times_read.append(datetime.datetime.strptime(line, "%Y-%m-%d %H:%M:%S.%f"))
f1.close()

f2 = open(file2, 'r')
for line in f2:
    line = line.strip('\n')
    times_del.append(datetime.datetime.strptime(line, "%Y-%m-%d %H:%M:%S.%f"))
f2.close()

print times_read

print "\n\n"
print times_del

of = open(outfile, 'w+')
for i in range (0, 30):
    elapsedtime = times_read[i] - times_del[i]
    of.write(str(abs(elapsedtime.microseconds/1000)) + '\n')
of.close()

print "Done"
