package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.Human; // 新增导入
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SampleAmbulanceTargetAllocator extends AmbulanceTargetAllocator {
  private Map<EntityID, EntityID> allocation;
  private Collection<EntityID> ambulanceIDs;
  private Collection<EntityID> humanIDs;

  public SampleAmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.allocation = new HashMap<>();
  }

  @Override
  public AmbulanceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }

  @Override
  public AmbulanceTargetAllocator preparate() {
    super.preparate();
    this.ambulanceIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.AMBULANCE_TEAM);
    this.humanIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.CIVILIAN);
    this.humanIDs.addAll(this.worldInfo.getEntityIDsOfType(StandardEntityURN.FIRE_BRIGADE));
    this.humanIDs.addAll(this.worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE));
    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    return this.allocation;
  }

  @Override
  public AmbulanceTargetAllocator calc() {
    Map<EntityID, EntityID> newAllocation = new HashMap<>();
    List<EntityID> unassignedAmbulances = new ArrayList<>(this.ambulanceIDs);
    List<EntityID> unassignedHumans = new ArrayList<>(this.humanIDs);

    // 按受伤程度排序人类（HP值越低，优先级越高）
    unassignedHumans.sort((h1, h2) -> {
      Human human1 = (Human) this.worldInfo.getEntity(h1);
      Human human2 = (Human) this.worldInfo.getEntity(h2);
      int hp1 = human1.getHP();
      int hp2 = human2.getHP();
      return Integer.compare(hp1, hp2); // 升序排序，HP值小的在前
    });

    // 为每个救护车分配最近的受伤人类
    for (EntityID ambulanceID : unassignedAmbulances) {
      if (unassignedHumans.isEmpty())
        break;

      StandardEntity ambulance = this.worldInfo.getEntity(ambulanceID);
      EntityID nearestHuman = findNearest(ambulance, unassignedHumans);

      if (nearestHuman != null) {
        newAllocation.put(ambulanceID, nearestHuman);
        unassignedHumans.remove(nearestHuman);
      }
    }

    this.allocation = newAllocation;
    return this;
  }

  private EntityID findNearest(StandardEntity ambulance, List<EntityID> humans) {
    EntityID nearest = null;
    int minDistance = Integer.MAX_VALUE;

    for (EntityID humanID : humans) {
      StandardEntity human = this.worldInfo.getEntity(humanID);
      int distance = this.worldInfo.getDistance(ambulance, human);
      if (distance < minDistance) {
        minDistance = distance;
        nearest = humanID;
      }
    }

    return nearest;
  }

  @Override
  public AmbulanceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }
}