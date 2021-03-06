/*
 * Userspace DIX API test program
 * Licensed under GPLv2. Copyright 2014 Oracle.
 *
 * http://marc.info/?l=linux-aio&m=139567817328633&w=2
 *
 * http://marc.info/?a=135050915400007&r=1&w=2
 *
 * XXX: We don't query the kernel for this information like we should!
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <libaio.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/uio.h>
#include <errno.h>
#include <stdlib.h>
#include <stdint.h>
#include <arpa/inet.h>
#include <sys/ioctl.h>
#include <linux/fs.h>
#include <sys/syscall.h>

#define GENERATE_GUARD	(1)
#define GENERATE_REF	(2)
#define GENERATE_APP	(4)
#define GENERATE_ALL	(7)

#define NR_IOS		(1)
#define NR_IOVS		(2)
#define NR_IOCB_EXTS	(1)

/* Stuff that should go in libaio.h */
#define IO_EXT_INVALID	(0)
#define	IO_EXT_PI	(1)	/* protection info attached */

#define IOCB_FLAG_EXTENSIONS	(1 << 1)

#define __FIOEXT	040000000

struct io_extension {
	__u64 ie_size;
	__u64 ie_has;

	/* PI stuff */
	__u64 ie_pi_buf;
	__u32 ie_pi_buflen;
	__u32 ie_pi_ret;
	__u32 ie_pi_flags;
};

static void io_prep_extensions(struct iocb *iocb, struct io_extension *ext,
			       unsigned int nr)
{
	iocb->u.c.flags |= IOCB_FLAG_EXTENSIONS;
	iocb->u.c.__pad3 = (long long)ext;
}

static void io_prep_extension(struct io_extension *ext)
{
	memset(ext, 0, sizeof(struct io_extension));
	ext->ie_size = sizeof(*ext);
}

static void io_prep_extension_pi(struct io_extension *ext, void *buf,
				 unsigned int buflen, unsigned int flags)
{
	ext->ie_has |= IO_EXT_PI;
	ext->ie_pi_buf = (__u64)buf;
	ext->ie_pi_buflen = buflen;
	ext->ie_pi_flags = flags;
}
/* End stuff for libaio.h */

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

struct sd_dif_tuple {
       uint16_t guard_tag;	/* Checksum */
       uint16_t app_tag;		/* Opaque storage */
       uint32_t ref_tag;		/* Target LBA or indirect LBA */
};

static void stamp_pi_buffer(struct sd_dif_tuple *t, uint16_t csum,
			    uint16_t tag, uint32_t sector)
{
	t->guard_tag = htons(csum);
	t->app_tag = htons(tag);
	t->ref_tag = htonl(sector);
}

static void print_help(const char *progname)
{
	printf("Usage: %s [OPTS] fname\n", progname);
	printf("-a	Use this application tag\n");
	printf("-b	Write this byte \n");
	printf("-d	Do not use O_DIRECT\n");
	printf("-o	Read/write offset in sectors\n");
	printf("-p	Protection type {a|g|r}+\n");
	printf("-q	Don't use AIO.\n");
	printf("-r	Use DIX to read\n");
	printf("-s	Allocate buffer of this many sectors\n");
	printf("-w	Use DIX to write\n");
	printf("-z	Do not use O_SYNC\n");
}

