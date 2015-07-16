/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package parquet.hadoop.example;

import static java.lang.Thread.sleep;
import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.parquet.Strings;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.junit.Before;
import org.junit.Test;

import parquet.Log;
import parquet.example.data.Group;
import parquet.example.data.simple.SimpleGroupFactory;
import parquet.hadoop.api.ReadSupport;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.hadoop.util.ContextUtil;
import parquet.schema.MessageTypeParser;

public class TestInputOutputFormat {
  private static final Log LOG = Log.getLog(TestInputOutputFormat.class);
  final Path parquetPath = new Path("target/test/example/TestInputOutputFormat/parquet");
  final Path inputPath = new Path("src/test/java/parquet/hadoop/example/TestInputOutputFormat.java");
  final Path outputPath = new Path("target/test/example/TestInputOutputFormat/out");
  Job writeJob;
  Job readJob;
  private String writeSchema;
  private String readSchema;
  private String partialSchema;
  private Configuration conf;

  private Class readMapperClass;
  private Class writeMapperClass;

  @Before
  public void setUp() {
    conf = new Configuration();
    writeSchema = "message example {\n" +
            "required int32 line;\n" +
            "required binary content;\n" +
            "}";

    readSchema = "message example {\n" +
            "required int32 line;\n" +
            "required binary content;\n" +
            "}";

    partialSchema = "message example {\n" +
            "required int32 line;\n" +
            "}";

    readMapperClass =ReadMapper.class;
    writeMapperClass=WriteMapper.class;
  }

  public static class ReadMapper extends Mapper<LongWritable, Text, Void, Group> {
    private SimpleGroupFactory factory;

    protected void setup(org.apache.hadoop.mapreduce.Mapper<LongWritable, Text, Void, Group>.Context context) throws java.io.IOException, InterruptedException {
      factory = new SimpleGroupFactory(GroupWriteSupport.getSchema(ContextUtil.getConfiguration(context)));
    }

    protected void map(LongWritable key, Text value, Mapper<LongWritable, Text, Void, Group>.Context context) throws java.io.IOException, InterruptedException {
      Group group = factory.newGroup()
              .append("line", (int) key.get())
              .append("content", value.toString());
      context.write(null, group);
    }
  }

  public static class WriteMapper extends Mapper<Void, Group, LongWritable, Text> {
    protected void map(Void key, Group value, Mapper<Void, Group, LongWritable, Text>.Context context) throws IOException, InterruptedException {
      context.write(new LongWritable(value.getInteger("line", 0)), new Text(value.getString("content", 0)));
    }
  }
  public static class PartialWriteMapper extends Mapper<Void, Group, LongWritable, Text> {
    protected void map(Void key, Group value, Mapper<Void, Group, LongWritable, Text>.Context context) throws IOException, InterruptedException {
      context.write(new LongWritable(value.getInteger("line", 0)), new Text("dummy"));
    }
  }
  private void runMapReduceJob(CompressionCodecName codec) throws IOException, ClassNotFoundException, InterruptedException {
    runMapReduceJob(codec, Collections.<String, String>emptyMap());
  }
  private void runMapReduceJob(CompressionCodecName codec, Map<String, String> extraConf) throws IOException, ClassNotFoundException, InterruptedException {
    Configuration conf = new Configuration(this.conf);
    for (Map.Entry<String, String> entry : extraConf.entrySet()) {
      conf.set(entry.getKey(), entry.getValue());
    }
    final FileSystem fileSystem = parquetPath.getFileSystem(conf);
    fileSystem.delete(parquetPath, true);
    fileSystem.delete(outputPath, true);
    {
      writeJob = new Job(conf, "write");
      TextInputFormat.addInputPath(writeJob, inputPath);
      writeJob.setInputFormatClass(TextInputFormat.class);
      writeJob.setNumReduceTasks(0);
      ExampleOutputFormat.setCompression(writeJob, codec);
      ExampleOutputFormat.setOutputPath(writeJob, parquetPath);
      writeJob.setOutputFormatClass(ExampleOutputFormat.class);
      writeJob.setMapperClass(readMapperClass);

      ExampleOutputFormat.setSchema(
              writeJob,
              MessageTypeParser.parseMessageType(
                      writeSchema));
      writeJob.submit();
      waitForJob(writeJob);
    }
    {

      conf.set(ReadSupport.PARQUET_READ_SCHEMA, readSchema);
      readJob = new Job(conf, "read");

      readJob.setInputFormatClass(ExampleInputFormat.class);

      ExampleInputFormat.setInputPaths(readJob, parquetPath);
      readJob.setOutputFormatClass(TextOutputFormat.class);
      TextOutputFormat.setOutputPath(readJob, outputPath);
      readJob.setMapperClass(writeMapperClass);
      readJob.setNumReduceTasks(0);
      readJob.submit();
      waitForJob(readJob);
    }
  }

  private void testReadWrite(CompressionCodecName codec) throws IOException, ClassNotFoundException, InterruptedException {
    testReadWrite(codec, Collections.<String, String>emptyMap());
  }
  private void testReadWrite(CompressionCodecName codec, Map<String, String> conf) throws IOException, ClassNotFoundException, InterruptedException {
    runMapReduceJob(codec, conf);
    final BufferedReader in = new BufferedReader(new FileReader(new File(inputPath.toString())));
    final BufferedReader out = new BufferedReader(new FileReader(new File(outputPath.toString(), "part-m-00000")));
    String lineIn;
    String lineOut = null;
    int lineNumber = 0;
    while ((lineIn = in.readLine()) != null && (lineOut = out.readLine()) != null) {
      ++lineNumber;
      lineOut = lineOut.substring(lineOut.indexOf("\t") + 1);
      assertEquals("line " + lineNumber, lineIn, lineOut);
    }
    assertNull("line " + lineNumber, out.readLine());
    assertNull("line " + lineNumber, lineIn);
    in.close();
    out.close();
  }

