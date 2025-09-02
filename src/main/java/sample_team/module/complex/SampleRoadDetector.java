package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.RoadDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class SampleRoadDetector extends RoadDetector {

  private Clustering clustering;
  private EntityID result;

  public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.clustering = moduleManager.getModule(
        "SampleRoadDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    this.result = null;
  }

  @Override
  public RoadDetector updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }

  @Override
  public RoadDetector calc() {
    EntityID position = agentInfo.getPosition();
    result = null;

    // 获取当前簇内的所有区域
    int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
    Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

    List<Blockade> targetBlockades = new ArrayList<>();
    for (StandardEntity entity : clusterEntities) {
      if (entity instanceof Road) {
        Road road = (Road) entity;
        if (road.isBlockadesDefined()) {
          for (EntityID blockadeID : road.getBlockades()) {
            StandardEntity blockadeEntity = worldInfo.getEntity(blockadeID);
            if (blockadeEntity instanceof Blockade) {
              targetBlockades.add((Blockade) blockadeEntity);
            }
          }
        }
      }
    }

    // 按严重性排序（修复成本高的优先）
    targetBlockades.sort((b1, b2) -> {
      int cost1 = b1.isRepairCostDefined() ? b1.getRepairCost() : 0;
      int cost2 = b2.isRepairCostDefined() ? b2.getRepairCost() : 0;
      return Integer.compare(cost2, cost1); // 降序排序
    });

    // 选择最紧急的障碍物
    Blockade bestTarget = null;
    double bestScore = Double.NEGATIVE_INFINITY;

    for (Blockade blockade : targetBlockades) {
      int distance = worldInfo.getDistance(position, blockade.getID());
      int cost = blockade.isRepairCostDefined() ? blockade.getRepairCost() : 0;
      double severity = cost / 100.0; // 标准化严重性分数
      double score = severity / (distance + 1); // 避免除零

      if (score > bestScore) {
        bestScore = score;
        bestTarget = blockade;
      }
    }

    if (bestTarget != null) {
      this.result = bestTarget.getID();
    }

    return this;
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }
}