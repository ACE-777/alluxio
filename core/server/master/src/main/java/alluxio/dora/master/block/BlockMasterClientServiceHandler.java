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

package alluxio.dora.master.block;

import alluxio.dora.RpcUtils;
import alluxio.dora.client.block.options.GetWorkerReportOptions;
import alluxio.dora.exception.status.NotFoundException;
import alluxio.dora.grpc.BlockMasterClientServiceGrpc;
import alluxio.dora.grpc.BlockMasterInfo;
import alluxio.dora.grpc.BlockMasterInfoField;
import alluxio.dora.grpc.GetBlockInfoPOptions;
import alluxio.dora.grpc.GetBlockInfoPRequest;
import alluxio.dora.grpc.GetBlockInfoPResponse;
import alluxio.dora.grpc.GetBlockMasterInfoPOptions;
import alluxio.dora.grpc.GetBlockMasterInfoPResponse;
import alluxio.dora.grpc.GetCapacityBytesPOptions;
import alluxio.dora.grpc.GetCapacityBytesPResponse;
import alluxio.dora.grpc.GetUsedBytesPOptions;
import alluxio.dora.grpc.GetUsedBytesPResponse;
import alluxio.dora.grpc.GetWorkerInfoListPOptions;
import alluxio.dora.grpc.GetWorkerInfoListPResponse;
import alluxio.dora.grpc.GetWorkerLostStoragePOptions;
import alluxio.dora.grpc.GetWorkerLostStoragePResponse;
import alluxio.dora.grpc.GetWorkerReportPOptions;
import alluxio.dora.grpc.GrpcUtils;
import alluxio.dora.grpc.RemoveDecommissionedWorkerPOptions;
import alluxio.dora.grpc.RemoveDecommissionedWorkerPResponse;
import alluxio.dora.grpc.WorkerRange;
import alluxio.dora.wire.WorkerInfo;

import com.google.common.base.Preconditions;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This class is a gRPC handler for block master RPCs invoked by an Alluxio client.
 */
