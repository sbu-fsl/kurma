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
package edu.stonybrook.kurma.journal;

import java.io.File;
import java.io.IOException;

public class JournalManager {

  private MetaJournal metaJournal;
  private GarbageBlockJournal gbJournal;

  public JournalManager(String journalDirPath, int cleanupFrequency) throws IOException {
    File journalDir = new File(journalDirPath);
    if (!journalDir.exists() && !journalDir.mkdirs()) {
      throw new IOException(String.format("Could not create nonexist journal directory: %s",
          journalDir.getAbsolutePath()));
    }

    File metaJournalDir = new File(journalDir + "FileStoreMeta/");
    if (!metaJournalDir.exists()) {
      metaJournalDir.mkdirs();
    }
    File gbJournalDir = new File(journalDir + "FileStoreJournal/");
    if (!gbJournalDir.exists()) {
      gbJournalDir.mkdirs();
    }

    metaJournal = new MetaJournal(metaJournalDir, cleanupFrequency);
    gbJournal = new GarbageBlockJournal(gbJournalDir, cleanupFrequency);

  }

  public MetaJournal getMetaJournal() {
    return metaJournal;
  }

  public GarbageBlockJournal getGCJournal() {
    return gbJournal;
  }

  public void closeAll() throws IOException {
    metaJournal.close();
    gbJournal.close();
  }

}