  @Test
  public void testReadWrite() throws IOException, ClassNotFoundException, InterruptedException {
    // TODO: Lzo requires additional external setup steps so leave it out for now
    testReadWrite(CompressionCodecName.GZIP);
    testReadWrite(CompressionCodecName.UNCOMPRESSED);
    testReadWrite(CompressionCodecName.SNAPPY);
  }

  @Test
  public void testReadWriteTaskSideMD() throws IOException, ClassNotFoundException, InterruptedException {
    testReadWrite(CompressionCodecName.UNCOMPRESSED, new HashMap<String, String>() {{ put("parquet.task.side.metadata", "true"); }});
  }

  /**
   * Uses a filter that drops all records to test handling of tasks (mappers) that need to do no work at all
   */
  @Test
  public void testReadWriteTaskSideMDAggressiveFilter() throws IOException, ClassNotFoundException, InterruptedException {
    Configuration conf = new Configuration();

    // this filter predicate should trigger row group filtering that drops all row-groups
    ParquetInputFormat.setFilterPredicate(conf, FilterApi.eq(FilterApi.intColumn("line"), -1000));
    final String fpString = conf.get(ParquetInputFormat.FILTER_PREDICATE);

    runMapReduceJob(CompressionCodecName.UNCOMPRESSED, new HashMap<String, String>() {{
      put("parquet.task.side.metadata", "true");
      put(ParquetInputFormat.FILTER_PREDICATE, fpString);
    }});

    List<String> lines = Files.readAllLines(new File(outputPath.toString(), "part-m-00000").toPath(), Charset.forName("UTF-8"));
    assertTrue(lines.isEmpty());
  }

  @Test
  public void testReadWriteFilter() throws IOException, ClassNotFoundException, InterruptedException {
    Configuration conf = new Configuration();

    // this filter predicate should keep some records but not all (first 500 characters)
    // "line" is actually position in the file...
    ParquetInputFormat.setFilterPredicate(conf, FilterApi.lt(FilterApi.intColumn("line"), 500));
    final String fpString = conf.get(ParquetInputFormat.FILTER_PREDICATE);

    runMapReduceJob(CompressionCodecName.UNCOMPRESSED, new HashMap<String, String>() {{
      put("parquet.task.side.metadata", "true");
      put(ParquetInputFormat.FILTER_PREDICATE, fpString);
    }});

    List<String> expected = Files.readAllLines(new File(inputPath.toString()).toPath(), Charset.forName("UTF-8"));

    // grab the lines that contain the first 500 characters (including the rest of the line past 500 characters)
    int size = 0;
    Iterator<String> iter = expected.iterator();
    while(iter.hasNext()) {
      String next = iter.next();

      if (size < 500) {
        size += next.length();
        continue;
      }

      iter.remove();
    }

    // put the output back into it's original format (remove the character counts / tabs)
    List<String> found = Files.readAllLines(new File(outputPath.toString(), "part-m-00000").toPath(), Charset.forName("UTF-8"));
    StringBuilder sbFound = new StringBuilder();
    for (String line : found) {
      sbFound.append(line.split("\t", -1)[1]);
      sbFound.append("\n");
    }

    sbFound.deleteCharAt(sbFound.length() - 1);

    assertEquals(Strings.join(expected, "\n"), sbFound.toString());
  }

  @Test
  public void testProjection() throws Exception{
    readSchema=partialSchema;
    writeMapperClass = PartialWriteMapper.class;
    runMapReduceJob(CompressionCodecName.GZIP);
  }

  @Test
  public void testReadWriteWithCounter() throws Exception {
    runMapReduceJob(CompressionCodecName.GZIP);
    assertTrue(readJob.getCounters().getGroup("parquet").findCounter("bytesread").getValue() > 0L);
    assertTrue(readJob.getCounters().getGroup("parquet").findCounter("bytestotal").getValue() > 0L);
    assertTrue(readJob.getCounters().getGroup("parquet").findCounter("bytesread").getValue()
            == readJob.getCounters().getGroup("parquet").findCounter("bytestotal").getValue());
    //not testing the time read counter since it could be zero due to the size of data is too small
  }

  @Test
  public void testReadWriteWithoutCounter() throws Exception {
    conf.set("parquet.benchmark.time.read", "false");
    conf.set("parquet.benchmark.bytes.total", "false");
    conf.set("parquet.benchmark.bytes.read", "false");
    runMapReduceJob(CompressionCodecName.GZIP);
    assertTrue(readJob.getCounters().getGroup("parquet").findCounter("bytesread").getValue() == 0L);
    assertTrue(readJob.getCounters().getGroup("parquet").findCounter("bytestotal").getValue() == 0L);
    assertTrue(readJob.getCounters().getGroup("parquet").findCounter("timeread").getValue() == 0L);
  }

  private void waitForJob(Job job) throws InterruptedException, IOException {
    while (!job.isComplete()) {
      LOG.debug("waiting for job " + job.getJobName());
      sleep(100);
    }
    LOG.info("status for job " + job.getJobName() + ": " + (job.isSuccessful() ? "SUCCESS" : "FAILURE"));
    if (!job.isSuccessful()) {
      throw new RuntimeException("job failed " + job.getJobName());
    }
  }
}
