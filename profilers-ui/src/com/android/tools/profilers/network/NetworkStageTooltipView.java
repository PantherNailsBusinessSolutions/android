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
package com.android.tools.profilers.network;

import com.android.tools.adtui.LegendComponent;
import com.android.tools.adtui.LegendConfig;
import com.android.tools.profilers.ProfilerColors;
import com.android.tools.profilers.ProfilerTooltipView;

import java.awt.*;

class NetworkStageTooltipView extends ProfilerTooltipView {
  private final NetworkProfilerStage myStage;

  NetworkStageTooltipView(NetworkProfilerStage stage) {
    super(stage.getStudioProfilers().getTimeline(), "Network Traffic");
    myStage = stage;
  }

  @Override
  protected Component createTooltip() {
    NetworkProfilerStage.NetworkStageLegends legends = myStage.getTooltipLegends();
    LegendComponent legend =
      new LegendComponent.Builder(legends).setVerticalPadding(0).setOrientation(LegendComponent.Orientation.VERTICAL).build();
    legend.configure(legends.getRxLegend(), new LegendConfig(LegendConfig.IconType.BOX, ProfilerColors.NETWORK_RECEIVING_COLOR));
    legend.configure(legends.getTxLegend(), new LegendConfig(LegendConfig.IconType.BOX, ProfilerColors.NETWORK_SENDING_COLOR));
    legend.configure(legends.getConnectionLegend(),
                     new LegendConfig(LegendConfig.IconType.DASHED_LINE, ProfilerColors.NETWORK_CONNECTIONS_COLOR));

    return legend;
  }
}
