#include <iostream>
#include <fstream>
#include <vector>
#include <cerrno>
#include <cstring>
#include <sstream>
#include <cstdlib>
#include <cassert>
#include "FrameOfReference/include/turbocompression.h"

using namespace std;

#define KB 1024
#define ONEMB (1024 * (KB))
void process_fh(long fh, vector <long> list, long max_size,
                long total_writes, long wcount, int chunk_size ) {
  long index;
  long max_index = (max_size / chunk_size) + 1;
  vector<uint64_t> ver_list(max_index, 0);

  int avg_writes = total_writes / wcount;
  long file_sz = (max_size) / KB;
  
  //populate the version_list from the input list
  //version gets incremented for each occurence of index
  for (uint64_t i = 0; i < list.size(); i++) {
    index = list[i];
    ver_list[index] += 1;
  }
#define FOR_COMPRESSION
#ifdef FOR_COMPRESSION
  //provision more space for compression since
  //compressed data size can be bigger than the 
  //original data size
  vector<uint8_t> cmp_list(4 * ver_list.size() + 1024, 0);
  int comp_pctg;
  size_t compressed_size, original_size;
  original_size = ver_list.size() * sizeof(uint64_t);

  uint8_t *end = turbocompress64(ver_list.data(), ver_list.size(), cmp_list.data());
   
  //compressed size can be bigger than the original size for small data
  //set comp_pctg = 0
  compressed_size = (size_t)(end - cmp_list.data());
  if (compressed_size > original_size) 
    comp_pctg = 0;
  else
    comp_pctg = ((original_size - compressed_size) * 100)/ original_size;

#define DECOMP_CHECK
//#undef DECOMP_CHECK
#ifdef DECOMP_CHECK
  vector<uint64_t> dcmp_list(max_index + 1024, 0);
  uint32_t dcmp_size = 0;
  turbouncompress64(cmp_list.data(), dcmp_list.data(), dcmp_size);
  dcmp_list.resize(dcmp_size); 
  if (dcmp_list != ver_list) {
      cerr << "Something wrong happened in (de)compression for.\n";
      cout << fh << "\t\t" << file_sz << "\t\t" 
           << original_size << "\t\t" << compressed_size <<
           "\t\t" << comp_pctg << "\t" << avg_writes << "\t\t" << wcount <<"\n";
      exit(-1);
  }
#endif
#endif
  cout << fh << "\t\t" << file_sz << "\t\t" 
       << original_size << "\t\t" << compressed_size <<
       "\t\t\t" << comp_pctg << "\t\t" << avg_writes << "\t\t" << wcount <<"\n";
}

//pushes all the modified chunk's indices into the list
void push_index(vector<long> &list, long offset, int length, int chunk_size, 
              long* max_size)
{
  int i;
  long index1, index2, cur_size;

  cur_size = offset + length;
  index1 = offset / chunk_size;
  index2 = cur_size / chunk_size;

  if (*max_size < cur_size) 
    *max_size = cur_size;

  for (i = index1; i <= index2; i++)
    list.push_back(i);
  
  return;
}
 
int main(int argc, char *argv[])
{
  //This list keeps track of all the modified chunk by its index
  //the index is determined by the given chunk_size and the location
  //at which file was modified. see push_index function.
  vector<long> list;
  //placeholder for row entry
  vector<long> record;
  string line;
  long cur_fh = -1, prev_fh = -1, max_size = -1;
  long  total_writes = 0;
  int count = 0;

  if (argc != 3) {
    cout << "Usage: ./ver_compress <path to preprocessed file> <chunk_size in KB>\n";
    exit(0);
  }

  ifstream input(argv[1]);
  if (!input) {
    cerr << "File couldn't be opened:" << strerror(errno) << "\n";
    return errno;
  }

  int chunk_size = atoi(argv[2]);
  if (chunk_size <= 0) {
    return -1;
  }
  //assume user input is in KB
  chunk_size = chunk_size * KB;
 
  cout <<"fname\t\t\tfsize(KB)\tver size(B)\tcompressed_size(B)\tcompressibility(%)\tavg_writes(B)\tcount\n";

  for (; getline(input, line);) {
    long num;
    stringstream st(line);

    while (st >> num) {
      record.push_back(num);
    }
    cur_fh = record[0];

    if (cur_fh != prev_fh) {
      if (prev_fh == -1) {
        push_index(list, record[1], record[2], chunk_size, &max_size);
        prev_fh = cur_fh;
        total_writes = record[2];
        count = 1;
        record.clear();
      } else {
        //filter out files of size smaller than 1MB
        if (max_size >= ONEMB)
          process_fh(prev_fh, list, max_size, total_writes, count, chunk_size);
        
        //reset the list
        list.clear();
        max_size = -1;
        push_index(list, record[1], record[2], chunk_size, &max_size);
        prev_fh = cur_fh;
        total_writes = record[2];
        count = 1;
        record.clear();
      }
    } else {
      push_index(list, record[1], record[2], chunk_size, &max_size);
      total_writes += record[2];
      count++;
      record.clear();
    }
  }

  if (list.size()) { 
    //filter out files of size smaller than 1MB
    if (max_size >= ONEMB)
      process_fh(cur_fh, list, max_size, total_writes, count, chunk_size);
  }

  input.close();
  return 0;
}
