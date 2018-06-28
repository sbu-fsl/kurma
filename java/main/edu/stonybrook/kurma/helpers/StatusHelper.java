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
package edu.stonybrook.kurma.helpers;

import edu.stonybrook.kurma.fs.KurmaError;
import edu.stonybrook.kurma.fs.KurmaStatus;

public class StatusHelper {
  public static KurmaStatus OKAY = newStatus(KurmaError.OKAY, "");

  public static KurmaStatus SESSION_NOT_EXIST =
      newStatus(KurmaError.SESSION_NOT_EXIST, "session not exist");

  public static KurmaStatus OBJECTID_INVALID =
      newStatus(KurmaError.OBJECTID_INVALID, "objectid invalid");

  public static KurmaStatus OBJECT_NOT_FOUND =
      newStatus(KurmaError.OBJECT_NOT_FOUND, "object not found");

  public static KurmaStatus newStatus() {
    return newStatus(KurmaError.OKAY, "");
  }

  public static KurmaStatus newStatus(KurmaError err, String format, Object... args) {
    KurmaStatus st = new KurmaStatus();
    st.setErrcode(err.getValue());
    st.setErrmsg(String.format(format, args));
    return st;
  }

  public static KurmaStatus invalidOid(String format, Object... args) {
    return newStatus(KurmaError.OBJECTID_INVALID, format, args);
  }

  public static KurmaStatus notFound(String format, Object... args) {
    return newStatus(KurmaError.OBJECT_NOT_FOUND, format, args);
  }

  public static boolean isOk(KurmaStatus status) {
    return status.errcode == 0;
  }

  public static KurmaStatus zkError(String format, Object... args) {
    return newStatus(KurmaError.ZOOKEEPER_ERROR, format, args);
  }

  public static KurmaStatus permissionDenied(String format, Object... args) {
    return newStatus(KurmaError.PERMISSION_DENIED, format, args);
  }

  public static KurmaStatus serverError(String format, Object... args) {
    return newStatus(KurmaError.SERVER_ERROR, format, args);
  }
}
