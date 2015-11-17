/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.run;

import com.intellij.execution.ExecutionManager;
import com.intellij.execution.Executor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AndroidSessionManager {
  @Nullable
  public static AndroidSessionInfo findOldSession(@NotNull Project project,
                                                  @NotNull Executor executor,
                                                  int currentID) {
    for (ProcessHandler handler : ExecutionManager.getInstance(project).getRunningProcesses()) {
      final AndroidSessionInfo info = handler.getUserData(AndroidDebugRunner.ANDROID_SESSION_INFO);

      if (info != null &&
          currentID == info.getState().getRunConfigurationId() &&
          executor.getId().equals(info.getExecutorId())) {
        return info;
      }
    }
    return null;
  }
}
