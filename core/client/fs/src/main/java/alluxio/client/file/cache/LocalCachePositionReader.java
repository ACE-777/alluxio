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

package alluxio.client.file.cache;

import static alluxio.client.file.CacheContext.StatsUnit.BYTE;
import static alluxio.client.file.CacheContext.StatsUnit.NANO;

import alluxio.AlluxioURI;
import alluxio.CloseableSupplier;
import alluxio.PositionReader;
import alluxio.client.file.CacheContext;
import alluxio.client.file.URIStatus;
import alluxio.client.file.cache.store.PageReadTargetBuffer;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Implementation of {@link PositionReader} that reads from a local cache if possible.
 */
@ThreadSafe
public class LocalCachePositionReader implements PositionReader {
  /** Page size in bytes. */
  protected final long mPageSize;

  /** Local store to store pages. */
  private final CacheManager mCacheManager;
  private final boolean mIsDora;
  /** Cache related context of this file. */
  private final CacheContext mCacheContext;
  /** File info, fetched from external FS. */
  private final URIStatus mStatus;

  /** External position reader to read data from source and cache. */
  private final CloseableSupplier<PositionReader>  mExternalReader;
  private volatile boolean mClosed;

  /**
   * Constructor when the {@link URIStatus} is already available.
   *
   * @param status file status
   * @param externalReader the external position reader supplier
   * @param cacheManager local cache manager
   * @param conf configuration
   */
  public LocalCachePositionReader(URIStatus status,
      CloseableSupplier<PositionReader> externalReader,
      CacheManager cacheManager, AlluxioConfiguration conf) {
    mPageSize = conf.getBytes(PropertyKey.USER_CLIENT_CACHE_PAGE_SIZE);
    mExternalReader = externalReader;
    mCacheManager = cacheManager;
    mStatus = status;
    mIsDora = conf.getBoolean(PropertyKey.DORA_CLIENT_READ_LOCATION_POLICY_ENABLED);
    if (conf.getBoolean(PropertyKey.USER_CLIENT_CACHE_QUOTA_ENABLED)
        && status.getCacheContext() != null) {
      mCacheContext = status.getCacheContext();
    } else {
      mCacheContext = CacheContext.defaults();
    }
    Metrics.registerGauges();
  }

  @Override
  public int positionRead(long position, PageReadTargetBuffer buffer, int length)
      throws IOException {
    Preconditions.checkArgument(length >= 0, "length should be non-negative");
    Preconditions.checkArgument(position >= 0, "position should be non-negative");
    Preconditions.checkArgument(!mClosed, "position reader is closed");
    if (length == 0) {
      return 0;
    }
    if (position >= mStatus.getLength()) { // at end of file
      return -1;
    }
    Stopwatch stopwatch = createUnstartedStopwatch();
    int totalBytesRead = 0;
    long lengthToRead = Math.min(length, mStatus.getLength() - position);
    // used in positionedRead, so make stopwatch a local variable rather than class member
    // for each page, check if it is available in the cache
    while (totalBytesRead < lengthToRead) {
      int bytesRead = localCachedRead(buffer,
          (int) (lengthToRead - totalBytesRead), position, stopwatch);
      totalBytesRead += bytesRead;
      position += bytesRead;
    }
    if (totalBytesRead > length
        || (totalBytesRead < length && position < mStatus.getLength())) {
      throw new IOException(String.format("Invalid number of bytes read - "
              + "bytes to read = %d, actual bytes read = %d, bytes remains in file %d",
          length, totalBytesRead, mStatus.getLength() - position));
    }
    return totalBytesRead;
  }

  @Override
  public synchronized void close() throws IOException {
    if (mClosed) {
      return;
    }
    mClosed = true;
    mExternalReader.close();
  }

