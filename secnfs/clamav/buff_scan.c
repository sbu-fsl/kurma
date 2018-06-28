#include <clamav.h>
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

int av_init(struct cl_engine **a_engine) {
  int ret;
  int sigs = 0;
  const char *dbdir;
  struct cl_engine *engine;

  if ((ret = cl_init(0)) != CL_SUCCESS) {
    printf("failed to initialize clamav, ret %d\n", ret);
    return -1;
  }

  if (!(engine = cl_engine_new())) {
    // TODO: uninitialize clamav?
    printf("failed to create new clamav engine\n");
    return -1;
  }

  if ((ret = cl_initialize_crypto()) != 0) {
    printf("failed to initialize clamav crypto, ret %d\n", ret);
    return -1;
  }

  dbdir = cl_retdbdir();
  if ((ret = cl_load(dbdir, engine, &sigs, CL_DB_STDOPT)) != CL_SUCCESS) {
    // TODO: uninitialize clamav?
    printf("failed to load clamav database %s, ret %d, sigs %d\n", dbdir, ret,
           sigs);
    return -1;
  }

  if ((ret = cl_engine_compile(engine)) != CL_SUCCESS) {
    // TODO: uninitialize clamav?
    printf("failed to compile clamav, ret %d\n", ret);
    return -1;
  }

  printf("clamav initialized successfully\n");
  *a_engine = engine;
  return 0;
}

void usage(const char *prog) { printf("Usage: %s <files>\n", prog); }

int main(int argc, char *argv[]) {
  struct cl_engine *engine;
  struct stat file_stat;
  void *buff;
  void *read_buff;
  int fd;
  ssize_t read_ret;
  int cl_ret;
  const char *virus;
  int i;

  if (argc < 2) {
    usage(argv[0]);
    return 0;
  }

  printf("initializing clamav\n");
  if (av_init(&engine) != 0) {
    return -1;
  }

  for (i = 1; i < argc; i++) {

    if (stat(argv[i], &file_stat) != 0) {
      printf("%s: stat returned error %d\n", argv[i], errno);
      continue;
    }

    buff = malloc(file_stat.st_size);
    fd = open(argv[i], O_RDONLY);
    if (fd == -1) {
      printf("%s: unable to open for read\n", argv[i]);
      continue;
    }
    read_buff = buff;
    do {
      read_ret = read(fd, read_buff, file_stat.st_size);
      read_buff += read_ret;
    } while (read_ret != 0 && read_ret != -1);
    if (read_ret == -1) {
      printf("%s: read failed with %d\n", argv[i], errno);
      free(buff);
      close(fd);
      continue;
    }
    close(fd);

    cl_ret = cl_scanbuff(buff, file_stat.st_size, &virus, NULL, engine,
                         CL_SCAN_STDOPT);
    if (cl_ret == CL_CLEAN) {
      printf("%s: clean\n", argv[i]);
      free(buff);
      continue;
    }

    if (cl_ret == CL_VIRUS) {
      printf("%s: infected by virus %s\n", argv[i], virus);
      free(buff);
      continue;
    }

    printf("%s: clamav internal error %d\n", argv[i], cl_ret);
    free(buff);
    continue;
  }

  return 0;
}
