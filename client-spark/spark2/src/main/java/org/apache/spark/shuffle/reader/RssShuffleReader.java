/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.reader;

import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.spark.InterruptibleIterator;
import org.apache.spark.ShuffleDependency;
import org.apache.spark.TaskContext;
import org.apache.spark.serializer.Serializer;
import org.apache.spark.shuffle.RssShuffleHandle;
import org.apache.spark.shuffle.ShuffleReader;
import org.apache.spark.util.CompletionIterator$;
import org.apache.spark.util.TaskCompletionListener;
import org.apache.spark.util.collection.ExternalSorter;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Function0;
import scala.Option;
import scala.Product2;
import scala.collection.AbstractIterator;
import scala.collection.Iterator;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import org.apache.uniffle.client.api.ShuffleReadClient;
import org.apache.uniffle.client.factory.ShuffleClientFactory;
import org.apache.uniffle.client.request.CreateShuffleReadClientRequest;
import org.apache.uniffle.common.ShuffleServerInfo;

public class RssShuffleReader<K, C> implements ShuffleReader<K, C> {

  private static final Logger LOG = LoggerFactory.getLogger(RssShuffleReader.class);

  private String appId;
  private int shuffleId;
  private int startPartition;
  private int endPartition;
  private TaskContext context;
  private ShuffleDependency<K, C, ?> shuffleDependency;
  private Serializer serializer;
  private String taskId;
  private String basePath;
  private int indexReadLimit;
  private int readBufferSize;
  private int partitionNumPerRange;
  private int partitionNum;
  private String storageType;
  private Map<Integer, Roaring64NavigableMap> partitionToExpectBlocks;
  private Roaring64NavigableMap taskIdBitmap;
  private Map<Integer, List<ShuffleServerInfo>> partitionToShuffleServers;
  private Configuration hadoopConf;

  public RssShuffleReader(
      int startPartition,
      int endPartition,
      TaskContext context,
      RssShuffleHandle rssShuffleHandle,
      String basePath,
      int indexReadLimit,
      Configuration hadoopConf,
      String storageType,
      int readBufferSize,
      int partitionNumPerRange,
      int partitionNum,
      Map<Integer, Roaring64NavigableMap> partitionToExpectBlocks,
      Roaring64NavigableMap taskIdBitmap) {
    this.appId = rssShuffleHandle.getAppId();
    this.startPartition = startPartition;
    this.endPartition = endPartition;
    this.context = context;
    this.shuffleDependency = rssShuffleHandle.getDependency();
    this.shuffleId = shuffleDependency.shuffleId();
    this.serializer = rssShuffleHandle.getDependency().serializer();
    this.taskId = "" + context.taskAttemptId() + "_" + context.attemptNumber();
    this.basePath = basePath;
    this.indexReadLimit = indexReadLimit;
    this.storageType = storageType;
    this.readBufferSize = readBufferSize;
    this.partitionNumPerRange = partitionNumPerRange;
    this.partitionNum = partitionNum;
    this.partitionToExpectBlocks = partitionToExpectBlocks;
    this.taskIdBitmap = taskIdBitmap;
    this.hadoopConf = hadoopConf;
    this.partitionToShuffleServers = rssShuffleHandle.getPartitionToServers();
  }

