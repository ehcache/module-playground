/*
 * Copyright Terracotta, Inc.
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
package org.ehcache.xml.ss;

import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceProvider;

public class SimpleServiceProvider implements Service {
  private final SimpleServiceConfiguration configuration;
  private volatile Service startedService;

  public SimpleServiceProvider(SimpleServiceConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public void start(ServiceProvider<Service> serviceProvider) {
    try {
      startedService = configuration.getService();
      startedService.start(serviceProvider);
    } catch (Exception e) {
      throw new RuntimeException("Error instantiating simple service", e);
    }
  }

  @Override
  public void stop() {
    startedService.stop();
    startedService = null;
  }

}
