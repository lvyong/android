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
package com.android.tools.idea.common.surface;

import static com.android.annotations.VisibleForTesting.Visibility;

import com.android.annotations.VisibleForTesting;
import com.android.tools.adtui.common.SwingCoordinate;
import com.android.tools.idea.common.editor.ActionManager;
import com.android.tools.idea.common.error.IssueModel;
import com.android.tools.idea.common.error.IssuePanel;
import com.android.tools.idea.common.error.LintIssueProvider;
import com.android.tools.idea.common.lint.LintAnnotationsModel;
import com.android.tools.idea.common.model.AndroidCoordinate;
import com.android.tools.idea.common.model.AndroidDpCoordinate;
import com.android.tools.idea.common.model.Coordinates;
import com.android.tools.idea.common.model.DnDTransferComponent;
import com.android.tools.idea.common.model.DnDTransferItem;
import com.android.tools.idea.common.model.ItemTransferable;
import com.android.tools.idea.common.model.ModelListener;
import com.android.tools.idea.common.model.NlComponent;
import com.android.tools.idea.common.model.NlLayoutType;
import com.android.tools.idea.common.model.NlModel;
import com.android.tools.idea.common.model.SelectionListener;
import com.android.tools.idea.common.model.SelectionModel;
import com.android.tools.idea.common.scene.Scene;
import com.android.tools.idea.common.scene.SceneComponent;
import com.android.tools.idea.common.scene.SceneManager;
import com.android.tools.idea.concurrent.EdtExecutor;
import com.android.tools.idea.configurations.Configuration;
import com.android.tools.idea.configurations.ConfigurationListener;
import com.android.tools.idea.configurations.ConfigurationManager;
import com.android.tools.idea.ui.designer.EditorDesignSurface;
import com.android.tools.idea.uibuilder.editor.NlPreviewForm;
import com.android.tools.idea.uibuilder.model.NlComponentHelperKt;
import com.android.tools.idea.uibuilder.surface.NlDesignSurface;
import com.android.utils.ImmutableCollectors;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeGlassPane;
import com.intellij.psi.xml.XmlTag;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollBar;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.Magnificator;
import com.intellij.util.Alarm;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.MergingUpdateQueue;
import java.awt.AWTEvent;
import java.awt.Adjustable;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.AdjustmentEvent;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.plaf.ScrollBarUI;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A generic design surface for use in a graphical editor.
 */
public abstract class DesignSurface extends EditorDesignSurface implements Disposable, DataProvider {
  private static final Integer LAYER_PROGRESS = JLayeredPane.POPUP_LAYER + 100;

  private final Project myProject;

  protected double myScale = 1;
  @NotNull protected final JScrollPane myScrollPane;
  private final MyLayeredPane myLayeredPane;
  @VisibleForTesting
  @NotNull
  public ImmutableList<Layer> myLayers = ImmutableList.of();
  private final InteractionManager myInteractionManager;
  private final GlassPane myGlassPane;
  protected final List<DesignSurfaceListener> myListeners = new ArrayList<>();
  private List<PanZoomListener> myZoomListeners;
  private final ActionManager myActionManager;
  @NotNull private WeakReference<FileEditor> myFileEditorDelegate = new WeakReference<>(null);
  protected final LinkedHashMap<NlModel, SceneManager> myModelToSceneManagers = new LinkedHashMap<>();

  private final SelectionModel mySelectionModel;
  private final ModelListener myModelListener = new ModelListener() {
    @Override
    public void modelChangedOnLayout(@NotNull NlModel model, boolean animate) {
      repaint();
    }
  };

  protected final IssueModel myIssueModel = new IssueModel();
  private final IssuePanel myIssuePanel;
  private final Object myErrorQueueLock = new Object();
  private MergingUpdateQueue myErrorQueue;
  private boolean myIsActive = false;
  private LintIssueProvider myLintIssueProvider;

  /**
   * Flag to indicate if the surface should resize its content when
   * it's being resized.
   */
  private boolean mySkipResizeContent;

  /**
   * Flag to indicate that the surface should not resize its content
   * on the next resize event.
   */
  private boolean mySkipResizeContentOnce;

  private final ConfigurationListener myConfigurationListener = flags -> {
    if ((flags & (ConfigurationListener.CFG_DEVICE | ConfigurationListener.CFG_DEVICE_STATE)) != 0 && !isLayoutDisabled()) {
      zoom(ZoomType.FIT_INTO);
    }

    return true;
  };
  private ZoomType myCurrentZoomType;

