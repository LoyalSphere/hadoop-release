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
package org.apache.hadoop.fs.azurebfs.contract;

import java.util.Arrays;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.contract.AbstractContractRootDirectoryTest;
import org.apache.hadoop.fs.contract.AbstractFSContract;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ITestAbfsFileSystemContractRootDirectory extends AbstractContractRootDirectoryTest {
  @Parameterized.Parameters(name = "SecureMode={0}")
  public static Iterable<Object[]> secure() {
    return Arrays.asList(new Object[][] { {true}, {false} });
  }

  private final boolean isSecure;
  private final DependencyInjectedContractTest dependencyInjectedContractTest;

  public ITestAbfsFileSystemContractRootDirectory(final boolean secure) throws Exception {
    this.isSecure = secure;
    dependencyInjectedContractTest = new DependencyInjectedContractTest(secure);
  }

  @Override
  public void setup() throws Exception {
    dependencyInjectedContractTest.initialize();
    super.setup();
  }

  @Override
  protected Configuration createConfiguration() {
    return this.dependencyInjectedContractTest.getConfiguration();
  }

  @Override
  protected AbstractFSContract createContract(final Configuration conf) {
    return new ITestAbfsFileSystemContract(conf, this.isSecure);
  }

  @Override
  @Ignore("ABFS always return false when non-recursively remove root dir")
  public void testRmNonEmptyRootDirNonRecursive() throws Throwable {
  }
}