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

package org.apache.celeborn.service.deploy.cluster

import java.nio.charset.StandardCharsets

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.commons.lang3.RandomStringUtils
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import org.apache.celeborn.client.{LifecycleManager, ShuffleClientImpl}
import org.apache.celeborn.common.CelebornConf
import org.apache.celeborn.common.identity.UserIdentifier
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.meta.WorkerInfo
import org.apache.celeborn.service.deploy.MiniClusterFeature

class PushMergedDataSplitSuite extends AnyFunSuite
  with Logging with MiniClusterFeature with BeforeAndAfterAll {

  var masterEndpoint = ""
  override def beforeAll(): Unit = {
    val conf = Map("celeborn.worker.flusher.buffer.size" -> "0")

    logInfo("test initialized , setup Celeborn mini cluster")
    val (master, _) = setupMiniClusterWithRandomPorts(conf, conf, 2)
    masterEndpoint = master.conf.get(CelebornConf.MASTER_ENDPOINTS.key)
  }

  override def afterAll(): Unit = {
    logInfo("all test complete , stop Celeborn mini cluster")
    super.shutdownMiniCluster()
  }

  test("push merged data and partial partition are split") {
    val SHUFFLE_ID = 0
    val MAP_ID = 0
    val ATTEMPT_ID = 0
    val MAP_NUM = 1
    val PARTITION_NUM = 3

    Array("SOFT", "HARD").foreach {
      splitMode =>
        val APP = s"app-${System.currentTimeMillis()}"
        val clientConf = new CelebornConf()
          .set(CelebornConf.MASTER_ENDPOINTS.key, masterEndpoint)
          .set(CelebornConf.CLIENT_PUSH_MAX_REVIVE_TIMES.key, "1")
          .set(CelebornConf.SHUFFLE_PARTITION_SPLIT_THRESHOLD.key, "5K")
          .set(CelebornConf.SHUFFLE_PARTITION_SPLIT_MODE.key, splitMode)
        val lifecycleManager = new LifecycleManager(APP, clientConf)
        val shuffleClient = new ShuffleClientImpl(APP, clientConf, UserIdentifier("mock", "mock"))
        shuffleClient.setupLifecycleManagerRef(lifecycleManager.self)

        // ping and reserveSlots
        val DATA0 = RandomStringUtils.secure().next(10).getBytes(StandardCharsets.UTF_8)
        shuffleClient.pushData(
          SHUFFLE_ID,
          MAP_ID,
          ATTEMPT_ID,
          0,
          DATA0,
          0,
          DATA0.length,
          MAP_NUM,
          PARTITION_NUM)

        // find the worker that has at least 2 partitions
        val partitionLocationMap =
          shuffleClient.getPartitionLocation(SHUFFLE_ID, MAP_NUM, PARTITION_NUM)
        val worker2PartitionIds = mutable.Map.empty[WorkerInfo, ArrayBuffer[Int]]
        for (partitionId <- 0 until PARTITION_NUM) {
          val partitionLocation = partitionLocationMap.get(partitionId)
          worker2PartitionIds
            .getOrElseUpdate(partitionLocation.getWorker, ArrayBuffer.empty)
            .append(partitionId)
        }
        val partitions = worker2PartitionIds.values.filter(_.size >= 2).head
        assert(partitions.length >= 2)

        // prepare merged data
        val PARTITION0_DATA = RandomStringUtils.secure().next(1024).getBytes(StandardCharsets.UTF_8)
        shuffleClient.mergeData(
          SHUFFLE_ID,
          MAP_ID,
          ATTEMPT_ID,
          partitions(0),
          PARTITION0_DATA,
          0,
          PARTITION0_DATA.length,
          MAP_NUM,
          PARTITION_NUM)

        val PARTITION1_DATA = RandomStringUtils.secure().next(1024).getBytes(StandardCharsets.UTF_8)
        shuffleClient.mergeData(
          SHUFFLE_ID,
          MAP_ID,
          ATTEMPT_ID,
          partitions(1),
          PARTITION1_DATA,
          0,
          PARTITION1_DATA.length,
          MAP_NUM,
          PARTITION_NUM)

        // pushData until partition(0) is split
        val GIANT_DATA =
          RandomStringUtils.secure().next(1024 * 100).getBytes(StandardCharsets.UTF_8)
        shuffleClient.pushData(
          SHUFFLE_ID,
          MAP_ID,
          ATTEMPT_ID,
          partitions(0),
          GIANT_DATA,
          0,
          GIANT_DATA.length,
          MAP_NUM,
          PARTITION_NUM)
        for (_ <- 0 until 5) {
          val TRIGGER_DATA = RandomStringUtils.secure().next(1024).getBytes(StandardCharsets.UTF_8)
          shuffleClient.pushData(
            SHUFFLE_ID,
            MAP_ID,
            ATTEMPT_ID,
            partitions(0),
            TRIGGER_DATA,
            0,
            TRIGGER_DATA.length,
            MAP_NUM,
            PARTITION_NUM)
          Thread.sleep(5 * 1000) // wait for flush
        }
        assert(
          partitionLocationMap.get(partitions(0)).getEpoch > 0
        ) // means partition(0) will be split

        // push merged data, we expect that partition(0) will be split, while partition(1) will not be split
        shuffleClient.pushMergedData(SHUFFLE_ID, MAP_ID, ATTEMPT_ID)
        shuffleClient.mapperEnd(SHUFFLE_ID, MAP_ID, ATTEMPT_ID, MAP_NUM)
        assert(
          partitionLocationMap.get(partitions(1)).getEpoch == 0
        ) // means partition(1) will not be split
    }
  }
}