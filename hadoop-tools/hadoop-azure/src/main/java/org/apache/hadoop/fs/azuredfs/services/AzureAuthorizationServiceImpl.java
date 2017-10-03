/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azuredfs.services;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.microsoft.azure.storage.Constants;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.core.PathUtility;
import com.microsoft.azure.storage.core.SR;
import com.microsoft.azure.storage.core.StorageCredentialsHelper;
import com.microsoft.azure.storage.core.Utility;
import okhttp3.Headers;
import okhttp3.Request;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.azuredfs.contracts.services.AzureAuthorizationService;
import org.apache.hadoop.fs.azuredfs.contracts.services.ConfigurationService;

@Singleton
@InterfaceAudience.Private
@InterfaceStability.Evolving
final class AzureAuthorizationServiceImpl implements AzureAuthorizationService {
  private static final int EXPECTED_BLOB_QUEUE_CANONICALIZED_STRING_LENGTH = 300;
  private static final Pattern CRLF = Pattern.compile("\r\n", 16);

  private final ConfigurationService configurationService;
  private final StorageCredentialsAccountAndKey storageCredentialsAccountAndKey;

  @Inject
  AzureAuthorizationServiceImpl(
      final ConfigurationService configurationService) throws InvalidKeyException, URISyntaxException {
    Preconditions.checkNotNull("configurationService", configurationService);

    this.configurationService = configurationService;
    this.storageCredentialsAccountAndKey = new StorageCredentialsAccountAndKey(
        this.configurationService.getStorageAccountName(),
        this.configurationService.getStorageAccountKey());
  }

  @Override
  public Request updateRequestWithAuthorizationHeader(Request request)
      throws IOException, InvalidKeyException, StorageException {
    Headers.Builder headerBuilder = request.headers().newBuilder();

    for (String header : request.headers().toMultimap().keySet()) {
      headerBuilder.removeAll(header);
    }

    if (headerBuilder.get(Constants.HeaderConstants.DATE) != null) {
      headerBuilder.removeAll(Constants.HeaderConstants.DATE);
    }

    if (headerBuilder.get(Constants.HeaderConstants.STORAGE_VERSION_HEADER) != null) {
      headerBuilder.removeAll(Constants.HeaderConstants.STORAGE_VERSION_HEADER);
    }

    headerBuilder.set(Constants.HeaderConstants.DATE, Utility.getGMTTime().toString());
    headerBuilder.set(Constants.HeaderConstants.STORAGE_VERSION_HEADER, Constants.HeaderConstants.TARGET_STORAGE_VERSION);

    request = request.newBuilder().headers(headerBuilder.build()).build();

    final String stringToSign = canonicalizeRequest(request);
    final String computedBase64Signature = StorageCredentialsHelper.computeHmac256(this.storageCredentialsAccountAndKey, stringToSign);
    final String authorization = String.format("%s %s:%s", "SharedKey", this.storageCredentialsAccountAndKey.getAccountName(), computedBase64Signature);

    headerBuilder.set(Constants.HeaderConstants.AUTHORIZATION, authorization);
    return request.newBuilder().headers(headerBuilder.build()).build();
  }

