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
package org.ehcache.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.lang.reflect.ParameterizedType;

import static java.util.Objects.requireNonNull;

/**
 * BaseConfigParser - Base class providing functionality for translating service configurations to corresponding xml
 * document.
 */
public abstract class BaseConfigParser<T> implements Parser<T> {
  private final Class<T> typeParameterClass;

  @SuppressWarnings("unchecked")
  public BaseConfigParser() {
    typeParameterClass = (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
  }

  private T validateConfig(Object config) {
    try {
      return typeParameterClass.cast(requireNonNull(config, "Configuration must not be null."));
    } catch (ClassCastException e) {
      throw new IllegalArgumentException("Invalid configuration parameter passed.", e);
    }
  }

  public final Element unparse(Document document, T config) {
    return safeUnparse(document, validateConfig(config));
  }

  protected abstract Element safeUnparse(Document doc, T config);
}
