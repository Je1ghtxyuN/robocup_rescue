package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }

  @Override
  public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }

  @Override
  public PoliceTargetAllocator preparate() {
    super.preparate();
    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    Map<EntityID, EntityID> allocation = new HashMap<>();

    // 获取所有警察和所有障碍物
    Collection<StandardEntity> policeForces = worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE);
    List<Blockade> allBlockades = new ArrayList<>();

    // 收集所有道路上的障碍物
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
      if (entity instanceof Road) {
        Road road = (Road) entity;
        if (road.isBlockadesDefined()) {
          for (EntityID blockadeID : road.getBlockades()) {
            StandardEntity blockadeEntity = worldInfo.getEntity(blockadeID);
            if (blockadeEntity instanceof Blockade) {
              allBlockades.add((Blockade) blockadeEntity);
            }
          }
        }
      }
    }

    // 按修复成本排序（成本低的优先处理）
    allBlockades.sort(Comparator.comparingInt(b -> {
      if (b.isRepairCostDefined())
        return b.getRepairCost();
      return Integer.MAX_VALUE;
    }));

    // 为每个警察分配最近的障碍物
    for (StandardEntity police : policeForces) {
      EntityID policeID = police.getID();
      Blockade closest = null;
      int minDistance = Integer.MAX_VALUE;

      for (Blockade blockade : allBlockades) {
        if (allocation.containsValue(blockade.getID()))
          continue;

        int distance = worldInfo.getDistance(policeID, blockade.getID());
        if (distance < minDistance) {
          minDistance = distance;
          closest = blockade;
        }
      }

      if (closest != null) {
        allocation.put(policeID, closest.getID());
        allBlockades.remove(closest);
      }
    }

    return allocation;
  }

  @Override
  public PoliceTargetAllocator calc() {
    return this;
  }

  @Override
  public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }
}