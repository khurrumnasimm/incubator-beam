/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.util;

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.Trigger;
import org.apache.beam.sdk.transforms.windowing.Trigger.MergingTriggerInfo;
import org.apache.beam.sdk.transforms.windowing.Trigger.TriggerInfo;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.state.MergingStateAccessor;
import org.apache.beam.sdk.util.state.State;
import org.apache.beam.sdk.util.state.StateAccessor;
import org.apache.beam.sdk.util.state.StateInternals;
import org.apache.beam.sdk.util.state.StateNamespace;
import org.apache.beam.sdk.util.state.StateNamespaces;
import org.apache.beam.sdk.util.state.StateTag;
import org.joda.time.Instant;

/**
 * Factory for creating instances of the various {@link Trigger} contexts.
 *
 * <p>These contexts are highly interdependent and share many fields; it is inadvisable
 * to create them via any means other than this factory class.
 */
public class TriggerContextFactory<W extends BoundedWindow> {

  private final WindowFn<?, W> windowFn;
  private StateInternals<?> stateInternals;
  private final Coder<W> windowCoder;

  public TriggerContextFactory(WindowFn<?, W> windowFn,
      StateInternals<?> stateInternals, ActiveWindowSet<W> activeWindows) {
    // Future triggers may be able to exploit the active window to state address window mapping.
    this.windowFn = windowFn;
    this.stateInternals = stateInternals;
    this.windowCoder = windowFn.windowCoder();
  }

  public Trigger.TriggerContext base(W window, Timers timers,
      ExecutableTrigger rootTrigger, FinishedTriggers finishedSet) {
    return new TriggerContextImpl(window, timers, rootTrigger, finishedSet);
  }

  public Trigger.OnElementContext createOnElementContext(
      W window, Timers timers, Instant elementTimestamp,
      ExecutableTrigger rootTrigger, FinishedTriggers finishedSet) {
    return new OnElementContextImpl(window, timers, rootTrigger, finishedSet, elementTimestamp);
  }

  public Trigger.OnMergeContext createOnMergeContext(W window, Timers timers,
      ExecutableTrigger rootTrigger, FinishedTriggers finishedSet,
      Map<W, FinishedTriggers> finishedSets) {
    return new OnMergeContextImpl(window, timers, rootTrigger, finishedSet, finishedSets);
  }

  public StateAccessor<?> createStateAccessor(W window, ExecutableTrigger trigger) {
    return new StateAccessorImpl(window, trigger);
  }

  public MergingStateAccessor<?, W> createMergingStateAccessor(
      W mergeResult, Collection<W> mergingWindows, ExecutableTrigger trigger) {
    return new MergingStateAccessorImpl(trigger, mergingWindows, mergeResult);
  }

  private class TriggerInfoImpl implements Trigger.TriggerInfo {

    protected final ExecutableTrigger trigger;
    protected final FinishedTriggers finishedSet;
    private final Trigger.TriggerContext context;

    public TriggerInfoImpl(ExecutableTrigger trigger, FinishedTriggers finishedSet,
        Trigger.TriggerContext context) {
      this.trigger = trigger;
      this.finishedSet = finishedSet;
      this.context = context;
    }

    @Override
    public boolean isMerging() {
      return !windowFn.isNonMerging();
    }

    @Override
    public Iterable<ExecutableTrigger> subTriggers() {
      return trigger.subTriggers();
    }

    @Override
    public ExecutableTrigger subTrigger(int subtriggerIndex) {
      return trigger.subTriggers().get(subtriggerIndex);
    }

    @Override
    public boolean isFinished() {
      return finishedSet.isFinished(trigger);
    }

    @Override
    public boolean isFinished(int subtriggerIndex) {
      return finishedSet.isFinished(subTrigger(subtriggerIndex));
    }

    @Override
    public boolean areAllSubtriggersFinished() {
      return Iterables.isEmpty(unfinishedSubTriggers());
    }

