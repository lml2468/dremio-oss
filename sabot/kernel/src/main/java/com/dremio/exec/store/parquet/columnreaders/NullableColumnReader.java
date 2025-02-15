/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.parquet.columnreaders;

import java.io.IOException;

import org.apache.arrow.memory.util.LargeMemoryUtil;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VectorDefinitionSetter;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.format.SchemaElement;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;

import com.dremio.common.exceptions.ExecutionSetupException;

abstract class NullableColumnReader<V extends ValueVector> extends ColumnReader<V>{
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(NullableColumnReader.class);
  VectorDefinitionSetter castedVector;
  protected long definitionLevelsRead = 0;

  NullableColumnReader(DeprecatedParquetVectorizedReader parentReader, int allocateSize, ColumnDescriptor descriptor, ColumnChunkMetaData columnChunkMetaData,
                       boolean fixedLength, V v, SchemaElement schemaElement) throws ExecutionSetupException {
    super(parentReader, allocateSize, descriptor, columnChunkMetaData, fixedLength, v, schemaElement);
    castedVector = (VectorDefinitionSetter) v;
  }

  @Override
  public void processPages(long recordsToReadInThisPass) throws IOException {
    readStartInBytes = 0;
    readLength = 0;
    readLengthInBits = 0;
    recordsReadInThisIteration = 0;
    vectorData = valueVec.getDataBuffer();

    // values need to be spaced out where nulls appear in the column
    // leaving blank space for nulls allows for random access to values
    // to optimize copying data out of the buffered disk stream, runs of defined values
    // are located and copied together, rather than copying individual values

    int runLength = -1;     // number of non-null records in this pass.
    int nullRunLength = -1; // number of consecutive null records that we read.
    int currentDefinitionLevel = -1;
    int readCount = 0; // the record number we last read.
    int writeCount = 0; // the record number we last wrote to the value vector.
                        // This was previously the indexInOutputVector variable
    boolean haveMoreData; // true if we have more data and have not filled the vector

    while (readCount < recordsToReadInThisPass && writeCount < valueVec.getValueCapacity()) {
      // read a page if needed
      if (!pageReader.hasPage()
          || (definitionLevelsRead >= pageReader.currentPageCount)) {
        if (!pageReader.next()) {
          break;
        }
        //New page. Reset the definition level.
        currentDefinitionLevel = -1;
        definitionLevelsRead = 0;
        recordsReadInThisIteration = 0;
        readStartInBytes = 0;
      }

      nullRunLength = 0;
      runLength = 0;

      //
      // Let's skip the next run of nulls if any ...
      //

      // If we are reentering this loop, the currentDefinitionLevel has already been read
      if (currentDefinitionLevel < 0) {
        currentDefinitionLevel = pageReader.definitionLevels.readInteger();
      }
      haveMoreData = readCount < recordsToReadInThisPass
          && writeCount + nullRunLength < valueVec.getValueCapacity()
          && definitionLevelsRead < pageReader.currentPageCount;
      while (haveMoreData && currentDefinitionLevel < columnDescriptor
          .getMaxDefinitionLevel()) {
        readCount++;
        nullRunLength++;
        definitionLevelsRead++;
        haveMoreData = readCount < recordsToReadInThisPass
            && writeCount + nullRunLength < valueVec.getValueCapacity()
            && definitionLevelsRead < pageReader.currentPageCount;
        if (haveMoreData) {
          currentDefinitionLevel = pageReader.definitionLevels.readInteger();
        }
      }
      //
      // Write the nulls if any
      //
      if (nullRunLength > 0) {
        long writerIndex = valueVec.getDataBuffer().writerIndex();
        valueVec.getDataBuffer().setIndex(0, LargeMemoryUtil.checkedCastToInt(writerIndex + (int) Math.ceil(nullRunLength * dataTypeLengthInBits / 8.0)));
        writeCount += nullRunLength;
        valuesReadInCurrentPass += nullRunLength;
        recordsReadInThisIteration += nullRunLength;
      }

      //
      // Handle the run of non-null values
      //
      haveMoreData = readCount < recordsToReadInThisPass
          && writeCount + runLength < valueVec.getValueCapacity()
          // note: writeCount+runLength
          && definitionLevelsRead < pageReader.currentPageCount;
      while (haveMoreData && currentDefinitionLevel >= columnDescriptor
          .getMaxDefinitionLevel()) {
        readCount++;
        runLength++;
        definitionLevelsRead++;
        castedVector.setIndexDefined(writeCount + runLength
            - 1); //set the nullable bit to indicate a non-null value
        haveMoreData = readCount < recordsToReadInThisPass
            && writeCount + runLength < valueVec.getValueCapacity()
            && definitionLevelsRead < pageReader.currentPageCount;
        if (haveMoreData) {
          currentDefinitionLevel = pageReader.definitionLevels.readInteger();
        }
      }

      //
      // Write the non-null values
      //
      if (runLength > 0) {
        // set up metadata

        // This _must_ be set so that the call to readField works correctly for all datatypes
        this.recordsReadInThisIteration += runLength;

        this.readStartInBytes = pageReader.readPosInBytes;
        this.readLengthInBits = runLength * dataTypeLengthInBits;
        this.readLength = (int) Math.ceil(readLengthInBits / 8.0);

        readField(runLength);

        writeCount += runLength;
        valuesReadInCurrentPass += runLength;
        pageReader.readPosInBytes = readStartInBytes + readLength;
      }

      pageReader.valuesRead += recordsReadInThisIteration;

      totalValuesRead += runLength + nullRunLength;

      if (logger.isTraceEnabled()) {
        logger.trace("" + "recordsToReadInThisPass: {} \t "
            + "Run Length: {} \t Null Run Length: {} \t readCount: {} \t writeCount: {} \t "
            + "recordsReadInThisIteration: {} \t valuesReadInCurrentPass: {} \t "
            + "totalValuesRead: {} \t readStartInBytes: {} \t readLength: {} \t pageReader.byteLength: {} \t "
            + "definitionLevelsRead: {} \t pageReader.currentPageCount: {}",
          recordsToReadInThisPass, runLength, nullRunLength, readCount,
          writeCount, recordsReadInThisIteration, valuesReadInCurrentPass,
          totalValuesRead, readStartInBytes, readLength, pageReader.byteLength,
          definitionLevelsRead, pageReader.currentPageCount);
      }
    }

    valueVec.setValueCount(valuesReadInCurrentPass);
  }

    @Override
  protected abstract void readField(long recordsToRead);
}
