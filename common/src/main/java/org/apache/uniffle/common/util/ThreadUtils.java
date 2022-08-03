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

package org.apache.uniffle.common.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Provide a general method to create a thread factory to make the code more standardized
 */
public class ThreadUtils {

  public static ThreadFactory getThreadFactory(String factoryName) {
    return new ThreadFactoryBuilder().setDaemon(true).setNameFormat(factoryName).build();
  }

  public static ScheduledExecutorService getSingleScheduledExecutorService(String factoryName) {
    return Executors.newSingleThreadScheduledExecutor(getThreadFactory(factoryName));
  }

  public static ExecutorService getFixedThreadPool(int num, String factoryName) {
    return Executors.newFixedThreadPool(num, getThreadFactory(factoryName));
  }

  public static ExecutorService getCachedThreadPool(String factoryName) {
    return Executors.newCachedThreadPool(getThreadFactory(factoryName));
  }
}
