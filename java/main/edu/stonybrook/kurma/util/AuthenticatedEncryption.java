/*
 * Copyright (C) 2013-2018 Ming Chen
 * Copyright (C) 2016-2016 Praveen Kumar Morampudi
 * Copyright (C) 2016-2016 Harshkumar Patel
 * Copyright (C) 2017-2017 Rushabh Shah
 * Copyright (C) 2013-2018 Erez Zadok
 * Copyright (c) 2013-2018 Stony Brook University
 * Copyright (c) 2013-2018 The Research Foundation for SUNY
 * This file is released under the GPL.
 */
package edu.stonybrook.kurma.util;

import java.nio.ByteBuffer;
import java.util.Map.Entry;

import com.google.common.io.ByteSource;

/**
 * Interface that provides both encryption and authentication, such as GCM and CCM mode, or simple
 * encryption-then-authenticate schemes. It also supports additional data (AD) as GCM does.
 *
 * The lengths of messages are indicated by array.length if the messages are byte[], or
 * buffer.remaining() if the messages are ByteBuffer. Any implementations need to pad the ciphertext
 * need to make sure the lengths of input/output are correct.
 *
 * @author mchen
 *
 */
public interface AuthenticatedEncryption {
  /**
   * Return the length of MAC.
   *
   * @return the number of bytes of the generated MAC.
   */
  public int getMacLength();

  /**
   * Return the length of the output message (including MAC) given the length of the input. If there
   * is additional data, simple use the sum of plaintext's and AD's length.
   *
   * @param inLength the length of plaintext to be authenticate-encrypted.
   * @return the length of the encrypted message (including AD if present)
   */
  public int getEncryptOutputLength(int inLength);

  /**
   * Return the length of the output message (without MAC) given the length of the input.
   *
   * @param inLength the length of ciphertext to be authenticate-decrypted.
   * @return the length of the recovered message (including AD if present)
   */
  public int getDecryptOutputLength(int inLength);

  /**
   * Encrypt and authenticate data. MAC will be a part of (e.g., tail) the returned ciphertext.
   *
   * <p>
   * <b>NOTE</b>: Do <b>NOT</b> repeat iv if the underlying encryption is counter mode.
   * </p>
   *
   * @param plain data to be encrypted and authenticated
   * @param iv initialization vector
   * @return the ciphertext (with MAC embedded), or null in case of errors.
   */
  public byte[] authenticatedEncrypt(byte[] plain, byte[] iv) throws Exception;

  /**
   * Encrypt and authenticate data. MAC will be a part of (e.g., tail) the returned ciphertext.
   *
   * <p>
   * <b>NOTE</b>: Do <b>NOT</b> repeat iv if the underlying encryption is counter mode.
   * </p>
   *
   * @param plain data to be encrypted and authenticated
   * @param ad additional data to be encrypted and authenticated; it is similar to GCM's additional
   *        data but here it is also encrypted (in addition to be authenticated). It can be null or
   *        an empty ByteBuffer if there is no additional data.
   * @param iv initialization vector; note that different implementations may have different
   *        requirements on how to choose iv.
   * @param out ciphertext with MAC embedded; out.hasArray() should be true
   * @return whether the operation is successful
   */
  public boolean authenticatedEncrypt(ByteBuffer plain, ByteBuffer ad, byte[] iv, ByteBuffer out)
      throws Exception;

  /**
   * Encrypt and authenticate data.
   * 
   * @param plain
   * @param ad
   * @param iv
   * @return A ByteBuffer that contains the encrypted "plaintext" and "ad".
   * @throws Exception
   */
  public ByteSource authenticatedEncrypt(ByteSource plain, ByteSource ad, byte[] iv)
      throws Exception;

  /**
   * Decrypt and very data.
   * 
   * @param cipher
   * @param iv
   * @return A pair of ByteSource for the "plaintext" and "ad".
   * @throws Exception
   */
  public Entry<ByteSource, ByteSource> authenticatedDecrypt(ByteSource cipher, byte[] iv)
      throws Exception;

  /**
   * Authenticate and decrypt data. MAC will be re-generated and compared to the MAC associated with
   * the cipher. The MAC will be striped after the data is successfully authenticated.
   *
   * @param cipher data to be authenticated and decrypted.
   * @param iv initialization vector
   * @return the data without MAC (stripped), or null in case of errors.
   */
  public byte[] authenticatedDecrypt(byte[] cipher, byte[] iv) throws Exception;

  /**
   * Authenticate and decrypt data. MAC will be re-generated and compared to the MAC associated with
   * the cipher. The MAC will be striped after the data is successfully authenticated.
   *
   * @param cipher data to be authenticated and decrypted; cipher.hasArray() should be true
   * @param iv initialization vector
   * @param out recovered data without MAC (stripped). It is possible that out.remaining() is
   *        smaller than the data encrypted when only a heading part of the decrypted data is
   *        interesting. In that case, @out will be filled with out.remaining() bytes of decrypted
   *        data.
   * @param ad additional data to be authenticated and decrypted; it is similar to GCM's additional
   *        data but here it is also encrypted (in addition to be authenticated). <b>IMPORTANT</b>
   *        To be able to tell the boundary between normal data and AD, ad.remain() should be the
   *        length of AD.
   * @return whether the operation is successful
   */
  public boolean authenticatedDecrypt(ByteBuffer cipher, byte[] iv, ByteBuffer out, ByteBuffer ad)
      throws Exception;
}
