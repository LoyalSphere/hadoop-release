/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azurebfs.services;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.CompletionService;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;

import org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AzureBlobFileSystemException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.AzureServiceErrorResponseException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidAzureServiceErrorResponseException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.InvalidFileSystemPropertyException;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.TimeoutException;
import org.apache.hadoop.fs.azurebfs.contracts.services.AbfsHttpService;
import org.apache.hadoop.fs.azurebfs.contracts.services.AbfsHttpClientFactory;
import org.apache.hadoop.fs.azurebfs.contracts.services.ConfigurationService;
import org.apache.hadoop.fs.azurebfs.contracts.services.TracingService;
import org.apache.hadoop.fs.azurebfs.contracts.services.LoggingService;
import org.apache.hadoop.fs.azurebfs.contracts.services.ListResultEntrySchema;
import org.apache.hadoop.fs.azurebfs.contracts.services.ListResultSchema;
import org.apache.hadoop.fs.azurebfs.contracts.services.AzureServiceErrorCode;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.azurebfs.AzureBlobFileSystem;
import org.apache.hadoop.fs.azurebfs.constants.HttpHeaderConfigurations;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;

import static org.apache.hadoop.util.Time.now;

@Singleton
@InterfaceAudience.Private
@InterfaceStability.Evolving
final class AbfsHttpServiceImpl implements AbfsHttpService {
  private static final String DATE_TIME_PATTERN = "E, dd MMM yyyy HH:mm:ss 'GMT'";
  private static final String XMS_PROPERTIES_ENCODING = "ISO-8859-1";
  private static final int LIST_MAX_RESULTS = 5000;
  private static final int DELETE_DIRECTORY_TIMEOUT_MILISECONDS = 180000;
  private static final int RENAME_TIMEOUT_MILISECONDS = 180000;

  private final AbfsHttpClientFactory abfsHttpClientFactory;
  private final ConcurrentHashMap<AzureBlobFileSystem, AbfsClient> clientCache;
  private final ConcurrentHashMap<AzureBlobFileSystem, ThreadPoolExecutor> abfsHttpClientWriteExecutorServiceCache;
  private final ConcurrentHashMap<AzureBlobFileSystem, ThreadPoolExecutor> abfsHttpClientReadExecutorServiceCache;
  private final ConcurrentHashMap<ThreadPoolExecutor, CompletionService> abfsHttpClientCompletionServiceCache;
  private final ConfigurationService configurationService;
  private final TracingService tracingService;
  private final LoggingService loggingService;
  private final Set<String> azureAtomicRenameDirSet;

  @Inject
  AbfsHttpServiceImpl(
      final ConfigurationService configurationService,
      final AbfsHttpClientFactory abfsHttpClientFactory,
      final TracingService tracingService,
      final LoggingService loggingService) {
    Preconditions.checkNotNull(abfsHttpClientFactory, "abfsHttpClientFactory");
    Preconditions.checkNotNull(configurationService, "configurationService");
    Preconditions.checkNotNull(tracingService, "tracingService");
    Preconditions.checkNotNull(loggingService, "loggingService");

    this.configurationService = configurationService;
    this.clientCache = new ConcurrentHashMap<>();
    this.abfsHttpClientReadExecutorServiceCache = new ConcurrentHashMap<>();
    this.abfsHttpClientWriteExecutorServiceCache = new ConcurrentHashMap<>();
    this.abfsHttpClientCompletionServiceCache = new ConcurrentHashMap<>();
    this.abfsHttpClientFactory = abfsHttpClientFactory;
    this.tracingService = tracingService;
    this.loggingService = loggingService.get(AbfsHttpService.class);
    this.azureAtomicRenameDirSet = new HashSet<>(Arrays.asList(configurationService.getAzureAtomicRenameDirs().split(AbfsHttpConstants.COMMA)));
  }

