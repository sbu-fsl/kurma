/**
 * Test and benchmark DIXIO.
 *
 * The DIXIO is much slower than the normal I/O. The benchmarking results on
 * SGDP06 is as follows:
 * #./dixio_test -p b -s 1 /tmp
 * finished creating 16 files
 * finished creating 16 files
 * R/W     #threads        POSIX   DIXIO
 * R       1       218738511872    12718080
 * W       1       2008346624      13099008
 *
 * R       2       202258083840    23425024
 * W       2       4448092160      23461888
 *
 */
#include <unistd.h>
#include <libaio.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <pthread.h>
#include <malloc.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <arpa/inet.h>
#include <linux/fs.h>
#include <assert.h>
#include <sys/mman.h>

#include "dixio.h"


const size_t N = 8;

const size_t SEC = 512;

const size_t PAGESIZE = 4096;

static uint8_t magic_byte = 0x73;

static uint32_t ref_tag = 0xbeaf;

static uint32_t pi_flags = GENERATE_GUARD;

/* Table generated using the following polynomium:
 * x^16 + x^15 + x^11 + x^9 + x^8 + x^7 + x^5 + x^4 + x^2 + x + 1
 * gt: 0x8bb7
 */
static const uint16_t t10_dif_crc_table[256] = {
	0x0000, 0x8BB7, 0x9CD9, 0x176E, 0xB205, 0x39B2, 0x2EDC, 0xA56B,
	0xEFBD, 0x640A, 0x7364, 0xF8D3, 0x5DB8, 0xD60F, 0xC161, 0x4AD6,
	0x54CD, 0xDF7A, 0xC814, 0x43A3, 0xE6C8, 0x6D7F, 0x7A11, 0xF1A6,
	0xBB70, 0x30C7, 0x27A9, 0xAC1E, 0x0975, 0x82C2, 0x95AC, 0x1E1B,
	0xA99A, 0x222D, 0x3543, 0xBEF4, 0x1B9F, 0x9028, 0x8746, 0x0CF1,
	0x4627, 0xCD90, 0xDAFE, 0x5149, 0xF422, 0x7F95, 0x68FB, 0xE34C,
	0xFD57, 0x76E0, 0x618E, 0xEA39, 0x4F52, 0xC4E5, 0xD38B, 0x583C,
	0x12EA, 0x995D, 0x8E33, 0x0584, 0xA0EF, 0x2B58, 0x3C36, 0xB781,
	0xD883, 0x5334, 0x445A, 0xCFED, 0x6A86, 0xE131, 0xF65F, 0x7DE8,
	0x373E, 0xBC89, 0xABE7, 0x2050, 0x853B, 0x0E8C, 0x19E2, 0x9255,
	0x8C4E, 0x07F9, 0x1097, 0x9B20, 0x3E4B, 0xB5FC, 0xA292, 0x2925,
	0x63F3, 0xE844, 0xFF2A, 0x749D, 0xD1F6, 0x5A41, 0x4D2F, 0xC698,
	0x7119, 0xFAAE, 0xEDC0, 0x6677, 0xC31C, 0x48AB, 0x5FC5, 0xD472,
	0x9EA4, 0x1513, 0x027D, 0x89CA, 0x2CA1, 0xA716, 0xB078, 0x3BCF,
	0x25D4, 0xAE63, 0xB90D, 0x32BA, 0x97D1, 0x1C66, 0x0B08, 0x80BF,
	0xCA69, 0x41DE, 0x56B0, 0xDD07, 0x786C, 0xF3DB, 0xE4B5, 0x6F02,
	0x3AB1, 0xB106, 0xA668, 0x2DDF, 0x88B4, 0x0303, 0x146D, 0x9FDA,
	0xD50C, 0x5EBB, 0x49D5, 0xC262, 0x6709, 0xECBE, 0xFBD0, 0x7067,
	0x6E7C, 0xE5CB, 0xF2A5, 0x7912, 0xDC79, 0x57CE, 0x40A0, 0xCB17,
	0x81C1, 0x0A76, 0x1D18, 0x96AF, 0x33C4, 0xB873, 0xAF1D, 0x24AA,
	0x932B, 0x189C, 0x0FF2, 0x8445, 0x212E, 0xAA99, 0xBDF7, 0x3640,
	0x7C96, 0xF721, 0xE04F, 0x6BF8, 0xCE93, 0x4524, 0x524A, 0xD9FD,
	0xC7E6, 0x4C51, 0x5B3F, 0xD088, 0x75E3, 0xFE54, 0xE93A, 0x628D,
	0x285B, 0xA3EC, 0xB482, 0x3F35, 0x9A5E, 0x11E9, 0x0687, 0x8D30,
	0xE232, 0x6985, 0x7EEB, 0xF55C, 0x5037, 0xDB80, 0xCCEE, 0x4759,
	0x0D8F, 0x8638, 0x9156, 0x1AE1, 0xBF8A, 0x343D, 0x2353, 0xA8E4,
	0xB6FF, 0x3D48, 0x2A26, 0xA191, 0x04FA, 0x8F4D, 0x9823, 0x1394,
	0x5942, 0xD2F5, 0xC59B, 0x4E2C, 0xEB47, 0x60F0, 0x779E, 0xFC29,
	0x4BA8, 0xC01F, 0xD771, 0x5CC6, 0xF9AD, 0x721A, 0x6574, 0xEEC3,
	0xA415, 0x2FA2, 0x38CC, 0xB37B, 0x1610, 0x9DA7, 0x8AC9, 0x017E,
	0x1F65, 0x94D2, 0x83BC, 0x080B, 0xAD60, 0x26D7, 0x31B9, 0xBA0E,
	0xF0D8, 0x7B6F, 0x6C01, 0xE7B6, 0x42DD, 0xC96A, 0xDE04, 0x55B3
};

