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

package org.apache.hadoop.fs.azurebfs;

import java.io.FileNotFoundException;

import org.junit.Test;

import org.apache.hadoop.fs.azurebfs.constants.ConfigurationKeys;

public class ITestAzureBlobFileSystemInitAndCreate extends DependencyInjectedTest {
  public ITestAzureBlobFileSystemInitAndCreate() throws Exception {
    super();

    this.getConfiguration().unset(ConfigurationKeys.AZURE_CREATE_REMOTE_FILESYSTEM_DURING_INITIALIZATION);
  }

  @Override
  public void initialize() throws Exception {
  }

  @Override
  public void testCleanup() throws Exception {
  }

  @Test (expected = FileNotFoundException.class)
  public void ensureFilesystemWillNotBeCreatedIfCreationConfigIsNotSet() throws Exception {
    super.initialize();
    this.getFileSystem();
  }
}