  @Override
  public Hashtable<String, String> getFilesystemProperties(final AzureBlobFileSystem azureBlobFileSystem)
      throws AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "getFilesystemProperties for filesystem: {0}",
        client.getFileSystem());

    final Hashtable<String, String> parsedXmsProperties;

    final AbfsRestOperation op = client.getFilesystemProperties();
    final String xMsProperties = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_PROPERTIES);

    parsedXmsProperties = parseCommaSeparatedXmsProperties(xMsProperties);

    return parsedXmsProperties;
  }

  @Override
  public void setFilesystemProperties(final AzureBlobFileSystem azureBlobFileSystem, final Hashtable<String, String> properties) throws
      AzureBlobFileSystemException {
    if (properties == null || properties.size() == 0) {
      return;
    }

    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "setFilesystemProperties for filesystem: {0} with properties: {1}",
        client.getFileSystem(),
        properties);

    final String commaSeparatedProperties;
    try {
      commaSeparatedProperties = convertXmsPropertiesToCommaSeparatedString(properties);
    } catch (CharacterCodingException ex) {
      throw new InvalidAzureServiceErrorResponseException(ex);
    }
    client.setFilesystemProperties(commaSeparatedProperties);
  }

  @Override
  public Hashtable<String, String> getPathProperties(final AzureBlobFileSystem azureBlobFileSystem, final Path path) throws
      AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "getPathProperties for filesystem: {0} path: {1}",
        client.getFileSystem(),
        path.toString());

    final Hashtable<String, String> parsedXmsProperties;
    final AbfsRestOperation op = client.getPathProperties(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path));

    final String xMsProperties = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_PROPERTIES);

    parsedXmsProperties = parseCommaSeparatedXmsProperties(xMsProperties);

    return parsedXmsProperties;
  }

  @Override
  public void setPathProperties(final AzureBlobFileSystem azureBlobFileSystem, final Path path, final Hashtable<String,
      String> properties) throws
      AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "setFilesystemProperties for filesystem: {0} path: {1} with properties: {2}",
        client.getFileSystem(),
        path.toString(),
        properties);

    final String commaSeparatedProperties;
    try {
      commaSeparatedProperties = convertXmsPropertiesToCommaSeparatedString(properties);
    } catch (CharacterCodingException ex) {
      throw new InvalidAzureServiceErrorResponseException(ex);
    }
    client.setPathProperties("/" + getRelativePath(path), commaSeparatedProperties);
  }

  @Override
  public void createFilesystem(final AzureBlobFileSystem azureBlobFileSystem) throws AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "createFilesystem for filesystem: {0}",
        client.getFileSystem());

    client.createFilesystem();
  }

  @Override
  public void deleteFilesystem(final AzureBlobFileSystem azureBlobFileSystem) throws AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "deleteFilesystem for filesystem: {0}",
        client.getFileSystem());

    client.deleteFilesystem();
  }

  @Override
  public OutputStream createFile(final AzureBlobFileSystem azureBlobFileSystem, final Path path, final boolean overwrite) throws
      AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "createFile filesystem: {0} path: {1} overwrite: {2}",
        client.getFileSystem(),
        path.toString(),
        overwrite);

    client.createPath(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path), true, overwrite);

    final OutputStream outputStream;
    try {
      outputStream = new FSDataOutputStream(
          new AbfsOutputStream(client, AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path), 0,
              configurationService.getWriteBufferSize(), configurationService.isFlushEnabled()), null);
    } catch (IOException ex) {
      throw new InvalidAzureServiceErrorResponseException(ex);
    }
    return outputStream;
  }

  @Override
  public Void createDirectory(final AzureBlobFileSystem azureBlobFileSystem, final Path path) throws AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "createDirectory filesystem: {0} path: {1} overwrite: {2}",
        client.getFileSystem(),
        path.toString());

    client.createPath("/" + getRelativePath(path), false, true);

    return null;
  }

  @Override
  public InputStream openFileForRead(final AzureBlobFileSystem azureBlobFileSystem, final Path path,
                                     final FileSystem.Statistics statistics) throws AzureBlobFileSystemException {
    final AbfsClient client = getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "openFileForRead filesystem: {0} path: {1}",
        client.getFileSystem(),
        path.toString());

    final AbfsRestOperation op = client.getPathProperties(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path));

    final String resourceType = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_RESOURCE_TYPE);
    final long contentLength = Long.valueOf(op.getResult().getResponseHeader(HttpHeaderConfigurations.CONTENT_LENGTH));
    final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);

    if (parseIsDirectory(resourceType)) {
      throw new AzureServiceErrorResponseException(
          AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode(),
          AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(),
          "openFileForRead must be used with files and not directories",
          null);
    }

    // Add statistics for InputStream
    return new FSDataInputStream(
        new AbfsInputStream(client, statistics, AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path), contentLength,
            configurationService.getReadBufferSize(), configurationService.getReadAheadQueueDepth(), eTag));
  }

  @Override
  public OutputStream openFileForWrite(final AzureBlobFileSystem azureBlobFileSystem, final Path path, final boolean overwrite) throws
      AzureBlobFileSystemException {
    final AbfsClient client = getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "openFileForWrite filesystem: {0} path: {1} overwrite: {2}",
        client.getFileSystem(),
        path.toString(),
        overwrite);

    final AbfsRestOperation op = client.getPathProperties(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path));

    final String resourceType = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_RESOURCE_TYPE);
    final Long contentLength = Long.valueOf(op.getResult().getResponseHeader(HttpHeaderConfigurations.CONTENT_LENGTH));

    if (parseIsDirectory(resourceType)) {
      throw new AzureServiceErrorResponseException(
          AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode(),
          AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(),
          "openFileForRead must be used with files and not directories",
          null);
    }

    final long offset = overwrite ? 0 : contentLength;

    final OutputStream outputStream;
    try {
      outputStream = new FSDataOutputStream(
          new AbfsOutputStream(client, AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path),
              offset, configurationService.getWriteBufferSize(), configurationService.isFlushEnabled()), null);

    } catch (IOException ex) {
      throw new InvalidAzureServiceErrorResponseException(ex);
    }
    return outputStream;
  }

  @Override
  public void rename(final AzureBlobFileSystem azureBlobFileSystem, final Path source, final Path destination) throws
      AzureBlobFileSystemException {

    if (isAtomicRenameKey(source.getName())) {
      this.loggingService.warning("The atomic rename feature is not supported by the ABFS scheme; however rename,"
          +" create and delete operations are atomic if Namespace is enabled for your Azure Storage account.");
    }

    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "renameAsync filesystem: {0} source: {1} destination: {2}",
        client.getFileSystem(),
        source.toString(),
        destination.toString());

    String continuation = null;
    long deadline = now() + RENAME_TIMEOUT_MILISECONDS;

    do {
      if (now() > deadline) {
        loggingService.debug(
                "Rename {0} to {1} timed out.",
                source,
                destination);

        throw new TimeoutException("Rename timed out.");
      }

      AbfsRestOperation op = client.renamePath(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(source),
              AbfsHttpConstants.FORWARD_SLASH + getRelativePath(destination), continuation);
      continuation = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_CONTINUATION);

    } while (continuation != null && !continuation.isEmpty());
  }

  @Override
  public void delete(final AzureBlobFileSystem azureBlobFileSystem, final Path path, final boolean recursive) throws
      AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "delete filesystem: {0} path: {1} recursive: {2}",
        client.getFileSystem(),
        path.toString(),
        String.valueOf(recursive));

    String continuation = null;
    long deadline = now() + DELETE_DIRECTORY_TIMEOUT_MILISECONDS;

    do {
      if (now() > deadline) {
        loggingService.debug(
                "Delete directory {0} timed out.", path);

        throw new TimeoutException("Delete directory timed out.");
      }

      AbfsRestOperation op = client.deletePath(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path), recursive, continuation);
      continuation = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_CONTINUATION);

    } while(continuation != null && !continuation.isEmpty());
  }

  @Override
  public FileStatus getFileStatus(final AzureBlobFileSystem azureBlobFileSystem, final Path path) throws AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "getFileStatus filesystem: {0} path: {1}",
        client.getFileSystem(),
        path.toString());

    if (path.isRoot()) {
      AbfsRestOperation op = client.getFilesystemProperties();
      final long blockSize = configurationService.getAzureBlockSize();
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);
      final String lastModified = op.getResult().getResponseHeader(HttpHeaderConfigurations.LAST_MODIFIED);
      return new VersionedFileStatus(
          azureBlobFileSystem.getOwnerUser(),
          azureBlobFileSystem.getOwnerUserPrimaryGroup(),
          0,
          true,
          1,
          blockSize,
          parseLastModifiedTime(lastModified).getMillis(),
          path,
          eTag);
    } else {
      AbfsRestOperation op = client.getPathProperties(AbfsHttpConstants.FORWARD_SLASH + getRelativePath(path));

      final long blockSize = configurationService.getAzureBlockSize();
      final String eTag = op.getResult().getResponseHeader(HttpHeaderConfigurations.ETAG);
      final String lastModified = op.getResult().getResponseHeader(HttpHeaderConfigurations.LAST_MODIFIED);
      final String contentLength = op.getResult().getResponseHeader(HttpHeaderConfigurations.CONTENT_LENGTH);
      final String resourceType = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_RESOURCE_TYPE);

      return new VersionedFileStatus(
          azureBlobFileSystem.getOwnerUser(),
          azureBlobFileSystem.getOwnerUserPrimaryGroup(),
          parseContentLength(contentLength),
          parseIsDirectory(resourceType),
          1,
          blockSize,
          parseLastModifiedTime(lastModified).getMillis(),
          path,
          eTag);
    }
  }

  @Override
  public FileStatus[] listStatus(final AzureBlobFileSystem azureBlobFileSystem, final Path path) throws AzureBlobFileSystemException {
    final AbfsClient client = this.getOrCreateClient(azureBlobFileSystem);

    this.loggingService.debug(
        "listStatus filesystem: {0} path: {1}",
        client.getFileSystem(),
        path.toString());

    String relativePath = path.isRoot() ? AbfsHttpConstants.EMPTY_STRING : getRelativePath(path);
    String continuation = null;
    ArrayList<FileStatus> fileStatuses = new ArrayList<>();

    do {
      AbfsRestOperation op = client.listPath(relativePath, false, LIST_MAX_RESULTS, continuation);
      continuation = op.getResult().getResponseHeader(HttpHeaderConfigurations.X_MS_CONTINUATION);
      ListResultSchema retrievedSchema = op.getResult().getListResultSchema();
      if (retrievedSchema == null) {
        throw new AzureServiceErrorResponseException(
            AzureServiceErrorCode.PATH_NOT_FOUND.getStatusCode(),
            AzureServiceErrorCode.PATH_NOT_FOUND.getErrorCode(),
            "listStatusAsync path not found",
            null);
      }

      long blockSize = configurationService.getAzureBlockSize();

      for (ListResultEntrySchema entry : retrievedSchema.paths()) {
        long lastModifiedMillis = 0;
        long contentLength = entry.contentLength() == null ? 0 : entry.contentLength();
        boolean isDirectory = entry.isDirectory() == null ? false : entry.isDirectory();
        if (entry.lastModified() != null && !entry.lastModified().isEmpty()) {
          final DateTime dateTime = DateTime.parse(
                  entry.lastModified(),
                  DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC());
          lastModifiedMillis = dateTime.getMillis();
        }

        fileStatuses.add(
            new VersionedFileStatus(
                azureBlobFileSystem.getOwnerUser(),
                azureBlobFileSystem.getOwnerUserPrimaryGroup(),
                contentLength,
                isDirectory,
                1,
                blockSize,
                lastModifiedMillis,
                azureBlobFileSystem.makeQualified(new Path(File.separator + entry.name())),
                entry.eTag()));
      }

    } while (continuation != null && !continuation.isEmpty());

    return fileStatuses.toArray(new FileStatus[0]);
  }

  @Override
  public synchronized void closeFileSystem(final AzureBlobFileSystem azureBlobFileSystem) throws AzureBlobFileSystemException {
    this.clientCache.remove(azureBlobFileSystem);
  }

  @VisibleForTesting
  synchronized boolean threadPoolsAreRunning(final AzureBlobFileSystem azureBlobFileSystem) {
    return this.abfsHttpClientReadExecutorServiceCache.get(azureBlobFileSystem) != null
        || this.abfsHttpClientWriteExecutorServiceCache.get(azureBlobFileSystem) != null;
  }

  @Override
  public boolean isAtomicRenameKey(String key) {
    return isKeyForDirectorySet(key, azureAtomicRenameDirSet);
  }

  private String getRelativePath(final Path path) {
    Preconditions.checkNotNull(path, "path");
    final String relativePath = path.toUri().getPath();

    if (relativePath.length() == 0) {
      return relativePath;
    }

    if (relativePath.charAt(0) == Path.SEPARATOR_CHAR) {
      if (relativePath.length() == 1) {
        return AbfsHttpConstants.EMPTY_STRING;
      }

      return relativePath.substring(1);
    }

    return relativePath;
  }

  private synchronized AbfsClient getOrCreateClient(final AzureBlobFileSystem azureBlobFileSystem) throws
      AzureBlobFileSystemException {
    Preconditions.checkNotNull(azureBlobFileSystem, "azureBlobFileSystem");

    AbfsClient client = this.clientCache.get(azureBlobFileSystem);

    if (client != null) {
      return client;
    }

    client = abfsHttpClientFactory.create(azureBlobFileSystem);
    this.clientCache.put(
        azureBlobFileSystem,
        client);
    return client;
  }

  private long parseContentLength(final String contentLength) {
    if (contentLength == null) {
      return -1;
    }

    return Long.valueOf(contentLength);
  }

  private boolean parseIsDirectory(final String resourceType) {
    return resourceType == null ? false : resourceType.equalsIgnoreCase(AbfsHttpConstants.DIRECTORY);
  }

  private DateTime parseLastModifiedTime(final String lastModifiedTime) {
    return DateTime.parse(
        lastModifiedTime,
        DateTimeFormat.forPattern(DATE_TIME_PATTERN).withZoneUTC());
  }

  private String convertXmsPropertiesToCommaSeparatedString(final Hashtable<String, String> properties) throws
      CharacterCodingException {
    String commaSeparatedProperties = AbfsHttpConstants.EMPTY_STRING;
    Set<String> keys = properties.keySet();
    Iterator<String> itr = keys.iterator();

    final CharsetEncoder encoder = Charset.forName(XMS_PROPERTIES_ENCODING).newEncoder();

    while (itr.hasNext()) {
      String key = itr.next();
      String value = properties.get(key);

      Boolean canEncodeValue = encoder.canEncode(value);
      if (!canEncodeValue) {
        throw new CharacterCodingException();
      }

      String encodedPropertyValue = DatatypeConverter.printBase64Binary(encoder.encode(CharBuffer.wrap(value)).array());
      commaSeparatedProperties += key + AbfsHttpConstants.EQUAL + encodedPropertyValue;

      if (itr.hasNext()) {
        commaSeparatedProperties += AbfsHttpConstants.COMMA;
      }
    }

    return commaSeparatedProperties;
  }

  private Hashtable<String, String> parseCommaSeparatedXmsProperties(String xMsProperties) throws
      InvalidFileSystemPropertyException, InvalidAzureServiceErrorResponseException {
    Hashtable<String, String> properties = new Hashtable<>();

    final CharsetDecoder decoder = Charset.forName(XMS_PROPERTIES_ENCODING).newDecoder();

    if (xMsProperties != null && !xMsProperties.isEmpty()) {
      String[] userProperties = xMsProperties.split(AbfsHttpConstants.COMMA);

      if (userProperties.length == 0) {
        return properties;
      }

      for (String property : userProperties) {
        if (property.isEmpty()) {
          throw new InvalidFileSystemPropertyException(xMsProperties);
        }

        String[] nameValue = property.split(AbfsHttpConstants.EQUAL, 2);
        if (nameValue.length != 2) {
          throw new InvalidFileSystemPropertyException(xMsProperties);
        }

        byte[] decodedValue = DatatypeConverter.parseBase64Binary(nameValue[1]);

        final String value;
        try {
          value = decoder.decode(ByteBuffer.wrap(decodedValue)).toString();
        } catch (CharacterCodingException ex) {
          throw new InvalidAzureServiceErrorResponseException(ex);
        }
        properties.put(nameValue[0], value);
      }
    }

    return properties;
  }

  private boolean isKeyForDirectorySet(String key, Set<String> dirSet) {
    for (String dir : dirSet) {
      if (dir.isEmpty() || key.startsWith(dir + AbfsHttpConstants.FORWARD_SLASH)) {
        return true;
      }

      try {
        URI uri = new URI(dir);
        if (null == uri.getAuthority()) {
          if (key.startsWith(dir + "/")){
            return true;
          }
        }
      } catch (URISyntaxException e) {
        this.loggingService.info("URI syntax error creating URI for {}", dir);
      }
    }

    return false;
  }

  private class VersionedFileStatus extends FileStatus {
    private final String version;

    VersionedFileStatus(
        final String owner, final String group,
        final long length, final boolean isdir, final int blockReplication,
        final long blocksize, final long modificationTime, final Path path,
        String version) {
      super(length, isdir, blockReplication, blocksize, modificationTime, 0,
          new FsPermission(FsAction.ALL, FsAction.ALL, FsAction.ALL),
          owner,
          group,
          path);

      this.version = version;
    }
  }
}