/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.commons.executable;

import org.apache.iotdb.commons.trigger.exception.TriggerJarToLargeException;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class ExecutableManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutableManager.class);

  protected final String temporaryLibRoot;
  protected final String libRoot;

  protected final AtomicLong requestCounter;

  public ExecutableManager(String temporaryLibRoot, String libRoot) {
    this.temporaryLibRoot = temporaryLibRoot;
    this.libRoot = libRoot;

    requestCounter = new AtomicLong(0);
  }

  public ExecutableResource request(List<String> uris) throws URISyntaxException, IOException {
    final long requestId = generateNextRequestId();
    downloadExecutables(uris, requestId);
    return new ExecutableResource(requestId, getDirStringUnderTempRootByRequestId(requestId));
  }

  public void moveTempDirToExtLibDir(ExecutableResource resource, String name) throws IOException {
    FileUtils.moveDirectory(
        getDirUnderTempRootByRequestId(resource.getRequestId()), getDirUnderLibRootByName(name));
  }

  public void moveFileUnderTempRootToExtLibDir(ExecutableResource resource, String name)
      throws IOException {
    FileUtils.moveFileToDirectory(
        getFileByFullPath(
            getDirStringUnderTempRootByRequestId(resource.getRequestId()) + File.separator + name),
        getFileByFullPath(libRoot),
        false);
  }

  public void copyFileToExtLibDir(String filePath) throws IOException {
    FileUtils.copyFileToDirectory(
        FSFactoryProducer.getFSFactory().getFile(filePath),
        FSFactoryProducer.getFSFactory().getFile(this.libRoot));
  }

  public void removeFromTemporaryLibRoot(ExecutableResource resource) {
    removeFromTemporaryLibRoot(resource.getRequestId());
  }

  private synchronized long generateNextRequestId() throws IOException {
    long requestId = requestCounter.getAndIncrement();
    while (FileUtils.isDirectory(getDirUnderTempRootByRequestId(requestId))) {
      requestId = requestCounter.getAndIncrement();
    }
    FileUtils.forceMkdir(getDirUnderTempRootByRequestId(requestId));
    return requestId;
  }

  private void downloadExecutables(List<String> uris, long requestId)
      throws IOException, URISyntaxException {
    // TODO: para download
    try {
      for (String uriString : uris) {
        final URL url = new URI(uriString).toURL();
        final String fileName = uriString.substring(uriString.lastIndexOf("/") + 1);
        final String destination =
            temporaryLibRoot + File.separator + requestId + File.separator + fileName;
        FileUtils.copyURLToFile(url, FSFactoryProducer.getFSFactory().getFile(destination));
      }
    } catch (Exception e) {
      removeFromTemporaryLibRoot(requestId);
      throw e;
    }
  }

  private void removeFromTemporaryLibRoot(long requestId) {
    FileUtils.deleteQuietly(getDirUnderTempRootByRequestId(requestId));
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // dir string and dir file generation
  /////////////////////////////////////////////////////////////////////////////////////////////////

  public File getDirUnderTempRootByRequestId(long requestId) {
    return FSFactoryProducer.getFSFactory()
        .getFile(getDirStringUnderTempRootByRequestId(requestId));
  }

  public String getDirStringUnderTempRootByRequestId(long requestId) {
    return temporaryLibRoot + File.separator + requestId + File.separator;
  }

  public File getDirUnderLibRootByName(String name) {
    return FSFactoryProducer.getFSFactory().getFile(getDirStringUnderLibRootByName(name));
  }

  public String getDirStringUnderLibRootByName(String name) {
    return libRoot + File.separator + name + File.separator;
  }

  public File getFileUnderLibRootByName(String name) {
    return FSFactoryProducer.getFSFactory().getFile(getFileStringUnderLibRootByName(name));
  }

  public String getFileStringUnderLibRootByName(String name) {
    return libRoot + File.separator + name;
  }

  private File getFileByFullPath(String path) {
    return FSFactoryProducer.getFSFactory().getFile(path);
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // transfer jar file to bytebuffer for thrift
  /////////////////////////////////////////////////////////////////////////////////////////////////

  public static ByteBuffer transferToBytebuffer(String filePath) throws IOException {
    try (FileChannel fileChannel = FileChannel.open(Paths.get(filePath), StandardOpenOption.READ)) {
      long size = fileChannel.size();
      if (size > Integer.MAX_VALUE) {
        // Max length of Thrift Binary is Integer.MAX_VALUE bytes.
        throw new TriggerJarToLargeException(
            String.format("Size of file exceed %d bytes", Integer.MAX_VALUE));
      }
      ByteBuffer byteBuffer = ByteBuffer.allocate((int) size);
      fileChannel.read(byteBuffer);
      byteBuffer.flip();
      return byteBuffer;
    } catch (Exception e) {
      LOGGER.warn(
          "Error occurred during transferring file{} to ByteBuffer, the cause is {}", filePath, e);
      throw e;
    }
  }

  /**
   * @param byteBuffer jar data
   * @param fileName The name of the file. Absolute Path will be libRoot + File_Separator + fileName
   */
  public void writeToLibDir(ByteBuffer byteBuffer, String fileName) throws IOException {
    String destination = this.libRoot + File.separator + fileName;
    Path path = Paths.get(destination);
    Files.deleteIfExists(path);
    Files.createFile(path);
    try (FileOutputStream outputStream = new FileOutputStream(destination)) {
      outputStream.getChannel().write(byteBuffer);
    } catch (IOException e) {
      LOGGER.warn(
          "Error occurred during writing bytebuffer to {} , the cause is {}", destination, e);
      throw e;
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // other functions
  /////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * @param fileName given file name
   * @return true if file exists under LibRoot
   */
  public boolean hasFileUnderLibRoot(String fileName) {
    return Files.exists(Paths.get(this.libRoot + File.separator + fileName));
  }

  public boolean hasFileUnderTemporaryRoot(String fileName) {
    return Files.exists(Paths.get(this.temporaryLibRoot + File.separator + fileName));
  }

  public void saveTextAsFileUnderTemporaryRoot(String text, String fileName) throws IOException {
    Path path = Paths.get(this.temporaryLibRoot + File.separator + fileName);
    Files.deleteIfExists(path);
    Files.write(path, text.getBytes());
  }

  public String readTextFromFileUnderTemporaryRoot(String fileName) throws IOException {
    Path path = Paths.get(this.temporaryLibRoot + File.separator + fileName);
    return new String(Files.readAllBytes(path));
  }

  public String getTemporaryLibRoot() {
    return temporaryLibRoot;
  }

  public String getLibRoot() {
    return libRoot;
  }
}
