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
package com.cloudera.crunch.impl.mr.collect;

import java.util.List;

import com.cloudera.crunch.DoFn;
import com.cloudera.crunch.PCollection;
import com.cloudera.crunch.PTable;
import com.cloudera.crunch.Pair;
import com.cloudera.crunch.Pipeline;
import com.cloudera.crunch.SourceTarget;
import com.cloudera.crunch.Target;
import com.cloudera.crunch.impl.mr.MRPipeline;
import com.cloudera.crunch.impl.mr.plan.DoNode;
import com.cloudera.crunch.type.PTableType;
import com.cloudera.crunch.type.PType;
import com.cloudera.crunch.type.PTypeFamily;
import com.google.common.collect.Lists;

public abstract class PCollectionImpl<S> implements PCollection<S> {

  private final String name;
  protected MRPipeline pipeline;
  private SourceTarget<S> materializedAt;
  
  public PCollectionImpl(String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getName();
  }
  
  @Override
  public PCollection<S> union(PCollection<S>... collections) {
    List<PCollectionImpl<S>> internal = Lists.newArrayList();
    internal.add(this);
    for (PCollection<S> collection : collections) {
      internal.add((PCollectionImpl<S>) collection);
    }
    return new UnionCollection<S>(internal);
  }

  @Override
  public <T> PCollection<T> parallelDo(DoFn<S, T> fn, PType<T> type) {
    MRPipeline pipeline = (MRPipeline) getPipeline();
    return parallelDo("S" + pipeline.getNextAnonymousStageId(), fn, type);
  }

  @Override
  public <T> PCollection<T> parallelDo(String name, DoFn<S, T> fn,
      PType<T> type) {
    return new DoCollectionImpl<T>(name, this, fn, type);
  }

  @Override
  public <K, V> PTable<K, V> parallelDo(DoFn<S, Pair<K, V>> fn,
      PTableType<K, V> type) {
    MRPipeline pipeline = (MRPipeline) getPipeline();
    return parallelDo("S" + pipeline.getNextAnonymousStageId(), fn, type);
  }

  @Override
  public <K, V> PTable<K, V> parallelDo(String name, DoFn<S, Pair<K, V>> fn,
      PTableType<K, V> type) {
    return new DoTableImpl<K, V>(name, this, fn, type);
  }

  @Override
  public PCollection<S> write(Target target) {
    getPipeline().write(this, target);
    return this;
  }

  @Override
  public Iterable<S> materialize() {
	return getPipeline().materialize(this);
  }
  
  public SourceTarget<S> getMaterializedAt() {
    return materializedAt;
  }
  
  public void materializeAt(SourceTarget<S> sourceTarget) {
    this.materializedAt = sourceTarget;
  }
  
  @Override
  public PTypeFamily getTypeFamily() {
    return getPType().getFamily();
  }

  public abstract DoNode createDoNode();

  public abstract List<PCollectionImpl<?>> getParents();

  public PCollectionImpl<?> getOnlyParent() {
    List<PCollectionImpl<?>> parents = getParents();
    if (parents.size() != 1) {
      throw new IllegalArgumentException("Expected exactly one parent PCollection");
    }
    return parents.get(0);
  }

  @Override
  public Pipeline getPipeline() {
    if (pipeline == null) {
      pipeline = (MRPipeline) getParents().get(0).getPipeline();
    }
    return pipeline;
  }

  public int getDepth() {
    int parentMax = 0;
    for (PCollectionImpl parent : getParents()) {
      parentMax = Math.max(parent.getDepth(), parentMax);
    }
    return 1 + parentMax;
  }
  
  public interface Visitor {
    void visitInputCollection(InputCollection<?> collection);

    void visitUnionCollection(UnionCollection<?> collection);

    void visitDoFnCollection(DoCollectionImpl<?> collection);

    void visitDoTable(DoTableImpl<?, ?> collection);

    void visitGroupedTable(PGroupedTableImpl<?, ?> collection);
  }

  public void accept(Visitor visitor) {
    if (materializedAt != null) {
      visitor.visitInputCollection(new InputCollection<S>(materializedAt, 
          (MRPipeline) getPipeline()));
    } else {
      acceptInternal(visitor);
    }
  }
  
  protected abstract void acceptInternal(Visitor visitor);
}
