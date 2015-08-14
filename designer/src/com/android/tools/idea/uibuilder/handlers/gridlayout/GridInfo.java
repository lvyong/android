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
package com.android.tools.idea.uibuilder.handlers.gridlayout;

import com.android.tools.idea.uibuilder.model.AndroidCoordinate;
import com.android.tools.idea.uibuilder.model.Insets;
import com.android.tools.idea.uibuilder.model.NlComponent;

import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

final class GridInfo {
  private static final int NEW_CELL_SIZE = 32;

  /**
   * The locations of the vertical drop zone grid lines. Some of them come from the GridLayout ViewGroup (GridLayout.mVerticalAxis
   * .locations) and the rest are calculated by initLineLocations. As such, the drop zone grid can be larger than the GridLayout grid.
   */
  @AndroidCoordinate private int[] verticalLineLocations;

  /**
   * The locations of the horizontal drop zone grid lines. Some of them come from the GridLayout ViewGroup (GridLayout.mHorizontalAxis
   * .locations) and the rest are calculated by initLineLocations. As such, the drop zone grid can be larger than the GridLayout grid.
   */
  @AndroidCoordinate private int[] horizontalLineLocations;

  private NlComponent[][] children;

  /**
   * The number of rows in the GridLayout.
   */
  private final int rowCount;

  /**
   * The number of columns in the GridLayout.
   */
  private final int columnCount;

  private final NlComponent layout;
  private final Dimension size;

  GridInfo(NlComponent layout) {
    if (layout.children == null || layout.viewInfo == null) {
      throw new IllegalArgumentException();
    }

    this.layout = layout;
    size = getSize();

    try {
      initVerticalLineLocations();
      initHorizontalLineLocations();

      Object view = layout.viewInfo.getViewObject();
      Class<?> c = view.getClass();

      rowCount = (Integer)c.getMethod("getRowCount").invoke(view);
      columnCount = (Integer)c.getMethod("getColumnCount").invoke(view);

      initChildren();
    }
    catch (NoSuchFieldException exception) {
      throw new IllegalArgumentException(exception);
    }
    catch (IllegalAccessException exception) {
      throw new IllegalArgumentException(exception);
    }
    catch (NoSuchMethodException exception) {
      throw new IllegalArgumentException(exception);
    }
    catch (InvocationTargetException exception) {
      throw new IllegalArgumentException(exception);
    }
  }

  @AndroidCoordinate
  private Dimension getSize() {
    Dimension size = new Dimension();

    if (layout.children != null) {
      Insets padding = layout.getPadding();

      for (NlComponent child : layout.children) {
        size.width = Math.max(child.x - layout.x - padding.left + child.w, size.width);
        size.height = Math.max(child.y - layout.y - padding.top + child.h, size.height);
      }
    }

    return size;
  }

  private void initVerticalLineLocations() throws NoSuchFieldException, IllegalAccessException {
    Insets padding = layout.getPadding();
    int[] horizontalAxisLocations = getAxisLocations("mHorizontalAxis", "horizontalAxis");

    verticalLineLocations = initLineLocations(layout.w - padding.width(), size.width, horizontalAxisLocations);
    translate(verticalLineLocations, layout.x + padding.left);
  }

  private void initHorizontalLineLocations() throws NoSuchFieldException, IllegalAccessException {
    Insets padding = layout.getPadding();
    int[] verticalAxisLocations = getAxisLocations("mVerticalAxis", "verticalAxis");

    horizontalLineLocations = initLineLocations(layout.h - padding.height(), size.height, verticalAxisLocations);
    translate(horizontalLineLocations, layout.y + padding.top);
  }

  @AndroidCoordinate
  private int[] getAxisLocations(String name1, String name2) throws NoSuchFieldException, IllegalAccessException {
    assert layout.viewInfo != null;
    Object view = layout.viewInfo.getViewObject();

    Field axisField = getDeclaredField(view.getClass(), name1, name2);
    axisField.setAccessible(true);

    Object axis = axisField.get(view);

    Field locationsField = axis.getClass().getDeclaredField("locations");
    locationsField.setAccessible(true);

    return (int[])locationsField.get(axis);
  }