uint16_t crc_t10dif(uint16_t crc, const unsigned char *buffer, uint32_t len)
{
	unsigned int i;

	for (i = 0 ; i < len ; i++)
		crc = (crc << 8) ^ t10_dif_crc_table[((crc >> 8) ^ buffer[i]) & 0xff];

	return crc;
}


static void stamp_pi_buffer(struct sd_dif_tuple *t,
			    void *sec_data, uint32_t pi_flags,
			    uint16_t app_tag,
			    uint32_t ref_tag)
{
	uint16_t csum = crc_t10dif(0, sec_data, SEC);

	t->guard_tag = pi_flags & GENERATE_GUARD ? 0 : htons(csum);
	t->app_tag = htons(app_tag);
	t->ref_tag = htonl(ref_tag);
}


static void dump_buffer(char *buf, size_t len)
{
	size_t off;
	char *p;

	for (p = buf; p < buf + len; p++) {
		off = p - buf;
		if (off % 32 == 0) {
			if (p != buf)
				printf("\n");
			printf("%05zu:", off);
		}
		printf(" %02x", *p & 0xFF);
	}
	printf("\n");
}

static void test_normal_dio(int fd, off_t offset, size_t block)
{
	ssize_t ret;
	void *buf, *buf2;
	struct iovec iov[1];

	size_t buf_len = block * PAGESIZE;

	if (posix_memalign(&buf, PAGESIZE, buf_len) ||
	    posix_memalign(&buf2, PAGESIZE, buf_len)) {
		error(1, ENOMEM, "posix_memalign");
	}

	memset(buf, magic_byte, buf_len);

	if ((ret = dixio_pwrite(fd, buf, NULL, buf_len, offset)) < 0) {
		error(1, -ret, "pwritev");
	}

	if ((ret = dixio_pread(fd, buf2, NULL, buf_len, offset)) < 0) {
		error(1, -ret, "preadv");
	}

	if (memcmp(buf, buf2, buf_len)) {
		error(1, EIO, "memcmp");
	}

	printf("%s succeed!\n", __FUNCTION__);

	free(buf);
	free(buf2);
}

// Allow buffer size of up to 10MB.
#define DIX_ALIGNMENT 10485760

static void set_dixio_buffer(int block, void *buf, void *prot_buf,
			     size_t buf_len)
{
	int i;
	void *pb;
	struct sd_dif_tuple *pp;

	memset(buf, magic_byte, buf_len);
	for (i = 0, pb = buf, pp = prot_buf;
	     i < block * 8; ++i, pb += SEC, ++pp) {
		stamp_pi_buffer(pp, pb, pi_flags, 0, ref_tag);
	}

}