  public DesignSurface(@NotNull Project project, @NotNull SelectionModel selectionModel, @NotNull Disposable parentDisposable) {
    super(new BorderLayout());
    Disposer.register(parentDisposable, this);
    myProject = project;

    setOpaque(true);
    setFocusable(true);
    setRequestFocusEnabled(true);

    mySelectionModel = selectionModel;
    mySelectionModel.addListener(mySelectionListener);
    myInteractionManager = new InteractionManager(this);

    myLayeredPane = new MyLayeredPane();
    myLayeredPane.setBounds(0, 0, 100, 100);
    myGlassPane = new GlassPane();
    myLayeredPane.add(myGlassPane, JLayeredPane.DRAG_LAYER);

    myProgressPanel = new MyProgressPanel();
    myProgressPanel.setName("Layout Editor Progress Panel");
    myLayeredPane.add(myProgressPanel, LAYER_PROGRESS);

    myScrollPane = new MyScrollPane();
    myScrollPane.setViewportView(myLayeredPane);
    myScrollPane.setBorder(null);
    myScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
    myScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
    myScrollPane.getHorizontalScrollBar().addAdjustmentListener(this::notifyPanningChanged);
    myScrollPane.getVerticalScrollBar().addAdjustmentListener(this::notifyPanningChanged);
    myScrollPane.getViewport().setBackground(getBackground());

    myIssuePanel = new IssuePanel(this, myIssueModel);
    Disposer.register(this, myIssuePanel);

    add(myScrollPane);

    // TODO: Do this as part of the layout/validate operation instead
    addComponentListener(new ComponentListener() {
      @Override
      public void componentResized(ComponentEvent componentEvent) {
        if (isShowing() && getWidth() > 0 && getHeight() > 0
            && (!contentResizeSkipped() || getFitScale(false) > myScale)) {
          // We skip the resize only if the flag is set to true
          // and the content size will be increased.
          // Like this, when the issue panel is opened, the content size stays the
          // same but if the user clicked "zoom to fit" while the issue panel was open,
          // we zoom to fit when the panel is closed so the content retake the optimal
          // space.
          zoomToFit();
        }
        else {
          layoutContent();
          updateScrolledAreaSize();
        }
      }

      @Override
      public void componentMoved(ComponentEvent componentEvent) {
      }

      @Override
      public void componentShown(ComponentEvent componentEvent) {
      }

      @Override
      public void componentHidden(ComponentEvent componentEvent) {
      }
    });

    myInteractionManager.startListening();
    //noinspection AbstractMethodCallInConstructor
    myActionManager = createActionManager();
    myActionManager.registerActionsShortcuts(myLayeredPane, this);
  }

  /**
   * @return The scaling factor between Scene coordinates and un-zoomed, un-offset Swing coordinates.
   *
   * TODO: reconsider where this value is stored/who's responsible for providing it. It might make more sense for it to be stored in
   * the Scene or provided by the SceneManager.
   */
  public abstract float getSceneScalingFactor();

  public float getScreenScalingFactor() {
    return 1f;
  }

  // TODO: add self-type parameter DesignSurface?
  @NotNull
  protected abstract ActionManager<? extends DesignSurface> createActionManager();

  @NotNull
  protected abstract SceneManager createSceneManager(@NotNull NlModel model);

  protected abstract void layoutContent();

  @NotNull
  public Project getProject() {
    return myProject;
  }

  @NotNull
  public NlLayoutType getLayoutType() {
    NlModel model = getModel();
    return model == null ? NlLayoutType.UNKNOWN : model.getType();
  }

  @NotNull
  public ActionManager getActionManager() {
    return myActionManager;
  }

  public SelectionModel getSelectionModel() {
    return mySelectionModel;
  }

  @NotNull
  public ItemTransferable getSelectionAsTransferable() {
    NlModel model = getModel();

    ImmutableList<DnDTransferComponent> components =
      getSelectionModel().getSelection().stream()
                         .map(component -> {
                           // TODO: improve this
                           int w = 0;
                           int h = 0;
                           if (this instanceof NlDesignSurface) {
                             w = NlComponentHelperKt.getW(component);
                             h = NlComponentHelperKt.getW(component);
                           }
                           return new DnDTransferComponent(component.getTagName(), component.getTag().getText(), w, h);
                         })
                         .collect(
        ImmutableCollectors.toImmutableList());
    return new ItemTransferable(new DnDTransferItem(model != null ? model.getId() : 0, components));
  }

  /**
   * @return the primary (first) {@link NlModel} if exist. null otherwise.
   * @see #getModels()
   */
  @Nullable
  public NlModel getModel() {
    return Iterables.getFirst(myModelToSceneManagers.keySet(), null);
  }

  /**
   * @return the list of added {@link NlModel}s.
   * @see #getModel()
   */
  @NotNull
  public ImmutableList<NlModel> getModels() {
    return ImmutableList.copyOf(myModelToSceneManagers.keySet());
  }

  /**
   * Add an {@link NlModel} to DesignSurface and return the associated SceneManager.
   * If it is added before then nothing happens.
   * @param model the added {@link NlModel}
   */
  @NotNull
  private SceneManager addModelImpl(@NotNull NlModel model) {
    SceneManager manager = myModelToSceneManagers.get(model);
    // No need to add same model twice.
    if (manager != null) {
      return manager;
    }

    model.addListener(myModelListener);
    model.getConfiguration().addListener(myConfigurationListener);
    manager = createSceneManager(model);
    myModelToSceneManagers.put(model, manager);
    return manager;
  }

  /**
   * Add an {@link NlModel} to DesignSurface. If it is added before then nothing happens.
   * @param model the added {@link NlModel}
   */
  public void addModel(@NotNull NlModel model) {
    addModelImpl(model);

    // We probably do not need to request a render for all models but it is currently the
    // only point subclasses can override to disable the layoutlib render behaviour.
    requestRender()
      .whenCompleteAsync((result, ex) -> {
        reactivateInteractionManager();
        zoomToFit();

        for (DesignSurfaceListener listener : ImmutableList.copyOf(myListeners)) {
          listener.modelChanged(this, model);
        }
      }, EdtExecutor.INSTANCE);
  }

  /**
   * Remove an {@link NlModel} from DesignSurface. If it had not been added before then nothing happens.
   * @param model the {@link NlModel} to remove
   * @returns true if the model existed and was removed
   */
  private boolean removeModelImpl(@NotNull NlModel model) {
    SceneManager manager = myModelToSceneManagers.remove(model);
    if (manager == null) {
      return false;
    }

    model.deactivate(this);

    model.getConfiguration().removeListener(myConfigurationListener);
    model.removeListener(myModelListener);

    // Removed the added layers.
    removeLayers(manager.getLayers());

    Disposer.dispose(manager);
    return true;
  }

