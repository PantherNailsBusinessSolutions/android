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
package com.android.tools.profilers.cpu;

import com.android.tools.adtui.model.AspectObserver;
import com.android.tools.adtui.model.FakeTimer;
import com.android.tools.adtui.model.Range;
import com.android.tools.perflib.vmtrace.ClockType;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profiler.proto.CpuProfiler;
import com.android.tools.profiler.proto.Profiler;
import com.android.tools.profilers.*;
import com.android.tools.profilers.event.FakeEventService;
import com.android.tools.profilers.memory.FakeMemoryService;
import com.android.tools.profilers.network.FakeNetworkService;
import com.android.tools.profilers.stacktrace.CodeLocation;
import com.google.protobuf3jarjar.ByteString;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

public class CpuProfilerStageTest extends AspectObserver {
  private final FakeProfilerService myProfilerService = new FakeProfilerService();

  private final FakeCpuService myCpuService = new FakeCpuService();

  private final FakeTimer myTimer = new FakeTimer();

  @Rule
  public FakeGrpcChannel myGrpcChannel =
    new FakeGrpcChannel("CpuProfilerStageTestChannel", myCpuService, myProfilerService,
                        new FakeMemoryService(), new FakeEventService(), FakeNetworkService.newBuilder().build());

  private CpuProfilerStage myStage;

  private FakeIdeProfilerServices myServices;

  private boolean myCaptureDetailsCalled;

  @Before
  public void setUp() throws Exception {
    myServices = new FakeIdeProfilerServices();
    StudioProfilers profilers = new StudioProfilers(myGrpcChannel.getClient(), myServices, myTimer);
    // One second must be enough for new devices (and processes) to be picked up
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    myStage = new CpuProfilerStage(profilers);
    myStage.getStudioProfilers().setStage(myStage);
  }

