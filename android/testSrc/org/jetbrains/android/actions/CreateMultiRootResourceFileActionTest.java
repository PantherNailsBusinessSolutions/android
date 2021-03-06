/*
 * Copyright (C) 2017 The Android Open Source Project
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
package org.jetbrains.android.actions;

import com.android.SdkConstants;
import com.android.resources.ResourceFolderType;
import org.jetbrains.android.AndroidTestCase;
import org.mockito.Mockito;

import java.util.Arrays;

public final class CreateMultiRootResourceFileActionTest extends AndroidTestCase {
  private CreateMultiRootResourceFileAction myAction;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myAction = Mockito.spy(new CreateMultiRootResourceFileAction("Layout", ResourceFolderType.LAYOUT));
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    myAction = null;
  }

  public void testGetAllowedTagNamesModuleDoesntDependOnConstaintLayout() {
    Mockito.doReturn(false).when(myAction).dependsOn(myFacet, SdkConstants.CONSTRAINT_LAYOUT_LIB_ARTIFACT);

    myAction.getAllowedTagNames(myFacet);
    assertEquals(SdkConstants.LINEAR_LAYOUT, myAction.getDefaultRootTag());
  }

  public void testGetAllowedTagNamesModuleDependsOnConstraintLayout() {
    Mockito.doReturn(true).when(myAction).dependsOn(myFacet, SdkConstants.CONSTRAINT_LAYOUT_LIB_ARTIFACT);
    Mockito.when(myAction.getPossibleRoots(myFacet)).thenReturn(Arrays.asList(SdkConstants.LINEAR_LAYOUT, SdkConstants.CONSTRAINT_LAYOUT));

    myAction.getAllowedTagNames(myFacet);
    assertEquals(SdkConstants.CONSTRAINT_LAYOUT, myAction.getDefaultRootTag());
  }
}
