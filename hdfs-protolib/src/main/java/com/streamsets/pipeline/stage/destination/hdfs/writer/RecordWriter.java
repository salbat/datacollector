/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.destination.hdfs.writer;

import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.Target;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.el.RecordEl;
import com.streamsets.pipeline.lib.generator.CharDataGeneratorFactory;
import com.streamsets.pipeline.lib.generator.DataGenerator;
import com.streamsets.pipeline.stage.destination.hdfs.ElUtil;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;

public class RecordWriter {
  private final static Logger LOG = LoggerFactory.getLogger(RecordWriter.class);

  private final long expires;
  private final Path path;
  private final CharDataGeneratorFactory generatorFactory;
  private long recordCount;

  private CountingOutputStream textOutputStream;
  private DataGenerator generator;
  private boolean textFile;

  private SequenceFile.Writer seqWriter;
  private String keyEL;
  private ELEval keyElEval;
  private ELEval.Variables elVars;
  private Text key;
  private Text value;
  private boolean seqFile;
  private Target.Context context;

  private RecordWriter(Path path, long timeToLiveMillis, CharDataGeneratorFactory generatorFactory) {
    this.expires = System.currentTimeMillis() + timeToLiveMillis;
    this.path = path;
    this.generatorFactory = generatorFactory;
    LOG.debug("Path[{}] - Creating", path);
  }

  public RecordWriter(Path path, long timeToLiveMillis, OutputStream textOutputStream,
      CharDataGeneratorFactory generatorFactory) throws StageException, IOException {
    this(path, timeToLiveMillis, generatorFactory);
    this.textOutputStream = new CountingOutputStream(textOutputStream);
    generator = generatorFactory.getGenerator(new OutputStreamWriter(this.textOutputStream));
    textFile = true;
  }

  public RecordWriter(Path path, long timeToLiveMillis, SequenceFile.Writer seqWriter, String keyEL,
      CharDataGeneratorFactory generatorFactory, Target.Context context) {
    this(path, timeToLiveMillis, generatorFactory);
    this.seqWriter = seqWriter;
    this.keyEL = keyEL;
    this.context = context;
    keyElEval = ElUtil.createKeyElEval(context);
    elVars = context.getDefaultVariables();
    key = new Text();
    value = new Text();
    seqFile = true;
  }

  public Path getPath() {
    return path;
  }

  public long getExpiresOn() {
    return expires;
  }

  public void write(Record record) throws IOException, StageException, ELEvalException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Path[{}] - Writing ['{}']", path, record.getHeader().getSourceId());
    }
    if (generator != null) {
      generator.write(record);
    } else if (seqWriter != null) {
      RecordEl.setRecordInContext(elVars, record);
      key.set(keyElEval.eval(elVars, keyEL, String.class));
      StringWriter sw = new StringWriter(1024);
      DataGenerator dg = generatorFactory.getGenerator(sw);
      dg.write(record);
      dg.close();
      value.set(sw.toString());
      seqWriter.append(key, value);
    } else {
      throw new IOException(Utils.format("RecordWriter '{}' is closed", path));
    }
    recordCount++;
  }

  public void flush() throws IOException {
    LOG.debug("Path[{}] - Flushing", path);
    if (generator != null) {
      generator.flush();
    } else if (seqWriter != null) {
      seqWriter.hflush();
    }
  }

  // due to buffering of underlying streams, the reported length may be less than the actual one up to the
  // buffer size.
  public long getLength() throws IOException {
    long length = -1;
    if (generator != null) {
      length = textOutputStream.getCount();
    } else if (seqWriter != null) {
      length = seqWriter.getLength();
    }
    return length;
  }

  public long getRecords() {
    return recordCount;
  }

  public void close() throws IOException {
    LOG.debug("Path[{}] - Closing", path);
    try {
      if (generator != null) {
        generator.close();
      } else if (seqWriter != null) {
        seqWriter.close();
      }
    } finally {
      generator = null;
      seqWriter = null;
    }
  }

  public boolean isTextFile() {
    return textFile;
  }

  public boolean isSeqFile() {
    return seqFile;
  }

  public boolean isClosed() {
    return generator == null && seqWriter == null;
  }

  public String toString() {
    return Utils.format("RecordWriter[path='{}']", path);
  }

}