  private String canonicalizeRequest(final Request request) throws IOException, StorageException {
    long contentLength = 0;
    URL url = request.url().url();
    String date = null;
    String contentType = null;

    if (request.body() != null && request.body().contentLength() < -1) {
      throw new InvalidParameterException(SR.INVALID_CONTENT_LENGTH);
    }

    if (request.body() != null) {
      contentLength = request.body().contentLength();
      contentType = request.body().contentType().toString();
    }

    // The first element should be the Method of the request.
    // I.e. GET, POST, PUT, or HEAD.
    final StringBuilder canonicalizedStringBuilder = new StringBuilder(EXPECTED_BLOB_QUEUE_CANONICALIZED_STRING_LENGTH);
    canonicalizedStringBuilder.append(request.method());

    // The next elements are
    // If any element is missing it may be empty.
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.CONTENT_ENCODING));
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.CONTENT_LANGUAGE));
    appendCanonicalizedElement(canonicalizedStringBuilder, contentLength <= 0 ? Constants.EMPTY_STRING : String.valueOf(contentLength));
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.CONTENT_MD5));
    appendCanonicalizedElement(canonicalizedStringBuilder, contentType != null ? contentType : Constants.EMPTY_STRING);

    final String dateString = getStandardHeaderValue(request, Constants.HeaderConstants.DATE);

    // If x-ms-date header exists, Date should be empty string
    appendCanonicalizedElement(canonicalizedStringBuilder, dateString.equals(Constants.EMPTY_STRING) ? date : Constants.EMPTY_STRING);
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.IF_MODIFIED_SINCE));
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.IF_MATCH));
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.IF_NONE_MATCH));
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.IF_UNMODIFIED_SINCE));
    appendCanonicalizedElement(canonicalizedStringBuilder, getStandardHeaderValue(request, Constants.HeaderConstants.RANGE));

    addCanonicalizedHeaders(request.headers().toMultimap(), canonicalizedStringBuilder);
    appendCanonicalizedElement(canonicalizedStringBuilder, getCanonicalizedResource(url, this.storageCredentialsAccountAndKey.getAccountName()));

    return canonicalizedStringBuilder.toString();
  }

  private void addCanonicalizedHeaders(final Map<String, List<String>> headers, final StringBuilder canonicalizedStringBuilder) {
    // Look for header names that start with
    // HeaderNames.PrefixForStorageHeader
    // Then sort them in case-insensitive manner.
    final ArrayList<String> httpStorageHeaderNameArray = new ArrayList<String>();

    for (final String key : headers.keySet()) {
      if (key.toLowerCase(Utility.LOCALE_US).startsWith(Constants.PREFIX_FOR_STORAGE_HEADER)) {
        httpStorageHeaderNameArray.add(key.toLowerCase(Utility.LOCALE_US));
      }
    }

    Collections.sort(httpStorageHeaderNameArray);

    // Now go through each header's values in the sorted order and append
    // them to the canonicalized string.
    for (final String key : httpStorageHeaderNameArray) {
      final StringBuilder canonicalizedElement = new StringBuilder(key);
      String delimiter = ":";
      final ArrayList<String> values = getHeaderValues(headers, key);

      boolean appendCanonicalizedElement = false;
      // Go through values, unfold them, and then append them to the
      // canonicalized element string.
      for (final String value : values) {
        if (value != null) {
          appendCanonicalizedElement = true;
        }

        // Unfolding is simply removal of CRLF.
        final String unfoldedValue = CRLF.matcher(value)
            .replaceAll(Matcher.quoteReplacement(Constants.EMPTY_STRING));

        // Append it to the canonicalized element string.
        canonicalizedElement.append(delimiter);
        canonicalizedElement.append(unfoldedValue);
        delimiter = ",";
      }

      // Now, add this canonicalized element to the canonicalized header
      // string.
      if (appendCanonicalizedElement) {
        appendCanonicalizedElement(canonicalizedStringBuilder, canonicalizedElement.toString());
      }
    }
  }

  private void appendCanonicalizedElement(final StringBuilder builder, final String element) {
    builder.append("\n");
    builder.append(element);
  }

  private String getCanonicalizedResource(final URL address, final String accountName)
      throws StorageException {
    // Resource path
    final StringBuilder resourcePath = new StringBuilder("/");
    resourcePath.append(accountName);

    // Note that AbsolutePath starts with a '/'.
    resourcePath.append(address.getPath());
    final StringBuilder canonicalizedResource = new StringBuilder(resourcePath.toString());

    // query parameters
    if (address.getQuery() == null || !address.getQuery().contains("=")) {
      //no query params.
      return canonicalizedResource.toString();
    }

    final Map<String, String[]> queryVariables = PathUtility.parseQueryString(address.getQuery());

    final Map<String, String> lowercasedKeyNameValue = new HashMap<String, String>();

    for (final Map.Entry<String, String[]> entry : queryVariables.entrySet()) {
      // sort the value and organize it as comma separated values
      final List<String> sortedValues = Arrays.asList(entry.getValue());
      Collections.sort(sortedValues);

      final StringBuilder stringValue = new StringBuilder();

      for (final String value : sortedValues) {
        if (stringValue.length() > 0) {
          stringValue.append(",");
        }

        stringValue.append(value);
      }

      // key turns out to be null for ?a&b&c&d
      lowercasedKeyNameValue.put((entry.getKey()) == null ? null
          : entry.getKey().toLowerCase(Utility.LOCALE_US), stringValue.toString());
    }

    final ArrayList<String> sortedKeys = new ArrayList<String>(lowercasedKeyNameValue.keySet());

    Collections.sort(sortedKeys);

    for (final String key : sortedKeys) {
      final StringBuilder queryParamString = new StringBuilder();

      queryParamString.append(key);
      queryParamString.append(":");
      queryParamString.append(lowercasedKeyNameValue.get(key));

      appendCanonicalizedElement(canonicalizedResource, queryParamString.toString());
    }

    return canonicalizedResource.toString();
  }

  private String getStandardHeaderValue(
      Request request,
      String headerName) {
    final String headerValue = request.header(headerName);
    return headerValue == null ? "" : headerValue.toString();
  }

  private ArrayList<String> getHeaderValues(final Map<String, List<String>> headers, final String headerName) {
    final ArrayList<String> arrayOfValues = new ArrayList<>();
    List<String> values = null;

    for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey().toLowerCase(Utility.LOCALE_US).equals(headerName)) {
        values = entry.getValue();
        break;
      }
    }
    if (values != null) {
      for (final String value : values) {
        // canonicalization formula requires the string to be left
        // trimmed.
        arrayOfValues.add(Utility.trimStart(value));
      }
    }
    return arrayOfValues;
  }
}