public final class BlockMasterClientServiceHandler
    extends BlockMasterClientServiceGrpc.BlockMasterClientServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(BlockMasterClientServiceHandler.class);

  private final BlockMaster mBlockMaster;

  /**
   * Creates a new instance of {@link BlockMasterClientServiceHandler}.
   *
   * @param blockMaster the {@link BlockMaster} the handler uses internally
   */
  public BlockMasterClientServiceHandler(BlockMaster blockMaster) {
    Preconditions.checkNotNull(blockMaster, "blockMaster");
    mBlockMaster = blockMaster;
  }

  @Override
  public void getBlockInfo(GetBlockInfoPRequest request,
      StreamObserver<GetBlockInfoPResponse> responseObserver) {
    long blockId = request.getBlockId();
    GetBlockInfoPOptions options = request.getOptions();
    RpcUtils.call(LOG,
        () -> GetBlockInfoPResponse.newBuilder()
            .setBlockInfo(GrpcUtils.toProto(mBlockMaster.getBlockInfo(blockId))).build(),
        "GetBlockInfo", "blockId=%s, options=%s", responseObserver, blockId, options);
  }

  @Override
  public void getBlockMasterInfo(GetBlockMasterInfoPOptions options,
      StreamObserver<GetBlockMasterInfoPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      BlockMasterInfo.Builder infoBuilder = BlockMasterInfo.newBuilder();
      for (BlockMasterInfoField field : (options.getFiltersCount() != 0)
          ? options.getFiltersList()
          : Arrays.asList(BlockMasterInfoField.values())) {
        switch (field) {
          case CAPACITY_BYTES:
            infoBuilder.setCapacityBytes(mBlockMaster.getCapacityBytes());
            break;
          case CAPACITY_BYTES_ON_TIERS:
            infoBuilder.putAllCapacityBytesOnTiers(mBlockMaster.getTotalBytesOnTiers());
            break;
          case FREE_BYTES:
            infoBuilder.setFreeBytes(mBlockMaster.getCapacityBytes() - mBlockMaster.getUsedBytes());
            break;
          case LIVE_WORKER_NUM:
            infoBuilder.setLiveWorkerNum(mBlockMaster.getWorkerCount());
            break;
          case LOST_WORKER_NUM:
            infoBuilder.setLostWorkerNum(mBlockMaster.getLostWorkerCount());
            break;
          case DECOMMISSIONED_WORKER_NUM:
            infoBuilder.setDecommissionedWorkerNum(mBlockMaster.getDecommissionedWorkerCount());
            break;
          case USED_BYTES:
            infoBuilder.setUsedBytes(mBlockMaster.getUsedBytes());
            break;
          case USED_BYTES_ON_TIERS:
            infoBuilder.putAllUsedBytesOnTiers(mBlockMaster.getUsedBytesOnTiers());
            break;
          default:
            LOG.warn("Unrecognized block master info field: " + field);
        }
      }
      return GetBlockMasterInfoPResponse.newBuilder().setBlockMasterInfo(infoBuilder).build();
    }, "GetBlockMasterInfo", "options=%s", responseObserver, options);
  }

  @Override
  public void getCapacityBytes(GetCapacityBytesPOptions options,
      StreamObserver<GetCapacityBytesPResponse> responseObserver) {
    RpcUtils.call(LOG,
        () -> GetCapacityBytesPResponse.newBuilder()
            .setBytes(mBlockMaster.getCapacityBytes()).build(),
        "GetCapacityBytes", "options=%s", responseObserver, options);
  }

  @Override
  public void getUsedBytes(GetUsedBytesPOptions options,
      StreamObserver<GetUsedBytesPResponse> responseObserver) {
    RpcUtils.call(LOG,
        () -> GetUsedBytesPResponse.newBuilder().setBytes(mBlockMaster.getUsedBytes()).build(),
        "GetUsedBytes", "options=%s", responseObserver, options);
  }

  @Override
  public void getWorkerInfoList(GetWorkerInfoListPOptions options,
      StreamObserver<GetWorkerInfoListPResponse> responseObserver) {
    RpcUtils.call(LOG,
        () -> GetWorkerInfoListPResponse.newBuilder()
            .addAllWorkerInfos(mBlockMaster.getWorkerInfoList().stream().map(GrpcUtils::toProto)
            .collect(Collectors.toList())).build(),
        "GetWorkerInfoList", "options=%s", responseObserver, options);
  }

  @Override
  public void removeDecommissionedWorker(RemoveDecommissionedWorkerPOptions options,
      StreamObserver<RemoveDecommissionedWorkerPResponse> responseObserver) {
    RpcUtils.call(LOG, () -> {
      List<WorkerInfo> decommissionedWorkers = mBlockMaster.getWorkerReport(
              new GetWorkerReportOptions(GetWorkerReportPOptions.newBuilder()
                      .setWorkerRange(WorkerRange.DECOMMISSIONED).build()));
      for (WorkerInfo worker : decommissionedWorkers) {
        if (worker.getAddress().getHost().equals(options.getWorkerName()))  {
          mBlockMaster.removeDecommissionedWorker(worker.getId());
          return RemoveDecommissionedWorkerPResponse.getDefaultInstance();
        }
      }
      // Exception info has been added in FreeWorkerCommand.
      throw new NotFoundException(options.getWorkerName());
    }, "RemoveDecommissionedWorker", "options=%s", responseObserver, options);
  }

  @Override
  public void getWorkerReport(GetWorkerReportPOptions options,
      StreamObserver<GetWorkerInfoListPResponse> responseObserver) {
    RpcUtils.call(LOG,
        () -> GetWorkerInfoListPResponse.newBuilder()
            .addAllWorkerInfos(mBlockMaster.getWorkerReport(new GetWorkerReportOptions(options))
                .stream().map(GrpcUtils::toProto).collect(Collectors.toList())).build(),
        "GetWorkerReport", "options=%s", responseObserver, options);
  }

  @Override
  public void getWorkerLostStorage(GetWorkerLostStoragePOptions options,
      StreamObserver<GetWorkerLostStoragePResponse> responseObserver) {
    RpcUtils.call(LOG,
        () -> GetWorkerLostStoragePResponse.newBuilder()
            .addAllWorkerLostStorageInfo(mBlockMaster.getWorkerLostStorage()).build(),
        "GetWorkerLostStorage", "options=%s", responseObserver, options);
  }
}