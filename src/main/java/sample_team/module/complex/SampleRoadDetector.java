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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleRoadDetector extends RoadDetector {

  private final Set<EntityID> openedAreas = new HashSet<>();
  private final Clustering clustering;
  private final PathPlanning pathPlanning;
  private final Logger logger;

  private EntityID result;
  private int lastUpdateTime = -1;
  private Set<EntityID> cachedTargets = Collections.emptySet();
  private Set<EntityID> clusterEntityIds = Collections.emptySet();

  public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.pathPlanning = moduleManager.getModule(
        "SampleRoadDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
  }

  @Override
  public RoadDetector updateInfo(MessageManager messageManager) {
    if (logger.isDebugEnabled()) {
      logger.debug("Time:" + agentInfo.getTime());
    }
    super.updateInfo(messageManager);
    return this;
  }

  @Override
  public RoadDetector calc() {
    EntityID positionID = this.agentInfo.getPosition();
    StandardEntity currentPosition = worldInfo.getEntity(positionID);

    if (currentPosition instanceof Area) {
      openedAreas.add(positionID);
    }

    // 检查是否到达目标
    if (positionID.equals(result)) {
      if (logger.isDebugEnabled()) {
        logger.debug("Reached target, resetting");
      }
      result = null;
    }

    // 检查当前目标是否仍然有效
    if (result != null && !isValidTarget(result)) {
      if (logger.isDebugEnabled()) {
        logger.debug("Current target is no longer valid, resetting");
      }
      result = null;
    }

    // 需要选择新目标
    if (result == null) {
      Set<EntityID> targets = getTargets();

      if (!targets.isEmpty()) {
        pathPlanning.setFrom(positionID);
        pathPlanning.setDestination(targets);
        List<EntityID> path = pathPlanning.calc().getResult();

        if (path != null && !path.isEmpty()) {
          result = path.get(path.size() - 1);
          if (logger.isDebugEnabled()) {
            logger.debug("Selected target: " + result);
          }
        }
      }
    }

    return this;
  }

  private Set<EntityID> getTargets() {
    int currentTime = agentInfo.getTime();
    if (currentTime != lastUpdateTime) {
      updateCachedTargets(currentTime);
    }
    return cachedTargets;
  }

  private void updateCachedTargets(int currentTime) {
    // 获取聚类信息
    updateClusterInfo();

    // 计算新目标
    Set<EntityID> newTargets = new HashSet<>();

    // 添加特殊区域
    addSpecialAreas(newTargets);

    // 添加需要救援的人类所在区域
    addHumanAreas(newTargets);

    // 过滤出聚类内的目标并移除已探索区域
    cachedTargets = filterTargets(newTargets);

    lastUpdateTime = currentTime;
  }

  private void updateClusterInfo() {
    int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
    Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

    clusterEntityIds = new HashSet<>();
    if (clusterEntities != null) {
      for (StandardEntity entity : clusterEntities) {
        clusterEntityIds.add(entity.getID());
      }
    }
  }

  private void addSpecialAreas(Set<EntityID> targets) {
    for (StandardEntity e : worldInfo.getEntitiesOfType(REFUGE, GAS_STATION)) {
      targets.add(e.getID());
    }
  }

  private void addHumanAreas(Set<EntityID> targets) {
    for (StandardEntity e : worldInfo.getEntitiesOfType(CIVILIAN,
        AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)) {
      if (isValidHuman(e)) {
        Human h = (Human) e;
        targets.add(h.getPosition());
      }
    }
  }

  private Set<EntityID> filterTargets(Set<EntityID> targets) {
    Set<EntityID> filtered = new HashSet<>();

    for (EntityID target : targets) {
      if (clusterEntityIds.contains(target) && !openedAreas.contains(target)) {
        filtered.add(target);
      }
    }

    return filtered;
  }

  private boolean isValidTarget(EntityID target) {
    StandardEntity entity = worldInfo.getEntity(target);
    if (entity == null)
      return false;

    // 检查是否是特殊区域
    StandardEntityURN urn = entity.getStandardURN();
    if (urn == REFUGE || urn == GAS_STATION) {
      return true;
    }

    // 检查是否有需要救援的人类在这个区域
    Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(
        StandardEntityURN.CIVILIAN,
        StandardEntityURN.AMBULANCE_TEAM,
        StandardEntityURN.FIRE_BRIGADE,
        StandardEntityURN.POLICE_FORCE
    );
    
    for (StandardEntity humanEntity : humans) {
      if (humanEntity instanceof Human && isValidHuman(humanEntity)) {
        Human human = (Human) humanEntity;
        if (human.isPositionDefined() && human.getPosition().equals(target)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public EntityID getTarget() {
    return result;
  }

  private boolean isValidHuman(StandardEntity entity) {
    if (!(entity instanceof Human))
      return false;

    Human human = (Human) entity;

    // 检查基本属性
    if (!human.isHPDefined() || human.getHP() == 0 ||
        !human.isPositionDefined() ||
        !human.isDamageDefined() && !human.isBuriednessDefined()) {
      return false;
    }

    // 检查是否需要救援
    if ((human.isDamageDefined() && human.getDamage() > 0) ||
        (human.isBuriednessDefined() && human.getBuriedness() > 0)) {
      // 检查位置是否有效
      StandardEntity position = worldInfo.getEntity(human.getPosition());
      if (position == null)
        return false;

      // 检查是否在安全区域
      StandardEntityURN urn = position.getStandardURN();
      return urn != REFUGE && urn != AMBULANCE_TEAM;
    }

    return false;
  }
}