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

package org.apache.hadoop.fs.azuredfs.services;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.hadoop.fs.azuredfs.contracts.services.InjectableService;

public class MockServiceProviderImpl {
  private final MockServiceInjectorImpl serviceInjector;
  private Injector injector;

  public MockServiceProviderImpl() {
    this.serviceInjector = new MockServiceInjectorImpl();
  }

  public void initialize() {
    this.injector = Guice.createInjector(serviceInjector);
    ServiceProviderImpl.create(this.injector);
  }

  public <T extends InjectableService> void bind(Class<T> tInterface, Class<? extends T> tClazz) {
    this.serviceInjector.bind(tInterface, tClazz);
  }

  public <T extends InjectableService> T get(Class<T> clazz) {
    return this.injector.getInstance(clazz);
  }
}