static void test_dixio(int fd, off_t offset, size_t block)
{
	int i, ret;
	void *buf, *buf2, *prot_buf, *prot_buf2;

	size_t buf_len = block * PAGESIZE;
	size_t pbuf_len = block * 8 * sizeof(struct sd_dif_tuple);

	buf = mmap(NULL, DIX_ALIGNMENT, PROT_READ | PROT_WRITE,
		   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	buf2 = mmap(NULL, DIX_ALIGNMENT, PROT_READ | PROT_WRITE,
		    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	prot_buf = mmap(NULL, DIX_ALIGNMENT, PROT_READ | PROT_WRITE,
			MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	prot_buf2 = mmap(NULL, DIX_ALIGNMENT, PROT_READ | PROT_WRITE,
			 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	assert(buf != MAP_FAILED && buf2 != MAP_FAILED &&
	       prot_buf != MAP_FAILED && prot_buf2 != MAP_FAILED);
	if (buf_len > DIX_ALIGNMENT) {
		error(1, EIO, "I/O size too large (%d > %d)", buf_len,
		      DIX_ALIGNMENT);
	}

	/*if (posix_memalign(&buf, DIX_ALIGNMENT, DIX_ALIGNMENT) ||*/
	    /*posix_memalign(&buf2, DIX_ALIGNMENT, DIX_ALIGNMENT) ||*/
	    /*posix_memalign(&prot_buf, DIX_ALIGNMENT, DIX_ALIGNMENT) ||*/
	    /*posix_memalign(&prot_buf2, DIX_ALIGNMENT, DIX_ALIGNMENT)) {*/
		/*error(1, ENOMEM, "memalign");*/
	/*}*/

	set_dixio_buffer(block, buf, prot_buf, buf_len);

	dump_buffer(prot_buf, pbuf_len);

	ret = dixio_pwrite(fd, buf, prot_buf, buf_len, offset);
	if (ret < 0) {
		error(1, -ret, "dixio_pwrite");
	}

	ret = dixio_pread(fd, buf2, prot_buf2, buf_len, offset);
	if (ret < 0) {
		error(1, -ret, "dixio_pread");
	}

	if (memcmp(buf, buf2, buf_len)) {
		error(1, EIO, "data do not match!");
	}

	if (memcmp(prot_buf, prot_buf2, pbuf_len)) {
		error(1, EIO, "protection data do not match!");
	}

	printf("%s succeed!\n", __FUNCTION__);

	/*free(buf);*/
	/*free(buf2);*/
	/*free(prot_buf);*/
	/*free(prot_buf2);*/
	munmap(buf, DIX_ALIGNMENT);
	munmap(buf2, DIX_ALIGNMENT);
	munmap(prot_buf, DIX_ALIGNMENT);
	munmap(prot_buf2, DIX_ALIGNMENT);
}


static void test_dixread(int fd, off_t offset, size_t block)
{
	int i, ret;
	void *pb, *buf, *prot_buf;
	struct sd_dif_tuple *pp;

	size_t buf_len = block * PAGESIZE;
	size_t pbuf_len = block * 8 * sizeof(struct sd_dif_tuple);

	if (posix_memalign(&buf, PAGESIZE, buf_len) ||
	    posix_memalign(&prot_buf, PAGESIZE, pbuf_len)) {
		error(1, ENOMEM, "memalign");
	}

	ret = dixio_pread(fd, buf, prot_buf, buf_len, offset);
	if (ret < 0) {
		error(1, -ret, "dixio_pread");
	}

	dump_buffer(prot_buf, pbuf_len);

	free(buf);
	free(prot_buf);
}


static void test_dixwrite(int fd, off_t offset, size_t block)
{
	test_dixio(fd, offset, block);
}

const size_t FILESIZE = 10 * 1024 * 1024;
size_t block_ = 1;
int is_read_;
size_t total_bytes_ = 0;
int stop_ = 1;

static void stop_threads()
{
	assert(stop_ == 0);
	while (!__sync_bool_compare_and_swap(&stop_, 0, 1))
		;
}

static int is_stopped()
{
	return __sync_fetch_and_or(&stop_, 0) == 1;
}

static char* get_filename(int i, int is_dix)
{
	char *filename = malloc(64);

	if (is_dix) {
		snprintf(filename, 64, "/vfs-ganesha/tmp_dixio_%06d", i);
	} else {
		snprintf(filename, 64, "/vfs-ganesha/tmp_posix_%06d", i);
	}
	return filename;
}

static void* benchmark_dixio(void *arg)
{
	int fd;
	int ret;
	void *buf, *prot_buf;
	size_t offset;
	size_t buf_len = __sync_fetch_and_or(&block_, 0) * PAGESIZE;
	size_t pbuf_len = __sync_fetch_and_or(&block_, 0) * 8 *
		sizeof(struct sd_dif_tuple);
	const int is_read = __sync_fetch_and_or(&is_read_, 0);
	const int block = __sync_fetch_and_or(&block_, 0);
	char *path = get_filename((int)(unsigned long)arg, 1);

	fd = open(path, O_DIRECT | O_RDWR);
	if (!fd) {
		error(EIO, error, "cannot open dix file");
	}

	if (posix_memalign(&buf, PAGESIZE, buf_len) ||
	    posix_memalign(&prot_buf, PAGESIZE, pbuf_len)) {
		error(1, ENOMEM, "memalign");
	}

	while (!is_stopped()) {
		offset = (random() % (FILESIZE / PAGESIZE)) * PAGESIZE;
		if (is_read) {
			ret = dixio_pread(fd, buf, prot_buf, buf_len, offset);
		} else {
			ret = dixio_pwrite(fd, buf, prot_buf, buf_len, offset);
		}
		if (ret < 0) {
			error(1, -ret, "dixio failed");
		}
		__sync_fetch_and_add(&total_bytes_, buf_len);
	}

	free(path);
	close(fd);
	free(buf);
	free(prot_buf);
	return NULL;
}

static void* benchmark_posix(void *arg)
{
	int fd;
	int ret;
	void *buf;
	size_t offset;
	size_t buf_len = __sync_fetch_and_or(&block_, 0) * PAGESIZE;
	const int is_read = __sync_fetch_and_or(&is_read_, 0);
	char *path = get_filename((int)(unsigned long)arg, 0);

	fd = open(path, O_DIRECT | O_RDWR);
	if (!fd) {
		error(EIO, error, "cannot open regular file");
	}

	if (posix_memalign(&buf, PAGESIZE, buf_len)) {
		error(1, ENOMEM, "memalign");
	}

	while (!is_stopped()) {
		offset = (random() % (FILESIZE / PAGESIZE)) * PAGESIZE;
		if (is_read) {
			ret = pread(fd, buf, buf_len, offset);
		} else {
			ret = pwrite(fd, buf, buf_len, offset);
		}
		if (ret < 0) {
			error(1, -ret, "posix I/O failed");
		}
		__sync_fetch_and_add(&total_bytes_, buf_len);
	}

	free(path);
	close(fd);
	free(buf);
}

static size_t __benchmark(int is_read, int nthread, void* (*worker)(void *))
{
	pthread_t *threads;
	pthread_attr_t attr;
	int i, ret;

	// No thread should be running, so it is okay.
	total_bytes_ = 0;
	is_read_ = is_read;
	stop_ = 0;

	ret = pthread_attr_init(&attr);
	if (ret != 0) {
		error(1, errno, "cannot initialize thread attr");
	}

	threads = calloc(nthread, sizeof(pthread_t));
	if (!threads) {
		error(1, ENOMEM, "cannot allocate thread_t array");
	}
	for (i = 0; i < nthread; ++i) {
		ret = pthread_create(&threads[i], &attr,
				worker, (void*)(unsigned long)i);
		if (ret) {
			error(1, errno, "cannot create thread");
		}
	}

	// Run for 1 minute.
	sleep(60);
	stop_threads();

	for (i = 0; i < nthread; ++i) {
		ret = pthread_join(threads[i], NULL);
		if (ret) {
			error(1, errno, "cannot join thread");
		}
	}

	free(threads);
	return total_bytes_;
}


static int fill_a_dix_file(int fd, int filesize)
{
	int buf_len = 1024 * 1024;
	size_t pbuf_len = 256 * 8 * sizeof(struct sd_dif_tuple);
	void *buf, *prot_buf;
	size_t offset;
	int ret;

	if (posix_memalign(&buf, PAGESIZE, buf_len) ||
	    posix_memalign(&prot_buf, PAGESIZE, pbuf_len)) {
		error(1, ENOMEM, "out of memeory");
	}

	set_dixio_buffer(buf_len / PAGESIZE, buf, prot_buf, buf_len);

	for (offset = 0; offset < filesize; offset += buf_len) {
		ret = dixio_pwrite(fd, buf, prot_buf, buf_len, offset);
		if (ret < 0) {
			error(1, -ret, "failed to create dix file");
		}
	}

	free(buf);
	free(prot_buf);

	return 0;
}


static void create_files(int nfiles, int is_dix)
{
	char *filename;
	int i, fd, ret;

	for (i = 0; i < nfiles; ++i) {
		filename = get_filename(i, is_dix);
		fd = open(filename, O_DIRECT | O_RDWR | O_CREAT, 0644);
		if (fd < 0) {
			error(1, errno, "cannot create file");
		}
		if (is_dix) {
			ret = fill_a_dix_file(fd, FILESIZE);
			if (ret) {
				error(EIO, errno, "cannot create a dix file");
			}
		} else {
			ret = ftruncate(fd, FILESIZE);
			if (ret) {
				error(EIO, errno, "cannot create normal file");
			}
		}
		close(fd);
		free(filename);
	}

	fprintf(stderr, "finished creating %d files\n", nfiles);
}


static void benchmark(size_t block)
{
	const int MAX_NTHREAD = 16;
	int nthread;

	block_ = block;

	// pre-create files
	create_files(MAX_NTHREAD, 1);
	create_files(MAX_NTHREAD, 0);

	printf("R/W\t#threads\tPOSIX(mb)\tDIXIO(mb)\n");
	for (nthread = 1; nthread <= MAX_NTHREAD; nthread *= 2) {
		printf("R\t%d\t%llu\t%llu\n",
		       nthread,
		       __benchmark(1, nthread, benchmark_posix) / 1048576,
		       __benchmark(1, nthread, benchmark_dixio) / 1048576);
		printf("W\t%d\t%llu\t%llu\n\n",
		       nthread,
		       __benchmark(0, nthread, benchmark_posix) / 1048576,
		       __benchmark(0, nthread, benchmark_dixio) / 1048576);
	}
}

const char *option_str = " -h	    print help\n"
	" -n	create a new file\n"
	" -o offset	file offset\n"
	" -r ref_tag	reference tag value\n"
	" -b byte	magic byte value\n"
	" -s iosize	I/O size in unit of 4KB pages\n"
	" -p [brwda]	operation code\n"
	"    b: benchmark; r: read;  w: write; "
	"    d: normal direct io;  x: verify dix\n";

static void print_help(const char *progname)
{
	printf("Usage: %s [OPTS] filename\n", progname);
	printf(option_str);
}


int main(int argc, char *argv[])
{
	int fd;
	int opt;
	struct stat st;
	size_t fsize;
	off_t offset = 0;
	char op = 'x';
	int new = 0;
	int iosize_page = 1;  // I/O size in unit of pages

	while ((opt = getopt(argc, argv, "no:t:p:r:b:s:h")) != -1) {
		switch(opt) {
		case 'o':
			offset = strtoull(optarg, NULL, 0);
			break;
		case 'n':
			new = O_CREAT | O_EXCL;
			break;
		case 'p':
			op = optarg[0];
			break;
		case 'r':
			ref_tag = strtoul(optarg, NULL, 16);
			break;
		case 'b':
			magic_byte = (uint8_t)strtoul(optarg, NULL, 16);
			break;
		case 's':
			iosize_page = strtoull(optarg, NULL, 0);
			break;
		case 'h':
			print_help(argv[0]);
			return 0;
		default:
			print_help(argv[0]);
			return 1;
		}
	}

	if (optind >= argc) {
		print_help(argv[0]);
		return 1;
	}

	if (op != 'b') {
		fd = open(argv[optind], O_DIRECT | O_SYNC | O_RDWR | new, 0644);
		if (fd < 0) {
			error(1, errno, argv[optind]);
		}
		fstat(fd, &st);
		fsize = st.st_size;
		fprintf(stderr, "file size of %s is %zu\n", argv[optind], fsize);
	}

	switch (op) {
	case 'b':
		benchmark(iosize_page);
		break;
	case 'd':
		test_normal_dio(fd, offset, iosize_page);
		break;
	case 'x':
		test_dixio(fd, offset, iosize_page);
		break;
	case 'r':
		test_dixread(fd, offset, iosize_page);
		break;
	case 'w':
		test_dixwrite(fd, offset, iosize_page);
		break;
	case 'c': // custom
		test_normal_dio(fd, 0, iosize_page);
		ftruncate(fd, 8192);
		test_dixwrite(fd, 8192, iosize_page);
		test_normal_dio(fd, 0, iosize_page);
		test_dixread(fd, 8192, iosize_page);
		break;
	default:
		error(1, EINVAL, "unknown opcode: %s", optarg);
	}

	close(fd);

	return 0;
}