    @Override
    public Iterable<ExecutableTrigger> unfinishedSubTriggers() {
      return FluentIterable
          .from(trigger.subTriggers())
          .filter(new Predicate<ExecutableTrigger>() {
            @Override
            public boolean apply(ExecutableTrigger trigger) {
              return !finishedSet.isFinished(trigger);
            }
          });
    }

    @Override
    public ExecutableTrigger firstUnfinishedSubTrigger() {
      for (ExecutableTrigger subTrigger : trigger.subTriggers()) {
        if (!finishedSet.isFinished(subTrigger)) {
          return subTrigger;
        }
      }
      return null;
    }

    @Override
    public void resetTree() throws Exception {
      finishedSet.clearRecursively(trigger);
      trigger.invokeClear(context);
    }

    @Override
    public void setFinished(boolean finished) {
      finishedSet.setFinished(trigger, finished);
    }

    @Override
    public void setFinished(boolean finished, int subTriggerIndex) {
      finishedSet.setFinished(subTrigger(subTriggerIndex), finished);
    }
  }

  private class TriggerTimers implements Timers {

    private final Timers timers;
    private final W window;

    public TriggerTimers(W window, Timers timers) {
      this.timers = timers;
      this.window = window;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain timeDomain) {
      timers.setTimer(timestamp, timeDomain);
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain timeDomain) {
      if (timeDomain == TimeDomain.EVENT_TIME
          && timestamp.equals(window.maxTimestamp())) {
        // Don't allow triggers to unset the at-max-timestamp timer. This is necessary for on-time
        // state transitions.
        return;
      }
      timers.deleteTimer(timestamp, timeDomain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }

  private class MergingTriggerInfoImpl
      extends TriggerInfoImpl implements Trigger.MergingTriggerInfo {

    private final Map<W, FinishedTriggers> finishedSets;

    public MergingTriggerInfoImpl(
        ExecutableTrigger trigger,
        FinishedTriggers finishedSet,
        Trigger.TriggerContext context,
        Map<W, FinishedTriggers> finishedSets) {
      super(trigger, finishedSet, context);
      this.finishedSets = finishedSets;
    }

    @Override
    public boolean finishedInAnyMergingWindow() {
      for (FinishedTriggers finishedSet : finishedSets.values()) {
        if (finishedSet.isFinished(trigger)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean finishedInAllMergingWindows() {
      for (FinishedTriggers finishedSet : finishedSets.values()) {
        if (!finishedSet.isFinished(trigger)) {
          return false;
        }
      }
      return true;
    }
  }

  private class StateAccessorImpl implements StateAccessor<Object> {
    protected final int triggerIndex;
    protected final StateNamespace windowNamespace;

    public StateAccessorImpl(
        W window,
        ExecutableTrigger trigger) {
      this.triggerIndex = trigger.getTriggerIndex();
      this.windowNamespace = namespaceFor(window);
    }

    protected StateNamespace namespaceFor(W window) {
      return StateNamespaces.windowAndTrigger(windowCoder, window, triggerIndex);
    }

    @Override
    public <StateT extends State> StateT access(StateTag<? super Object, StateT> address) {
      return stateInternals.state(windowNamespace, address);
    }
  }

  private class MergingStateAccessorImpl extends StateAccessorImpl
  implements MergingStateAccessor<Object, W> {
    private final Collection<W> activeToBeMerged;

    public MergingStateAccessorImpl(ExecutableTrigger trigger, Collection<W> activeToBeMerged,
        W mergeResult) {
      super(mergeResult, trigger);
      this.activeToBeMerged = activeToBeMerged;
    }

    @Override
    public <StateT extends State> StateT access(
        StateTag<? super Object, StateT> address) {
      return stateInternals.state(windowNamespace, address);
    }

    @Override
    public <StateT extends State> Map<W, StateT> accessInEachMergingWindow(
        StateTag<? super Object, StateT> address) {
      ImmutableMap.Builder<W, StateT> builder = ImmutableMap.builder();
      for (W mergingWindow : activeToBeMerged) {
        StateT stateForWindow = stateInternals.state(namespaceFor(mergingWindow), address);
        builder.put(mergingWindow, stateForWindow);
      }
      return builder.build();
    }
  }

  private class TriggerContextImpl extends Trigger.TriggerContext {

    private final W window;
    private final StateAccessorImpl state;
    private final Timers timers;
    private final TriggerInfoImpl triggerInfo;

    private TriggerContextImpl(
        W window,
        Timers timers,
        ExecutableTrigger trigger,
        FinishedTriggers finishedSet) {
      trigger.getSpec().super();
      this.window = window;
      this.state = new StateAccessorImpl(window, trigger);
      this.timers = new TriggerTimers(window, timers);
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
    }

    @Override
    public Trigger.TriggerContext forTrigger(ExecutableTrigger trigger) {
      return new TriggerContextImpl(window, timers, trigger, triggerInfo.finishedSet);
    }

    @Override
    public TriggerInfo trigger() {
      return triggerInfo;
    }

    @Override
    public StateAccessor<?> state() {
      return state;
    }

    @Override
    public W window() {
      return window;
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain domain) {
      timers.deleteTimer(timestamp, domain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }

  private class OnElementContextImpl extends Trigger.OnElementContext {

    private final W window;
    private final StateAccessorImpl state;
    private final Timers timers;
    private final TriggerInfoImpl triggerInfo;
    private final Instant eventTimestamp;

    private OnElementContextImpl(
        W window,
        Timers timers,
        ExecutableTrigger trigger,
        FinishedTriggers finishedSet,
        Instant eventTimestamp) {
      trigger.getSpec().super();
      this.window = window;
      this.state = new StateAccessorImpl(window, trigger);
      this.timers = new TriggerTimers(window, timers);
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
      this.eventTimestamp = eventTimestamp;
    }


    @Override
    public Instant eventTimestamp() {
      return eventTimestamp;
    }

    @Override
    public Trigger.OnElementContext forTrigger(ExecutableTrigger trigger) {
      return new OnElementContextImpl(
          window, timers, trigger, triggerInfo.finishedSet, eventTimestamp);
    }

    @Override
    public TriggerInfo trigger() {
      return triggerInfo;
    }

    @Override
    public StateAccessor<?> state() {
      return state;
    }

    @Override
    public W window() {
      return window;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain domain) {
      timers.setTimer(timestamp, domain);
    }


    @Override
    public void deleteTimer(Instant timestamp, TimeDomain domain) {
      timers.deleteTimer(timestamp, domain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }

  private class OnMergeContextImpl extends Trigger.OnMergeContext {
    private final MergingStateAccessor<?, W> state;
    private final W window;
    private final Collection<W> mergingWindows;
    private final Timers timers;
    private final MergingTriggerInfoImpl triggerInfo;

    private OnMergeContextImpl(
        W window,
        Timers timers,
        ExecutableTrigger trigger,
        FinishedTriggers finishedSet,
        Map<W, FinishedTriggers> finishedSets) {
      trigger.getSpec().super();
      this.mergingWindows = finishedSets.keySet();
      this.window = window;
      this.state = new MergingStateAccessorImpl(trigger, mergingWindows, window);
      this.timers = new TriggerTimers(window, timers);
      this.triggerInfo = new MergingTriggerInfoImpl(trigger, finishedSet, this, finishedSets);
    }

    @Override
    public Trigger.OnMergeContext forTrigger(ExecutableTrigger trigger) {
      return new OnMergeContextImpl(
          window, timers, trigger, triggerInfo.finishedSet, triggerInfo.finishedSets);
    }

    @Override
    public MergingStateAccessor<?, W> state() {
      return state;
    }

    @Override
    public MergingTriggerInfo trigger() {
      return triggerInfo;
    }

    @Override
    public W window() {
      return window;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain domain) {
      timers.setTimer(timestamp, domain);
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain domain) {
      timers.setTimer(timestamp, domain);

    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }
}
