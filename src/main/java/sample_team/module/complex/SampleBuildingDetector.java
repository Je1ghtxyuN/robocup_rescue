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
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleBuildingDetector extends BuildingDetector {

  private EntityID result;
  private Clustering clustering;
  private PathPlanning pathPlanning; // 新增路径规划模块
  private Logger logger;
  private long lastClusterUpdate = -1; // 集群最后更新时间
  private List<Building> lastTargets = new ArrayList<>(); // 缓存上次计算的目标

  public SampleBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule(
        "SampleBuildingDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    this.pathPlanning = moduleManager.getModule( // 初始化路径规划
        "SampleBuildingDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.clustering);
    registerModule(this.pathPlanning); // 注册路径规划模块
  }

  @Override
  public BuildingDetector updateInfo(MessageManager messageManager) {
    long currentTime = agentInfo.getTime();
    logger.debug("Time:" + currentTime);
    super.updateInfo(messageManager);

    // 周期性更新集群信息（每100步更新一次）
    if (currentTime - lastClusterUpdate > 100) {
      clustering.calc();
      lastClusterUpdate = currentTime;
      lastTargets.clear(); // 集群更新后清空缓存
    }
    return this;
  }

  @Override
  public BuildingDetector calc() {
    this.result = this.calcTarget();
    return this;
  }

  private EntityID calcTarget() {
    // 获取所有相关建筑
    Collection<StandardEntity> entities = this.worldInfo.getEntitiesOfType(
        StandardEntityURN.BUILDING, StandardEntityURN.GAS_STATION,
        StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.FIRE_STATION,
        StandardEntityURN.POLICE_OFFICE);

    // 过滤出着火建筑
    List<Building> fireyBuildings = filterFiery(entities);
    if (fireyBuildings.isEmpty()) {
      logger.debug("No fiery buildings found");
      return null;
    }

    // 优先选择集群内的建筑
    List<Building> clusterBuildings = filterInCluster(fireyBuildings);
    List<Building> targets = clusterBuildings.isEmpty() ? fireyBuildings : clusterBuildings;

    // 使用优先级排序器
    Collections.sort(targets, new PrioritySorter(worldInfo, agentInfo.me()));

    // 检查路径可达性
    EntityID currentPosition = agentInfo.getPosition();
    for (Building building : targets) {
      pathPlanning.setFrom(currentPosition);
      pathPlanning.setDestination(building.getID());
      List<EntityID> path = pathPlanning.calc().getResult();

      if (path != null && !path.isEmpty()) {
        logger.debug("Selected building: " + building.getID() +
            " | Fieryness: " + building.getFieryness() +
            " | Path length: " + path.size());
        lastTargets = targets; // 缓存有效目标
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

  // 优化后的优先级排序器
  private class PrioritySorter implements Comparator<Building> {
    private StandardEntity reference;
    private WorldInfo worldInfo;

    PrioritySorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    public int compare(Building a, Building b) {
      // 计算优先级分数 = 紧急程度/距离
      double priorityA = calculatePriority(a);
      double priorityB = calculatePriority(b);
      return Double.compare(priorityB, priorityA); // 降序排列
    }

    private double calculatePriority(Building building) {
      // 紧急程度 = 火势严重度 + 伤员情况 + 建筑重要性
      double urgency = 0;

      // 火势严重度（0-8）
      if (building.isFierynessDefined()) {
        urgency += building.getFieryness() * 10; // 火势越严重优先级越高

        // 特别关注即将倒塌的建筑（火势等级3）
        if (building.getFieryness() == 3) {
          urgency += 50;
        }
      }

      // 伤员情况（如果有信息）
      if (building.isTotalVictimsDefined()) {
        urgency += building.getTotalVictims() * 5;
      }

      // 建筑重要性（如医院、避难所等）
      StandardEntityURN urn = building.getStandardURN();
      if (urn == StandardEntityURN.AMBULANCE_CENTRE) {
        urgency += 30;
      } else if (urn == StandardEntityURN.REFUGE) {
        urgency += 20;
      }

      // 距离因子（避免除零）
      int distance = worldInfo.getDistance(reference, building);
      return urgency / (distance + 1);
    }
  }
}