  /**
   * Remove an {@link NlModel} from DesignSurface. If it isn't added before then nothing happens.
   * @param model the {@link NlModel} to remove
   */
  public void removeModel(@NotNull NlModel model) {
    if (!removeModelImpl(model)) {
      return;
    }

    reactivateInteractionManager();
    zoomToFit();
    requestRender();
  }

  /**
   * Sets the current {@link NlModel} to DesignSurface.
   * @see #addModel(NlModel)
   * @see #removeModel(NlModel)
   */
  public void setModel(@Nullable NlModel model) {
    NlModel oldModel = getModel();
    if (model == oldModel) {
      return;
    }

    if (oldModel != null) {
      removeModelImpl(oldModel);
    }

    // Should not have any other NlModel in this use case.
    assert myModelToSceneManagers.isEmpty();

    if (model != null) {
      addModelImpl(model);

      requestRender()
        .whenCompleteAsync((result, ex) -> {
          reactivateInteractionManager();
          zoomToFit();

          // TODO: The listeners have the expectation of the call happening in the EDT. We need
          //       to address that.
          for (DesignSurfaceListener listener : ImmutableList.copyOf(myListeners)) {
            listener.modelChanged(this, model);
          }
        }, EdtExecutor.INSTANCE);
      
      if (myIsActive) {
        model.activate(this);
      }
    }
  }

  /**
   * Update the status of {@link InteractionManager}. It will start or stop listening depending on the current layout type.
   */
  private void reactivateInteractionManager() {
    if (getLayoutType().isSupportedByDesigner()) {
      myInteractionManager.startListening();
    }
    else {
      myInteractionManager.stopListening();
    }
  }

  @Override
  public void dispose() {
    myInteractionManager.stopListening();
    for (NlModel model : myModelToSceneManagers.keySet()) {
      model.getConfiguration().removeListener(myConfigurationListener);
      model.removeListener(myModelListener);
    }
  }

  /**
   * @return The new {@link Dimension} of the LayeredPane (SceneView)
   */
  @Nullable
  public abstract Dimension getScrolledAreaSize();

  public void updateScrolledAreaSize() {
    final Dimension dimension = getScrolledAreaSize();
    if (dimension == null) {
      return;
    }
    myLayeredPane.setSize(dimension.width, dimension.height);
    myLayeredPane.setPreferredSize(dimension);
    myScrollPane.revalidate();

    SceneView view = getCurrentSceneView();
    if (view != null) {
      myProgressPanel.setBounds(getContentOriginX(), getContentOriginY(), view.getSize().width, view.getSize().height);
    }
  }

  /**
   * The x (swing) coordinate of the origin of this DesignSurface's content.
   */
  @SwingCoordinate
  public abstract int getContentOriginX();

  /**
   * The y (swing) coordinate of the origin of this DesignSurface's content.
   */
  @SwingCoordinate
  public abstract int getContentOriginY();

  public JComponent getPreferredFocusedComponent() {
    return myGlassPane;
  }

  private Timer myRepaintTimer = new Timer(15, (actionEvent) -> { repaint(); });

  /**
   * Call this to generate repaints
   */
  public void needsRepaint() {
     if (!myRepaintTimer.isRunning()) {
      myRepaintTimer.setRepeats(false);
      myRepaintTimer.start();
    }
  }

  @Override
  protected void paintChildren(Graphics graphics) {
    super.paintChildren(graphics);

    if (isFocusOwner()) {
      graphics.setColor(UIUtil.getFocusedBoundsColor());
      graphics.drawRect(getX(), getY(), getWidth() - 1, getHeight() - 1);
    }
  }

  @Nullable
  public SceneView getCurrentSceneView() {
    SceneManager sceneManager = getSceneManager();
    return sceneManager != null ? sceneManager.getSceneView() : null;
  }

  /**
   * Gives us a chance to change layers behaviour upon drag and drop interaction starting
   */
  public void startDragDropInteraction() {
    for (Layer layer : myLayers) {
      if (layer instanceof SceneLayer) {
        SceneLayer sceneLayer = (SceneLayer)layer;
        if (!sceneLayer.isShowOnHover()) {
          sceneLayer.setShowOnHover(true);
          repaint();
        }
      }
    }
  }

  /**
   * Gives us a chance to change layers behaviour upon drag and drop interaction ending
   */
  public void stopDragDropInteraction() {
    for (Layer layer : myLayers) {
      if (layer instanceof SceneLayer) {
        SceneLayer sceneLayer = (SceneLayer)layer;
        if (sceneLayer.isShowOnHover()) {
          sceneLayer.setShowOnHover(false);
          repaint();
        }
      }
    }
  }

  /**
   * @param dimension the Dimension object to reuse to avoid reallocation
   * @return The total size of all the ScreenViews in the DesignSurface
   */
  @NotNull
  public abstract Dimension getContentSize(@Nullable Dimension dimension);

  public void hover(@SwingCoordinate int x, @SwingCoordinate int y) {
    for (Layer layer : myLayers) {
      layer.hover(x, y);
    }
  }

  /**
   * Execute a zoom on the content. See {@link ZoomType} for the different type of zoom available.
   *
   * @see #zoom(ZoomType, int, int)
   */
  public void zoom(@NotNull ZoomType type) {
    zoom(type, -1, -1);
  }