  private static Field getDeclaredField(Class<?> c, String name1, String name2) throws NoSuchFieldException {
    try {
      return c.getDeclaredField(name1);
    }
    catch (NoSuchFieldException exception) {
      return c.getDeclaredField(name2);
    }
  }

  @AndroidCoordinate
  static int[] initLineLocations(int layoutSize, int gridInfoSize, int[] axisLocations) {
    int difference = layoutSize - gridInfoSize;
    int lineLocationsLength = difference > 0 ? axisLocations.length + difference / NEW_CELL_SIZE : axisLocations.length;

    int startIndex;
    int[] lineLocations = Arrays.copyOf(axisLocations, lineLocationsLength);

    if (axisLocations.length == 0) {
      startIndex = 1;
    }
    else {
      lineLocations[axisLocations.length - 1] = gridInfoSize;
      startIndex = axisLocations.length;
    }

    for (int i = startIndex; i < lineLocationsLength; i++) {
      lineLocations[i] = lineLocations[i - 1] + NEW_CELL_SIZE;
    }

    if (lineLocations[lineLocations.length - 1] != layoutSize) {
      lineLocations = Arrays.copyOf(lineLocations, lineLocations.length + 1);
    }

    lineLocations[lineLocations.length - 1] = layoutSize - 1;
    return lineLocations;
  }

  private static void translate(@AndroidCoordinate int[] locations, @AndroidCoordinate int distance) {
    for (int i = 0; i < locations.length; i++) {
      locations[i] += distance;
    }
  }

  private void initChildren() throws NoSuchFieldException, IllegalAccessException {
    children = new NlComponent[rowCount][columnCount];
    assert layout.children != null;

    for (NlComponent child : layout.children) {
      ChildInfo info = getInfo(child);
      int endRow = Math.min(info.getRow2(), rowCount);
      int endColumn = Math.min(info.getColumn2(), columnCount);

      for (int row = info.getRow1(); row < endRow; row++) {
        for (int column = info.getColumn1(); column < endColumn; column++) {
          children[row][column] = child;
        }
      }
    }
  }

  private static ChildInfo getInfo(NlComponent child) throws NoSuchFieldException, IllegalAccessException {
    assert child.viewInfo != null;

    Object params = child.viewInfo.getLayoutParamsObject();
    Class<?> paramsClass = params.getClass();

    Object rowSpec = paramsClass.getDeclaredField("rowSpec").get(params);
    Object columnSpec = paramsClass.getDeclaredField("columnSpec").get(params);

    Field span = rowSpec.getClass().getDeclaredField("span");
    span.setAccessible(true);

    return new ChildInfo(span.get(rowSpec), span.get(columnSpec));
  }

  int getRow(@AndroidCoordinate int y) {
    return getIndex(horizontalLineLocations, y);
  }

  int getColumn(@AndroidCoordinate int x) {
    return getIndex(verticalLineLocations, x);
  }

  private static int getIndex(int[] lineLocations, int location) {
    if (lineLocations.length < 2) {
      throw new IllegalArgumentException(Arrays.toString(lineLocations));
    }
    else if (location < lineLocations[0]) {
      return 0;
    }
    else if (location >= lineLocations[lineLocations.length - 1]) {
      return lineLocations.length - 2;
    }

    for (int i = 0; i < lineLocations.length - 1; i++) {
      if (lineLocations[i] <= location && location < lineLocations[i + 1]) {
        return i;
      }
    }

    throw new AssertionError();
  }

  int[] getVerticalLineLocations() {
    return verticalLineLocations;
  }

  int[] getHorizontalLineLocations() {
    return horizontalLineLocations;
  }

  boolean cellHasChild(int row, int column) {
    return 0 <= row && row < rowCount && 0 <= column && column < columnCount && children[row][column] != null;
  }

  NlComponent[][] getChildren() {
    return children;
  }

  int getRowCount() {
    return rowCount;
  }

  int getColumnCount() {
    return columnCount;
  }
}