package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_OFFICE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import adf.core.debug.DefaultLogger;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;
  private Logger logger;

  public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.unsearchedBuildingIDs = new HashSet<>();

    StandardEntityURN agentURN = ai.me().getStandardURN();
    if (agentURN == AMBULANCE_TEAM) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Ambulance",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
          "SampleSearch.Clustering.Ambulance",
          "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == FIRE_BRIGADE) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Fire",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
          "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == POLICE_FORCE) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Police",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
          "SampleSearch.Clustering.Police",
          "adf.impl.module.algorithm.KMeansClustering");
    }
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
  }

  @Override
  public Search updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);

    this.unsearchedBuildingIDs
        .removeAll(this.worldInfo.getChanged().getChangedEntities());
    if (this.unsearchedBuildingIDs.isEmpty()) {
      this.reset();
      this.unsearchedBuildingIDs
          .removeAll(this.worldInfo.getChanged().getChangedEntities());
    }
    return this;
  }

  @Override
public Search calc() {
    this.result = null;
    if (unsearchedBuildingIDs.isEmpty()) {
      return this;
    }

    logger.debug("unsearchedBuildingIDs: " + unsearchedBuildingIDs);
    this.pathPlanning.setFrom(this.agentInfo.getPosition());

    // 获取优先级目标
    Set<EntityID> priorityTargets = getPriorityTargets();

    // 如果没有找到优先级目标，使用默认目标
    if (priorityTargets.isEmpty()) {
      this.pathPlanning.setDestination(this.unsearchedBuildingIDs);
    } else {
      this.pathPlanning.setDestination(priorityTargets);
    }

    // 计算路径
    List<EntityID> path = this.pathPlanning.calc().getResult();

    // 路径容错处理 - 如果路径计算失败，选择备用目标
    if (path == null || path.isEmpty()) {
      logger.debug("Path planning failed, selecting alternative target");
      path = handlePathPlanningFailure();
    }

    if (path != null && path.size() > 2) {
        this.result = path.get(path.size() - 3);
    } else if (path != null && path.size() > 0) {
        this.result = path.get(path.size() - 1);
    }

    logger.debug("chose: " + result);
    return this;
}

  private List<EntityID> handlePathPlanningFailure() {
    // 尝试选择更近的目标
    EntityID currentPosition = agentInfo.getPosition();
    List<EntityID> sortedTargets = unsearchedBuildingIDs.stream()
        .sorted((a, b) -> {
          // 获取实体对象，然后计算距离
          StandardEntity entityA = worldInfo.getEntity(a);
          StandardEntity entityB = worldInfo.getEntity(b);
          int distA = worldInfo.getDistance(currentPosition, entityA.getID());
          int distB = worldInfo.getDistance(currentPosition, entityB.getID());
          return Integer.compare(distA, distB);
        })
        .collect(Collectors.toList());

    if (!sortedTargets.isEmpty()) {
      pathPlanning.setDestination(Collections.singleton(sortedTargets.get(0)));
      return pathPlanning.calc().getResult();
    }

    return null;
  }

  private void reset() {
    this.unsearchedBuildingIDs.clear();
    int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
    Collection<StandardEntity> clusterEntities = this.clustering
        .getClusterEntities(clusterIndex);
    if (clusterEntities != null && clusterEntities.size() > 0) {
      for (StandardEntity entity : clusterEntities) {
        if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
          this.unsearchedBuildingIDs.add(entity.getID());
        }
      }
    } else {
      this.unsearchedBuildingIDs
          .addAll(this.worldInfo.getEntityIDsOfType(BUILDING, GAS_STATION,
              AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE));
    }
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  // 使用ADF标准接口获取优先级目标
  private Set<EntityID> getPriorityTargets() {
    Set<EntityID> priorityTargets = new HashSet<>();
    Map<EntityID, Double> targetScores = new HashMap<>();

    for (EntityID id : unsearchedBuildingIDs) {
      StandardEntity entity = worldInfo.getEntity(id);
      if (entity instanceof Building) {
        Building building = (Building) entity;
        double score = calculateBuildingPriority(building);
        targetScores.put(id, score);
      }
    }

    // 选择得分最高的前3个目标
    targetScores.entrySet().stream()
        .sorted(Map.Entry.<EntityID, Double>comparingByValue().reversed())
        .limit(3)
        .forEach(entry -> priorityTargets.add(entry.getKey()));

    return priorityTargets;
  }

  /**
   * 计算建筑优先级得分
   */
  private double calculateBuildingPriority(Building building) {
    double score = 0.0;
    EntityID myPosition = agentInfo.getPosition();

    // 1. 火势因素（火势越大优先级越高）
    if (building.isOnFire()) {
      score += building.getFieryness() * 100.0;
    }

    // 2. 距离因素（距离越近优先级越高）
    int distance = worldInfo.getDistance(myPosition, building.getID());
    score += 500.0 / Math.max(1, distance);

    // 3. 建筑重要性因素（根据建筑类型）
    score += getBuildingImportance(building);

    return score;
  }

  /**
   * 根据建筑类型确定重要性得分
   */
  private double getBuildingImportance(Building building) {
    StandardEntityURN urn = building.getStandardURN();
    if (urn == StandardEntityURN.REFUGE) {
      return 300.0;
    } else if (urn == StandardEntityURN.AMBULANCE_CENTRE ||
        urn == StandardEntityURN.FIRE_STATION ||
        urn == StandardEntityURN.POLICE_OFFICE) {
      return 200.0;
    } else if (urn == StandardEntityURN.GAS_STATION) {
      return -100.0; // 加油站危险，优先级较低
    }
    return 0.0;
  }

}