  /**
   * <p>
   * Execute a zoom on the content. See {@link ZoomType} for the different types of zoom available.
   * </p><p>
   * If type is {@link ZoomType#IN}, zoom toward the given
   * coordinates (relative to {@link #getLayeredPane()})
   *
   * If x or y are negative, zoom toward the center of the viewport.
   * </p>
   *
   * @param type Type of zoom to execute
   * @param x    Coordinate where the zoom will be centered
   * @param y    Coordinate where the zoom will be centered
   */
  public void zoom(@NotNull ZoomType type, @SwingCoordinate int x, @SwingCoordinate int y) {
    SceneView view = getCurrentSceneView();
    if (type == ZoomType.IN && (x < 0 || y < 0)
        && view != null && !getSelectionModel().isEmpty()) {
      Scene scene = getScene();
      if (scene != null) {
        SceneComponent component = scene.getSceneComponent(getSelectionModel().getPrimary());
        if (component != null) {
          x = Coordinates.getSwingXDip(view, component.getCenterX());
          y = Coordinates.getSwingYDip(view, component.getCenterY());
        }
      }
    }
    switch (type) {
      case IN: {
        double currentScale = myScale * getScreenScalingFactor();
        int current = (int)(currentScale * 100);
        double scale = (ZoomType.zoomIn(current) / 100.0) / getScreenScalingFactor();
        setScale(scale, x, y);
        repaint();
        break;
      }
      case OUT: {
        double currentScale = myScale * getScreenScalingFactor();
        int current = (int)(currentScale * 100);
        double scale = (ZoomType.zoomOut(current) / 100.0) / getScreenScalingFactor();
        setScale(scale, x, y);
        repaint();
        break;
      }
      case ACTUAL:
        setScale(1d / getScreenScalingFactor());
        repaint();
        myCurrentZoomType = type;
        break;
      case FIT:
      case FIT_INTO:
        if (getCurrentSceneView() == null) {
          return;
        }

        setScale(getFitScale(type == ZoomType.FIT_INTO));
        repaint();
        myCurrentZoomType = type;
        break;
      default:
      case SCREEN:
        throw new UnsupportedOperationException("Not yet implemented: " + type);
    }
  }

  /**
   */
  protected double getFitScale(boolean fitInto) {
    int availableWidth = myScrollPane.getWidth() - myScrollPane.getVerticalScrollBar().getWidth();
    int availableHeight = myScrollPane.getHeight() - myScrollPane.getHorizontalScrollBar().getHeight();
    return getFitScale(getPreferredContentSize(availableWidth, availableHeight), fitInto);
  }

  /**
   * @param size dimension to fit into the view
   * @param fitInto {@link ZoomType#FIT_INTO}
   * @return The scale to make the content fit the design surface
   */
  protected double getFitScale(@AndroidCoordinate Dimension size, boolean fitInto) {
    // Fit to zoom
    int availableWidth = myScrollPane.getWidth() - myScrollPane.getVerticalScrollBar().getWidth();
    int availableHeight = myScrollPane.getHeight() - myScrollPane.getHorizontalScrollBar().getHeight();
    Dimension padding = getDefaultOffset();
    availableWidth -= padding.width;
    availableHeight -= padding.height;

    double scaleX = size.width == 0 ? 1 : (double)availableWidth / size.width;
    double scaleY = size.height == 0 ? 1 : (double)availableHeight / size.height;
    double scale = Math.min(scaleX, scaleY);
    if (fitInto) {
      double min = 1d / getScreenScalingFactor();
      scale = Math.min(min, scale);
    }
    return scale;
  }

  @SwingCoordinate
  protected abstract Dimension getDefaultOffset();

  @SwingCoordinate
  @NotNull
  protected abstract Dimension getPreferredContentSize(int availableWidth, int availableHeight);

  public void zoomActual() {
    zoom(ZoomType.ACTUAL);
  }

  public void zoomIn() {
    zoom(ZoomType.IN);
  }

  public void zoomOut() {
    zoom(ZoomType.OUT);
  }

  public void zoomToFit() {
    zoom(ZoomType.FIT);
  }

  public double getScale() {
    return myScale;
  }

  public boolean canZoomIn() {
    return getScale() < getMaxScale();
  }

  public boolean canZoomOut() {
    return getScale() > getMinScale();
  }

  public boolean canZoomToFit() {
    return true;
  }

  public void setScrollPosition(@SwingCoordinate int x, @SwingCoordinate int y) {
    setScrollPosition(new Point(x, y));
  }

  /**
   * Sets the offset for the scroll viewer to the specified x and y values
   * The offset will never be less than zero, and never greater that the
   * maximum value allowed by the sizes of the underlying view and the extent.
   * If the zoom factor is large enough that a scroll bars isn't visible,
   * the position will be set to zero.
   */
  public void setScrollPosition(@SwingCoordinate Point p) {
    Dimension extent = myScrollPane.getViewport().getExtentSize();
    Dimension view = myScrollPane.getViewport().getViewSize();

    p.setLocation(Math.max(0, Math.min(view.width - extent.width, p.x)),
                  Math.max(0, Math.min(view.height - extent.height, p.y)));

    myScrollPane.getViewport().setViewPosition(p);
  }

  @SwingCoordinate
  public Point getScrollPosition() {
    return myScrollPane.getViewport().getViewPosition();
  }

  /**
   * Set the scale factor used to multiply the content size.
   *
   * @param scale The scale factor. Can be any value but it will be capped between -1 and 10
   *              (value below 0 means zoom to fit)
   */
  private void setScale(double scale) {
    setScale(scale, -1, -1);
  }

