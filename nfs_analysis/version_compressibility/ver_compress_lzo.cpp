#include <iostream>
#include <fstream>
#include <vector>
#include <cerrno>
#include <cstring>
#include <sstream>
#include <cstdlib>
#include <cassert>
#include <lzo/lzoconf.h>
#include <lzo/lzo1x.h>

using namespace std;
#define KB 1024
#define ONEMB (1024 * (KB))
void process_fh(long fh, vector <long> list, long max_size,
                long total_writes, long wcount, int chunk_size ) {
  long index;
  long max_index = (max_size / chunk_size) + 1;
  vector<long> ver_list(max_index, 0);
  //provision more space for compression since
  //compressed data size can be bigger than the 
  //original data size
  vector<long> cmp_list(max_index + 100, 0);

  int avg_writes = total_writes / wcount;
  long file_sz = (max_size) / KB;
  int comp_pctg;
  
  //populate the version_list from the input list
  //version gets incremented for each occurence of index
  for (auto int i = 0; i < list.size(); i++) {
    index = list[i];
    ver_list[index] += 1;
  }
  //compression
  int r;
  lzo_voidp wrkmem;
  lzo_uint in_len = ver_list.size() * sizeof(long);
  lzo_uint out_len;
  lzo_uint new_len;

  if (lzo_init() != LZO_E_OK) {
    cout << "LZO couldn't be initialized\n";
    return;
  }

#define BEST_COMPRESSION
#ifdef BEST_COMPRESSION
  wrkmem = (lzo_voidp) malloc(LZO1X_999_MEM_COMPRESS);
#else
  wrkmem = (lzo_voidp) malloc(LZO1X_1_MEM_COMPRESS);
#endif

  if(wrkmem == NULL) {
    cout << "Out of memory\n";
    return;
  }
#ifdef BEST_COMPRESSION
  r = lzo1x_999_compress((lzo_bytep)&ver_list[0], in_len, (lzo_bytep)&cmp_list[0], &out_len, wrkmem);
#else
  //level-1 compression
  r = lzo1x_1_compress((lzo_bytep)&ver_list[0], in_len, (lzo_bytep)&cmp_list[0], &out_len, wrkmem);
#endif
  //compressed size can be bigger than the original size for small data
  //set comp_pctg = 0
  if (out_len > in_len) 
    comp_pctg = 0;
  else
    comp_pctg = ((in_len - out_len) * 100)/ in_len;

#define DECOMP_CHECK
//#undef DECOMP_CHECK
#ifdef DECOMP_CHECK
  new_len = in_len;
  vector<long> dcmp_list(max_index, 0);

  r = lzo1x_decompress_safe((lzo_bytep)&cmp_list[0], out_len, (lzo_bytep)&dcmp_list[0], &new_len, NULL);
  if (r == LZO_E_OK && new_len == in_len) {
    if (dcmp_list != ver_list) {
      cout << "something wrong happened in compression/decompression\n";
      return;
    }
  } else {
      cout << "something wrong happened in decompression\n";
      return;
  }
#endif
  cout << fh << "\t\t" << file_sz << "\t\t" 
       << in_len << "\t\t" << out_len <<
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

  for (line; getline(input, line);) {
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
