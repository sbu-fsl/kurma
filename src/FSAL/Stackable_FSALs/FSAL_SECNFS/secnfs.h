/*
 * Copyright (c) 2013-2018 Ming Chen
 * Copyright (c) 2016-2016 Praveen Kumar Morampudi
 * Copyright (c) 2016-2016 Harshkumar Patel
 * Copyright (c) 2017-2017 Rushabh Shah
 * Copyright (c) 2013-2014 Arun Olappamanna Vasudevan
 * Copyright (c) 2013-2014 Kelong Wang
 * Copyright (c) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
/**
 * vim:expandtab:shiftwidth=8:tabstop=8:
 *
 * @file  secnfs.h
 * @brief Encrypt and decrypt data
 */

#ifndef H_SECNFS
#define H_SECNFS

#include <stdint.h>
#include <stdlib.h>
#include <assert.h>
#include <string.h>
#include <sys/param.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SECNFS_KEY_LENGTH 16

// TODO allow keyfile to be larger
#define FILE_HEADER_SIZE 4096
#define FILE_META_SIZE 1024

#define PI_SECNFS_DIF_SIZE 48 /* 4096 / 512 * (8-2) */
#define VERSION_SIZE 8
#define TAG_SIZE 16
#define DIF_UNUSED_SIZE PI_SECNFS_DIF_SIZE - VERSION_SIZE - TAG_SIZE
/* 48 - 8 - 16 = 24 */

#define FILL_ZERO_BUFFER_SIZE 1048576

typedef struct { uint8_t bytes[SECNFS_KEY_LENGTH]; } secnfs_key_t;

/**
 * Status codes of SECNFS.
 */
typedef enum {
        SECNFS_OKAY = 0,
        SECNFS_CRYPTO_ERROR = 1,
        SECNFS_WRONG_CONFIG,
        SECNFS_KEYFILE_ERROR,
        SECNFS_NOT_ALIGNED,
        SECNFS_NOT_VERIFIED,
        SECNFS_INVALID,
        SECNFS_READ_UPDATE_FAIL,
        SECNFS_FILL_ZERO_FAIL,
} secnfs_s;


/**
 * SECNFS context.
 */
typedef struct secnfs_info {
        uint32_t context_size;          /*!< size of context */
        void *context;                  /*!< context data */
        char *context_cache_file;
        char *secnfs_name;       /*!< secnfs unique name */
        char *plist_file;        /*!< list of secnfs proxies */
        bool create_if_no_context;
        bool file_encryption;
} secnfs_info_t;


/**
 * SECNFS DIF for each protection interval.
 * The size is PI_SECNFS_DIF_SIZE.
 */
typedef struct secnfs_dif {
        uint64_t version;               /* Additional Authenticated Data */
        uint8_t tag[TAG_SIZE];          /* Authentication Tag (checksum) */
        uint8_t unused[DIF_UNUSED_SIZE];
} secnfs_dif_t;


/*
 * @brief Increase the counter.
 */
secnfs_key_t *incr_ctr(secnfs_key_t *iv, unsigned size, int incr);


/**
 * @brief Generate a key and an IV from a crypto PRNG.
 */
void generate_key_and_iv(secnfs_key_t *key, secnfs_key_t *iv);


/**
 * @brief Encrypt buffer contents
 *
 * @param[in]   key     Encryption key
 * @param[in]   iv      Initialization vector
 * @param[in]   offset  Offset of data in file
 * @param[in]   size    Size of buffer, also the amount of data to encrypt
 * @param[in]   plain   Buffer containing plaintext
 * @param[out]  buffer  Output buffer for ciphertext, can be the same as plain
 *
 * @return 0 on success.
 */
secnfs_s secnfs_encrypt(secnfs_key_t key,
                        secnfs_key_t iv,
                        uint64_t offset,
                        uint64_t size,
                        void *plain,
                        void *buffer);


/**
 * @brief Decrypt buffer contents
 *
 * @param[in]   key      Decryption key
 * @param[in]   iv       Initialization vector
 * @param[in]   offset   Offset of data in file
 * @param[in]   size     Size of buffer, also the amount of data to decrypt
 * @param[in]   cipher   Buffer containing ciphertext
 * @param[out]  buffer   Output buffer for decrypted plaintext
 *
 * @return 0 on success.
 */
secnfs_s secnfs_decrypt(secnfs_key_t key,
                        secnfs_key_t iv,
                        uint64_t offset,
                        uint64_t size,
                        void *cipher,
                        void *buffer);


