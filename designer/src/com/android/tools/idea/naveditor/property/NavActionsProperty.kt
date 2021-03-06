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
package com.android.tools.idea.naveditor.property

import com.android.tools.idea.common.model.NlComponent
import com.android.tools.idea.naveditor.model.actionDestinationId
import com.android.tools.idea.naveditor.model.inclusive
import com.android.tools.idea.naveditor.model.isAction
import com.android.tools.idea.naveditor.model.popUpTo
import com.android.tools.idea.naveditor.property.inspector.SimpleProperty

/**
 * Property representing all the actions (possibly zero) for a destinations.
 */
class NavActionsProperty(components: List<NlComponent>) : ListProperty("Actions", components) {

  init {
    refreshList()
  }

  override fun refreshList() {
    properties.clear()

    components.flatMap { it.children }
      .filter { it.isAction }
      .forEach { child ->
        child.actionDestinationId?.also {
          properties.put(it, SimpleProperty(it, listOf(child)))
        } ?: child.popUpTo?.also {
          val title = "${if (child.inclusive == true) "Caller of " else ""}$it"
          properties.put(title, SimpleProperty(title, listOf(child)))
        } ?: properties.put("Invalid Action", SimpleProperty("Invalid Action", listOf(child)))
      }
  }
}