  /**
   * <p>
   * Set the scale factor used to multiply the content size and try to
   * position the viewport such that its center is the closest possible
   * to the provided x and y coordinate in the Viewport's view coordinate system
   * ({@link JViewport#getView()}).
   * </p><p>
   * If x OR y are negative, the scale will be centered toward the center the viewport.
   * </p>
   *
   * @param scale The scale factor. Can be any value but it will be capped between -1 and 10
   *              (value below 0 means zoom to fit)
   * @param x     The X coordinate to center the scale to (in the Viewport's view coordinate system)
   * @param y     The Y coordinate to center the scale to (in the Viewport's view coordinate system)
   */
  @VisibleForTesting(visibility = Visibility.PROTECTED)
  public void setScale(double scale, @SwingCoordinate int x, @SwingCoordinate int y) {
    double newScale = Math.min(Math.max(scale, getMinScale()), getMaxScale());
    if (Math.abs(newScale - myScale) < 0.005) {
      return;
    }
    myCurrentZoomType = null;

    Point oldViewPosition = getScrollPosition();

    if (x < 0 || y < 0) {
      x = oldViewPosition.x + myScrollPane.getWidth() / 2;
      y = oldViewPosition.y + myScrollPane.getHeight() / 2;
    }

    SceneView view = getCurrentSceneView();

    @AndroidDpCoordinate int androidX = 0;
    @AndroidDpCoordinate int androidY = 0;
    if (view != null) {
      androidX = Coordinates.getAndroidXDip(view, x);
      androidY = Coordinates.getAndroidYDip(view, y);
    }

    myScale = newScale;
    layoutContent();
    updateScrolledAreaSize();

    if (view != null) {
      @SwingCoordinate int shiftedX = Coordinates.getSwingXDip(view, androidX);
      @SwingCoordinate int shiftedY = Coordinates.getSwingYDip(view, androidY);
      myScrollPane.getViewport().setViewPosition(new Point(oldViewPosition.x + shiftedX - x, oldViewPosition.y + shiftedY - y));
    }

    notifyScaleChanged();
  }

  /**
   * The minimum scale we'll allow.
   */
  protected double getMinScale() {
    return 0;
  }

  /**
   * The maximum scale we'll allow.
   */
  protected double getMaxScale() {
    return 1;
  }

  private void notifyScaleChanged() {
    if (myZoomListeners != null) {
      for (PanZoomListener myZoomListener : myZoomListeners) {
        myZoomListener.zoomChanged(this);
      }
    }
  }

  private void notifyPanningChanged(AdjustmentEvent adjustmentEvent) {
    if (myZoomListeners != null) {
      for (PanZoomListener myZoomListener : myZoomListeners) {
        myZoomListener.panningChanged(adjustmentEvent);
      }
    }
  }

  @NotNull
  public JComponent getLayeredPane() {
    return myLayeredPane;
  }

  private void notifySelectionListeners(@NotNull List<NlComponent> newSelection) {
    List<DesignSurfaceListener> listeners = Lists.newArrayList(myListeners);
    for (DesignSurfaceListener listener : listeners) {
      listener.componentSelectionChanged(this, newSelection);
    }
  }

  /**
   * @param x the x coordinate of the double click converted to pixels in the Android coordinate system
   * @param y the y coordinate of the double click converted to pixels in the Android coordinate system
   */
  public void notifyComponentActivate(@NotNull NlComponent component, @AndroidCoordinate int x, @AndroidCoordinate int y) {
    notifyComponentActivate(component);
  }

  public void notifyComponentActivate(@NotNull NlComponent component) {
    activatePreferredEditor(component);
  }

  protected void activatePreferredEditor(@NotNull NlComponent component) {
    for (DesignSurfaceListener listener : new ArrayList<>(myListeners)) {
      if (listener.activatePreferredEditor(this, component)) {
        break;
      }
    }
  }

  public void addListener(@NotNull DesignSurfaceListener listener) {
    myListeners.remove(listener); // ensure single registration
    myListeners.add(listener);
  }

  public void removeListener(@NotNull DesignSurfaceListener listener) {
    myListeners.remove(listener);
  }

  private final SelectionListener mySelectionListener = (model, selection) -> {
    if (getCurrentSceneView() != null) {
      notifySelectionListeners(selection);
    }
    else {
      notifySelectionListeners(Collections.emptyList());
    }
  };

  public void addPanZoomListener(PanZoomListener listener) {
    if (myZoomListeners == null) {
      myZoomListeners = Lists.newArrayList();
    }
    else {
      myZoomListeners.remove(listener);
    }
    myZoomListeners.add(listener);
  }

  public void removePanZoomListener(PanZoomListener listener) {
    if (myZoomListeners != null) {
      myZoomListeners.remove(listener);
    }
  }

  /**
   * The editor has been activated
   */
  public void activate() {
    if (!myIsActive) {
      for (NlModel model : myModelToSceneManagers.keySet()) {
        model.activate(this);
      }
    }
    myIsActive = true;
  }

  public void deactivate() {
    if (myIsActive) {
      for (NlModel model : myModelToSceneManagers.keySet()) {
        model.deactivate(this);
      }
    }
    myIsActive = false;

    myInteractionManager.cancelInteraction();
  }

  /**
   * Sets the file editor to which actions like undo/redo will be delegated. This is only needed if this DesignSurface is not a child
   * of a {@link FileEditor} like in the case of {@link NlPreviewForm}.
   * <p>
   * The surface will only keep a {@link WeakReference} to the editor.
   */
  public void setFileEditorDelegate(@Nullable FileEditor fileEditor) {
    myFileEditorDelegate = new WeakReference<>(fileEditor);
  }

  @Nullable
  public SceneView getSceneView(@SwingCoordinate int x, @SwingCoordinate int y) {
    return getCurrentSceneView();
  }

  /**
   * Return the SceneView under the given position
   *
   * @return the SceneView, or null if we are not above one.
   */
  @Nullable
  public SceneView getHoverSceneView(@SwingCoordinate int x, @SwingCoordinate int y) {
    return getCurrentSceneView();
  }