/*
 * @brief Perform authenticated encryption (GCM)
 * See http://www.cryptopp.com/wiki/GCM
 *
 * @param[in]   key     Decryption key
 * @param[in]   iv      Initialization vector
 * @param[in]   offset  Offset of data in file
 * @param[in]   size    Size of buffer, also the amount of data to decrypt
 * @param[in]   plain   Buffer containing plaintext
 * @param[in]   auth_size size of authentication payload
 * @param[in]   auth_msg  authentication payload
 * @param[out]  buffer  encrypted data, unused in auth_only mode
 * @param[out]  tag     TAG, a.k.a. MAC
 * @param[in]   auth_only do not encrypt, do GMAC on plain + auth_msg
 *
 * REQUIRES:
 *  1. buffer is large enough for the data and the tag
 *  2. offset and size is aligned to AES::BLOCKSIZE (128bit)
 *
 */
secnfs_s secnfs_auth_encrypt(secnfs_key_t key, secnfs_key_t iv,
                             uint64_t offset, uint64_t size, const void *plain,
                             uint64_t auth_size, const void *auth_msg,
                             void *buffer, void *tag, bool auth_only);

/*
 * @brief Perform verfication and decryption (GCM)
 * The inverse operation of secnfs_auth_encrypt().
 *
 * @param[in]   key     Decryption key
 * @param[in]   iv      Initialization vector
 * @param[in]   offset  Offset of data in file
 * @param[in]   size    Size of buffer, also the amount of data to decrypt
 * @param[in]   cipher  cipthertext to decrypt (or plaintext in auth_only mode)
 * @param[in]   auth_size size of authentication payload
 * @param[in]   auth_msg  authentication payload
 * @param[in]   tag     TAG, a.k.a. MAC generated by secnfs_auth_encrypt()
 * @param[out]  buffer  decrypted data, unused in auth_only mode
 * @param[in]   auth_only do not decrypt, verify 'cipher' + auth_msg against tag
 */
secnfs_s secnfs_verify_decrypt(secnfs_key_t key, secnfs_key_t iv,
                               uint64_t offset, uint64_t size,
                               const void *cipher, uint64_t auth_size,
                               const void *auth_msg, const void *tag,
                               void *buffer, bool auth_only);

/*
 * @brief Generate message authentication code with VMAC
 * VMAC is a message authentication code designed for high performance on 64-bit
 * machines. VMAC uses a universal hash. See http://www.cryptopp.com/wiki/VMAC
 *
 * @param[in]   key     Decryption key
 * @param[in]   iv      Initialization vector
 * @param[in]   offset  Offset of data in file
 * @param[in]   size    Size of buffer, also the amount of data to decrypt
 * @param[in]   plain   Buffer containing plaintext
 * @param[out]  tag     TAG, a.k.a. MAC
 *
 * REQUIRES:
 *  1. offset and size is aligned to AES::BLOCKSIZE (128bit)
 *
 */
secnfs_s secnfs_mac_generate(secnfs_key_t key, secnfs_key_t iv, uint64_t offset,
			     uint64_t size, const void *plain, void *tag);

/*
 * @brief Perform verfication of VMAC
 *
 * @param[in]   key     Decryption key
 * @param[in]   iv      Initialization vector
 * @param[in]   offset  Offset of data in file
 * @param[in]   size    Size of buffer, also the amount of data to decrypt
 * @param[in]   plain   Buffer containing plaintext
 * @param[in]  tag     TAG, a.k.a. MAC
 *
 */
secnfs_s secnfs_mac_verify(secnfs_key_t key, secnfs_key_t iv, uint64_t offset,
			   uint64_t size, const void *plain, const void *tag);

secnfs_s secnfs_init_info(secnfs_info_t *info);


/**
 * @brief Create SECNFS context.
 *
 * @param[out] context  SECNFS context.
 *
 * The caller should use secnfs_destroy_context to free the returned context.
 *
 * @return SECNFS_OKAY on success.
 */
secnfs_s secnfs_create_context(secnfs_info_t *info);


/**
 * @brief Destroy SECNFS context.
 *
 * @param[in]  context   SECNFS context.
 */
void secnfs_destroy_context(secnfs_info_t *info);


/**
 * @brief Create new file header.
 *
 * @param[in]   context         SECNFS Context
 * @param[in]   fek             File Encryption Key
 * @param[in]   iv              Initialization vector
 * @param[in]   filesize        effective file size
 * @param[in]   encrypted       whether file content is encrypted
 * @param[in]   holes           blockmap pointer to file holes
 * @param[in]   modify_time     timestamp of the last file data modification
 * @param[in]   change_time     timestamp of the last file attributes change
 * @param[in]   change          change id of the file; see RFC5661 5.8.1.4
 * @param[out]  buf             header data
 * @param[out]  len             Length of header data
 * @param[in/out]  kf_cache     keyfile cache pointer
 *
 * The caller is the owner of the returned buf and should free them properly.
 * If kf_cache is presented, header will reuse the cached keyfile.
 * If not, kf_cache will point to the new keyfile.
 *
 * @return SECNFS_OKAY on success.
 */
