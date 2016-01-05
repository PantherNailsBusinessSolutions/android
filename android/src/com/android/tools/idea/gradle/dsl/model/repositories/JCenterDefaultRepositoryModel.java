/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.gradle.dsl.model.repositories;

import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslMethodCall;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a repository defined with jcenter().
 */
public class JCenterDefaultRepositoryModel extends RepositoryModel {
  public static final String JCENTER = "jcenter";

  private static final String NAME = "BintrayJCenter2";
  private static final String URL = "https://jcenter.bintray.com/";

  @NotNull
  @Override
  public String name() {
    return NAME;
  }

  @NotNull
  @Override
  public String url() {
    return URL;
  }
}