  /**
   * @return the {@link Scene} of {@link SceneManager} associates to primary {@link NlModel}.
   * @see #getSceneManager()
   * @see #getSceneManager(NlModel)
   * @see SceneManager#getScene()
   */
  @Nullable
  public Scene getScene() {
    SceneManager sceneManager = getSceneManager();
    return sceneManager != null ? sceneManager.getScene() : null;
  }

  /**
   * @see #getSceneManager(NlModel)
   */
  @Nullable
  public SceneManager getSceneManager() {
    NlModel model = getModel();
    return model != null ? getSceneManager(model) : null;
  }

  /**
   * @return The {@link SceneManager} associated to the given {@link NlModel}.
   */
  @Nullable
  public SceneManager getSceneManager(@NotNull NlModel model) {
    return myModelToSceneManagers.get(model);
  }

  /**
   * Set to true if the content should automatically
   * resize when its surface is resized.
   * <p>
   * If once is set to true, the skip flag will be reset to false after the first
   * skip. The once flag is ignored if skipLayout is false.
   */
  public void setSkipResizeContent(boolean skipLayout) {
    mySkipResizeContent = skipLayout;
  }

  public void skipContentResizeOnce() {
    mySkipResizeContentOnce = true;
  }

  /**
   * Return true if the content resize should be skipped
   */
  public boolean isSkipContentResize() {
    return mySkipResizeContent || mySkipResizeContentOnce
           || myCurrentZoomType != ZoomType.FIT;
  }

  /**
   * Return true if the content resize step should skipped and reset mySkipResizeContentOnce to
   * false
   */
  protected boolean contentResizeSkipped() {
    boolean skip = isSkipContentResize();
    mySkipResizeContentOnce = false;
    return skip;
  }

  /**
   * This is called before {@link #setModel(NlModel)}. After the returned future completes, we'll wait for smart mode and then invoke
   * {@link #setModel(NlModel)}. If a {@code DesignSurface} needs to do any extra work before the model is set it should be done here.
   */
  public CompletableFuture<?> goingToSetModel(NlModel model) {
    return CompletableFuture.completedFuture(null);
  }

  @NotNull
  public InteractionManager getInteractionManager() {
    return myInteractionManager;
  }

  protected boolean getSupportPinchAndZoom() {
    return true;
  }

  private static class MyScrollPane extends JBScrollPane {
    private MyScrollPane() {
      super(0);
      setupCorners();
    }

    @NotNull
    @Override
    public JScrollBar createVerticalScrollBar() {
      return new MyScrollBar(Adjustable.VERTICAL);
    }

    @NotNull
    @Override
    public JScrollBar createHorizontalScrollBar() {
      return new MyScrollBar(Adjustable.HORIZONTAL);
    }
  }

  private static class MyScrollBar extends JBScrollBar implements IdeGlassPane.TopComponent {
    private ScrollBarUI myPersistentUI;

    private MyScrollBar(@JdkConstants.AdjustableOrientation int orientation) {
      super(orientation);
      setOpaque(false);
    }

    @Override
    public boolean canBePreprocessed(MouseEvent e) {
      return JBScrollPane.canBePreprocessed(e, this);
    }

    @Override
    public void setUI(ScrollBarUI ui) {
      if (myPersistentUI == null) myPersistentUI = ui;
      super.setUI(myPersistentUI);
      setOpaque(false);
    }

    @Override
    public int getUnitIncrement(int direction) {
      return 5;
    }

    @Override
    public int getBlockIncrement(int direction) {
      return 1;
    }
  }

  private class MyLayeredPane extends JLayeredPane implements Magnificator, DataProvider {
    public MyLayeredPane() {
      if (getSupportPinchAndZoom()) {
        // Enable pinching to zoom
        putClientProperty(Magnificator.CLIENT_PROPERTY_KEY, this);
      }
    }

    // ---- Implements Magnificator ----

    @Override
    public Point magnify(double scale, Point at) {
      // Handle screen zooming.
      // Note: This only seems to work (be invoked) on Mac with the Apple JDK (1.6) currently
      setScale(scale * myScale);
      DesignSurface.this.repaint();
      return new Point((int)(at.x * scale), (int)(at.y * scale));
    }

    @Override
    protected void paintComponent(@NotNull Graphics graphics) {
      super.paintComponent(graphics);

      Graphics2D g2d = (Graphics2D)graphics;
      // (x,y) coordinates of the top left corner in the view port
      @SwingCoordinate int tlx = myScrollPane.getHorizontalScrollBar().getValue();
      @SwingCoordinate int tly = myScrollPane.getVerticalScrollBar().getValue();

      paintBackground(g2d, tlx, tly);

      if (getCurrentSceneView() == null) {
        return;
      }

      for (Layer layer : myLayers) {
        if (!layer.isHidden()) {
          layer.paint(g2d);
        }
      }

      if (!getLayoutType().isSupportedByDesigner()) {
        return;
      }

      // Temporary overlays:
      List<Layer> layers = myInteractionManager.getLayers();
      if (layers != null) {
        for (Layer layer : layers) {
          if (!layer.isHidden()) {
            layer.paint(g2d);
          }
        }
      }
    }

    private void paintBackground(@NotNull Graphics2D graphics, @SwingCoordinate int lx, @SwingCoordinate int ly) {
      int width = myScrollPane.getWidth();
      int height = myScrollPane.getHeight();
      graphics.setColor(getBackground());
      graphics.fillRect(lx, ly, width, height);
    }

