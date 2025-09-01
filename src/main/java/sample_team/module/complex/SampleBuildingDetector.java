package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.BuildingDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleBuildingDetector extends BuildingDetector {

  private EntityID result;
  private Clustering clustering;
  private PathPlanning pathPlanning; 
  private Logger logger;
  private long lastClusterUpdate = -1;
  private List<Building> lastTargets = new ArrayList<>();

  public SampleBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule(
        "SampleBuildingDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    this.pathPlanning = moduleManager.getModule(
        "SampleBuildingDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
  }

  @Override
  public BuildingDetector updateInfo(MessageManager messageManager) {
    long currentTime = agentInfo.getTime();
    logger.debug("Time:" + currentTime);
    super.updateInfo(messageManager);

    if (currentTime - lastClusterUpdate > 100) {
      clustering.calc();
      lastClusterUpdate = currentTime;
      lastTargets.clear();
    }
    return this;
  }

  @Override
  public BuildingDetector calc() {
    this.result = this.calcTarget();
    return this;
  }

  private EntityID calcTarget() {
    Collection<StandardEntity> entities = this.worldInfo.getEntitiesOfType(
        StandardEntityURN.BUILDING, StandardEntityURN.GAS_STATION,
        StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.FIRE_STATION,
        StandardEntityURN.POLICE_OFFICE);

    List<Building> fireyBuildings = filterFiery(entities);
    if (fireyBuildings.isEmpty()) {
      logger.debug("No fiery buildings found");
      return null;
    }

    List<Building> clusterBuildings = filterInCluster(fireyBuildings);
    List<Building> targets = clusterBuildings.isEmpty() ? fireyBuildings : clusterBuildings;

    Collections.sort(targets, new PrioritySorter(worldInfo, agentInfo.me()));

    EntityID currentPosition = agentInfo.getPosition();
    for (Building building : targets) {
      pathPlanning.setFrom(currentPosition);
      pathPlanning.setDestination(building.getID());
      List<EntityID> path = pathPlanning.calc().getResult();

      if (path != null && !path.isEmpty()) {
        logger.debug("Selected building: " + building.getID() +
            " | Fieryness: " + building.getFieryness() +
            " | Path length: " + path.size());
        lastTargets = targets;
        return building.getID();
      } else {
        logger.debug("No path to building: " + building.getID());
      }
    }

    logger.debug("No reachable target found");
    return null;
  }

  private List<Building> filterFiery(Collection<? extends StandardEntity> input) {
    ArrayList<Building> fireBuildings = new ArrayList<>();
    for (StandardEntity entity : input) {
      if (entity instanceof Building && ((Building) entity).isOnFire()) {
        fireBuildings.add((Building) entity);
      }
    }
    return fireBuildings;
  }

  private List<Building> filterInCluster(Collection<Building> targetAreas) {
    int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
    List<Building> clusterTargets = new ArrayList<>();
    HashSet<StandardEntity> inCluster = new HashSet<>(
        clustering.getClusterEntities(clusterIndex));
    for (Building target : targetAreas) {
      if (inCluster.contains(target))
        clusterTargets.add(target);
    }
    return clusterTargets;
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  private class PrioritySorter implements Comparator<Building> {
    private StandardEntity reference;
    private WorldInfo worldInfo;

    PrioritySorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    public int compare(Building a, Building b) {
      double priorityA = calculatePriority(a);
      double priorityB = calculatePriority(b);
      return Double.compare(priorityB, priorityA);
    }

    private double calculatePriority(Building building) {
      double urgency = 0;

      if (building.isFierynessDefined()) {
        urgency += building.getFieryness() * 10;

        if (building.getFieryness() == 3) {
          urgency += 50;
        }
      }

      int victimCount = 0;
      // 替换为正确的方法：获取所有平民实体
      Collection<StandardEntity> civilians = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
      for (StandardEntity entity : civilians) {
        if (entity instanceof Civilian) {
          Civilian civilian = (Civilian) entity;
          // 检查平民位置是否在目标建筑物内
          if (civilian.isPositionDefined() && civilian.getPosition().equals(building.getID())) {
            // 检查平民是否受伤
            if (civilian.isDamageDefined() && civilian.getDamage() > 0) {
              victimCount++;
            }
          }
        }
      }
      urgency += victimCount * 5;

      StandardEntityURN urn = building.getStandardURN();
      if (urn == StandardEntityURN.AMBULANCE_CENTRE) {
        urgency += 30;
      } else if (urn == StandardEntityURN.REFUGE) {
        urgency += 20;
      }

      int distance = worldInfo.getDistance(reference, building);
      return urgency / (distance + 1);
    }
  }
}