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

import static org.junit.Assert.assertEquals;

import com.cloudera.crunch.impl.mr.MRPipeline;
import com.cloudera.crunch.lib.Aggregate;
import com.cloudera.crunch.lib.Cogroup;
import com.cloudera.crunch.test.FileHelper;
import com.cloudera.crunch.type.PType;
import com.cloudera.crunch.type.PTypeFamily;
import com.cloudera.crunch.type.avro.AvroTypeFamily;
import com.cloudera.crunch.type.writable.WritableTypeFamily;
import com.cloudera.crunch.util.Collects;
import com.google.common.collect.Iterables;

import java.util.Collection;

import org.junit.Test;

public class PageRankTest {

  public static class PageRankData extends Tuple3<Float, Float, Collection<String>> {
    public PageRankData(Float first, Float second, Collection<String> third) {
      super(first, second, third);
    }
  }
  
  @Test public void testAvro() throws Exception {
    run(new MRPipeline(PageRankTest.class), AvroTypeFamily.getInstance());
  }

  @Test public void testWritables() throws Exception {
    run(new MRPipeline(PageRankTest.class), WritableTypeFamily.getInstance());
  }

  public static PTable<String, PageRankData> pageRank(PTable<String, PageRankData> input) {
    PTypeFamily ptf = input.getTypeFamily();
    PTable<String, Float> outbound = input.parallelDo(
        new DoFn<Pair<String, PageRankData>, Pair<String, Float>>() {
          @Override
          public void process(Pair<String, PageRankData> input, Emitter<Pair<String, Float>> emitter) {
            float pr = input.second().first() / input.second().third().size();
            for (String link : input.second().third()) {
              emitter.emit(Pair.of(link, pr));
            }
          }
        }, ptf.tableOf(ptf.strings(), ptf.floats()));
    
    DoFn<Pair<String, Pair<Collection<Tuple3<Float, Float, Collection<String>>>,
        Collection<Float>>>, Pair<Object, Object>> doFn;
    return Cogroup.cogroup(input, outbound).parallelDo(
        new MapFn<Pair<String, Pair<Collection<PageRankData>, Collection<Float>>>, Pair<String, PageRankData>>() {
              @Override
              public Pair<String, PageRankData> map(Pair<String, Pair<Collection<PageRankData>, Collection<Float>>> input) {
                PageRankData prd = input.second().first().iterator().next();
                float sum = 0.0f;
                for (Float s : input.second().second()) {
                  sum += s;
                }
                return Pair.of(input.first(), new PageRankData(0.5f + 0.5f*sum, prd.first(), prd.third()));
              }
            }, input.getPTableType());
  }
  
  public static void run(Pipeline pipeline, PTypeFamily ptf) throws Exception {
    String urlInput = FileHelper.createTempCopyOf("urls.txt");
    PType<PageRankData> prType = ptf.tuples(PageRankData.class, ptf.floats(), ptf.floats(),
        ptf.collections(ptf.strings()));
    PTable<String, PageRankData> scores = pipeline.readTextFile(urlInput)
        .parallelDo(new MapFn<String, Pair<String, String>>() {
          @Override
          public Pair<String, String> map(String input) {
            String[] urls = input.split("\\t");
            return Pair.of(urls[0], urls[1]);
          }
        }, ptf.tableOf(ptf.strings(), ptf.strings()))
        .groupByKey()
        .parallelDo(new MapFn<Pair<String, Iterable<String>>, Pair<String, PageRankData>>() {
              @Override
              public Pair<String, PageRankData> map(
                  Pair<String, Iterable<String>> input) {
                return Pair.of(input.first(), new PageRankData(1.0f, 0.0f, Collects.newArrayList(input.second())));
              }
            }, ptf.tableOf(ptf.strings(), prType));
    
    Float delta = 1.0f;
    while (delta > 0.01) {
      scores = pageRank(scores);
      scores.materialize().iterator(); // force the write
      delta = Iterables.getFirst(Aggregate.max(
          scores.parallelDo(new MapFn<Pair<String, PageRankData>, Float>() {
            @Override
            public Float map(Pair<String, PageRankData> input) {
              PageRankData prd = input.second();
              return Math.abs(prd.first() - prd.second());
            }
          }, ptf.floats())).materialize(), null);
    }
    assertEquals(0.0048, delta, 0.001);
  }
}