int main(int argc, char *argv[])
{
	struct sd_dif_tuple *pi;
	int page_size = sysconf(_SC_PAGESIZE);
	io_context_t ioctx;
	struct io_event events[NR_IOS];
	struct iocb iocbs[NR_IOS];
	struct iocb *iocbps[NR_IOS];
	void *buf, *buf2;
	unsigned char *p;
	void *mbuf, *mbuf2;
	int ret, fd, i;
	struct iovec iov[NR_IOVS];
	struct io_extension iocb_ext[NR_IOCB_EXTS];
	int opt;
	int dix_read = 0, dix_write = 0;
	unsigned int SECTOR_SIZE = 0;
	unsigned long long num_sectors = 8, BUF_SIZE;
	unsigned long long sector_offset = 256, BDEV_OFFSET;
	unsigned int APP_TAG = 0xEF53;
	unsigned int the_byte = 0x55;
	size_t pi_buflen;
	int o_direct = O_DIRECT;
	int o_sync = O_SYNC;
	int use_aio = 1;
	uint32_t pi_flags = 0;

	while ((opt = getopt(argc, argv, "b:zdrws:o:a:p:q")) != -1) {
		switch (opt) {
		case 'a':
			APP_TAG = strtoul(optarg, NULL, 0);
			break;
		case 'b':
			the_byte = strtoul(optarg, NULL, 0) & 0xFF;
			break;
		case 'd':
			o_direct = 0;
			break;
		case 'o':
			sector_offset = strtoull(optarg, NULL, 0);
			break;
		case 'p':
			for (i = 0; i < strlen(optarg); i++)
				switch (optarg[i]) {
				case 'a':
					pi_flags |= GENERATE_APP;
					break;
				case 'g':
					pi_flags |= GENERATE_GUARD;
					break;
				case 'r':
					pi_flags |= GENERATE_REF;
					break;
				default:
					print_help(argv[0]);
					return 2;
				}
			break;
		case 'r':
			dix_read = 1;
			break;
		case 's':
			num_sectors = strtoull(optarg, NULL, 0);
			break;
		case 'w':
			dix_write = 1;
			break;
		case 'z':
			o_sync = 0;
			break;
		case 'q':
			use_aio = 0;
			break;
		default:
			print_help(argv[0]);
			return 0;
		}
	}

	if (optind >= argc) {
		print_help(argv[0]);
		return 0;
	}

	if (dix_read)
		fprintf(stderr, "Using DIX read.\n");
	if (dix_write)
		fprintf(stderr, "Using DIX write.\n");

	fd = open(argv[optind], o_direct | o_sync | O_RDWR);
	if (fd < 0) {
		perror(argv[optind]);
		return 1;
	}

	/* For now, don't let non-block devices in */
	SECTOR_SIZE = 512;
	if (ioctl(fd, BLKSSZGET, &SECTOR_SIZE)) {
		perror(argv[optind]);
	}

	pi_buflen = num_sectors * sizeof(struct sd_dif_tuple);

	BUF_SIZE = num_sectors * SECTOR_SIZE;
	BDEV_OFFSET = sector_offset * SECTOR_SIZE;
	fprintf(stderr, "sector=%d num_sectors=%llu pi_len=%zu pi_flag=0x%x\n",
		SECTOR_SIZE, num_sectors, pi_buflen, pi_flags);
	if (posix_memalign(&buf, page_size, BUF_SIZE) ||
	    posix_memalign(&buf2, page_size, BUF_SIZE) ||
	    posix_memalign(&mbuf, page_size, pi_buflen) ||
	    posix_memalign(&mbuf2, page_size, pi_buflen)) {
		perror("memalign");
		return 1;
	}

	if (io_queue_init(2, &ioctx)) {
		perror("io_queue_init");
		return 1;
	}

	/* Write everything out */
	memset(mbuf, 0, pi_buflen);
	memset(buf, the_byte, BUF_SIZE);
	for (p = buf, i = 0, pi = mbuf;
	     i < num_sectors;
	     i++, pi++, p += SECTOR_SIZE)
		stamp_pi_buffer(pi,
				pi_flags & GENERATE_GUARD ? 0 : crc_t10dif(0, p, SECTOR_SIZE),
				pi_flags & GENERATE_APP ? 0 : APP_TAG,
				pi_flags & GENERATE_REF ? 0 : (BDEV_OFFSET / SECTOR_SIZE) + i);
	io_prep_extension(iocb_ext);
	io_prep_extension_pi(iocb_ext, mbuf, pi_buflen, pi_flags);
	iov[0].iov_base = buf;
	iov[0].iov_len = page_size;
	iov[1].iov_base = buf + page_size;
	iov[1].iov_len = BUF_SIZE - page_size;
	iocbps[0] = iocbs;
	io_prep_pwritev(iocbs, fd, iov, NR_IOVS, BDEV_OFFSET);
	if (dix_write)
		io_prep_extensions(iocbs, iocb_ext, NR_IOCB_EXTS);

	fprintf(stderr, "Writing %llu bytes\n", BUF_SIZE);
	if (use_aio) {
		ret = io_submit(ioctx, 1, iocbps);
		if (ret < 0) {
			errno = -ret;
			perror("io_submit");
			return 1;
		}

		ret = io_getevents(ioctx, 1, 1, events, NULL);
		if (ret < 0) {
			errno = -ret;
			perror("io_getevents");
			return 1;
		}

		if ((signed)events[0].res < 0) {
			errno = -((signed)events[0].res);
			perror("io_pwritev");
			return 1;
		}
	} else {
		ret = fcntl(fd, F_GETFL);
		ret = fcntl(fd, F_SETFL, ret | __FIOEXT);
		if (ret) {
			perror("fcntl set");
			return 1;
		}
		fprintf(stderr, "flags=0x%x iocb=%p\n", fcntl(fd, F_GETFL),
			iocb_ext);
		/* ret = pwritev(fd, iov, NR_IOVS, BDEV_OFFSET); */
		ret = syscall(SYS_pwritev, fd, iov, NR_IOVS, BDEV_OFFSET,
			      dix_write ? iocb_ext : NULL);
		if (ret < 0) {
			errno = -ret;
			perror("pwritev");
			return 1;
		}
	}
	fprintf(stderr, "Wrote %lu bytes\n", events[0].res);

	/* Read everything back in */
	memset(buf2, 0x00, BUF_SIZE);
	memset(mbuf2, 0x00, pi_buflen);
	io_prep_extension(iocb_ext);
	io_prep_extension_pi(iocb_ext, mbuf2, pi_buflen, 0);
	iov[0].iov_base = buf2;
	iov[0].iov_len = page_size;
	iov[1].iov_base = buf2 + page_size;
	iov[1].iov_len = BUF_SIZE - page_size;
	iocbps[0] = iocbs;
	io_prep_preadv(iocbs, fd, iov, NR_IOVS, BDEV_OFFSET);
	if (dix_read)
		io_prep_extensions(iocbs, iocb_ext, NR_IOCB_EXTS);

	fprintf(stderr, "Reading %llu bytes\n", BUF_SIZE);
	if (use_aio) {
		ret = io_submit(ioctx, 1, iocbps);
		if (ret < 0) {
			errno = -ret;
			perror("io_submit");
			return 1;
		}

		ret = io_getevents(ioctx, 1, 1, events, NULL);
		if (ret < 0) {
			errno = -ret;
			perror("io_getevents");
			return 1;
		}

		if ((signed)events[0].res < 0) {
			errno = -((signed)events[0].res);
			perror("io_preadv");
			return 1;
		}
	} else {
		ret = fcntl(fd, F_GETFL);
		ret = fcntl(fd, F_SETFL, ret | __FIOEXT);
		if (ret) {
			perror("fcntl set");
			return 1;
		}
		fprintf(stderr, "flags=0x%x\n", fcntl(fd, F_GETFL));
		/* ret = preadv(fd, iov, NR_IOVS, BDEV_OFFSET, iocb_ext); */
		ret = syscall(SYS_preadv, fd, iov, NR_IOVS, BDEV_OFFSET,
			      dix_read ? iocb_ext : NULL);
		if (ret < 0) {
			errno = -ret;
			perror("preadv");
			return 1;
		}
	}
	fprintf(stderr, "Read %lu bytes\n", events[0].res);

	/* Compare */
	ret = 0;
	if (memcmp(buf, buf2, BUF_SIZE)) {
		ret = 2;
		fprintf(stderr, "Buffers do not match!\n");
	}
	if (dix_read && dix_write) {
		fprintf(stderr, "write pi\n");
		dump_buffer(mbuf, pi_buflen);
		fprintf(stderr, "read pi\n");
		dump_buffer(mbuf2, pi_buflen);
		if(memcmp(mbuf, mbuf2, pi_buflen)) {
			ret = 2;
			fprintf(stderr, "DIX buffers do not match!\n");
		}
	} else
		fprintf(stderr, "Need to pass -rw to compare DIX buffers!\n");

	if (io_queue_release(ioctx)) {
		perror("io_queue_release");
		return 1;
	}

	close(fd);
	if (!ret)
		fprintf(stderr, "Success.\n");

	return ret;
}