    @Nullable
    @Override
    public Object getData(@NonNls String dataId) {
      if (CommonDataKeys.PSI_ELEMENT.is(dataId)) {
        if (getCurrentSceneView() != null) {
          SelectionModel selectionModel = getCurrentSceneView().getSelectionModel();
          NlComponent primary = selectionModel.getPrimary();
          if (primary != null) {
            return primary.getTag();
          }
        }
      }
      if (LangDataKeys.PSI_ELEMENT_ARRAY.is(dataId)) {
        if (getCurrentSceneView() != null) {
          SelectionModel selectionModel = getCurrentSceneView().getSelectionModel();
          List<NlComponent> selection = selectionModel.getSelection();
          List<XmlTag> list = Lists.newArrayListWithCapacity(selection.size());
          for (NlComponent component : selection) {
            list.add(component.getTag());
          }
          return list.toArray(XmlTag.EMPTY);
        }
      }
      NlModel model = getModel();
      if (LangDataKeys.MODULE.is(dataId) && model != null) {
        return model.getModule();
      }

      return null;
    }
  }

  private static class GlassPane extends JComponent {
    private static final long EVENT_FLAGS = AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK;

    public GlassPane() {
      enableEvents(EVENT_FLAGS);
    }

    @Override
    public void setEnabled(boolean enabled) {
      super.setEnabled(enabled);
      if (enabled) {
        enableEvents(EVENT_FLAGS);
      }
      else {
        disableEvents(EVENT_FLAGS);
      }
    }

    @Override
    protected void processKeyEvent(KeyEvent event) {
      if (!event.isConsumed()) {
        super.processKeyEvent(event);
      }
    }

    @Override
    protected void processMouseEvent(MouseEvent event) {
      if (event.getID() == MouseEvent.MOUSE_PRESSED) {
        requestFocusInWindow();
      }

      super.processMouseEvent(event);
    }
  }

  private final List<ProgressIndicator> myProgressIndicators = new ArrayList<>();

  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
  private final MyProgressPanel myProgressPanel;

  public void registerIndicator(@NotNull ProgressIndicator indicator) {
    if (myProject.isDisposed() || Disposer.isDisposed(this)) {
      return;
    }

    synchronized (myProgressIndicators) {
      myProgressIndicators.add(indicator);
      myProgressPanel.showProgressIcon();
    }
  }

  public void unregisterIndicator(@NotNull ProgressIndicator indicator) {
    synchronized (myProgressIndicators) {
      myProgressIndicators.remove(indicator);

      if (myProgressIndicators.isEmpty()) {
        myProgressPanel.hideProgressIcon();
      }
    }
  }

  protected boolean useSmallProgressIcon() {
    return true;
  }

  /**
   * Panel which displays the progress icon. The progress icon can either be a large icon in the
   * center, when there is no rendering showing, or a small icon in the upper right corner when there
   * is a rendering. This is necessary because even though the progress icon looks good on some
   * renderings, depending on the layout theme colors it is invisible in other cases.
   */
  private class MyProgressPanel extends JPanel {
    private AsyncProcessIcon mySmallProgressIcon;
    private AsyncProcessIcon myLargeProgressIcon;
    private boolean mySmall;
    private boolean myProgressVisible;

    private MyProgressPanel() {
      super(new BorderLayout());
      setOpaque(false);
      setVisible(false);
    }

    /**
     * The "small" icon mode isn't just for the icon size; it's for the layout position too; see {@link #doLayout}
     */
    private void setSmallIcon(boolean small) {
      if (small != mySmall) {
        if (myProgressVisible && getComponentCount() != 0) {
          AsyncProcessIcon oldIcon = getProgressIcon();
          oldIcon.suspend();
        }
        mySmall = true;
        removeAll();
        AsyncProcessIcon icon = getProgressIcon();
        add(icon, BorderLayout.CENTER);
        if (myProgressVisible) {
          icon.setVisible(true);
          icon.resume();
        }
      }
    }

    public void showProgressIcon() {
      if (!myProgressVisible) {
        setSmallIcon(useSmallProgressIcon());
        myProgressVisible = true;
        setVisible(true);
        AsyncProcessIcon icon = getProgressIcon();
        if (getComponentCount() == 0) { // First time: haven't added icon yet?
          add(getProgressIcon(), BorderLayout.CENTER);
        }
        else {
          icon.setVisible(true);
        }
        icon.resume();
      }
    }

    public void hideProgressIcon() {
      if (myProgressVisible) {
        myProgressVisible = false;
        setVisible(false);
        AsyncProcessIcon icon = getProgressIcon();
        icon.setVisible(false);
        icon.suspend();
      }
    }

    @Override
    public void doLayout() {
      super.doLayout();
      setBackground(JBColor.RED); // make this null instead?

      if (!myProgressVisible) {
        return;
      }

      // Place the progress icon in the center if there's no rendering, and in the
      // upper right corner if there's a rendering. The reason for this is that the icon color
      // will depend on whether we're in a light or dark IDE theme, and depending on the rendering
      // in the layout it will be invisible. For example, in Darcula the icon is white, and if the
      // layout is rendering a white screen, the progress is invisible.
      AsyncProcessIcon icon = getProgressIcon();
      Dimension size = icon.getPreferredSize();
      if (mySmall) {
        icon.setBounds(getWidth() - size.width - 1, 1, size.width, size.height);
      }
      else {
        icon.setBounds(getWidth() / 2 - size.width / 2, getHeight() / 2 - size.height / 2, size.width, size.height);
      }
    }

    @Override
    public Dimension getPreferredSize() {
      return getProgressIcon().getPreferredSize();
    }

    @NotNull
    private AsyncProcessIcon getProgressIcon() {
      return getProgressIcon(mySmall);
    }