  @Test
  public void testDefaultValues() throws IOException {
    assertThat(myStage.getCpuTraceDataSeries()).isNotNull();
    assertThat(myStage.getThreadStates()).isNotNull();
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.NORMAL);
    assertThat(myStage.getCapture()).isNull();
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    assertThat(myStage.getAspect()).isNotNull();
  }

  @Test
  public void testStartCapturing() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);

    // Start a successful capture
    startCapturingSuccess();

    // Start a failing capture
    myCpuService.setStartProfilingStatus(CpuProfiler.CpuProfilingAppStartResponse.Status.FAILURE);
    startCapturing();
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
  }

  @Test
  public void startCapturingInstrumented() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    myCpuService.setStartProfilingStatus(CpuProfiler.CpuProfilingAppStartResponse.Status.SUCCESS);
    myServices.setPrePoolExecutor(() -> assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.STARTING));
    // Start a capture using INSTRUMENTED mode
    ProfilingConfiguration instrumented = new ProfilingConfiguration("My Instrumented Config",
                                                                     CpuProfiler.CpuProfilerType.ART,
                                                                     CpuProfiler.CpuProfilingAppStartRequest.Mode.INSTRUMENTED);
    myStage.setProfilingConfiguration(instrumented);
    startCapturing();
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.CAPTURING);
  }

  @Test
  public void testStopCapturingInvalidTrace() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);

    // Start a successful capture
    startCapturingSuccess();

    // Stop capturing, but don't include a trace in the response.
    myServices.setOnExecute(() -> {
      // First, the main executor is going to be called to execute stopCapturingCallback,
      // which should set the capture state to PARSING
      assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.PARSING);
      // Then, the next time the main executor is called, it will try to parse the capture unsuccessfully
      // and set the capture state to IDLE
      myServices.setOnExecute(() -> {
        assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
        // Capture was stopped successfully, but capture should still be null as the response has no valid trace
        assertThat(myStage.getCapture()).isNull();
      });
    });
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.SUCCESS);
    myCpuService.setValidTrace(false);
    stopCapturing();
  }

  @Test
  public void testStopCapturingInvalidTraceFailureStatus() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);

    // Start a successful capture
    startCapturingSuccess();

    // Stop a capture unsuccessfully
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.FAILURE);
    myCpuService.setValidTrace(false);
    myServices.setOnExecute(() -> {
      assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
      assertThat(myStage.getCapture()).isNull();
    });
    stopCapturing();
  }

  @Test
  public void testStopCapturingValidTraceFailureStatus() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);

    // Start a successful capture
    startCapturingSuccess();

    // Stop a capture unsuccessfully, but with a valid trace
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.FAILURE);
    myCpuService.setValidTrace(true);
    myCpuService.setGetTraceResponseStatus(CpuProfiler.GetTraceResponse.Status.SUCCESS);
    myServices.setOnExecute(() -> {
      assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
      // Despite the fact of having a valid trace, we first check for the response status.
      // As it wasn't SUCCESS, capture should not be set.
      assertThat(myStage.getCapture()).isNull();
    });
    stopCapturing();
  }

  @Test
  public void testStopCapturingSuccessfully() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    captureSuccessfully();
  }

  @Test
  public void testSelectedThread() {
    myStage.setSelectedThread(0);
    assertThat(myStage.getSelectedThread()).isEqualTo(0);

    myStage.setSelectedThread(42);
    assertThat(myStage.getSelectedThread()).isEqualTo(42);
  }

  @Test
  public void testCaptureDetails() throws InterruptedException, IOException, ExecutionException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);

    captureSuccessfully();

    myStage.setSelectedThread(myStage.getCapture().getMainThreadId());

    AspectObserver observer = new AspectObserver();
    myStage.getAspect().addDependency(observer).onChange(CpuProfilerAspect.CAPTURE_DETAILS, () -> myCaptureDetailsCalled = true);

    // Top Down
    myCaptureDetailsCalled = false;
    myStage.setCaptureDetails(CaptureModel.Details.Type.TOP_DOWN);
    assertThat(myCaptureDetailsCalled).isTrue();

    CaptureModel.Details details = myStage.getCaptureDetails();
    assertThat(details).isInstanceOf(CaptureModel.TopDown.class);
    assertThat(((CaptureModel.TopDown)details).getModel()).isNotNull();

    // Bottom Up
    myCaptureDetailsCalled = false;
    myStage.setCaptureDetails(CaptureModel.Details.Type.BOTTOM_UP);
    assertThat(myCaptureDetailsCalled).isTrue();

    details = myStage.getCaptureDetails();
    assertThat(details).isInstanceOf(CaptureModel.BottomUp.class);
    assertThat(((CaptureModel.BottomUp)details).getModel()).isNotNull();

    // Chart
    myCaptureDetailsCalled = false;
    myStage.setCaptureDetails(CaptureModel.Details.Type.CALL_CHART);
    assertThat(myCaptureDetailsCalled).isTrue();

    details = myStage.getCaptureDetails();
    assertThat(details).isInstanceOf(CaptureModel.CallChart.class);
    assertThat(((CaptureModel.CallChart)details).getNode()).isNotNull();

    // null
    myCaptureDetailsCalled = false;
    myStage.setCaptureDetails(null);
    assertThat(myCaptureDetailsCalled).isTrue();
    assertThat(myStage.getCaptureDetails()).isNull();

    // CaptureNode is null, as a result the model is null as well
    myStage.setSelectedThread(-1);
    myCaptureDetailsCalled = false;
    myStage.setCaptureDetails(CaptureModel.Details.Type.BOTTOM_UP);
    assertThat(myCaptureDetailsCalled).isTrue();
    details = myStage.getCaptureDetails();
    assertThat(details).isInstanceOf(CaptureModel.BottomUp.class);
    assertThat(((CaptureModel.BottomUp)details).getModel()).isNull();

    // Capture has changed, keeps the same type of details
    CpuCapture capture = CpuProfilerTestUtils.getValidCapture();
    myStage.setAndSelectCapture(capture);
    CaptureModel.Details newDetails = myStage.getCaptureDetails();
    assertThat(newDetails).isNotEqualTo(details);
    assertThat(newDetails).isInstanceOf(CaptureModel.BottomUp.class);
    assertThat(((CaptureModel.BottomUp)newDetails).getModel()).isNotNull();
  }

  @Test
  public void setCaptureShouldChangeDetails() throws Exception {
    // Capture a trace
    myCpuService.setTraceId(0);
    captureSuccessfully();

    AspectObserver observer = new AspectObserver();
    myStage.getAspect().addDependency(observer).onChange(CpuProfilerAspect.CAPTURE_DETAILS, () -> myCaptureDetailsCalled = true);

    myCaptureDetailsCalled = false;
    // Capture another trace
    myCpuService.setTraceId(1);
    captureSuccessfully();

    assertThat(myStage.getCapture()).isNotNull();
    assertThat(myStage.getCapture()).isEqualTo(myStage.getCaptureFuture(1).get());
    assertThat(myCaptureDetailsCalled).isTrue();
  }

  @Test
  public void setSelectedThreadShouldChangeDetails() throws Exception {
    captureSuccessfully();

    AspectObserver observer = new AspectObserver();
    myStage.getAspect().addDependency(observer).onChange(CpuProfilerAspect.CAPTURE_DETAILS, () -> myCaptureDetailsCalled = true);

    myCaptureDetailsCalled = false;
    myStage.setSelectedThread(42);

    assertThat(myStage.getSelectedThread()).isEqualTo(42);
    assertThat(myCaptureDetailsCalled).isTrue();
  }

  @Test
  public void unselectingThreadSetDetailsNodeToNull() throws InterruptedException {
    captureSuccessfully();
    myStage.setCaptureDetails(CaptureModel.Details.Type.CALL_CHART);
    myStage.setSelectedThread(myStage.getCapture().getMainThreadId());
    assertThat(myStage.getCaptureDetails()).isInstanceOf(CaptureModel.CallChart.class);
    assertThat(((CaptureModel.CallChart)myStage.getCaptureDetails()).getNode()).isNotNull();

    myStage.setSelectedThread(CaptureModel.NO_THREAD);
    assertThat(((CaptureModel.CallChart)myStage.getCaptureDetails()).getNode()).isNull();
  }

  @Test
  public void settingTheSameThreadDoesNothing() throws Exception {
    myCpuService.setTraceId(0);
    captureSuccessfully();

    AspectObserver observer = new AspectObserver();
    myStage.getAspect().addDependency(observer).onChange(CpuProfilerAspect.CAPTURE_DETAILS, () -> myCaptureDetailsCalled = true);

    myCaptureDetailsCalled = false;
    myStage.setSelectedThread(42);
    assertThat(myCaptureDetailsCalled).isTrue();

    myCaptureDetailsCalled = false;
    // Thread id is the same as the current selected thread, so it should do nothing
    myStage.setSelectedThread(42);
    assertThat(myCaptureDetailsCalled).isFalse();
  }

  @Test
  public void settingTheSameDetailsTypeDoesNothing() throws Exception {
    myCpuService.setTraceId(0);
    captureSuccessfully();

    AspectObserver observer = new AspectObserver();
    myStage.getAspect().addDependency(observer).onChange(CpuProfilerAspect.CAPTURE_DETAILS, () -> myCaptureDetailsCalled = true);
    assertThat(myStage.getCaptureDetails().getType()).isEqualTo(CaptureModel.Details.Type.CALL_CHART);

    myCaptureDetailsCalled = false;
    // The first time we set it to bottom up, CAPTURE_DETAILS should be fired
    myStage.setCaptureDetails(CaptureModel.Details.Type.BOTTOM_UP);
    assertThat(myCaptureDetailsCalled).isTrue();

    myCaptureDetailsCalled = false;
    // If we call it again for bottom up, we shouldn't fire CAPTURE_DETAILS
    myStage.setCaptureDetails(CaptureModel.Details.Type.BOTTOM_UP);
    assertThat(myCaptureDetailsCalled).isFalse();
  }

  @Test
  public void callChartShouldBeSetAfterACapture() throws Exception {
    captureSuccessfully();
    assertThat(myStage.getCaptureDetails().getType()).isEqualTo(CaptureModel.Details.Type.CALL_CHART);

    // Change details type and verify it was actually changed.
    myStage.setCaptureDetails(CaptureModel.Details.Type.BOTTOM_UP);
    assertThat(myStage.getCaptureDetails().getType()).isEqualTo(CaptureModel.Details.Type.BOTTOM_UP);

    CpuCapture capture = CpuProfilerTestUtils.getValidCapture();
    myStage.setAndSelectCapture(capture);
    // Just selecting a different capture shouldn't change the capture details
    assertThat(myStage.getCaptureDetails().getType()).isEqualTo(CaptureModel.Details.Type.BOTTOM_UP);

    captureSuccessfully();
    // Capturing again should set the details to call chart
    assertThat(myStage.getCaptureDetails().getType()).isEqualTo(CaptureModel.Details.Type.CALL_CHART);
  }

  @Test
  public void profilerReturnsToNormalModeAfterNavigatingToCode() throws IOException, ExecutionException, InterruptedException {
    // We need to be on the stage itself or else we won't be listening to code navigation events
    myStage.getStudioProfilers().setStage(myStage);

    // to EXPANDED mode
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.NORMAL);
    myStage.setAndSelectCapture(CpuProfilerTestUtils.getValidCapture());
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.EXPANDED);
    // After code navigation it should be Normal mode.
    myStage.getStudioProfilers().getIdeServices().getCodeNavigator().navigate(CodeLocation.stub());
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.NORMAL);

    myStage.setCapture(CpuProfilerTestUtils.getValidCapture());
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.EXPANDED);
  }

  @Test
  public void captureStateDependsOnAppBeingProfiling() {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    myCpuService.setStartProfilingStatus(CpuProfiler.CpuProfilingAppStartResponse.Status.SUCCESS);
    startCapturing();
    myCpuService.setValidTrace(true);
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.CAPTURING);
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.SUCCESS);
    stopCapturing();
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
  }

  @Test
  public void setAndSelectCaptureDifferentClockType() throws IOException, InterruptedException {
    captureSuccessfully();
    CpuCapture capture = myStage.getCapture();
    CaptureNode captureNode = capture.getCaptureNode(capture.getMainThreadId());
    assertThat(captureNode).isNotNull();
    myStage.setSelectedThread(capture.getMainThreadId());

    assertThat(captureNode.getClockType()).isEqualTo(ClockType.GLOBAL);
    myStage.setAndSelectCapture(capture);
    ProfilerTimeline timeline = myStage.getStudioProfilers().getTimeline();
    double eps = 0.00001;
    // In GLOBAL clock type, selection should be the main node range
    assertThat(capture.getRange().getMin()).isWithin(eps).of(timeline.getSelectionRange().getMin());
    assertThat(capture.getRange().getMax()).isWithin(eps).of(timeline.getSelectionRange().getMax());

    timeline.getSelectionRange().set(captureNode.getStartGlobal(), captureNode.getEndGlobal());
    myStage.setClockType(ClockType.THREAD);
    assertThat(captureNode.getClockType()).isEqualTo(ClockType.THREAD);
    myStage.setCapture(capture);
    // In THREAD clock type, selection should scale the interval based on thread-clock/wall-clock ratio [node's startTime, node's endTime].
    double threadToGlobal = 1 / captureNode.threadGlobalRatio();
    double threadSelectionStart = captureNode.getStartGlobal() +
                                  threadToGlobal * (captureNode.getStartThread() - timeline.getSelectionRange().getMin());
    double threadSelectionEnd = threadSelectionStart +
                                threadToGlobal * captureNode.duration();
    assertThat(threadSelectionStart).isWithin(eps).of(timeline.getSelectionRange().getMin());
    assertThat(threadSelectionEnd).isWithin(eps).of(timeline.getSelectionRange().getMax());

    myStage.setClockType(ClockType.GLOBAL);
    assertThat(captureNode.getClockType()).isEqualTo(ClockType.GLOBAL);
    // Just setting the clock type shouldn't change the selection range
    assertThat(threadSelectionStart).isWithin(eps).of(timeline.getSelectionRange().getMin());
    assertThat(threadSelectionEnd).isWithin(eps).of(timeline.getSelectionRange().getMax());
  }

  @Test
  public void testCaptureRangeConversion() throws Exception {
    captureSuccessfully();

    myStage.setSelectedThread(myStage.getCapture().getMainThreadId());
    myStage.setCaptureDetails(CaptureModel.Details.Type.BOTTOM_UP);

    Range selection = myStage.getStudioProfilers().getTimeline().getSelectionRange();
    double eps = 1e-5;
    assertThat(selection.getMin()).isWithin(eps).of(myStage.getCapture().getRange().getMin());
    assertThat(selection.getMax()).isWithin(eps).of(myStage.getCapture().getRange().getMax());

    assertThat(myStage.getCaptureDetails()).isInstanceOf(CaptureModel.BottomUp.class);
    CaptureModel.BottomUp details = (CaptureModel.BottomUp)myStage.getCaptureDetails();

    Range detailsRange = details.getModel().getRange();

    // When ClockType.Global is used, the range of a capture details should the same as the selection range
    assertThat(myStage.getClockType()).isEqualTo(ClockType.GLOBAL);
    assertThat(selection.getMin()).isWithin(eps).of(detailsRange.getMin());
    assertThat(selection.getMax()).isWithin(eps).of(detailsRange.getMax());

    detailsRange.set(0, 10);
    assertThat(selection.getMin()).isWithin(eps).of(0);
    assertThat(selection.getMax()).isWithin(eps).of(10);

    selection.set(1, 5);
    assertThat(detailsRange.getMin()).isWithin(eps).of(1);
    assertThat(detailsRange.getMax()).isWithin(eps).of(5);
  }

  @Test
  public void settingACaptureAfterNullShouldSelectMainThread() throws Exception {
    assertThat(myStage.getSelectedThread()).isEqualTo(CaptureModel.NO_THREAD);
    assertThat(myStage.getCapture()).isNull();
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.NORMAL);

    CpuCapture capture = CpuProfilerTestUtils.getValidCapture();
    assertThat(capture).isNotNull();
    myStage.setAndSelectCapture(capture);
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.EXPANDED);
    // Capture main thread should be selected
    assertThat(myStage.getSelectedThread()).isEqualTo(capture.getMainThreadId());

    myStage.setCapture(null);
    assertThat(myStage.getProfilerMode()).isEqualTo(ProfilerMode.NORMAL);
    // Thread selection is reset when going to NORMAL mode
    assertThat(myStage.getSelectedThread()).isEqualTo(CaptureModel.NO_THREAD);
  }

  @Test
  public void changingCaptureShouldKeepThreadSelection() throws Exception {
    CpuCapture capture1 = CpuProfilerTestUtils.getValidCapture();
    CpuCapture capture2 = CpuProfilerTestUtils.getValidCapture();
    assertThat(capture1).isNotEqualTo(capture2);

    myStage.setAndSelectCapture(capture1);
    // Capture main thread should be selected
    int mainThread = capture1.getMainThreadId();
    assertThat(myStage.getSelectedThread()).isEqualTo(mainThread);

    int otherThread = mainThread;
    // Select a thread other than main
    for (CpuThreadInfo thread : capture1.getThreads()) {
      if (thread.getId() != mainThread) {
        otherThread = thread.getId();
        break;
      }
    }

    assertThat(otherThread).isNotEqualTo(mainThread);
    myStage.setSelectedThread(otherThread);
    assertThat(myStage.getSelectedThread()).isEqualTo(otherThread);

    myStage.setAndSelectCapture(capture2);
    assertThat(myStage.getCapture()).isEqualTo(capture2);
    // Thread selection should be kept instead of selecting capture2 main thread.
    assertThat(myStage.getSelectedThread()).isEqualTo(otherThread);
  }

  @Test
  public void testUsageTooltip() {
    myStage.enter();
    myStage.setTooltip(CpuProfilerStage.Tooltip.Type.USAGE);
    assertThat(myStage.getTooltip()).isInstanceOf(CpuProfilerStage.UsageTooltip.class);
    CpuProfilerStage.UsageTooltip tooltip = (CpuProfilerStage.UsageTooltip)myStage.getTooltip();

    CpuProfilerStage.CpuStageLegends legends = tooltip.getLegends();
    double tooltipTime = TimeUnit.SECONDS.toMicros(0);
    myCpuService.setAppTimeMs(10);
    myCpuService.setSystemTimeMs(50);
    myStage.getStudioProfilers().getTimeline().getTooltipRange().set(tooltipTime, tooltipTime);
    assertThat(legends.getCpuLegend().getName()).isEqualTo("App");
    assertThat(legends.getOthersLegend().getName()).isEqualTo("Others");
    assertThat(legends.getThreadsLegend().getName()).isEqualTo("Threads");
    assertThat(legends.getCpuLegend().getValue()).isEqualTo("10%");
    assertThat(legends.getOthersLegend().getValue()).isEqualTo("40%");
    assertThat(legends.getThreadsLegend().getValue()).isEqualTo("1");
  }

  @Test
  public void testThreadsTooltip() {
    Range viewRange = myStage.getStudioProfilers().getTimeline().getViewRange();
    Range tooltipRange = myStage.getStudioProfilers().getTimeline().getTooltipRange();

    viewRange.set(TimeUnit.SECONDS.toMicros(0), TimeUnit.SECONDS.toMicros(11));

    myStage.enter();
    myStage.setTooltip(CpuProfilerStage.Tooltip.Type.THREADS);
    assertThat(myStage.getTooltip()).isInstanceOf(CpuProfilerStage.ThreadsTooltip.class);
    CpuProfilerStage.ThreadsTooltip tooltip = (CpuProfilerStage.ThreadsTooltip)myStage.getTooltip();

    ThreadStateDataSeries series = new ThreadStateDataSeries(myStage, 1, ProfilersTestData.SESSION_DATA, 1);
    // 1 - running - 8 - dead - 11
    tooltip.setThread("myThread", series);

    assertThat(tooltip.getThreadName()).isEqualTo("myThread");
    tooltipRange.set(TimeUnit.SECONDS.toMicros(5), TimeUnit.SECONDS.toMicros(5));
    assertThat(tooltip.getThreadState()).isEqualTo(CpuProfilerStage.ThreadState.RUNNING);

    tooltipRange.set(TimeUnit.SECONDS.toMicros(9), TimeUnit.SECONDS.toMicros(9));
    assertThat(tooltip.getThreadState()).isEqualTo(CpuProfilerStage.ThreadState.DEAD);
  }

  @Test
  public void testElapsedTime() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    // When there is no capture in progress, elapsed time is set to Long.MAX_VALUE.
    // As a result CpuProfilerStage#getCaptureElapsedTimeUs should return a negative value.
    assertThat(myStage.getCaptureElapsedTimeUs()).isLessThan((long)0);

    // Start capturing
    startCapturingSuccess();
    // Increment 3 seconds on data range
    Range dataRange = myStage.getStudioProfilers().getTimeline().getDataRange();
    dataRange.setMax(dataRange.getMax() + TimeUnit.SECONDS.toMicros(3));
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.CAPTURING);

    // Check that we're capturing for three seconds
    assertThat(myStage.getCaptureElapsedTimeUs()).isEqualTo(TimeUnit.SECONDS.toMicros(3));

    myCpuService.setValidTrace(true);
    stopCapturing();
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    // Capture has finished. CpuProfilerStage#getCaptureElapsedTimeUs should return a negative value.
    assertThat(myStage.getCaptureElapsedTimeUs()).isLessThan((long)0);
  }

  @Test
  public void profilingModesAvailableDependOnDeviceApi() {
    myServices.enableSimplePerf(true);

    // Set a device that doesn't support simpleperf
    addAndSetDevice(14, "FakeDevice1");

    List<ProfilingConfiguration> configs = myStage.getProfilingConfigurations();
    // First configuration in the list should be a dummy entry used to open the configurations dialog
    assertThat(configs.get(0)).isEqualTo(CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY);

    List<ProfilingConfiguration> realConfigs = filterFakeConfigs(configs);
    assertThat(realConfigs).hasSize(2);
    // First actual configuration should be ART Sampled
    assertThat(realConfigs.get(0).getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    assertThat(realConfigs.get(0).getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    assertThat(realConfigs.get(0).getName()).isEqualTo("Sampled");
    // Second actual configuration should be ART Instrumented
    assertThat(realConfigs.get(1).getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    assertThat(realConfigs.get(1).getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.INSTRUMENTED);
    assertThat(realConfigs.get(1).getName()).isEqualTo("Instrumented");

    // Simpleperf is supported on API 26 and greater.
    addAndSetDevice(26, "FakeDevice2");

    configs = myStage.getProfilingConfigurations();
    // Dummy configuration
    assertThat(configs.get(0)).isEqualTo(CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY);

    realConfigs = filterFakeConfigs(configs);
    assertThat(realConfigs).hasSize(3);

    // First and second actual configurations should be the same
    assertThat(realConfigs.get(0).getName()).isEqualTo("Sampled");
    assertThat(realConfigs.get(1).getName()).isEqualTo("Instrumented");
    // Third configuration should be simpleperf
    assertThat(realConfigs.get(2).getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.SIMPLE_PERF);
    assertThat(realConfigs.get(2).getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    assertThat(realConfigs.get(2).getName()).isEqualTo("Sampled (Hybrid)");
  }

  @Test
  public void simpleperfIsOnlyAvailableWhenFlagIsTrue() {
    myServices.enableSimplePerf(true);

    // Set a device that supports simpleperf
    addAndSetDevice(26, "Fake Device 1");

    List<ProfilingConfiguration> realConfigs = filterFakeConfigs(myStage.getProfilingConfigurations());

    assertThat(realConfigs).hasSize(3);
    assertThat(realConfigs.get(0).getName()).isEqualTo("Sampled");
    assertThat(realConfigs.get(1).getName()).isEqualTo("Instrumented");
    assertThat(realConfigs.get(2).getName()).isEqualTo("Sampled (Hybrid)");

    // Now disable simpleperf
    myServices.enableSimplePerf(false);

    // Set a device that supports simpleperf
    addAndSetDevice(26, "Fake Device 2");
    realConfigs = filterFakeConfigs(myStage.getProfilingConfigurations());
    // Simpleperf should not be listed as a profiling option
    assertThat(realConfigs).hasSize(2);
    assertThat(realConfigs.get(0).getName()).isEqualTo("Sampled");
    assertThat(realConfigs.get(1).getName()).isEqualTo("Instrumented");
  }

  @Test
  public void editConfigurationsEntryCantBeSetAsProfilingConfiguration() {
    assertThat(myStage.getProfilingConfiguration()).isNotNull();
    // ART Sampled should be the default configuration when starting the stage,
    // as it's the first configuration on the list.
    assertThat(myStage.getProfilingConfiguration().getName()).isEqualTo("Sampled");

    // Set a new configuration and check it's actually set as stage's profiling configuration
    ProfilingConfiguration instrumented = new ProfilingConfiguration("Instrumented",
                                                                     CpuProfiler.CpuProfilerType.ART,
                                                                     CpuProfiler.CpuProfilingAppStartRequest.Mode.INSTRUMENTED);
    myStage.setProfilingConfiguration(instrumented);
    assertThat(myStage.getProfilingConfiguration().getName()).isEqualTo("Instrumented");

    // Set CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY as profiling configuration
    // and check it doesn't actually replace the current configuration
    myStage.setProfilingConfiguration(CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY);
    assertThat(myStage.getProfilingConfiguration().getName()).isEqualTo("Instrumented");

    // Just sanity check "Instrumented" is not the name of CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY
    assertThat(CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY.getName()).isNotEqualTo("Instrumented");
  }

  @Test
  public void stopProfilerIsConsistentToStartProfiler() throws InterruptedException, IOException {
    assertThat(myCpuService.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    ProfilingConfiguration config1 = new ProfilingConfiguration("My Config",
                                                                CpuProfiler.CpuProfilerType.SIMPLE_PERF,
                                                                CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    myStage.setProfilingConfiguration(config1);
    myCpuService.setTrace(CpuProfilerTestUtils.traceFileToByteString("simpleperf.trace"));
    captureSuccessfully();
    assertThat(myCpuService.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.SIMPLE_PERF);

    ProfilingConfiguration config2 = new ProfilingConfiguration("My Config 2",
                                                                CpuProfiler.CpuProfilerType.ART,
                                                                CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);

    myCpuService.setTrace(CpuProfilerTestUtils.traceFileToByteString("valid_trace.trace"));
    myStage.setProfilingConfiguration(config2);
    // Start capturing with ART
    startCapturingSuccess();
    // Change the profiling configurations in the middle of the capture and stop capturing
    myStage.setProfilingConfiguration(config1);
    stopCapturing();
    // Stop profiler should be the same as the one passed in the start request
    assertThat(myCpuService.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
  }

  @Test
  public void exitingStateAndEnteringAgainShouldPreserveCaptureState() throws IOException, InterruptedException {
    assertThat(myCpuService.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    ProfilingConfiguration config1 = new ProfilingConfiguration("My Config",
                                                                CpuProfiler.CpuProfilerType.SIMPLE_PERF,
                                                                CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    myStage.setProfilingConfiguration(config1);
    startCapturingSuccess();

    // Go back to monitor stage and go back to a new Cpu profiler stage
    myStage.getStudioProfilers().setStage(new StudioMonitorStage(myStage.getStudioProfilers()));
    CpuProfilerStage stage = new CpuProfilerStage(myStage.getStudioProfilers());
    myStage.getStudioProfilers().setStage(stage);

    // Make sure we're capturing
    assertThat(stage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.CAPTURING);

    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.SUCCESS);
    myCpuService.setTrace(CpuProfilerTestUtils.traceFileToByteString("simpleperf.trace"));
    myCpuService.setValidTrace(true);
    myCpuService.setGetTraceResponseStatus(CpuProfiler.GetTraceResponse.Status.SUCCESS);
    stopCapturing(stage);
    assertThat(stage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);

    // Stop profiler should be the same as the one passed in the start request
    assertThat(myCpuService.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.SIMPLE_PERF);

    // Make sure we tracked the correct configuration
    ProfilingConfiguration trackedConfig =
      ((FakeFeatureTracker)myServices.getFeatureTracker()).getLastCpuCaptureMetadata().getProfilingConfiguration();
    assertThat(trackedConfig.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.SIMPLE_PERF);
    assertThat(trackedConfig.getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
  }

  @Test
  public void setAndSelectCaptureShouldNotChangeStreamingMode() throws Exception {
    // Capture has changed, keeps the same type of details
    CpuCapture capture = CpuProfilerTestUtils.getValidCapture();
    myStage.getStudioProfilers().getTimeline().setIsPaused(false);
    myStage.getStudioProfilers().getTimeline().setStreaming(true);
    myStage.setAndSelectCapture(capture);
    assertThat(myStage.getStudioProfilers().getTimeline().isStreaming()).isTrue();
    myStage.getStudioProfilers().getTimeline().setStreaming(false);
    myStage.setAndSelectCapture(capture);
    assertThat(myStage.getStudioProfilers().getTimeline().isStreaming()).isFalse();
  }

  @Test
  public void testInProgressDuration() throws InterruptedException {
    assertThat(myStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(0);
    startCapturingSuccess();
    // Starting capturing should display in progress duration, it will be displayed when
    // myStage.getInProgressTraceDuration() contains exactly one element corresponding to unfinished duration.
    assertThat(myStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(1);
    assertThat(myStage.getInProgressTraceDuration().getSeries().getSeries().get(0).value.getDuration()).isEqualTo(Long.MAX_VALUE);
    myCpuService.setValidTrace(true);
    stopCapturing();
    assertThat(myStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(0);
  }

  @Test
  public void testInProgressDurationAfterExitAndEnter() throws InterruptedException {
    assertThat(myStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(0);
    startCapturingSuccess();
    assertThat(myStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(1);
    myStage.exit();

    StudioProfilers profilers = new StudioProfilers(myGrpcChannel.getClient(), myServices, myTimer);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    CpuProfilerStage newStage = new CpuProfilerStage(profilers);
    newStage.getStudioProfilers().setStage(newStage);

    assertThat(newStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(1);
    myCpuService.setValidTrace(true);
    stopCapturing(newStage);
    assertThat(newStage.getInProgressTraceDuration().getSeries().getSeries()).hasSize(0);
  }

  @Test
  public void selectARangeWithNoCapturesShouldKeepCurrentCaptureSelected() throws InterruptedException {
    assertThat(myStage.getCapture()).isNull();
    captureSuccessfully();
    assertThat(myStage.getCapture()).isNotNull();
    CpuCapture capture = myStage.getCapture();

    Range selectionRange = myStage.getStudioProfilers().getTimeline().getSelectionRange();
    // Select an area before the capture.
    selectionRange.set(capture.getRange().getMin() - 20, capture.getRange().getMin() - 10);
    // Last selected capture should remain selected.
    assertThat(myStage.getCapture()).isEqualTo(capture);
  }

  /**
   * Simulate the scenario of calling {@link CpuProfilerStage#getCapture(int)} before calling {@link CpuProfilerStage#stopCapturing()}.
   */
  @Test
  public void captureShouldBeParsedOnlyOnceSyncGetCaptureBefore() throws InterruptedException, IOException, ExecutionException {
    assertThat(myStage.getCapture()).isNull();
    myCpuService.setGetTraceResponseStatus(CpuProfiler.GetTraceResponse.Status.SUCCESS);
    myCpuService.setTrace(CpuProfilerTestUtils.traceFileToByteString("valid_trace.trace"));
    // Capture with FAKE_TRACE_ID doesn't exist yet. myStage.getCapture(...) will parse it.
    CpuCapture capture = myStage.getCaptureFuture(FakeCpuService.FAKE_TRACE_ID).get();
    assertThat(capture).isNotNull();

    captureSuccessfully();
    // Capture should be the same as the one obtained by myStage.getCapture(...),
    // because we should not parse the trace into another CpuCapture object.
    assertThat(myStage.getCapture()).isEqualTo(capture);
  }

  /**
   * Simulate the scenario of calling {@link CpuProfilerStage#stopCapturing()} before calling {@link CpuProfilerStage#getCapture(int)}.
   */
  @Test
  public void captureShouldBeParsedOnlyOnceStopCapturingBefore() throws InterruptedException, IOException, ExecutionException {
    assertThat(myStage.getCapture()).isNull();
    // stopCapturing() should create a capture with FAKE_TRACE_ID
    captureSuccessfully();
    CpuCapture capture = myStage.getCapture();
    assertThat(capture).isNotNull();

    // Capture should be the same as the one created by stopCapturing(),
    // because we should not parse the trace into another CpuCapture object.
    assertThat(myStage.getCaptureFuture(FakeCpuService.FAKE_TRACE_ID).get()).isEqualTo(capture);
  }

  @Test
  public void nullProcessShouldNotThrowException() {
    // Set a device to null (e.g. when stop profiling) should not crash the CpuProfilerStage
    myStage.getStudioProfilers().setDevice(null);
    assertThat(myStage.getStudioProfilers().getDevice()).isNull();

    // Open the profiling configurations dialog with null device shouldn't crash CpuProfilerStage.
    // Dialog is expected to be open so the user can edit configurations to be used by other devices later.
    myStage.openProfilingConfigurationsDialog();
  }

  @Test
  public void cpuMetadataSuccessfulCapture() throws InterruptedException, IOException {
    ProfilingConfiguration config = new ProfilingConfiguration("My Config",
                                                               CpuProfiler.CpuProfilerType.ART,
                                                               CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    config.setProfilingSamplingIntervalUs(10);
    config.setProfilingBufferSizeInMb(15);
    myStage.setProfilingConfiguration(config);
    captureSuccessfully();
    CpuCaptureMetadata metadata = ((FakeFeatureTracker)myServices.getFeatureTracker()).getLastCpuCaptureMetadata();
    assertThat(metadata.getStatus()).isEqualTo(CpuCaptureMetadata.CaptureStatus.SUCCESS);
    ProfilingConfiguration metadataConfig = metadata.getProfilingConfiguration();
    assertThat(metadataConfig.getProfilingSamplingIntervalUs()).isEqualTo(10);
    assertThat(metadataConfig.getProfilingBufferSizeInMb()).isEqualTo(15);
    assertThat(metadataConfig.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    assertThat(metadataConfig.getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    assertThat(metadata.getParsingTimeMs()).isGreaterThan(0L);
    assertThat(metadata.getRecordDurationMs()).isGreaterThan(0L);
    assertThat(metadata.getCaptureDurationMs()).isGreaterThan(0L);
    assertThat(metadata.getTraceFileSizeBytes()).isGreaterThan(0);
  }

  @Test
  public void cpuMetadataFailureStopCapture() throws InterruptedException {
    // Try to parse a simpleperf trace with ART config. Parsing should fail.
    ProfilingConfiguration config = new ProfilingConfiguration("My Config",
                                                               CpuProfiler.CpuProfilerType.ART,
                                                               CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    config.setProfilingSamplingIntervalUs(10);
    config.setProfilingBufferSizeInMb(15);
    myStage.setProfilingConfiguration(config);

    startCapturingSuccess();
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.FAILURE);
    stopCapturing();
    CpuCaptureMetadata metadata = ((FakeFeatureTracker)myServices.getFeatureTracker()).getLastCpuCaptureMetadata();
    assertThat(metadata.getStatus()).isEqualTo(CpuCaptureMetadata.CaptureStatus.STOP_CAPTURING_FAILURE);
    // Profiling Configurations should remain the same
    ProfilingConfiguration metadataConfig = metadata.getProfilingConfiguration();
    assertThat(metadataConfig.getProfilingSamplingIntervalUs()).isEqualTo(10);
    assertThat(metadataConfig.getProfilingBufferSizeInMb()).isEqualTo(15);
    assertThat(metadataConfig.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    assertThat(metadataConfig.getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    // Trace was not generated, so trace size, parsing time, recording duration and capture duration should be -1
    assertThat(metadata.getParsingTimeMs()).isEqualTo(-1);
    assertThat(metadata.getRecordDurationMs()).isEqualTo(-1);
    assertThat(metadata.getCaptureDurationMs()).isEqualTo(-1);
    assertThat(metadata.getTraceFileSizeBytes()).isEqualTo(-1);
  }

  @Test
  public void cpuMetadataFailureParsing() throws InterruptedException, IOException {
    // Try to parse a simpleperf trace with ART config. Parsing should fail.
    ProfilingConfiguration config = new ProfilingConfiguration("My Config",
                                                                CpuProfiler.CpuProfilerType.ART,
                                                                CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    config.setProfilingSamplingIntervalUs(10);
    config.setProfilingBufferSizeInMb(15);
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.SUCCESS);
    myCpuService.setTrace(CpuProfilerTestUtils.traceFileToByteString("simpleperf.trace"));
    myCpuService.setValidTrace(true);
    myStage.setProfilingConfiguration(config);

    startCapturingSuccess();
    stopCapturing();
    CpuCaptureMetadata metadata = ((FakeFeatureTracker)myServices.getFeatureTracker()).getLastCpuCaptureMetadata();
    assertThat(metadata.getStatus()).isEqualTo(CpuCaptureMetadata.CaptureStatus.PARSING_FAILURE);
    // Profiling Configurations should remain the same.
    // However, the config object itself is expected to be different, as we copy it when start capturing.
    ProfilingConfiguration metadataConfig = metadata.getProfilingConfiguration();
    assertThat(metadataConfig).isNotEqualTo(config);
    assertThat(metadataConfig.getProfilingSamplingIntervalUs()).isEqualTo(10);
    assertThat(metadataConfig.getProfilingBufferSizeInMb()).isEqualTo(15);
    assertThat(metadataConfig.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    assertThat(metadataConfig.getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    // Trace was generated, so trace size should be greater than 0
    assertThat(metadata.getTraceFileSizeBytes()).isGreaterThan(0);
    // Trace was not parsed correctly, so parsing time, recording duration and capture duration should be -1
    assertThat(metadata.getParsingTimeMs()).isEqualTo(-1);
    assertThat(metadata.getRecordDurationMs()).isEqualTo(-1);
    assertThat(metadata.getCaptureDurationMs()).isEqualTo(-1);
  }

  @Test
  public void cpuMetadataFailureUserAbort() throws InterruptedException, IOException {
    // Try to parse a simpleperf trace with ART config. Parsing should fail.
    ProfilingConfiguration config = new ProfilingConfiguration("My Config",
                                                               CpuProfiler.CpuProfilerType.ART,
                                                               CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    config.setProfilingSamplingIntervalUs(10);
    config.setProfilingBufferSizeInMb(15);
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.SUCCESS);
    ByteString largeTraceFile = ByteString.copyFrom(new byte[CpuCaptureParser.MAX_SUPPORTED_TRACE_SIZE + 1]);
    myCpuService.setTrace(largeTraceFile);
    myCpuService.setValidTrace(true);
    myStage.setProfilingConfiguration(config);
    myServices.setShouldParseLongTraces(false);

    startCapturingSuccess();
    stopCapturing();
    CpuCaptureMetadata metadata = ((FakeFeatureTracker)myServices.getFeatureTracker()).getLastCpuCaptureMetadata();
    assertThat(metadata.getStatus()).isEqualTo(CpuCaptureMetadata.CaptureStatus.USER_ABORTED_PARSING);
    // Profiling Configurations should remain the same.
    ProfilingConfiguration metadataConfig = metadata.getProfilingConfiguration();
    assertThat(metadataConfig.getProfilingSamplingIntervalUs()).isEqualTo(10);
    assertThat(metadataConfig.getProfilingBufferSizeInMb()).isEqualTo(15);
    assertThat(metadataConfig.getProfilerType()).isEqualTo(CpuProfiler.CpuProfilerType.ART);
    assertThat(metadataConfig.getMode()).isEqualTo(CpuProfiler.CpuProfilingAppStartRequest.Mode.SAMPLED);
    // Trace was generated, so trace size should be greater than 0
    assertThat(metadata.getTraceFileSizeBytes()).isGreaterThan(0);
    // Trace was not parsed at all, so parsing time, recording duration and capture duration should be -1
    assertThat(metadata.getParsingTimeMs()).isEqualTo(-1);
    assertThat(metadata.getRecordDurationMs()).isEqualTo(-1);
    assertThat(metadata.getCaptureDurationMs()).isEqualTo(-1);
  }

  @Test
  public void startCapturingJumpsToLiveData() throws InterruptedException, IOException {
    ProfilerTimeline timeline = myStage.getStudioProfilers().getTimeline();
    timeline.setStreaming(false);
    assertThat(timeline.isStreaming()).isFalse();

    startCapturingSuccess();
    assertThat(timeline.isStreaming()).isTrue();
    stopCapturing();

    // Sanity test to check that start recording doesn't flip the status of isStreaming, but actually sets it to true
    assertThat(timeline.isStreaming()).isTrue();
    startCapturingSuccess();
    assertThat(timeline.isStreaming()).isTrue();
  }

  private void addAndSetDevice(int featureLevel, String serial) {
    Profiler.Device device =
      Profiler.Device.newBuilder().setFeatureLevel(featureLevel).setSerial(serial).setState(Profiler.Device.State.ONLINE).build();
    Profiler.Process process = Profiler.Process.newBuilder()
      .setPid(20)
      .setState(Profiler.Process.State.ALIVE)
      .setName("FakeProcess")
      .build();
    Common.Session session = Common.Session.newBuilder()
      .setBootId(device.getBootId())
      .setDeviceSerial(device.getSerial())
      .build();
    myProfilerService.addDevice(device);
    // Adds at least one ALIVE process as well. Otherwise, StudioProfilers would prefer selecting a device that has live processes.
    myProfilerService.addProcess(session, process);

    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS); // One second must be enough for new device to be picked up
    myStage.getStudioProfilers().setDevice(device);
    // Setting the device will change the stage. We need to go back to CpuProfilerStage
    myStage.getStudioProfilers().setStage(myStage);
  }

  private void captureSuccessfully() throws InterruptedException {
    // Start a successful capture
    startCapturingSuccess();

    // Stop a capture successfully with a valid trace
    myServices.setOnExecute(() -> {
      // First, the main executor is going to be called to execute stopCapturingCallback,
      // which should set the capture state to PARSING
      assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.PARSING);
      // Then, the next time the main executor is called, it will parse the capture successfully
      // and set the capture state to IDLE
      myServices.setOnExecute(() -> {
        assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
        assertThat(myStage.getCapture()).isNotNull();
      });
    });
    myCpuService.setStopProfilingStatus(CpuProfiler.CpuProfilingAppStopResponse.Status.SUCCESS);
    myCpuService.setValidTrace(true);
    myCpuService.setGetTraceResponseStatus(CpuProfiler.GetTraceResponse.Status.SUCCESS);
    stopCapturing();
  }

  /**
   * This is a convenience method to start a capture successfully.
   * It sets all the necessary states in the service and call {@link CpuProfilerStage#startCapturing}.
   */
  private void startCapturingSuccess() throws InterruptedException {
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.IDLE);
    myCpuService.setStartProfilingStatus(CpuProfiler.CpuProfilingAppStartResponse.Status.SUCCESS);
    startCapturing();
    assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.CAPTURING);
  }

  /**
   * This is a convenience method to start a capture.
   * It makes sure to check the intermediate state (STARTING) between pressing the "Start" button and effectively start capturing.
   */
  private void startCapturing() {
    myServices.setPrePoolExecutor(() -> assertThat(myStage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.STARTING));
    myStage.startCapturing();
  }

  /**
   * This is a convenience method to stop a capture.
   * It makes sure to check the intermidiate state (STOPPING) between pressing the "Stop" button and effectively stop capturing,
   * and the PARSING state which happens after a capture is stopped.
   */
  private void stopCapturing(CpuProfilerStage stage) {
    // The pre executor will pass through STOPPING and then PARSING
    myServices.setPrePoolExecutor(() -> {
      assertThat(stage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.STOPPING);
      myServices.setPrePoolExecutor(() -> assertThat(stage.getCaptureState()).isEqualTo(CpuProfilerStage.CaptureState.PARSING));
    });
    stage.stopCapturing();
  }

  private void stopCapturing() {
    stopCapturing(myStage);
  }

  /**
   * Some configs are used as placeholders and never actually meant to be selected by users. Strip
   * those out to test against the configurations that matter.
   */
  private List<ProfilingConfiguration> filterFakeConfigs(List<ProfilingConfiguration> configs) {
    return configs.stream()
      .filter(pc -> pc != CpuProfilerStage.EDIT_CONFIGURATIONS_ENTRY && pc != CpuProfilerStage.CONFIG_SEPARATOR_ENTRY)
      .collect(Collectors.toList());
  }
}
