package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.Human;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import rescuecore2.worldmodel.EntityID;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
  }


  @Override
  public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    return this;
  }


  @Override
  public PoliceTargetAllocator preparate() {
    super.preparate();
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    return this;
  }


@Override
public Map<EntityID, EntityID> getResult() {
    Map<EntityID, EntityID> allocation = new HashMap<>();

    // 获取所有警察
    List<StandardEntity> policeAgents = new ArrayList<>();
    for (EntityID id : worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE)) {
        StandardEntity agent = worldInfo.getEntity(id);
        if (agent != null) {
            policeAgents.add(agent);
        }
    }

    // 获取所有消防/救护的目标
    List<EntityID> rescueTargets = new ArrayList<>();
    for (EntityID id : worldInfo.getEntityIDsOfType(StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.AMBULANCE_TEAM)) {
        // 获取对应的HumanDetector模块
        HumanDetector detector = moduleManager.getModule(
            "SampleHumanDetector", "sample_team.module.complex.SampleHumanDetector"
        );
        if (detector == null) continue;
        detector.calc(); // 确保目标已计算
        EntityID target = detector.getTarget();
        if (target != null) {
            rescueTargets.add(target);
        }
    }

    // 如果没有目标，则返回空分配
    if (rescueTargets.isEmpty()) {
        return allocation;
    }

    // 为每个警察分配最近的救助目标，并找到路径上的第一个有障碍物的道路
    for (StandardEntity police : policeAgents) {
        EntityID policeId = police.getID();

        // 获取警察当前位置 - 修改部分
        EntityID policePosition = null;
        if (police instanceof Human) {
            policePosition = ((Human)police).getPosition();
        } else {
            // 如果不是Human类型，使用WorldInfo获取位置
            StandardEntity positionEntity = worldInfo.getPosition(policeId);
            if (positionEntity != null) {
                policePosition = positionEntity.getID();
            }
        }
        
        if (policePosition == null) continue;

        // 选择最近的目标
        EntityID nearestTarget = null;
        int minDist = Integer.MAX_VALUE;
        for (EntityID target : rescueTargets) {
            int dist = worldInfo.getDistance(policePosition, target);
            if (dist >= 0 && dist < minDist) {
                minDist = dist;
                nearestTarget = target;
            }
        }
        if (nearestTarget == null) continue;

        // 路径规划
        PathPlanning pathPlanning = moduleManager.getModule(
            "SampleSearch.PathPlanning.Police", "adf.impl.module.algorithm.DijkstraPathPlanning"
        );
        if (pathPlanning == null) continue;
        
        pathPlanning.setFrom(policePosition);
        List<EntityID> dest = new ArrayList<>();
        dest.add(nearestTarget);
        pathPlanning.setDestination(dest);
        List<EntityID> path = pathPlanning.calc().getResult();

        // 找到路径上的第一个有障碍物的道路
        EntityID blockadeRoad = null;
        if (path != null && path.size() > 1) {
            for (int i = 1; i < path.size(); i++) {
                StandardEntity entity = worldInfo.getEntity(path.get(i));
                if (entity instanceof Road) {
                    Road road = (Road) entity;
                    if (road.isBlockadesDefined() && road.getBlockades() != null && !road.getBlockades().isEmpty()) {
                        blockadeRoad = road.getID();
                        break;
                    }
                }
            }
        }
        // 如果路径上没有障碍物，则目标为救助目标本身
        if (blockadeRoad != null) {
            allocation.put(policeId, blockadeRoad);
        } else {
            allocation.put(policeId, nearestTarget);
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
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    return this;
  }
}