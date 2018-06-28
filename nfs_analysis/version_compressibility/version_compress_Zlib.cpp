#include <iostream>
#include <fstream>
#include <vector>
#include <cerrno>
#include <cstring>
#include <sstream>
#include <cstdlib>
#include <cassert>
#include "zlib-1.2.10/zlib.h"

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

  z_stream strm;
  strm.zalloc = Z_NULL;
  strm.zfree = Z_NULL;
  strm.opaque = Z_NULL;

  strm.avail_in = ver_list.size() * sizeof(long);
  strm.next_in = (Bytef *)&ver_list[0];
  strm.avail_out = cmp_list.size() * sizeof(long); 
  strm.next_out = (Bytef *)&cmp_list[0];

  //initialize the stream and do compression
  deflateInit(&strm, Z_BEST_COMPRESSION); //level 9
  int err = deflate(&strm, Z_FINISH);
  if (err < 0) {
    cerr << err << "\tCompression failed\n";
    exit(err);
  }
  deflateEnd(&strm);
  
  int original_size = ver_list.size() * sizeof(long);
  int cmp_size = strm.total_out;
  //compressed size can be bigger than the original size for small data
  //set comp_pctg = 0
  if (cmp_size > original_size) 
    comp_pctg = 0;
  else
    comp_pctg = ((original_size - cmp_size) * 100)/ original_size;

#define DECOMP_CHECK
//#undef DECOMP_CHECK
#ifdef DECOMP_CHECK
  z_stream instrm;
  vector<long> dcmp_list(max_index, 0);
  
  instrm.zalloc = Z_NULL;
  instrm.zfree = Z_NULL;
  instrm.opaque = Z_NULL;
  
  instrm.avail_in = (int)(strm.next_out - (Bytef *)&cmp_list[0]);
  if (instrm.avail_in != strm.total_out) {
    cout << instrm.avail_in << "\t" << strm.total_out << " debug check" << "\n";
    exit(-1);
  }
  instrm.next_in = (Bytef *)&cmp_list[0];
  instrm.avail_out = dcmp_list.size() * sizeof(long);
  instrm.next_out = (Bytef *)&dcmp_list[0];

  inflateInit(&instrm);
  err =inflate(&instrm, Z_NO_FLUSH);
  if (err < 0) {
    cerr << err << "\tDecompression failed\n";
     exit(err);
  }
  inflateEnd(&instrm);

  for (auto int i = 0 ; i < dcmp_list.size(); i++) {
    if (ver_list[i] != dcmp_list[i]) {
      cout << "Original version list:\n";  
      for (auto int j = 0 ; j < ver_list.size(); j++) {
        cout << ver_list[j] << " ";
      }
      cout << "\n";
      cout << "Decompressed version list with decompression err: "<< err << "\n";  
      for (auto int j = 0 ; j < dcmp_list.size(); j++) {
        cout << dcmp_list[j] << " ";
      }
      cout << "\n";
      cout << fh << "\t\t" << file_sz << "\t\t" 
           << original_size << "\t\t" << cmp_size <<
           "\t\t" << comp_pctg << "\t" << avg_writes << "\t\t" << wcount <<"\n";
      cerr << "Something wrong happened in (de)compression.\n";
      exit(i);
    }
  }
#endif
  cout << fh << "\t\t" << file_sz << "\t\t" 
       << original_size << "\t\t" << cmp_size <<
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
