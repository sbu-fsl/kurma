#include <unistd.h>
#include <libaio.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
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

static void test_dixwrite(int fd, int seed, size_t block, size_t filesize,
			  size_t count)
{
	int i, ret;
	void *pb, *buf, *prot_buf;
	struct sd_dif_tuple *pp;
	off_t offset, max_offset_block;

	const size_t buf_len = block * PAGESIZE;
	const size_t pbuf_len = block * 8 * sizeof(struct sd_dif_tuple);

	if (posix_memalign(&buf, PAGESIZE, buf_len) ||
	    posix_memalign(&prot_buf, PAGESIZE, pbuf_len)) {
		error(1, ENOMEM, "memalign");
	}

	memset(buf, magic_byte, buf_len);
	for (i = 0, pb = buf, pp = prot_buf;
	     i < block * 8; ++i, pb += SEC, ++pp) {
		stamp_pi_buffer(pp, pb, pi_flags, 0, ref_tag);
	}

	srand(seed);
	max_offset_block = (filesize >> 12) - block;

	for (i = 1; i <= count; ++i) {
		offset = rand() * max_offset_block / RAND_MAX;
		ret = dixio_pwrite(fd, buf, prot_buf, buf_len, (offset << 12));
		if (ret < 0) {
			error(1, -ret, "dixio_pread");
		}
		if (i % 500 == 0) {
			fprintf(stderr, "%d operations done\n", i);
		}
	}

	fprintf(stderr, "all done\n");

	free(buf);
	free(prot_buf);
}


static void create_file(int fd, size_t block, size_t filesize) {
	int i = 0, ret;
	void *pb, *buf, *prot_buf;
	struct sd_dif_tuple *pp;
	off_t offset, max_offset_block;

	const size_t buf_len = block * PAGESIZE;
	const size_t pbuf_len = block * 8 * sizeof(struct sd_dif_tuple);

	if (posix_memalign(&buf, PAGESIZE, buf_len) ||
	    posix_memalign(&prot_buf, PAGESIZE, pbuf_len)) {
		error(1, ENOMEM, "memalign");
	}

	memset(buf, magic_byte, buf_len);
	for (i = 0, pb = buf, pp = prot_buf;
	     i < block * 8; ++i, pb += SEC, ++pp) {
		stamp_pi_buffer(pp, pb, pi_flags, 0, ref_tag);
	}

	for (offset = 0; offset < filesize; offset += buf_len) {
		ret = dixio_pwrite(fd, buf, prot_buf, buf_len, offset);
		if (ret < 0) {
			error(1, -ret, "dixio_pread");
		}
		if (++i % 500 == 0) {
			fprintf(stderr, "%d operations done", i);
		}
	}

	free(buf);
	free(prot_buf);
}


const char *option_str = " -h	    print help\n"
	" -n filesize 	create a new file\n"
	" -c #op	the operation count to be performed\n"
	" -r ref_tag	reference tag value\n"
	" -b byte	magic byte value\n"
	" -i iosize	I/O size in unit of 4KB pages\n"
	" -s seed	random seed\n";


static void print_help(const char *progname)
{
	printf("Usage: %s [OPTS] filename\n", progname);
	printf(option_str);
}


size_t get_bytesize(const char *size) {
	size_t bytesize = strtoull(size, NULL, 0);
	char unit = size[strlen(size) - 1];
	if (unit == 'k' || unit == 'K') {
		bytesize <<= 10;
	} else if (unit == 'm' || unit == 'M') {
		bytesize <<= 20;
	}
	if (unit == 'g' || unit == 'G') {
		bytesize <<= 30;
	}
	return bytesize;
}


int main(int argc, char *argv[])
{
	int fd;
	int opt;
	struct stat st;
	size_t fsize = 0;
	int oflags = 0;
	int iosize_page = 1;  // I/O size in unit of pages
	int seed = 8887;
	int count = 1000;

	while ((opt = getopt(argc, argv, "n:c:r:b:i:s:h")) != -1) {
		switch(opt) {
		case 'c':
			count = strtoull(optarg, NULL, 0);
			break;
		case 'n':
			oflags = O_CREAT | O_EXCL;
			fsize = get_bytesize(optarg);
			break;
		case 'r':
			ref_tag = strtoul(optarg, NULL, 16);
			break;
		case 'b':
			magic_byte = (uint8_t)strtoul(optarg, NULL, 16);
			break;
		case 'i':
			iosize_page = strtoull(optarg, NULL, 0);
			break;
		case 's':
			seed = strtoul(optarg, NULL, 0);
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

	fd = open(argv[optind], O_DIRECT | O_SYNC | O_RDWR | oflags, 0644);
	if (fd < 0) {
		error(1, errno, argv[optind]);
	}
	if (!fsize) {
		fstat(fd, &st);
		fsize = st.st_size;
	}

	fprintf(stderr, "file size of %s is %zu\n", argv[optind], fsize);

	if (oflags) {
		create_file(fd, iosize_page, fsize);
	}

	test_dixwrite(fd, seed, iosize_page, fsize, count);

	close(fd);

	return 0;
}
