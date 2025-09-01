package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.FireTargetAllocator;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SampleFireTargetAllocator extends FireTargetAllocator {
  private Map<EntityID, EntityID> allocation;
  private Collection<EntityID> fireBrigadeIDs;
  private Collection<EntityID> fireIDs; // 存储燃烧建筑的ID

  public SampleFireTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.allocation = new HashMap<>();
  }

  @Override
  public FireTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }

  @Override
  public FireTargetAllocator preparate() {
    super.preparate();
    // 获取所有消防队ID
    this.fireBrigadeIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.FIRE_BRIGADE);

    // 获取所有燃烧的建筑ID (火灾)
    this.fireIDs = new HashSet<>();
    for (EntityID id : this.worldInfo.getEntityIDsOfType(StandardEntityURN.BUILDING)) {
      Building building = (Building) this.worldInfo.getEntity(id);
      if (building.isFierynessDefined() && building.getFieryness() > 0) {
        fireIDs.add(id);
      }
    }
    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    return this.allocation;
  }

  @Override
  public FireTargetAllocator calc() {
    Map<EntityID, EntityID> newAllocation = new HashMap<>();
    List<EntityID> unassignedFireBrigades = new ArrayList<>(this.fireBrigadeIDs);
    List<EntityID> unassignedFires = new ArrayList<>(this.fireIDs);

    // 按火势大小排序 (降序)
    unassignedFires.sort((f1, f2) -> {
      Building building1 = (Building) this.worldInfo.getEntity(f1);
      Building building2 = (Building) this.worldInfo.getEntity(f2);
      int fieryness1 = building1.getFieryness();
      int fieryness2 = building2.getFieryness();
      return Integer.compare(fieryness2, fieryness1); // 降序排序
    });

    // 为每个消防队分配最近的火灾
    for (EntityID fireBrigadeID : unassignedFireBrigades) {
      if (unassignedFires.isEmpty())
        break;

      StandardEntity fireBrigade = this.worldInfo.getEntity(fireBrigadeID);
      EntityID nearestFire = findNearest(fireBrigade, unassignedFires);

      if (nearestFire != null) {
        newAllocation.put(fireBrigadeID, nearestFire);
        unassignedFires.remove(nearestFire);
      }
    }

    this.allocation = newAllocation;
    return this;
  }

  private EntityID findNearest(StandardEntity fireBrigade, List<EntityID> fires) {
    EntityID nearest = null;
    int minDistance = Integer.MAX_VALUE;

    for (EntityID fireID : fires) {
      StandardEntity fire = this.worldInfo.getEntity(fireID);
      int distance = this.worldInfo.getDistance(fireBrigade, fire);
      if (distance < minDistance) {
        minDistance = distance;
        nearest = fireID;
      }
    }

    return nearest;
  }

  @Override
  public FireTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }
}