  private int localCachedRead(PageReadTargetBuffer bytesBuffer, int length,
      long position, Stopwatch stopwatch) throws IOException {
    long currentPage = position / mPageSize;
    PageId pageId;
    CacheContext cacheContext = mStatus.getCacheContext();
    if (cacheContext != null && cacheContext.getCacheIdentifier() != null) {
      pageId = new PageId(cacheContext.getCacheIdentifier(), currentPage);
    } else {
      // In Dora, the fileId is generated by Worker or by local client, which maybe is not unique.
      // So we use the ufs path hash as its fileId.
      String fileId = mIsDora ? new AlluxioURI(mStatus.getUfsPath()).hash() :
          Long.toString(mStatus.getFileId());
      pageId = new PageId(fileId, currentPage);
    }
    int currentPageOffset = (int) (position % mPageSize);
    int bytesLeftInPage = (int) (mPageSize - currentPageOffset);
    int bytesToReadInPage = Math.min(bytesLeftInPage, length);
    stopwatch.reset().start();
    int bytesRead =
        mCacheManager.get(pageId, currentPageOffset, bytesToReadInPage, bytesBuffer, mCacheContext);
    stopwatch.stop();
    if (bytesRead > 0) {
      MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getName()).mark(bytesRead);
      if (cacheContext != null) {
        cacheContext.incrementCounter(MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getMetricName(), BYTE,
            bytesRead);
        cacheContext.incrementCounter(
            MetricKey.CLIENT_CACHE_PAGE_READ_CACHE_TIME_NS.getMetricName(), NANO,
            stopwatch.elapsed(TimeUnit.NANOSECONDS));
      }
      return bytesRead;
    }
    // on local cache miss, read a complete page from external storage. This will always make
    // progress or throw an exception
    stopwatch.reset().start();
    byte[] page = readExternalPage(position);
    stopwatch.stop();
    if (page.length > 0) {
      bytesBuffer.writeBytes(page, currentPageOffset, bytesToReadInPage);
      // cache misses
      MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_REQUESTED_EXTERNAL.getName())
          .mark(bytesToReadInPage);
      if (cacheContext != null) {
        cacheContext.incrementCounter(
            MetricKey.CLIENT_CACHE_BYTES_REQUESTED_EXTERNAL.getMetricName(), BYTE,
            bytesToReadInPage);
        cacheContext.incrementCounter(
            MetricKey.CLIENT_CACHE_PAGE_READ_EXTERNAL_TIME_NS.getMetricName(), NANO,
            stopwatch.elapsed(TimeUnit.NANOSECONDS)
        );
      }
      mCacheManager.put(pageId, page, mCacheContext);
    }
    return bytesToReadInPage;
  }

  /**
   * Reads a page from external storage which contains the position specified. Note that this makes
   * a copy of the page.
   * <p>
   * This method is synchronized to ensure thread safety for positioned reads. Only a single thread
   * should call this method at a time because the underlying state (mExternalFileInStream) is
   * shared. Another way would be to use positioned reads instead of seek and read, but that assumes
   * the underlying FileInStream implements thread safe positioned reads which are not much more
   * expensive than seek and read.
   * <p>
   * TODO(calvin): Consider a more efficient API which does not require a data copy.
   *
   * @param position the position which the page will contain
   * @return a byte array of the page data
   */
  private synchronized byte[] readExternalPage(long position)
      throws IOException {
    long pageStart = position - (position % mPageSize);
    int pageSize = (int) Math.min(mPageSize, mStatus.getLength() - pageStart);
    byte[] page = new byte[pageSize];
    int totalBytesRead = 0;
    while (totalBytesRead < pageSize) {
      // TODO(lu) consider how to position read from source and write to local cache with zero copy
      // read from input stream and write to local file
      // read from Netty ByteBuf and write to local file
      int bytesRead = mExternalReader.get()
          .positionRead(position, page, totalBytesRead, pageSize);
      if (bytesRead <= 0) {
        break;
      }
      totalBytesRead += bytesRead;
    }
    // Bytes read from external, may be larger than requests due to reading complete pages
    MetricsSystem.meter(MetricKey.CLIENT_CACHE_BYTES_READ_EXTERNAL.getName()).mark(totalBytesRead);
    if (totalBytesRead != pageSize) {
      throw new IOException("Failed to read complete page from external storage. Bytes read: "
          + totalBytesRead + " Page size: " + pageSize);
    }
    return page;
  }

  @VisibleForTesting
  protected Stopwatch createUnstartedStopwatch() {
    return Stopwatch.createUnstarted(Ticker.systemTicker());
  }

  private static final class Metrics {
    // Note that only counter/guage can be added here.
    // Both meter and timer need to be used inline
    // because new meter and timer will be created after {@link MetricsSystem.resetAllMetrics()}

    private static void registerGauges() {
      // Cache hit rate = Cache hits / (Cache hits + Cache misses).
      MetricsSystem.registerGaugeIfAbsent(
          MetricsSystem.getMetricName(MetricKey.CLIENT_CACHE_HIT_RATE.getName()),
          () -> {
            long cacheHits = MetricsSystem.meter(
                MetricKey.CLIENT_CACHE_BYTES_READ_CACHE.getName()).getCount();
            long cacheMisses = MetricsSystem.meter(
                MetricKey.CLIENT_CACHE_BYTES_REQUESTED_EXTERNAL.getName()).getCount();
            long total = cacheHits + cacheMisses;
            if (total > 0) {
              return cacheHits / (1.0 * total);
            }
            return 0;
          });
    }
  }
}
