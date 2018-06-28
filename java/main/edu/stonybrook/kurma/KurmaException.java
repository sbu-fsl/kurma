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
package edu.stonybrook.kurma;

public class KurmaException extends Exception {
  private static final long serialVersionUID = 8485784435794279841L;

  public KurmaException(String message) {
    super(message);
  }

  public KurmaException(String message, Throwable cause) {
    super(message, cause);
  }

  public static class CuratorException extends KurmaException {
    private static final long serialVersionUID = 213601882551642892L;

    public CuratorException(String message) {
      super(message);
    }

    public CuratorException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class NoZNodeException extends CuratorException {
    private static final long serialVersionUID = -4480155505854952303L;

    public NoZNodeException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class RemoteException extends KurmaException {
    private static final long serialVersionUID = -99064232841361069L;

    public RemoteException(String message) {
      super(message);
    }
  }

  public static class ErasureException extends KurmaException {
    private static final long serialVersionUID = 2285890030871525406L;

    public ErasureException(String message) {
      super(message);
    }
  }
}
