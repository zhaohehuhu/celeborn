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

package org.apache.celeborn.service.deploy.master.clustermeta;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.celeborn.common.identity.UserIdentifier;
import org.apache.celeborn.common.identity.UserIdentifier$;
import org.apache.celeborn.common.meta.DiskInfo;
import org.apache.celeborn.common.meta.WorkerInfo;
import org.apache.celeborn.common.meta.WorkerStatus;
import org.apache.celeborn.common.protocol.StorageInfo;
import org.apache.celeborn.common.quota.ResourceConsumption;
import org.apache.celeborn.common.util.CollectionUtils;
import org.apache.celeborn.common.util.Utils;
import org.apache.celeborn.common.protocol.WorkerAddress;
import org.apache.celeborn.common.protocol.PbWorkerInfo;
import org.apache.celeborn.common.protocol.PbDiskInfo;
import org.apache.celeborn.common.protocol.PbResourceConsumption;
import org.apache.celeborn.common.protocol.PbWorkerStatus;

public class MetaUtil {
  private MetaUtil() {}

  public static WorkerInfo addrToInfo(ResourceProtos.WorkerAddress address) {
    return new WorkerInfo(
        address.getHost(),
        address.getRpcPort(),
        address.getPushPort(),
        address.getFetchPort(),
        address.getReplicatePort(),
        address.getInternalPort());
  }

  public static WorkerAddress infoToAddr(WorkerInfo info) {
    return WorkerAddress.newBuilder()
        .setHost(info.host())
        .setRpcPort(info.rpcPort())
        .setPushPort(info.pushPort())
        .setFetchPort(info.fetchPort())
        .setReplicatePort(info.replicatePort())
        .setInternalPort(info.internalPort())
        .build();
  }

  public static PbWorkerInfo infoToPbWorkerInfo(WorkerInfo info) {
    return PbWorkerInfo.newBuilder()
            .setHost(info.host())
            .setRpcPort(info.rpcPort())
            .setPushPort(info.pushPort())
            .setFetchPort(info.fetchPort())
            .setReplicatePort(info.replicatePort())
            .setInternalPort(info.internalPort())
            .build();
  }

  public static Map<String, DiskInfo> fromPbDiskInfos(
      Map<String, ResourceProtos.DiskInfo> diskInfos) {
    Map<String, DiskInfo> map = new HashMap<>();

    diskInfos.forEach(
        (k, v) -> {
          DiskInfo diskInfo =
              new DiskInfo(
                      v.getMountPoint(),
                      v.getUsableSpace(),
                      v.getAvgFlushTime(),
                      v.getAvgFetchTime(),
                      v.getUsedSlots(),
                      StorageInfo.typesMap.get(v.getStorageType()))
                  .setStatus(Utils.toDiskStatus(v.getStatus()))
                  .setTotalSpace(v.getTotalSpace());
          map.put(k, diskInfo);
        });
    return map;
  }

  public static Map<String,  PbDiskInfo> toPbDiskInfos(
      Map<String,DiskInfo> diskInfos) {
    Map<String, PbDiskInfo> map = new HashMap<>();
    diskInfos.forEach(
        (k, v) ->
            map.put(
                k,
                    PbDiskInfo.newBuilder()
                    .setMountPoint(v.mountPoint())
                    .setUsableSpace(v.actualUsableSpace())
                    .setAvgFlushTime(v.avgFlushTime())
                    .setAvgFetchTime(v.avgFetchTime())
                    .setUsedSlots(v.activeSlots())
                    .setStorageType(v.storageType().getValue())
                    .setStatus(v.status().getValue())
                    .setTotalSpace(v.totalSpace())
                    .build()));
    return map;
  }

  public static ResourceConsumption fromPbResourceConsumption(
          PbResourceConsumption pbResourceConsumption) {
    return new ResourceConsumption(
        pbResourceConsumption.getDiskBytesWritten(),
        pbResourceConsumption.getDiskFileCount(),
        pbResourceConsumption.getHdfsBytesWritten(),
        pbResourceConsumption.getHdfsFileCount(),
        fromPbSubResourceConsumptions(pbResourceConsumption.getSubResourceConsumptionsMap()));
  }

  public static PbResourceConsumption toPbResourceConsumption(
      ResourceConsumption resourceConsumption) {
    return PbResourceConsumption.newBuilder()
        .setDiskBytesWritten(resourceConsumption.diskBytesWritten())
        .setDiskFileCount(resourceConsumption.diskFileCount())
        .setHdfsBytesWritten(resourceConsumption.hdfsBytesWritten())
        .setHdfsFileCount(resourceConsumption.hdfsFileCount())
        .putAllSubResourceConsumptions(
            toPbSubResourceConsumptions(resourceConsumption.subResourceConsumptions()))
        .build();
  }

  public static Map<String, ResourceConsumption> fromPbSubResourceConsumptions(
      Map<String, PbResourceConsumption> pbSubResourceConsumptions) {
    return CollectionUtils.isEmpty(pbSubResourceConsumptions)
        ? null
        : pbSubResourceConsumptions.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    resourceConsumption ->
                        fromPbResourceConsumption(resourceConsumption.getValue())));
  }

  public static Map<String, PbResourceConsumption> toPbSubResourceConsumptions(
      Map<String, ResourceConsumption> subResourceConsumptions) {
    return CollectionUtils.isEmpty(subResourceConsumptions)
        ? new HashMap<String, PbResourceConsumption>()
        : subResourceConsumptions.entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    resourceConsumption ->
                        toPbResourceConsumption(resourceConsumption.getValue())));
  }

  public static Map<UserIdentifier, ResourceConsumption> fromPbUserResourceConsumption(
      Map<String, PbResourceConsumption> pbUserResourceConsumption) {
    Map<UserIdentifier, ResourceConsumption> map = new HashMap<>();
    pbUserResourceConsumption.forEach(
        (k, v) -> map.put(UserIdentifier$.MODULE$.apply(k), fromPbResourceConsumption(v)));
    return map;
  }

  public static Map<String, PbResourceConsumption> toPbUserResourceConsumption(
      Map<UserIdentifier, ResourceConsumption> userResourceConsumption) {
    Map<String, PbResourceConsumption> map = new HashMap<>();
    userResourceConsumption.forEach((k, v) -> map.put(k.toString(), toPbResourceConsumption(v)));
    return map;
  }

  public static PbWorkerStatus toPbWorkerStatus(WorkerStatus workerStatus) {
    return PbWorkerStatus.newBuilder()
        .setState(org.apache.celeborn.common.protocol.PbWorkerStatus.State.forNumber(workerStatus.getStateValue()))
        .setStateStartTime(workerStatus.getStateStartTime())
        .build();
  }

  public static WorkerStatus fromPbWorkerStatus(WorkerStatus workerStatus) {
    return new WorkerStatus(workerStatus.getState().getNumber(), workerStatus.getStateStartTime());
  }
}