    @NotNull
    private AsyncProcessIcon getProgressIcon(boolean small) {
      if (small) {
        if (mySmallProgressIcon == null) {
          mySmallProgressIcon = new AsyncProcessIcon("Android layout rendering");
          Disposer.register(DesignSurface.this, mySmallProgressIcon);
        }
        return mySmallProgressIcon;
      }
      else {
        if (myLargeProgressIcon == null) {
          myLargeProgressIcon = new AsyncProcessIcon.Big("Android layout rendering");
          Disposer.register(DesignSurface.this, myLargeProgressIcon);
        }
        return myLargeProgressIcon;
      }
    }
  }

  /**
   * Invalidates all models and request a render of the layout. This will re-inflate the layout and render it.
   * The result {@link ListenableFuture} will notify when the render has completed.
   */
  @NotNull
  public CompletableFuture<Void> requestRender() {
    if (myModelToSceneManagers.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    return CompletableFuture.allOf(myModelToSceneManagers.values().stream()
                                                         .map(manager -> manager.requestRender())
                                                         .toArray(CompletableFuture[]::new));
  }

  @NotNull
  public JScrollPane getScrollPane() {
    return myScrollPane;
  }

  /**
   * Sets the tooltip for the design surface
   */
  public void setDesignToolTip(@Nullable String text) {
    myLayeredPane.setToolTipText(text);
  }

  @Override
  public Object getData(@NonNls String dataId) {
    if (PlatformDataKeys.FILE_EDITOR.is(dataId)) {
      return myFileEditorDelegate.get();
    }
    else if (PlatformDataKeys.DELETE_ELEMENT_PROVIDER.is(dataId) ||
             PlatformDataKeys.CUT_PROVIDER.is(dataId) ||
             PlatformDataKeys.COPY_PROVIDER.is(dataId) ||
             PlatformDataKeys.PASTE_PROVIDER.is(dataId)) {
      return createActionHandler();
    }
    else if (PlatformDataKeys.CONTEXT_MENU_POINT.is(dataId)) {
      SceneView view = getCurrentSceneView();
      NlComponent selection = getSelectionModel().getPrimary();
      Scene scene = getScene();
      if (view == null || scene == null || selection == null) {
        return null;
      }
      SceneComponent sceneComponent = scene.getSceneComponent(selection);
      if (sceneComponent == null) {
        return null;
      }
      return new Point(Coordinates.getSwingXDip(view, sceneComponent.getCenterX()),
                       Coordinates.getSwingYDip(view, sceneComponent.getCenterY()));
    }
    return myLayeredPane.getData(dataId);
  }

  @NotNull
  abstract protected DesignSurfaceActionHandler createActionHandler();

  /**
   * Returns true we shouldn't currently try to relayout our content (e.g. if some other operations is in progress).
   */
  public abstract boolean isLayoutDisabled();

  @Nullable
  @Override
  public Configuration getConfiguration() {
    return getModel() != null ? getModel().getConfiguration() : null;
  }

  @NotNull
  public IssueModel getIssueModel() {
    return myIssueModel;
  }

  public void setLintAnnotationsModel(@NotNull LintAnnotationsModel model) {
    if (myLintIssueProvider != null) {
      myLintIssueProvider.setLintAnnotationsModel(model);
    }
    else {
      myLintIssueProvider = new LintIssueProvider(model);
      getIssueModel().addIssueProvider(myLintIssueProvider);
    }
  }

  @NotNull
  public IssuePanel getIssuePanel() {
    return myIssuePanel;
  }

  public void setShowIssuePanel(boolean show) {
    UIUtil.invokeLaterIfNeeded(() -> {
      myIssuePanel.setMinimized(!show);
      revalidate();
      repaint();
    });
  }

  @NotNull
  protected MergingUpdateQueue getErrorQueue() {
    synchronized (myErrorQueueLock) {
      if (myErrorQueue == null) {
        myErrorQueue = new MergingUpdateQueue("android.error.computation", 200, true, null, myProject, null,
                                              Alarm.ThreadToUse.POOLED_THREAD);
      }
      return myErrorQueue;
    }
  }


  /**
   * Attaches the given {@link Layer}s to the current design surface.
   */
  public void addLayers(@NotNull ImmutableList<Layer> layers) {
    myLayers = ImmutableList.copyOf(Iterables.concat(myLayers, layers));
  }

  /**
   * Deattaches the given {@link Layer}s to the current design surface
   */
  public void removeLayers(@NotNull ImmutableList<Layer> layers) {
    myLayers = ImmutableList.copyOf((Iterables.filter(myLayers, l -> !layers.contains(l))));
  }

  /**
   * Returns the list of {@link Layer}s attached to this {@link DesignSurface}
   */
  @NotNull
  protected List<Layer> getLayers() {
    return myLayers;
  }

  @Nullable
  public final Interaction createInteractionOnClick(@SwingCoordinate int mouseX, @SwingCoordinate int mouseY) {
    SceneView sceneView = getSceneView(mouseX, mouseY);
    if (sceneView == null) {
      return null;
    }
    return doCreateInteractionOnClick(mouseX, mouseY, sceneView);
  }

  @VisibleForTesting(visibility = Visibility.PROTECTED)
  @Nullable
  public abstract Interaction doCreateInteractionOnClick(@SwingCoordinate int mouseX, @SwingCoordinate int mouseY, @NotNull SceneView view);

  @Nullable
  public abstract Interaction createInteractionOnDrag(@NotNull SceneComponent draggedSceneComponent, @Nullable SceneComponent primary);

  @NotNull
  public ConfigurationManager getConfigurationManager(@NotNull AndroidFacet facet) {
    return ConfigurationManager.getOrCreateInstance(facet);
  }
}
