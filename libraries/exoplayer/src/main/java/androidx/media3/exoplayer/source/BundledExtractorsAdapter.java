/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp3.Mp3Extractor;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * {@link ProgressiveMediaExtractor} built on top of {@link Extractor} instances, whose
 * implementation classes are bundled in the app.
 */
@UnstableApi
public final class BundledExtractorsAdapter implements ProgressiveMediaExtractor {

  private final ExtractorsFactory extractorsFactory;
  @Nullable private final DataSource.Factory dataSourceFactory;

  @Nullable private Extractor extractor;
  @Nullable private ExtractorInput extractorInput;

  /**
   * Creates a holder that will select an extractor and initialize it using the specified output.
   *
   * @param extractorsFactory The {@link ExtractorsFactory} providing the extractors to choose from.
   */
  public BundledExtractorsAdapter(ExtractorsFactory extractorsFactory) {
    this(extractorsFactory, /* dataSourceFactory= */ null);
  }

  public BundledExtractorsAdapter(ExtractorsFactory extractorsFactory, @Nullable DataSource.Factory dataSourceFactory) {
    this.extractorsFactory = extractorsFactory;
    this.dataSourceFactory = dataSourceFactory;
  }

  @Override
  public void init(
      DataReader dataReader,
      Uri uri,
      Map<String, List<String>> responseHeaders,
      long position,
      long length,
      ExtractorOutput output)
      throws IOException {
    ExtractorInput extractorInput = new DefaultExtractorInput(dataReader, position, length);
    this.extractorInput = extractorInput;
    if (extractor != null) {
      return;
    }
    Extractor[] extractors = extractorsFactory.createExtractors(uri, responseHeaders);
    ImmutableList.Builder<SniffFailure> sniffFailures =
        ImmutableList.builderWithExpectedSize(extractors.length);
    if (extractors.length == 1) {
      this.extractor = extractors[0];
    } else {
      for (Extractor extractor : extractors) {
        try {
          if (extractor.sniff(extractorInput)) {
            this.extractor = extractor;
            break;
          } else {
            List<SniffFailure> sniffFailureDetails = extractor.getSniffFailureDetails();
            sniffFailures.addAll(sniffFailureDetails);
          }
        } catch (EOFException e) {
          // Do nothing.
        } finally {
          checkState(this.extractor != null || extractorInput.getPosition() == position);
          extractorInput.resetPeekPosition();
        }
      }
      if (extractor == null) {
        throw new UnrecognizedInputFormatException(
            "None of the available extractors ("
                + Joiner.on(", ")
                    .join(
                        Lists.transform(
                            ImmutableList.copyOf(extractors),
                            extractor ->
                                extractor.getUnderlyingImplementation().getClass().getSimpleName()))
                + ") could read the stream.",
            checkNotNull(uri),
            sniffFailures.build());
      }
    }
    extractor.init(output);
    maybeConfigureMatroskaBackgroundCues(extractor.getUnderlyingImplementation(), uri);
  }

  @Override
  public void release() {
    if (extractor != null) {
      extractor.release();
      extractor = null;
    }
    extractorInput = null;
  }

  @Override
  public void disableSeekingOnMp3Streams() {
    if (extractor == null) {
      return;
    }
    Extractor underlyingExtractor = extractor.getUnderlyingImplementation();
    if (underlyingExtractor instanceof Mp3Extractor) {
      ((Mp3Extractor) underlyingExtractor).disableSeeking();
    }
  }

  @Override
  public long getCurrentInputPosition() {
    return extractorInput != null ? extractorInput.getPosition() : C.INDEX_UNSET;
  }

  @Override
  public void seek(long position, long seekTimeUs) {
    checkNotNull(extractor).seek(position, seekTimeUs);
  }

  @Override
  public int read(PositionHolder positionHolder) throws IOException {
    return checkNotNull(extractor).read(checkNotNull(extractorInput), positionHolder);
  }

  private void maybeConfigureMatroskaBackgroundCues(Extractor underlying, Uri uri) {
    if (dataSourceFactory == null || !(underlying instanceof MatroskaExtractor)) {
      return;
    }
    DataSource.Factory factory = dataSourceFactory;
    ((MatroskaExtractor) underlying).setBackgroundDataProviderFactory(position -> {
      DataSource ds = factory.createDataSource();
      ds.open(new DataSpec(uri, position, C.LENGTH_UNSET));
      return new MatroskaExtractor.BackgroundDataProvider() {
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          return ds.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
          ds.close();
        }
      };
    });
  }
}
