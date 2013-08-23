/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.gradle;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;

/**
 * Describes an unusual event that occurred during a project import.
 */
public class ProjectImportEventMessage implements Serializable {
  @NotNull private final String myCategory;
  @NotNull private final String myText;

  public ProjectImportEventMessage(@NotNull String category, @NotNull String text) {
    myCategory = category;
    myText = text;
  }

  @NotNull
  public String getCategory() {
    return myCategory;
  }

  @NotNull
  public String getText() {
    return myText;
  }

  @Override
  public String toString() {
    if (myCategory.isEmpty()) {
      return myText;
    }
    return myCategory + " " + myText;
  }
}
