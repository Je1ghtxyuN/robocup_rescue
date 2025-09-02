package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.RoadDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleRoadDetector extends RoadDetector {

  private Set<Area> openedAreas = new HashSet<>();
  private Clustering clustering;
  private PathPlanning pathPlanning;

  private EntityID result;
  private Logger logger;

  public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.pathPlanning = moduleManager.getModule(
        "SampleRoadDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustring");
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
    this.result = null;
  }


  @Override
  public RoadDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);
    return this;
  }


  @Override
  public RoadDetector calc() {
    EntityID positionID = this.agentInfo.getPosition();
    StandardEntity currentPosition = worldInfo.getEntity(positionID);
    openedAreas.add((Area) currentPosition);
    if (positionID.equals(result)) {
      logger.debug("reach to " + currentPosition + " resetting target");
      this.result = null;
    }

    if (this.result == null) {
      HashSet<Area> currentTargets = calcTargets();
      logger.debug("Targets: " + currentTargets);
      if (currentTargets.isEmpty()) {
        this.result = null;
        return this;
      }
      this.pathPlanning.setFrom(positionID);
      this.pathPlanning.setDestination(toEntityIds(currentTargets));
      List<EntityID> path = this.pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        this.result = path.get(path.size() - 1);
      }
      logger.debug("Selected Target: " + this.result);
    }
    return this;
  }


  private Collection<EntityID>
      toEntityIds(Collection<? extends StandardEntity> entities) {
    ArrayList<EntityID> eids = new ArrayList<>();
    for (StandardEntity standardEntity : entities) {
      eids.add(standardEntity.getID());
    }
    return eids;
  }


//   private HashSet<Area> calcTargets() {
//     HashSet<Area> targetAreas = new HashSet<>();
    
//     // 1. 添加避难所和加油站
//     for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, GAS_STATION)) {
//         targetAreas.add((Area) e);
//     }
    
//     // 2. 添加需要救援的人员位置
//     for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN,
//         AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)) {
//         if (isValidHuman(e)) {
//             Human h = (Human) e;
//             // 优化: 只添加高优先级人员位置
//             if (isHighPriorityHuman(h)) {
//                 targetAreas.add((Area) worldInfo.getEntity(h.getPosition()));
//             }
//         }
//     }
    
//     HashSet<Area> inClusterTarget = filterInCluster(targetAreas);
//     inClusterTarget.removeAll(openedAreas);
//     return inClusterTarget;
// }

// // 新增方法: 判断是否为高优先级人员
// private boolean isHighPriorityHuman(Human human) {
//   if (!human.isHPDefined() || !human.isDamageDefined()) {
//       return false;
//   }
//   // HP低于50或伤害高于50的视为高优先级
//   return human.getHP() < 50 || human.getDamage() > 50;
// }

private HashSet<Area> calcTargets() {
  HashSet<Area> targetAreas = new HashSet<>();
  logger.debug("[RoadDetector] Starting target area calculation");
  
  // 获取所有障碍物位置
  Set<EntityID> blockadePositions = new HashSet<>();
  for (StandardEntity e : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
      if (e instanceof Blockade) {
          blockadePositions.add(((Blockade)e).getPosition());
      }
  }
  logger.debug("[RoadDetector] Found " + blockadePositions.size() + " blocked road positions");
  
  // 1. 添加避难所和加油站
  int refugeCount = 0;
  int gasStationCount = 0;
  for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, GAS_STATION)) {
      // 优先选择没有障碍物的道路
      if (!blockadePositions.contains(e.getID())) {
          targetAreas.add((Area) e);
          if (e.getStandardURN() == REFUGE) refugeCount++;
          else gasStationCount++;
      }
  }
  logger.debug("[RoadDetector] Added " + refugeCount + " refuges and " + 
              gasStationCount + " gas stations without blockades");
  
  // 2. 添加需要救援的人员位置
  int humanTargets = 0;
  for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN,
      AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)) {
      if (isValidHuman(e)) {
          Human h = (Human) e;
          StandardEntity position = worldInfo.getEntity(h.getPosition());
          // 优先选择没有障碍物的道路
          if (position != null && !blockadePositions.contains(position.getID())) {
              targetAreas.add((Area) position);
              humanTargets++;
          }
      }
  }
  logger.debug("[RoadDetector] Added " + humanTargets + " human targets without blockades");
  
  // 如果没有无障碍物的目标，则添加有障碍物的目标
  if (targetAreas.isEmpty()) {
      logger.debug("[RoadDetector] No unblocked targets found, considering blocked targets");
      int blockedRefugeCount = 0;
      int blockedGasStationCount = 0;
      int blockedHumanTargets = 0;
      
      for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE, GAS_STATION)) {
          targetAreas.add((Area) e);
          if (e.getStandardURN() == REFUGE) blockedRefugeCount++;
          else blockedGasStationCount++;
      }
      
      for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN,
          AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)) {
          if (isValidHuman(e)) {
              Human h = (Human) e;
              StandardEntity position = worldInfo.getEntity(h.getPosition());
              if (position != null) {
                  targetAreas.add((Area) position);
                  blockedHumanTargets++;
              }
          }
      }
      
      logger.debug("[RoadDetector] Added " + blockedRefugeCount + " blocked refuges, " + 
                  blockedGasStationCount + " blocked gas stations, and " + 
                  blockedHumanTargets + " blocked human targets");
  }
  
  HashSet<Area> inClusterTarget = filterInCluster(targetAreas);
  inClusterTarget.removeAll(openedAreas);
  logger.debug("[RoadDetector] Final target count after clustering and filtering: " + 
              inClusterTarget.size());
  
  return inClusterTarget;
}

  private HashSet<Area> filterInCluster(HashSet<Area> targetAreas) {
    int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
    HashSet<Area> clusterTargets = new HashSet<>();
    HashSet<StandardEntity> inCluster = new HashSet<>(
        clustering.getClusterEntities(clusterIndex));
    for (Area target : targetAreas) {
      if (inCluster.contains(target))
        clusterTargets.add(target);

    }
    return clusterTargets;
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  private boolean isValidHuman(StandardEntity entity) {
    if (entity == null)
      return false;
    if (!(entity instanceof Human))
      return false;

    Human target = (Human) entity;
    if (!target.isHPDefined() || target.getHP() == 0)
      return false;
    if (!target.isPositionDefined())
      return false;
    if (!target.isDamageDefined() || target.getDamage() == 0)
      return false;
    if (!target.isBuriednessDefined())
      return false;

    StandardEntity position = worldInfo.getPosition(target);
    if (position == null)
      return false;

    StandardEntityURN positionURN = position.getStandardURN();
    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
      return false;

    return true;
  }
}