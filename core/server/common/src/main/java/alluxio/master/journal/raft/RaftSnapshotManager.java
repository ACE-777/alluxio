/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.raft;

import alluxio.concurrent.jsr.CompletableFuture;
import alluxio.conf.Configuration;
import alluxio.grpc.SnapshotMetadata;
import alluxio.master.selectionpolicy.MasterSelectionPolicy;
import alluxio.util.ConfigurationUtils;
import alluxio.util.network.NetworkAddressUtils;

import com.amazonaws.annotation.GuardedBy;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.RaftLog;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Manages a snapshot download.
 */
public class RaftSnapshotManager {
  private static final Logger LOG = LoggerFactory.getLogger(RaftSnapshotManager.class);

  private final StateMachineStorage mStorage;
  @Nullable @GuardedBy("this")
  private CompletableFuture<Long> mDownloadFuture = null;

  RaftSnapshotManager(StateMachineStorage storage) {
    mStorage = storage;
  }

  /**
   * @return the log index of the last successful snapshot installation, or -1 if failure
   */
  public synchronized long downloadSnapshotFromFollowers() {
    if (mDownloadFuture == null) {
      mDownloadFuture = CompletableFuture.supplyAsync(this::core).whenComplete((index, err) -> {
        LOG.debug("Finished download routine with index {} and error {}", index, err.getMessage());
      });
    } else if (mDownloadFuture.isDone()) {
      try {
        return mDownloadFuture.get();
      } catch (Exception e) {
       // do nothing and return -1
      } finally {
        mDownloadFuture = null;
      }
    }
    return RaftLog.INVALID_LOG_INDEX;
  }

  private long core() {
    List<InetSocketAddress> masterRpcAddresses =
        ConfigurationUtils.getMasterRpcAddresses(Configuration.global());
    InetSocketAddress localAddress = NetworkAddressUtils.getConnectAddress(
        NetworkAddressUtils.ServiceType.MASTER_RPC, Configuration.global());
    SnapshotInfo snapshotInfo = mStorage.getLatestSnapshot();
    if (snapshotInfo == null) {
      LOG.debug("No local snapshot found");
    } else {
      LOG.debug("Local snapshot is {}", TermIndex.valueOf(snapshotInfo.getTerm(),
          snapshotInfo.getIndex()));
    }

    List<Map.Entry<InetSocketAddress, SnapshotMetadata>> followerInfos =
        masterRpcAddresses.parallelStream()
        // filter ourselves out: we do not need to poll ourselves
        .filter(address -> !address.equals(localAddress))
        // map into a pair of (address, SnapshotMetadata) by requesting all followers in parallel
        .collect(Collectors.toMap(Function.identity(), address -> {
          LOG.debug("Requesting snapshot info from {}", address);
          MasterSelectionPolicy policy = MasterSelectionPolicy.Factory.specifiedMaster(address);
          try (RaftJournalServiceClient client = new RaftJournalServiceClient(policy)) {
            client.connect();
            SnapshotMetadata metadata = client.requestLatestSnapshotInfo();
            LOG.debug("Received snapshot info from {} with status {}", address,
                TermIndex.valueOf(metadata.getSnapshotTerm(), metadata.getSnapshotIndex()));
            return metadata;
          } catch (Exception e) {
            LOG.debug("Failed to retrieve snapshot info from {}", address, e);
            return SnapshotMetadata.newBuilder().setExists(false).build();
          }
        })).entrySet().stream()
        // filter out followers that do not have any snapshot or no updated snapshot
        .filter(entry -> snapshotInfo == null || (entry.getValue().getExists()
            && entry.getValue().getSnapshotIndex() > snapshotInfo.getIndex()))
        // sort them by snapshotIndex, the - sign is to reverse the sorting order
        .sorted(Comparator.comparingLong(entry -> -entry.getValue().getSnapshotIndex()))
        .collect(Collectors.toList());

    if (followerInfos.size() == 0) {
      LOG.debug("Did not find any follower with an updated snapshot");
      return RaftLog.INVALID_LOG_INDEX;
    }

    followerInfos.forEach(entry -> {
      LOG.debug("Would request data from {} at index {}", entry.getKey(), TermIndex.valueOf(
          entry.getValue().getSnapshotTerm(), entry.getValue().getSnapshotIndex()));
    });

    return RaftLog.INVALID_LOG_INDEX;
  }
}
