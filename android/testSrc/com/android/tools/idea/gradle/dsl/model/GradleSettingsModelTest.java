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
package com.android.tools.idea.gradle.dsl.model;

import com.android.tools.idea.gradle.dsl.api.GradleBuildModel;
import com.android.tools.idea.gradle.dsl.api.GradleSettingsModel;
import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.io.File;

import static com.android.tools.idea.Projects.getBaseDirPath;
import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;

/**
 * Tests for {@link GradleSettingsModel}.
 */
public class GradleSettingsModelTest extends GradleFileModelTestCase {
  @Test
  public void testIncludedModulePaths() throws Exception {
    String text = "include \":app\" \n" +
                  "include \":lib\", \":lib:subLib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib:subLib"), settingsModel.modulePaths());
  }

  @Test
  public void testIncludedModulePathsWithDotSeparator() throws Exception {
    String text = "include \":app:app.ext\"\n";
    writeToBuildFile("");
    writeToSettingsFile(text);
    Module newModule = writeToNewSubModule("app.ext", "", "");

    ProjectBuildModel projectModel = ProjectBuildModel.get(myProject);
    GradleBuildModel buildModel = projectModel.getModuleBuildModel(newModule);

    assertNotNull(buildModel);
  }

  @Test
  public void testAddAndResetModulePaths() throws Exception {
    String text = "include \":app\", \":lib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib"), settingsModel.modulePaths());

    settingsModel.addModulePath("lib1");
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib1"), settingsModel.modulePaths());

    settingsModel.resetState();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib"), settingsModel.modulePaths());
  }

  @Test
  public void testAddAndApplyModulePaths() throws Exception {
    String text = "include \":app\", \":lib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib"), settingsModel.modulePaths());

    settingsModel.addModulePath("lib1");
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib1"), settingsModel.modulePaths());

    applyChanges(settingsModel);
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib1"), settingsModel.modulePaths());

    settingsModel.reparse();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib1"), settingsModel.modulePaths());
  }

  @Test
  public void testAddAndApplyAllModulePaths() throws Exception {
    String text = "";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":"), settingsModel.modulePaths());

    settingsModel.addModulePath("app");
    assertEquals("include", ImmutableList.of(":", ":app"), settingsModel.modulePaths());

    applyChanges(settingsModel);
    assertEquals("include", ImmutableList.of(":", ":app"), settingsModel.modulePaths());

    settingsModel.reparse();
    assertEquals("include", ImmutableList.of(":", ":app"), settingsModel.modulePaths());
  }

  @Test
  public void testRemoveAndResetModulePaths() throws Exception {
    String text = "include \":app\" \n" +
                  "include \":lib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib"), settingsModel.modulePaths());

    settingsModel.removeModulePath(":app");
    assertEquals("include", ImmutableList.of(":", ":lib"), settingsModel.modulePaths());

    settingsModel.resetState();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib"), settingsModel.modulePaths());
  }

  @Test
  public void testRemoveAndApplyModulePaths() throws Exception {
    String text = "include \":app\" \n" +
                  "include \":lib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib"), settingsModel.modulePaths());

    settingsModel.removeModulePath(":app");
    assertEquals("include", ImmutableList.of(":", ":lib"), settingsModel.modulePaths());

    applyChanges(settingsModel);
    assertEquals("include", ImmutableList.of(":", ":lib"), settingsModel.modulePaths());

    settingsModel.reparse();
    assertEquals("include", ImmutableList.of(":", ":lib"), settingsModel.modulePaths());
  }

  @Test
  public void testRemoveAndApplyAllModulePaths() throws Exception {
    String text = "include \":app\" \n" +
                  "include \":lib\", \":lib1\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib1"), settingsModel.modulePaths());

    settingsModel.removeModulePath(":app");
    settingsModel.removeModulePath("lib");
    settingsModel.removeModulePath(":lib1");
    assertEquals("include", ImmutableList.of(":"), settingsModel.modulePaths());

    applyChanges(settingsModel);
    assertEquals("include", ImmutableList.of(":"), settingsModel.modulePaths());

    settingsModel.reparse();
    assertEquals("include", ImmutableList.of(":"), settingsModel.modulePaths());
  }

  @Test
  public void testReplaceAndResetModulePaths() throws Exception {
    String text = "include \":app\" \n" +
                  "include \":lib\", \":lib:subLib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib:subLib"), settingsModel.modulePaths());

    settingsModel.replaceModulePath("lib", "lib1");
    assertEquals("include", ImmutableList.of(":", ":app", ":lib1", ":lib:subLib"), settingsModel.modulePaths());

    settingsModel.resetState();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib:subLib"), settingsModel.modulePaths());
  }

  @Test
  public void testReplaceAndApplyModulePaths() throws Exception {
    String text = "include \":app\" \n" +
                  "include \":lib\", \":lib:subLib\"";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib", ":lib:subLib"), settingsModel.modulePaths());

    settingsModel.replaceModulePath("lib", "lib1");
    assertEquals("include", ImmutableList.of(":", ":app", ":lib1", ":lib:subLib"), settingsModel.modulePaths());

    applyChanges(settingsModel);
    assertEquals("include", ImmutableList.of(":", ":app", ":lib1", ":lib:subLib"), settingsModel.modulePaths());

    settingsModel.reparse();
    assertEquals("include", ImmutableList.of(":", ":app", ":lib1", ":lib:subLib"), settingsModel.modulePaths());
  }

  @Test
  public void testGetModuleDirectory() throws Exception {
    String text = "include \"app\", \":libs\"\n" +
                  "include 'libs:mylibrary'\n" +
                  "project(':libs:mylibrary').projectDir = new File(rootDir, 'xyz/mylibrary')\n" +
                  "include \":olibs\"\n" +
                  "project  (   \":olibs\"  )   .   projectDir = new File(rootDir, 'otherlibs')\n" +
                  "include 'olibs:mylibrary'\n" +
                  "include ':notamodule:deepmodule'";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals(ImmutableList.of(":", ":app", ":libs", ":libs:mylibrary", ":olibs", ":olibs:mylibrary", ":notamodule:deepmodule"),
                 settingsModel.modulePaths());

    File rootDir = getBaseDirPath(myProject);
    assertEquals(rootDir, settingsModel.moduleDirectory(":"));
    assertEquals(new File(rootDir, "app"), settingsModel.moduleDirectory("app"));
    assertEquals(new File(rootDir, "libs"), settingsModel.moduleDirectory(":libs"));
    assertEquals(new File(rootDir, "xyz/mylibrary"), settingsModel.moduleDirectory(":libs:mylibrary"));
    assertEquals(new File(rootDir, "otherlibs"), settingsModel.moduleDirectory("olibs"));
    assertEquals(new File(rootDir, "otherlibs/mylibrary"), settingsModel.moduleDirectory(":olibs:mylibrary"));
    assertEquals(new File(rootDir, "notamodule/deepmodule"), settingsModel.moduleDirectory(":notamodule:deepmodule"));
  }

  @Test
  public void testGetModuleWithDirectory() throws Exception {
    String text = "include \"app\", \":libs\"\n" +
                  "include 'libs:mylibrary'\n" +
                  "project(':libs:mylibrary').projectDir = new File(rootDir, 'xyz/mylibrary')\n" +
                  "include \":olibs\"\n" +
                  "project  (   \":olibs\"  )   .   projectDir = new File(rootDir, 'otherlibs')\n" +
                  "include 'olibs:mylibrary'\n" +
                  "include ':notamodule:deepmodule'";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals(ImmutableList.of(":", ":app", ":libs", ":libs:mylibrary", ":olibs", ":olibs:mylibrary", ":notamodule:deepmodule"),
                 settingsModel.modulePaths());

    File rootDir = getBaseDirPath(myProject);
    assertEquals(":", settingsModel.moduleWithDirectory(rootDir));
    assertEquals(":app", settingsModel.moduleWithDirectory(new File(rootDir, "app")));
    assertEquals(":libs", settingsModel.moduleWithDirectory(new File(rootDir, "libs")));
    assertEquals(":libs:mylibrary", settingsModel.moduleWithDirectory(new File(rootDir, "xyz/mylibrary")));
    assertEquals(":olibs", settingsModel.moduleWithDirectory(new File(rootDir, "otherlibs")));
    assertEquals(":olibs:mylibrary", settingsModel.moduleWithDirectory(new File(rootDir, "otherlibs/mylibrary")));
    assertEquals(":notamodule:deepmodule", settingsModel.moduleWithDirectory(new File(rootDir, "notamodule/deepmodule")));
  }

  @Test
  public void testGetBuildFile() throws Exception {
    String text = "include \"app\", \":lib\"\n" +
                  "project(':lib').buildFileName = 'test.gradle'\n" +
                  "include 'olib'\n" +
                  "project(\":olib\").projectDir = new File(rootDir, \"otherlibs/xyz\")\n" +
                  "project(':olib').buildFileName = 'other.gradle'";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals(ImmutableList.of(":", ":app", ":lib", ":olib"), settingsModel.modulePaths());

    File rootDir = getBaseDirPath(myProject);
    assertEquals(new File(rootDir, "build.gradle"), settingsModel.buildFile(""));
    assertEquals(new File(rootDir, "app/build.gradle"), settingsModel.buildFile("app"));
    assertEquals(new File(rootDir, "lib/test.gradle"), settingsModel.buildFile(":lib"));
    assertEquals(new File(rootDir, "otherlibs/xyz/other.gradle"), settingsModel.buildFile(":olib"));
  }

  @Test
  public void testGetParentModule() throws Exception {
    String text = "include \"app\", \":libs\"\n" +
                  "include 'libs:mylibrary'\n" +
                  "include \":olibs\"\n" +
                  "include 'olibs:mylibrary'\n" +
                  "include ':notamodule:deepmodule'";

    writeToSettingsFile(text);
    GradleSettingsModel settingsModel = getGradleSettingsModel();
    assertEquals(ImmutableList.of(":", ":app", ":libs", ":libs:mylibrary", ":olibs", ":olibs:mylibrary", ":notamodule:deepmodule"),
                 settingsModel.modulePaths());

    assertEquals(":", settingsModel.parentModule("app"));
    assertEquals(":", settingsModel.parentModule(":libs"));
    assertEquals(":libs", settingsModel.parentModule("libs:mylibrary"));
    assertEquals(":", settingsModel.parentModule("olibs"));
    assertEquals(":olibs", settingsModel.parentModule(":olibs:mylibrary"));
    assertEquals(":", settingsModel.parentModule(":notamodule:deepmodule"));
  }

  private void applyChanges(@NotNull final GradleSettingsModel settingsModel) {
    runWriteCommandAction(myProject, new Runnable() {
      @Override
      public void run() {
        settingsModel.applyChanges();
      }
    });
    assertFalse(settingsModel.isModified());
  }
}