secnfs_s secnfs_create_header(secnfs_info_t *info,
                              secnfs_key_t *fek,
                              secnfs_key_t *iv,
                              uint64_t filesize,
                              bool encrypted,
                              void *holes,
                              const struct timespec *modify_time,
                              const struct timespec *change_time,
                              uint64_t change,
                              void **buf,
                              uint32_t *len,
                              void **kf_cache);


/**
 * Read and decrypt file encryption key, meta data from file header.
 *
 * @param[in]   info        secnfs info, containing the context
 * @param[in]   buf         buffer holding the keyfile data
 * @param[in]   buf_size    size of the buffer
 * @param[out]  fek         the resultant file encryption key
 * @param[out]  iv          iv used for file data encryption/decryption
 * @param[out]  filesize    effective file size
 * @param[out]  encrypted   whether file content is encrypted
 * @param[out]  holes       blockmap pointer to file holes
 * @param[out]  modify_time timestamp of the last file data modification
 * @param[out]  change_time timestamp of the last file attributes change
 * @param[out]  change      change id of the file; see RFC5661 5.8.1.4
 * @param[out]  len         real length of the header
 * @param[out]  kf_cache    keyfile cache pointer
 *
 * Keyfile will be cached in kf_cache.
 */
secnfs_s secnfs_read_header(secnfs_info_t *info,
                            void *buf,
                            uint32_t buf_size,
                            secnfs_key_t *fek,
                            secnfs_key_t *iv,
                            uint64_t *filesize,
                            bool *encrypted,
                            void *holes,
                            struct timespec *modify_time,
                            struct timespec *change_time,
                            uint64_t *change,
                            uint32_t *len,
                            void **kf_cache);

/* destruct keyfile cache */
void secnfs_release_keyfile_cache(void **kf_cache);

/* blockmap init / release */
void *secnfs_alloc_blockmap();
void secnfs_release_blockmap(void **p);

/* range lock */
uint64_t secnfs_range_try_lock(void *p, uint64_t offset, uint64_t length);
void secnfs_range_unlock(void *p, uint64_t offset, uint64_t length);

/* file hole */
void secnfs_hole_add(void *p, uint64_t offset, uint64_t length);
size_t secnfs_hole_remove(void *p, uint64_t offset, uint64_t length);
void secnfs_hole_find_next(void *p, uint64_t offset,
                           uint64_t *nxt_offset, uint64_t *nxt_length);
bool secnfs_offset_in_hole(void *p, uint64_t offset);
bool secnfs_range_has_hole(void *p, uint64_t offset, uint64_t size);

/**
 * Serialize uint64_t to "little-endian" byte array
 */
static inline void uint64_to_bytes(uint8_t *buf, uint64_t n)
{
        int i;
        for (i = 0; i < 8; ++i)
                buf[i] = (n >> i * 8) & 0xff;
}

/**
 * Deserialize from byte array to uint64_t
 */
static inline void uint64_from_bytes(uint8_t *buf, uint64_t *n)
{
        int i;
        *n = 0;
        for (i = 7; i >= 0; --i)
                *n = (*n << 8) | buf[i];
}

/**
 * Serialize secnfs_dif_t to a contiguous buf
 *
 * @param[in]   dif     sencfs_dif_t
 * @param[out]  buf     buffer whose size is at least PI_SECNFS_DIF_SIZE
 */
static inline void secnfs_dif_to_buf(struct secnfs_dif *dif,
                                     uint8_t *buf)
{
        uint64_to_bytes(buf, dif->version);
        memcpy(buf + VERSION_SIZE, dif->tag, TAG_SIZE);
        memcpy(buf + VERSION_SIZE + TAG_SIZE, dif->unused, DIF_UNUSED_SIZE);
}

/**
 * Deserialize from buf to secnfs_dif_t
 *
 * @param[out]  dif     sencfs_dif_t
 * @param[in]   buf     buffer containing serilized bytes of secnfs_dif
 */
static inline void secnfs_dif_from_buf(struct secnfs_dif *dif,
                                       uint8_t *buf)
{
        uint64_from_bytes(buf, &dif->version);
        memcpy(dif->tag, buf + VERSION_SIZE, TAG_SIZE);
        memcpy(dif->unused, buf + VERSION_SIZE + TAG_SIZE, DIF_UNUSED_SIZE);
}

#ifdef __cplusplus
}
#endif

#endif