  @Override
  public Iterator<Product2<K, C>> read() {
    LOG.info("Shuffle read started:" + getReadInfo());

    MultiPartitionIterator rssShuffleDataIterator = new MultiPartitionIterator<K, C>();

    Iterator<Product2<K, C>> resultIter = null;
    Iterator<Product2<K, C>> aggregatedIter = null;

    if (shuffleDependency.aggregator().isDefined()) {
      if (shuffleDependency.mapSideCombine()) {
        // We are reading values that are already combined
        aggregatedIter = shuffleDependency.aggregator().get().combineCombinersByKey(
            rssShuffleDataIterator, context);
      } else {
        // We don't know the value type, but also don't care -- the dependency *should*
        // have made sure its compatible w/ this aggregator, which will convert the value
        // type to the combined type C
        aggregatedIter = shuffleDependency.aggregator().get().combineValuesByKey(rssShuffleDataIterator, context);
      }
    } else {
      aggregatedIter = rssShuffleDataIterator;
    }

    if (shuffleDependency.keyOrdering().isDefined()) {
      // Create an ExternalSorter to sort the data
      ExternalSorter sorter = new ExternalSorter<K, C, C>(context, Option.empty(), Option.empty(),
          shuffleDependency.keyOrdering(), serializer);
      LOG.info("Inserting aggregated records to sorter");
      long startTime = System.currentTimeMillis();
      sorter.insertAll(aggregatedIter);
      LOG.info("Inserted aggregated records to sorter: millis:" + (System.currentTimeMillis() - startTime));
      context.taskMetrics().incMemoryBytesSpilled(sorter.memoryBytesSpilled());
      context.taskMetrics().incDiskBytesSpilled(sorter.diskBytesSpilled());
      context.taskMetrics().incPeakExecutionMemory(sorter.peakMemoryUsedBytes());

      // Use completion callback to stop sorter if task was finished/cancelled.
      context.addTaskCompletionListener(new TaskCompletionListener() {
        public void onTaskCompletion(TaskContext context) {
          sorter.stop();
        }
      });

      Function0<BoxedUnit> fn0 = new AbstractFunction0<BoxedUnit>() {
        @Override
        public BoxedUnit apply() {
          sorter.stop();
          return BoxedUnit.UNIT;
        }
      };
      resultIter = CompletionIterator$.MODULE$.apply(sorter.iterator(), fn0);
    } else {
      resultIter = aggregatedIter;
    }

    if (!(resultIter instanceof InterruptibleIterator)) {
      resultIter = new InterruptibleIterator<>(context, resultIter);
    }
    return resultIter;
  }

  private String getReadInfo() {
    return "appId=" + appId
        + ", shuffleId=" + shuffleId
        + ",taskId=" + taskId
        + ", partitions: [" + startPartition
        + ", " + endPartition + ")";
  }

  public Configuration getHadoopConf() {
    return hadoopConf;
  }

  class MultiPartitionIterator<K, C> extends AbstractIterator<Product2<K, C>> {
    java.util.Iterator<RssShuffleDataIterator> iterator;
    RssShuffleDataIterator dataIterator;

    MultiPartitionIterator() {
      List<RssShuffleDataIterator> iterators = Lists.newArrayList();
      for (int partition = startPartition; partition < endPartition; partition++) {
        if (partitionToExpectBlocks.get(partition).isEmpty()) {
          LOG.info("{} partition is empty partition", partition);
          continue;
        }
        List<ShuffleServerInfo> shuffleServerInfoList = partitionToShuffleServers.get(partition);
        CreateShuffleReadClientRequest request = new CreateShuffleReadClientRequest(
            appId, shuffleId, partition, storageType, basePath, indexReadLimit, readBufferSize,
            1 , partitionNum, partitionToExpectBlocks.get(partition), taskIdBitmap, shuffleServerInfoList, hadoopConf);
        ShuffleReadClient shuffleReadClient = ShuffleClientFactory.getInstance().createShuffleReadClient(request);
        RssShuffleDataIterator iterator = new RssShuffleDataIterator<K, C>(
            shuffleDependency.serializer(), shuffleReadClient,
            context.taskMetrics().shuffleReadMetrics());
        iterators.add(iterator);
      }
      iterator = iterators.iterator();
      if (iterator.hasNext()) {
        dataIterator = iterator.next();
        iterator.remove();
      }
    }

    @Override
    public boolean hasNext() {
      if (dataIterator == null) {
        return false;
      }
      while (!dataIterator.hasNext()) {
        if (!iterator.hasNext()) {
          return false;
        }
        dataIterator = iterator.next();
        iterator.remove();
      }
      return dataIterator.hasNext();
    }

    @Override
    public Product2<K, C> next() {
      Product2<K, C> result = dataIterator.next();
      return result;
    }
  }

}
