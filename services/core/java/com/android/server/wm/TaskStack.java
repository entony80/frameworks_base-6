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

package com.android.server.wm;

import android.app.ActivityManager.StackId;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.os.RemoteException;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.ArrayList;

import static android.app.ActivityManager.DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerService.H.RESIZE_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

public class TaskStack implements DimLayer.DimLayerUser,
        BoundsAnimationController.AnimateBoundsUser {

    // If the stack should be resized to fullscreen.
    private static final boolean FULLSCREEN = true;

    /** Unique identifier */
    final int mStackId;

    /** The service */
    private final WindowManagerService mService;

    /** The display this stack sits under. */
    private DisplayContent mDisplayContent;

    /** The Tasks that define this stack. Oldest Tasks are at the bottom. The ordering must match
     * mTaskHistory in the ActivityStack with the same mStackId */
    private final ArrayList<Task> mTasks = new ArrayList<>();

    /** For comparison with DisplayContent bounds. */
    private Rect mTmpRect = new Rect();
    private Rect mTmpRect2 = new Rect();

    /** Content limits relative to the DisplayContent this sits in. */
    private Rect mBounds = new Rect();

    /** Stack bounds adjusted to screen content area (taking into account IM windows, etc.) */
    private final Rect mAdjustedBounds = new Rect();

    /** Whether mBounds is fullscreen */
    private boolean mFullscreen = true;

    // Device rotation as of the last time {@link #mBounds} was set.
    int mRotation;

    /** Support for non-zero {@link android.view.animation.Animation#getBackgroundColor()} */
    DimLayer mAnimationBackgroundSurface;

    /** The particular window with an Animation with non-zero background color. */
    WindowStateAnimator mAnimationBackgroundAnimator;

    /** Application tokens that are exiting, but still on screen for animations. */
    final AppTokenList mExitingAppTokens = new AppTokenList();

    /** Detach this stack from its display when animation completes. */
    boolean mDeferDetach;
    private boolean mUpdateBoundsAfterRotation = false;

    // Whether the stack and all its tasks is currently being drag-resized
    private boolean mDragResizing;

    private final Rect mLastContentBounds = new Rect();
    private final Rect mTmpAdjustedBounds = new Rect();
    private boolean mAdjustedForIme;
    private WindowState mImeWin;
    private float mMinimizeAmount;
    private final int mDockedStackMinimizeThickness;

    TaskStack(WindowManagerService service, int stackId) {
        mService = service;
        mStackId = stackId;
        mDockedStackMinimizeThickness = service.mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_minimize_thickness);
        EventLog.writeEvent(EventLogTags.WM_STACK_CREATED, stackId);
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    ArrayList<Task> getTasks() {
        return mTasks;
    }

    /**
     * Set the bounds of the stack and its containing tasks.
     * @param stackBounds New stack bounds. Passing in null sets the bounds to fullscreen.
     * @param configs Configuration for individual tasks, keyed by task id.
     * @param taskBounds Bounds for individual tasks, keyed by task id.
     * @return True if the stack bounds was changed.
     * */
    boolean setBounds(
            Rect stackBounds, SparseArray<Configuration> configs, SparseArray<Rect> taskBounds,
            SparseArray<Rect> taskTempInsetBounds) {
        if (!setBounds(stackBounds)) {
            return false;
        }

        // Update bounds of containing tasks.
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mTasks.get(taskNdx);
            Configuration config = configs.get(task.mTaskId);
            if (config != null) {
                Rect bounds = taskBounds.get(task.mTaskId);
                if (task.isTwoFingerScrollMode()) {
                    // This is a non-resizeable task that's docked (or side-by-side to the docked
                    // stack). It might have been scrolled previously, and after the stack resizing,
                    // it might no longer fully cover the stack area.
                    // Save the old bounds and re-apply the scroll. This adjusts the bounds to
                    // fit the new stack bounds.
                    task.resizeLocked(bounds, config, false /* forced */);
                    task.getBounds(mTmpRect);
                    task.scrollLocked(mTmpRect);
                } else {
                    task.resizeLocked(bounds, config, false /* forced */);
                    task.setTempInsetBounds(
                            taskTempInsetBounds != null ? taskTempInsetBounds.get(task.mTaskId)
                                    : null);
                }
            } else {
                Slog.wtf(TAG_WM, "No config for task: " + task + ", is there a mismatch with AM?");
            }
        }
        return true;
    }

    void prepareFreezingTaskBounds() {
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mTasks.get(taskNdx);
            task.prepareFreezingBounds();
        }
    }

    boolean isFullscreenBounds(Rect bounds) {
        if (mDisplayContent == null || bounds == null) {
            return true;
        }
        mDisplayContent.getLogicalDisplayRect(mTmpRect);
        return mTmpRect.equals(bounds);
    }

    /**
     * Overrides the adjusted bounds, i.e. sets temporary layout bounds which are different from
     * the normal task bounds.
     *
     * @param bounds The adjusted bounds.
     * @param keepInsets Whether to keep the insets from the original bounds or to calculate new
     *                   ones depending on the adjusted bounds.
     */
    private void setAdjustedBounds(Rect bounds, boolean keepInsets) {
        if (mAdjustedBounds.equals(bounds)) {
            return;
        }

        mAdjustedBounds.set(bounds);
        final boolean adjusted = !mAdjustedBounds.isEmpty();
        alignTasksToAdjustedBounds(adjusted ? mAdjustedBounds : mBounds,
                adjusted && keepInsets ? mBounds : null);
        mDisplayContent.layoutNeeded = true;
    }

    private void alignTasksToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds) {
        if (mFullscreen) {
            return;
        }
        // Update bounds of containing tasks.
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mTasks.get(taskNdx);
            if (task.isTwoFingerScrollMode()) {
                // If we're scrolling we don't care about your bounds or configs,
                // they should be null as if we were in fullscreen.
                task.resizeLocked(null, null, false /* forced */);
                task.getBounds(mTmpRect2);
                task.scrollLocked(mTmpRect2);
            } else if (task.isResizeable() && task.mOverrideConfig != Configuration.EMPTY) {
                task.getBounds(mTmpRect2);
                mTmpRect2.offsetTo(adjustedBounds.left, adjustedBounds.top);
                task.setTempInsetBounds(tempInsetBounds);
                task.resizeLocked(mTmpRect2, task.mOverrideConfig, false /* forced */);
            }
        }
    }

    private boolean setBounds(Rect bounds) {
        boolean oldFullscreen = mFullscreen;
        int rotation = Surface.ROTATION_0;
        if (mDisplayContent != null) {
            mDisplayContent.getLogicalDisplayRect(mTmpRect);
            rotation = mDisplayContent.getDisplayInfo().rotation;
            mFullscreen = bounds == null;
            if (mFullscreen) {
                bounds = mTmpRect;
            }
        }

        if (bounds == null) {
            // Can't set to fullscreen if we don't have a display to get bounds from...
            return false;
        }
        if (mBounds.equals(bounds) && oldFullscreen == mFullscreen && mRotation == rotation) {
            return false;
        }

        if (mDisplayContent != null) {
            mDisplayContent.mDimLayerController.updateDimLayer(this);
            mAnimationBackgroundSurface.setBounds(bounds);
        }

        mBounds.set(bounds);
        mRotation = rotation;

        updateAdjustedBounds();

        return true;
    }

    /** Bounds of the stack without adjusting for other factors in the system like visibility
     * of docked stack.
     * Most callers should be using {@link #getBounds} as it take into consideration other system
     * factors. */
    void getRawBounds(Rect out) {
        out.set(mBounds);
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        if (mFullscreen
                || !StackId.isResizeableByDockedStack(mStackId)
                || mDisplayContent == null
                || mDisplayContent.getDockedStackLocked() != null) {
            return true;
        }
        return false;
    }

    public void getBounds(Rect out) {
        if (useCurrentBounds()) {
            // If we're currently adjusting for IME or minimized docked stack, we use the adjusted
            // bounds; otherwise, no need to adjust the output bounds if fullscreen or the docked
            // stack is visible since it is already what we want to represent to the rest of the
            // system.
            if (!mAdjustedBounds.isEmpty()) {
                out.set(mAdjustedBounds);
            } else {
                out.set(mBounds);
            }
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        mDisplayContent.getLogicalDisplayRect(out);
    }

    /** Bounds of the stack with other system factors taken into consideration. */
    @Override
    public void getDimBounds(Rect out) {
        getBounds(out);
    }

    void updateDisplayInfo(Rect bounds) {
        mUpdateBoundsAfterRotation = false;
        if (mDisplayContent != null) {
            for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
                mTasks.get(taskNdx).updateDisplayInfo(mDisplayContent);
            }
            if (bounds != null) {
                setBounds(bounds);
            } else if (mFullscreen) {
                setBounds(null);
            } else {
                mUpdateBoundsAfterRotation = true;
                mTmpRect2.set(mBounds);
                final int newRotation = mDisplayContent.getDisplayInfo().rotation;
                if (mRotation == newRotation) {
                    setBounds(mTmpRect2);
                }

                // If the rotation changes, we'll handle it in updateBoundsAfterRotation
            }
        }
    }

    /**
     * Updates the bounds after rotating the screen. We can't handle it in
     * {@link #updateDisplayInfo} because at that point the configuration might not be fully updated
     * yet.
     */
    void updateBoundsAfterRotation() {
        if (!mUpdateBoundsAfterRotation) {
            return;
        }
        mUpdateBoundsAfterRotation = false;
        final int newRotation = getDisplayInfo().rotation;
        mDisplayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (mStackId == DOCKED_STACK_ID) {
            repositionDockedStackAfterRotation(mTmpRect2);
            snapDockedStackAfterRotation(mTmpRect2);
        }

        // Post message to inform activity manager of the bounds change simulating
        // a one-way call. We do this to prevent a deadlock between window manager
        // lock and activity manager lock been held.
        mService.mH.sendMessage(mService.mH.obtainMessage(
                RESIZE_STACK, mStackId, 0 /*allowResizeInDockedMode*/, mTmpRect2));
    }

    /**
     * Some dock sides are not allowed by the policy. This method queries the policy and moves
     * the docked stack around if needed.
     *
     * @param inOutBounds the bounds of the docked stack to adjust
     */
    private void repositionDockedStackAfterRotation(Rect inOutBounds) {
        int dockSide = getDockSide(inOutBounds);
        if (mService.mPolicy.isDockSideAllowed(dockSide)) {
            return;
        }
        mDisplayContent.getLogicalDisplayRect(mTmpRect);
        dockSide = DockedDividerUtils.invertDockSide(dockSide);
        switch (dockSide) {
            case DOCKED_LEFT:
                int movement = inOutBounds.left;
                inOutBounds.left -= movement;
                inOutBounds.right -= movement;
                break;
            case DOCKED_RIGHT:
                movement = mTmpRect.right - inOutBounds.right;
                inOutBounds.left += movement;
                inOutBounds.right += movement;
                break;
            case DOCKED_TOP:
                movement = inOutBounds.top;
                inOutBounds.top -= movement;
                inOutBounds.bottom -= movement;
                break;
            case DOCKED_BOTTOM:
                movement = mTmpRect.bottom - inOutBounds.bottom;
                inOutBounds.top += movement;
                inOutBounds.bottom += movement;
                break;
        }
    }

    /**
     * Snaps the bounds after rotation to the closest snap target for the docked stack.
     */
    private void snapDockedStackAfterRotation(Rect outBounds) {

        // Calculate the current position.
        final DisplayInfo displayInfo = mDisplayContent.getDisplayInfo();
        final int dividerSize = mService.getDefaultDisplayContentLocked()
                .getDockedDividerController().getContentWidth();
        final int dockSide = getDockSide(outBounds);
        final int dividerPosition = DockedDividerUtils.calculatePositionForBounds(outBounds,
                dockSide, dividerSize);
        final int displayWidth = mDisplayContent.getDisplayInfo().logicalWidth;
        final int displayHeight = mDisplayContent.getDisplayInfo().logicalHeight;

        // Snap the position to a target.
        final int rotation = displayInfo.rotation;
        final int orientation = mService.mCurConfiguration.orientation;
        mService.mPolicy.getStableInsetsLw(rotation, displayWidth, displayHeight, outBounds);
        final DividerSnapAlgorithm algorithm = new DividerSnapAlgorithm(
                mService.mContext.getResources(), displayWidth, displayHeight,
                dividerSize, orientation == Configuration.ORIENTATION_PORTRAIT, outBounds);
        final SnapTarget target = algorithm.calculateNonDismissingSnapTarget(dividerPosition);

        // Recalculate the bounds based on the position of the target.
        DockedDividerUtils.calculateBoundsForPosition(target.position, dockSide,
                outBounds, displayInfo.logicalWidth, displayInfo.logicalHeight,
                dividerSize);
    }

    boolean isAnimating() {
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<AppWindowToken> activities = mTasks.get(taskNdx).mAppTokens;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ArrayList<WindowState> windows = activities.get(activityNdx).allAppWindows;
                for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                    final WindowStateAnimator winAnimator = windows.get(winNdx).mWinAnimator;
                    if (winAnimator.isAnimating() || winAnimator.mWin.mAnimatingExit) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void addTask(Task task, boolean toTop) {
        addTask(task, toTop, task.showForAllUsers());
    }

    /**
     * Put a Task in this stack. Used for adding and moving.
     * @param task The task to add.
     * @param toTop Whether to add it to the top or bottom.
     * @param showForAllUsers Whether to show the task regardless of the current user.
     */
    void addTask(Task task, boolean toTop, boolean showForAllUsers) {
        positionTask(task, toTop ? mTasks.size() : 0, showForAllUsers);
    }

    void positionTask(Task task, int position, boolean showForAllUsers) {
        final boolean canShowTask =
                showForAllUsers || mService.isCurrentProfileLocked(task.mUserId);
        mTasks.remove(task);
        int stackSize = mTasks.size();
        int minPosition = 0;
        int maxPosition = stackSize;

        if (canShowTask) {
            minPosition = computeMinPosition(minPosition, stackSize);
        } else {
            maxPosition = computeMaxPosition(maxPosition);
        }
        // Reset position based on minimum/maximum possible positions.
        position = Math.min(Math.max(position, minPosition), maxPosition);

        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM,
                "positionTask: task=" + task + " position=" + position);
        mTasks.add(position, task);

        // If we are moving the task across stacks, the scroll is no longer valid.
        if (task.mStack != this) {
            task.resetScrollLocked();
        }
        task.mStack = this;
        task.updateDisplayInfo(mDisplayContent);
        boolean toTop = position == mTasks.size() - 1;
        if (toTop) {
            mDisplayContent.moveStack(this, true);
        }
        EventLog.writeEvent(EventLogTags.WM_TASK_MOVED, task.mTaskId, toTop ? 1 : 0, position);
    }

    /** Calculate the minimum possible position for a task that can be shown to the user.
     *  The minimum position will be above all other tasks that can't be shown.
     *  @param minPosition The minimum position the caller is suggesting.
     *                  We will start adjusting up from here.
     *  @param size The size of the current task list.
     */
    private int computeMinPosition(int minPosition, int size) {
        while (minPosition < size) {
            final Task tmpTask = mTasks.get(minPosition);
            final boolean canShowTmpTask =
                    tmpTask.showForAllUsers()
                            || mService.isCurrentProfileLocked(tmpTask.mUserId);
            if (canShowTmpTask) {
                break;
            }
            minPosition++;
        }
        return minPosition;
    }

    /** Calculate the maximum possible position for a task that can't be shown to the user.
     *  The maximum position will be below all other tasks that can be shown.
     *  @param maxPosition The maximum position the caller is suggesting.
     *                  We will start adjusting down from here.
     */
    private int computeMaxPosition(int maxPosition) {
        while (maxPosition > 0) {
            final Task tmpTask = mTasks.get(maxPosition - 1);
            final boolean canShowTmpTask =
                    tmpTask.showForAllUsers()
                            || mService.isCurrentProfileLocked(tmpTask.mUserId);
            if (!canShowTmpTask) {
                break;
            }
            maxPosition--;
        }
        return maxPosition;
    }

    void moveTaskToTop(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM, "moveTaskToTop: task=" + task + " Callers="
                + Debug.getCallers(6));
        mTasks.remove(task);
        addTask(task, true);
    }

    void moveTaskToBottom(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM, "moveTaskToBottom: task=" + task);
        mTasks.remove(task);
        addTask(task, false);
    }

    /**
     * Delete a Task from this stack. If it is the last Task in the stack, move this stack to the
     * back.
     * @param task The Task to delete.
     */
    void removeTask(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM, "removeTask: task=" + task);
        mTasks.remove(task);
        if (mDisplayContent != null) {
            if (mTasks.isEmpty()) {
                mDisplayContent.moveStack(this, false);
            }
            mDisplayContent.layoutNeeded = true;
        }
        for (int appNdx = mExitingAppTokens.size() - 1; appNdx >= 0; --appNdx) {
            final AppWindowToken wtoken = mExitingAppTokens.get(appNdx);
            if (wtoken.mTask == task) {
                wtoken.mIsExiting = false;
                mExitingAppTokens.remove(appNdx);
            }
        }
    }

    void attachDisplayContent(DisplayContent displayContent) {
        if (mDisplayContent != null) {
            throw new IllegalStateException("attachDisplayContent: Already attached");
        }

        mDisplayContent = displayContent;
        mAnimationBackgroundSurface = new DimLayer(mService, this, mDisplayContent.getDisplayId());

        Rect bounds = null;
        final TaskStack dockedStack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        if (mStackId == DOCKED_STACK_ID
                || (dockedStack != null && StackId.isResizeableByDockedStack(mStackId))) {
            // The existence of a docked stack affects the size of other static stack created since
            // the docked stack occupies a dedicated region on screen.
            bounds = new Rect();
            displayContent.getLogicalDisplayRect(mTmpRect);
            mTmpRect2.setEmpty();
            if (dockedStack != null) {
                dockedStack.getRawBounds(mTmpRect2);
            }
            final boolean dockedOnTopOrLeft = mService.mDockedStackCreateMode
                    == DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
            getStackDockedModeBounds(mTmpRect, bounds, mStackId, mTmpRect2,
                    mDisplayContent.mDividerControllerLocked.getContentWidth(),
                    dockedOnTopOrLeft);
        }

        updateDisplayInfo(bounds);

        if (mStackId == DOCKED_STACK_ID) {
            // Attaching a docked stack to the display affects the size of all other static
            // stacks since the docked stack occupies a dedicated region on screen.
            // Resize existing static stacks so they are pushed to the side of the docked stack.
            resizeNonDockedStacks(!FULLSCREEN, mBounds);
        }
    }

    void getStackDockedModeBoundsLocked(Rect outBounds, boolean ignoreVisibility) {
        if ((mStackId != DOCKED_STACK_ID && !StackId.isResizeableByDockedStack(mStackId))
                || mDisplayContent == null) {
            outBounds.set(mBounds);
            return;
        }

        final TaskStack dockedStack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        if (dockedStack == null) {
            // Not sure why you are calling this method when there is no docked stack...
            throw new IllegalStateException(
                    "Calling getStackDockedModeBoundsLocked() when there is no docked stack.");
        }
        if (!ignoreVisibility && !dockedStack.isVisibleLocked()) {
            // The docked stack is being dismissed, but we caught before it finished being
            // dismissed. In that case we want to treat it as if it is not occupying any space and
            // let others occupy the whole display.
            mDisplayContent.getLogicalDisplayRect(outBounds);
            return;
        }

        final int dockedSide = dockedStack.getDockSide();
        if (dockedSide == DOCKED_INVALID) {
            // Not sure how you got here...Only thing we can do is return current bounds.
            Slog.e(TAG_WM, "Failed to get valid docked side for docked stack=" + dockedStack);
            outBounds.set(mBounds);
            return;
        }

        mDisplayContent.getLogicalDisplayRect(mTmpRect);
        dockedStack.getRawBounds(mTmpRect2);
        final boolean dockedOnTopOrLeft = dockedSide == DOCKED_TOP || dockedSide == DOCKED_LEFT;
        getStackDockedModeBounds(mTmpRect, outBounds, mStackId, mTmpRect2,
                mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);

    }

    /**
     * Outputs the bounds a stack should be given the presence of a docked stack on the display.
     * @param displayRect The bounds of the display the docked stack is on.
     * @param outBounds Output bounds that should be used for the stack.
     * @param stackId Id of stack we are calculating the bounds for.
     * @param dockedBounds Bounds of the docked stack.
     * @param dockDividerWidth We need to know the width of the divider make to the output bounds
     *                         close to the side of the dock.
     * @param dockOnTopOrLeft If the docked stack is on the top or left side of the screen.
     */
    private void getStackDockedModeBounds(
            Rect displayRect, Rect outBounds, int stackId, Rect dockedBounds, int dockDividerWidth,
            boolean dockOnTopOrLeft) {
        final boolean dockedStack = stackId == DOCKED_STACK_ID;
        final boolean splitHorizontally = displayRect.width() > displayRect.height();

        outBounds.set(displayRect);
        if (dockedStack) {
            if (mService.mDockedStackCreateBounds != null) {
                outBounds.set(mService.mDockedStackCreateBounds);
                return;
            }

            // The initial bounds of the docked stack when it is created about half the screen space
            // and its bounds can be adjusted after that. The bounds of all other stacks are
            // adjusted to occupy whatever screen space the docked stack isn't occupying.
            final DisplayInfo di = mDisplayContent.getDisplayInfo();
            mService.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                    mTmpRect2);
            final int position = new DividerSnapAlgorithm(mService.mContext.getResources(),
                    di.logicalWidth,
                    di.logicalHeight,
                    dockDividerWidth,
                    mService.mCurConfiguration.orientation == ORIENTATION_PORTRAIT,
                    mTmpRect2).getMiddleTarget().position;

            if (dockOnTopOrLeft) {
                if (splitHorizontally) {
                    outBounds.right = position;
                } else {
                    outBounds.bottom = position;
                }
            } else {
                if (splitHorizontally) {
                    outBounds.left = position - dockDividerWidth;
                } else {
                    outBounds.top = position - dockDividerWidth;
                }
            }
            return;
        }

        // Other stacks occupy whatever space is left by the docked stack.
        if (!dockOnTopOrLeft) {
            if (splitHorizontally) {
                outBounds.right = dockedBounds.left - dockDividerWidth;
            } else {
                outBounds.bottom = dockedBounds.top - dockDividerWidth;
            }
        } else {
            if (splitHorizontally) {
                outBounds.left = dockedBounds.right + dockDividerWidth;
            } else {
                outBounds.top = dockedBounds.bottom + dockDividerWidth;
            }
        }
        DockedDividerUtils.sanitizeStackBounds(outBounds, !dockOnTopOrLeft);
    }

    /** Resizes all non-docked stacks in the system to either fullscreen or the appropriate size
     * based on the presence of a docked stack.
     * @param fullscreen If true the stacks will be resized to fullscreen, else they will be
     *                   resized to the appropriate size based on the presence of a docked stack.
     * @param dockedBounds Bounds of the docked stack.
     */
    private void resizeNonDockedStacks(boolean fullscreen, Rect dockedBounds) {
        // Not using mTmpRect because we are posting the object in a message.
        final Rect bounds = new Rect();
        mDisplayContent.getLogicalDisplayRect(bounds);
        if (!fullscreen) {
            final boolean dockedOnTopOrLeft = mService.mDockedStackCreateMode
                    == DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;
            getStackDockedModeBounds(bounds, bounds, FULLSCREEN_WORKSPACE_STACK_ID, dockedBounds,
                    mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
        }

        final int count = mService.mStackIdToStack.size();
        for (int i = 0; i < count; i++) {
            final TaskStack otherStack = mService.mStackIdToStack.valueAt(i);
            final int otherStackId = otherStack.mStackId;
            if (StackId.isResizeableByDockedStack(otherStackId)
                    && !otherStack.mBounds.equals(bounds)) {
                mService.mH.sendMessage(
                        mService.mH.obtainMessage(RESIZE_STACK, otherStackId,
                                1 /*allowResizeInDockedMode*/, fullscreen ? null : bounds));
            }
        }
    }

    void resetDockedStackToMiddle() {
        if (mStackId != DOCKED_STACK_ID) {
            throw new IllegalStateException("Not a docked stack=" + this);
        }

        mService.mDockedStackCreateBounds = null;

        final Rect bounds = new Rect();
        getStackDockedModeBoundsLocked(bounds, true /*ignoreVisibility*/);
        mService.mH.obtainMessage(RESIZE_STACK, DOCKED_STACK_ID,
                1 /*allowResizeInDockedMode*/, bounds).sendToTarget();
    }

    void detachDisplay() {
        EventLog.writeEvent(EventLogTags.WM_STACK_REMOVED, mStackId);

        boolean doAnotherLayoutPass = false;
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final AppTokenList appWindowTokens = mTasks.get(taskNdx).mAppTokens;
            for (int appNdx = appWindowTokens.size() - 1; appNdx >= 0; --appNdx) {
                final WindowList appWindows = appWindowTokens.get(appNdx).allAppWindows;
                for (int winNdx = appWindows.size() - 1; winNdx >= 0; --winNdx) {
                    // We are in the middle of changing the state of displays/stacks/tasks. We need
                    // to finish that, before we let layout interfere with it.
                    mService.removeWindowLocked(appWindows.get(winNdx));
                    doAnotherLayoutPass = true;
                }
            }
        }
        if (doAnotherLayoutPass) {
            mService.mWindowPlacerLocked.requestTraversal();
        }

        if (mStackId == DOCKED_STACK_ID) {
            // Docked stack was detached from the display, so we no longer need to restrict the
            // region of the screen other static stacks occupy. Go ahead and make them fullscreen.
            resizeNonDockedStacks(FULLSCREEN, null);
        }

        close();
    }

    void resetAnimationBackgroundAnimator() {
        mAnimationBackgroundAnimator = null;
        mAnimationBackgroundSurface.hide();
    }

    void setAnimationBackground(WindowStateAnimator winAnimator, int color) {
        int animLayer = winAnimator.mAnimLayer;
        if (mAnimationBackgroundAnimator == null
                || animLayer < mAnimationBackgroundAnimator.mAnimLayer) {
            mAnimationBackgroundAnimator = winAnimator;
            animLayer = mService.adjustAnimationBackground(winAnimator);
            mAnimationBackgroundSurface.show(animLayer - WindowManagerService.LAYER_OFFSET_DIM,
                    ((color >> 24) & 0xff) / 255f, 0);
        }
    }

    void switchUser() {
        int top = mTasks.size();
        for (int taskNdx = 0; taskNdx < top; ++taskNdx) {
            Task task = mTasks.get(taskNdx);
            if (mService.isCurrentProfileLocked(task.mUserId) || task.showForAllUsers()) {
                mTasks.remove(taskNdx);
                mTasks.add(task);
                --top;
            }
        }
    }

    void close() {
        if (mAnimationBackgroundSurface != null) {
            mAnimationBackgroundSurface.destroySurface();
            mAnimationBackgroundSurface = null;
        }
        mDisplayContent = null;
    }

    /**
     * Adjusts the stack bounds if the IME is visible.
     *
     * @param imeWin The IME window.
     */
    void setAdjustedForIme(WindowState imeWin) {
        mAdjustedForIme = true;
        mImeWin = imeWin;
        updateAdjustedBounds();
    }

    /**
     * Resets the adjustment after it got adjusted for the IME.
     */
    void resetAdjustedForIme() {
        mAdjustedForIme = false;
        mImeWin = null;
        updateAdjustedBounds();
    }

    /**
     * Sets the amount how much we currently minimize our stack.
     *
     * @param minimizeAmount The amount, between 0 and 1.
     * @return Whether the amount has changed and a layout is needed.
     */
    boolean setAdjustedForMinimizedDock(float minimizeAmount) {
        if (minimizeAmount != mMinimizeAmount) {
            mMinimizeAmount = minimizeAmount;
            updateAdjustedBounds();
            return isVisibleForUserLocked();
        } else {
            return false;
        }
    }

    private boolean adjustForIME(final WindowState imeWin) {
        final int dockedSide = getDockSide();
        final boolean dockedTopOrBottom = dockedSide == DOCKED_TOP || dockedSide == DOCKED_BOTTOM;
        final Rect adjustedBounds = mTmpAdjustedBounds;
        if (imeWin == null || !dockedTopOrBottom) {
            return false;
        }

        final Rect displayContentRect = mTmpRect;
        final Rect contentBounds = mTmpRect2;

        // Calculate the content bounds excluding the area occupied by IME
        getDisplayContent().getContentRect(displayContentRect);
        contentBounds.set(displayContentRect);
        int imeTop = Math.max(imeWin.getDisplayFrameLw().top, contentBounds.top);
        imeTop += imeWin.getGivenContentInsetsLw().top;
        if (contentBounds.bottom > imeTop) {
            contentBounds.bottom = imeTop;
        }

        // If content bounds not changing, nothing to do.
        if (mLastContentBounds.equals(contentBounds)) {
            return true;
        }

        // Content bounds changed, need to apply adjustments depending on dock sides.
        mLastContentBounds.set(contentBounds);
        adjustedBounds.set(mBounds);
        final int yOffset = displayContentRect.bottom - contentBounds.bottom;

        if (dockedSide == DOCKED_TOP) {
            // If this stack is docked on top, we make it smaller so the bottom stack is not
            // occluded by IME. We shift its bottom up by the height of the IME (capped by
            // the display content rect). Note that we don't change the task bounds.
            adjustedBounds.bottom = Math.max(
                    adjustedBounds.bottom - yOffset, displayContentRect.top);
        } else {
            // If this stack is docked on bottom, we shift it up so that it's not occluded by
            // IME. We try to move it up by the height of the IME window (although the best
            // we could do is to make the top stack fully collapsed).
            final int dividerWidth = getDisplayContent().mDividerControllerLocked
                    .getContentWidth();
            adjustedBounds.top = Math.max(
                    adjustedBounds.top - yOffset, displayContentRect.top + dividerWidth);
            adjustedBounds.bottom = adjustedBounds.top + mBounds.height();
        }
        return true;
    }

    private boolean adjustForMinimizedDockedStack(float minimizeAmount) {
        final int dockSide = getDockSide();
        if (dockSide == DOCKED_INVALID && !mTmpAdjustedBounds.isEmpty()) {
            return false;
        }

        if (dockSide == DOCKED_TOP) {
            mService.getStableInsetsLocked(mTmpRect);
            int topInset = mTmpRect.top;
            mTmpAdjustedBounds.set(mBounds);
            mTmpAdjustedBounds.bottom =
                    (int) (minimizeAmount * topInset + (1 - minimizeAmount) * mBounds.bottom);
        } else if (dockSide == DOCKED_LEFT) {
            mTmpAdjustedBounds.set(mBounds);
            mTmpAdjustedBounds.right =
                    (int) (minimizeAmount * mDockedStackMinimizeThickness
                            + (1 - minimizeAmount) * mBounds.right);
        } else if (dockSide == DOCKED_RIGHT) {
            mTmpAdjustedBounds.set(mBounds);
            mTmpAdjustedBounds.left =
                    (int) (minimizeAmount * (mBounds.right - mDockedStackMinimizeThickness)
                            + (1 - minimizeAmount) * mBounds.left);
        }
        return true;
    }

    /**
     * Updates the adjustment depending on it's current state.
     */
    void updateAdjustedBounds() {
        boolean adjust = false;
        if (mMinimizeAmount != 0f) {
            adjust = adjustForMinimizedDockedStack(mMinimizeAmount);
        } else if (mAdjustedForIme) {
            adjust = adjustForIME(mImeWin);
        }
        if (!adjust) {
            mTmpAdjustedBounds.setEmpty();
            mLastContentBounds.setEmpty();
        }
        setAdjustedBounds(mTmpAdjustedBounds, isAdjustedForMinimizedDockedStack());
    }

    boolean isAdjustedForMinimizedDockedStack() {
        return mMinimizeAmount != 0f;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "mStackId=" + mStackId);
        pw.println(prefix + "mDeferDetach=" + mDeferDetach);
        pw.println(prefix + "mFullscreen=" + mFullscreen);
        pw.println(prefix + "mBounds=" + mBounds.toShortString());
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; taskNdx--) {
            mTasks.get(taskNdx).dump(prefix + "  ", pw);
        }
        if (mAnimationBackgroundSurface.isDimming()) {
            pw.println(prefix + "mWindowAnimationBackgroundSurface:");
            mAnimationBackgroundSurface.printTo(prefix + "  ", pw);
        }
        if (!mExitingAppTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i = mExitingAppTokens.size() - 1; i >= 0; i--) {
                WindowToken token = mExitingAppTokens.get(i);
                pw.print("  Exiting App #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ");
            }
        }
    }

    /** Fullscreen status of the stack without adjusting for other factors in the system like
     * visibility of docked stack.
     * Most callers should be using {@link #isFullscreen} as it take into consideration other
     * system factors. */
    boolean getRawFullscreen() {
        return mFullscreen;
    }

    @Override
    public boolean isFullscreen() {
        if (useCurrentBounds()) {
            return mFullscreen;
        }
        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return true;
    }

    @Override
    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    @Override
    public String toString() {
        return "{stackId=" + mStackId + " tasks=" + mTasks + "}";
    }

    @Override
    public String toShortString() {
        return "Stack=" + mStackId;
    }

    /**
     * For docked workspace (or workspace that's side-by-side to the docked), provides
     * information which side of the screen was the dock anchored.
     */
    int getDockSide() {
        return getDockSide(mBounds);
    }

    int getDockSide(Rect bounds) {
        if (mStackId != DOCKED_STACK_ID && !StackId.isResizeableByDockedStack(mStackId)) {
            return DOCKED_INVALID;
        }
        if (mDisplayContent == null) {
            return DOCKED_INVALID;
        }
        mDisplayContent.getLogicalDisplayRect(mTmpRect);
        final int orientation = mService.mCurConfiguration.orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // Portrait mode, docked either at the top or the bottom.
            if (bounds.top - mTmpRect.top <= mTmpRect.bottom - bounds.bottom) {
                return DOCKED_TOP;
            } else {
                return DOCKED_BOTTOM;
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape mode, docked either on the left or on the right.
            if (bounds.left - mTmpRect.left <= mTmpRect.right - bounds.right) {
                return DOCKED_LEFT;
            } else {
                return DOCKED_RIGHT;
            }
        } else {
            return DOCKED_INVALID;
        }
    }

    boolean isVisibleLocked() {
        final boolean keyguardOn = mService.mPolicy.isKeyguardShowingOrOccluded()
                && !mService.mAnimator.mKeyguardGoingAway;
        if (keyguardOn && !StackId.isAllowedOverLockscreen(mStackId)) {
            // The keyguard is showing and the stack shouldn't show on top of the keyguard.
            return false;
        }

        for (int i = mTasks.size() - 1; i >= 0; i--) {
            final Task task = mTasks.get(i);
            for (int j = task.mAppTokens.size() - 1; j >= 0; j--) {
                if (!task.mAppTokens.get(j).hidden) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * @return true if a the stack is visible for the current in user, ignoring any other visibility
     *         aspects, and false otherwise
     */
    boolean isVisibleForUserLocked() {
        for (int i = mTasks.size() - 1; i >= 0; i--) {
            final Task task = mTasks.get(i);
            if (task.isVisibleForUser()) {
                return true;
            }
        }
        return false;
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    private void setDragResizingLocked(boolean resizing) {
        if (mDragResizing == resizing) {
            return;
        }
        mDragResizing = resizing;
        for (int i = mTasks.size() - 1; i >= 0 ; i--) {
            mTasks.get(i).resetDragResizingChangeReported();
        }
    }

    @Override  // AnimatesBounds
    public boolean setSize(Rect bounds) {
        synchronized (mService.mWindowMap) {
            if (mDisplayContent == null) {
                return false;
            }
        }
        try {
            mService.mActivityManager.resizeStack(mStackId, bounds, false, true, false);
        } catch (RemoteException e) {
        }
        return true;
    }

    @Override  // AnimatesBounds
    public void onAnimationStart() {
        synchronized (mService.mWindowMap) {
            setDragResizingLocked(true);
        }
    }

    @Override  // AnimatesBounds
    public void onAnimationEnd() {
        synchronized (mService.mWindowMap) {
            setDragResizingLocked(false);
            mService.requestTraversal();
        }
        if (mStackId == PINNED_STACK_ID) {
            try {
                mService.mActivityManager.notifyPinnedStackAnimationEnded();
            } catch (RemoteException e) {
                // I don't believe you...
            }
        }
    }

    @Override
    public void moveToFullscreen() {
        try {
            mService.mActivityManager.moveTasksToFullscreenStack(mStackId, true);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void getFullScreenBounds(Rect bounds) {
        getDisplayContent().getContentRect(bounds);
    }
}