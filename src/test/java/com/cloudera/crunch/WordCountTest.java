/**
 * Copyright (c) 2011, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */
package com.cloudera.crunch;

import static org.junit.Assert.assertTrue;

import com.cloudera.crunch.DoFn;
import com.cloudera.crunch.Emitter;
import com.cloudera.crunch.PCollection;
import com.cloudera.crunch.PTable;
import com.cloudera.crunch.Pipeline;
import com.cloudera.crunch.impl.mr.MRPipeline;
import com.cloudera.crunch.io.To;
import com.cloudera.crunch.lib.Aggregate;
import com.cloudera.crunch.test.FileHelper;
import com.cloudera.crunch.type.PTypeFamily;
import com.cloudera.crunch.type.avro.AvroTypeFamily;
import com.cloudera.crunch.type.writable.WritableTypeFamily;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import org.junit.Test;

public class WordCountTest {
  
  public static PTable<String, Long> wordCount(PCollection<String> words, PTypeFamily typeFamily) {
    return Aggregate.count(words.parallelDo(new DoFn<String, String>() {
      @Override
      public void process(String line, Emitter<String> emitter) {
        for (String word : line.split("\\s+")) {
          emitter.emit(word);
        }
      }
    }, typeFamily.strings()));
  }
  
  public static PTable<String, Long> substr(PTable<String, Long> ptable) {
	return ptable.parallelDo(new DoFn<Pair<String, Long>, Pair<String, Long>>() {
	  public void process(Pair<String, Long> input,
		  Emitter<Pair<String, Long>> emitter) {
		if (input.first().length() > 0) {
		  emitter.emit(Pair.of(input.first().substring(0, 1), input.second()));
		}
	  }      
    }, ptable.getPTableType());
  }
  
  private boolean runSecond = false;
  private boolean useToOutput = false;
  
  @Test
  public void testWritables() throws IOException {
    run(new MRPipeline(WordCountTest.class), WritableTypeFamily.getInstance());
  }

  @Test
  public void testWritablesWithSecond() throws IOException {
    runSecond = true;
	run(new MRPipeline(WordCountTest.class), WritableTypeFamily.getInstance());
  }

  @Test
  public void testWritablesWithSecondUseToOutput() throws IOException {
    runSecond = true;
    useToOutput = true;
    run(new MRPipeline(WordCountTest.class), WritableTypeFamily.getInstance());
  }

  @Test
  public void testAvro() throws IOException {
    run(new MRPipeline(WordCountTest.class), AvroTypeFamily.getInstance());
  }
  
  @Test
  public void testAvroWithSecond() throws IOException {
    runSecond = true;
    run(new MRPipeline(WordCountTest.class), AvroTypeFamily.getInstance());
  }
  
  public void run(Pipeline pipeline, PTypeFamily typeFamily) throws IOException {
	String inputPath = FileHelper.createTempCopyOf("shakes.txt");
	File output = FileHelper.createOutputPath();
	String outputPath = output.getAbsolutePath();
	
    PCollection<String> shakespeare = pipeline.readTextFile(inputPath);
    PTable<String, Long> wordCount = wordCount(shakespeare, typeFamily);
    if (useToOutput) {
      wordCount.write(To.textFile(outputPath));
    } else {
      pipeline.writeTextFile(wordCount, outputPath);
    }
    
    if (runSecond) {
      File substrCount = File.createTempFile("substr", "");
      String substrPath = substrCount.getAbsolutePath();
      substrCount.delete();
      PTable<String, Long> we = substr(wordCount).groupByKey().combineValues(
          CombineFn.<String>SUM_LONGS());
      pipeline.writeTextFile(we, substrPath);
    }
    pipeline.done();
    
    File outputFile = new File(outputPath, "part-r-00000");
    List<String> lines = Files.readLines(outputFile, Charset.defaultCharset());
    boolean passed = false;
    for (String line : lines) {
      if (line.equals("Macbeth\t28")) {
        passed = true;
        break;
      }
    }
    assertTrue(passed);
	output.deleteOnExit();
  }  
}
