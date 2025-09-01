package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {
  private Map<EntityID, EntityID> allocation;
  private Collection<EntityID> policeForceIDs;
  private Collection<EntityID> blockadeIDs;

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.allocation = new HashMap<>();
  }

  @Override
  public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    return this;
  }

  @Override
  public PoliceTargetAllocator preparate() {
    super.preparate();
    this.policeForceIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE);
    this.blockadeIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.ROAD);
    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    return this.allocation;
  }

  @Override
  public PoliceTargetAllocator calc() {
    Map<EntityID, EntityID> newAllocation = new HashMap<>();
    List<EntityID> unassignedPoliceForces = new ArrayList<>(this.policeForceIDs);
    List<EntityID> unassignedBlockades = new ArrayList<>(this.blockadeIDs);

    // 为每个警察分配最近的路障
    for (EntityID policeForceID : unassignedPoliceForces) {
      if (unassignedBlockades.isEmpty())
        break;

      StandardEntity policeForce = this.worldInfo.getEntity(policeForceID);
      EntityID nearestBlockade = findNearest(policeForce, unassignedBlockades);

      if (nearestBlockade != null) {
        newAllocation.put(policeForceID, nearestBlockade);
        unassignedBlockades.remove(nearestBlockade);
      }
    }

    this.allocation = newAllocation;
    return this;
  }

  private EntityID findNearest(StandardEntity policeForce, List<EntityID> blockades) {
    EntityID nearest = null;
    int minDistance = Integer.MAX_VALUE;

    for (EntityID blockadeID : blockades) {
      StandardEntity blockade = this.worldInfo.getEntity(blockadeID);
      int distance = this.worldInfo.getDistance(policeForce, blockade);
      if (distance < minDistance) {
        minDistance = distance;
        nearest = blockadeID;
      }
    }

    return nearest;
  }

  @Override
  public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }
}