package sample_team.module.complex;

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
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.properties.StandardPropertyURN;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.properties.IntProperty;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.log4j.Logger;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  // 新增优先级因子计算器
  private interface PriorityCalculator {
    double calculatePriority(Building building);
  }

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;
  private Logger logger;

  // 新增私有成员变量
  private final Map<EntityID, Double> buildingPriorities = new HashMap<>();
  private final Set<EntityID> searchedBuildings = new HashSet<>();
  private long lastClusterUpdate = -1;
  private PriorityCalculator priorityCalculator;

  // 定义关键属性URN
  private final int CASUALTIES_URN = StandardPropertyURN.CIVILIAN_CASUALTIES.getURN();
  private final int FIERYNESS_URN = StandardPropertyURN.FIERYNESS.getURN();
  private final int GAS_LEAK_URN = StandardPropertyURN.GAS_LEAK.getURN();

  public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);

    logger = DefaultLogger.getLogger(agentInfo.me());
    this.unsearchedBuildingIDs = new HashSet<>();

    // 根据智能体类型初始化模块
    StandardEntityURN agentURN = ai.me().getStandardURN();
    if (agentURN == StandardEntityURN.AMBULANCE_TEAM) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Ambulance",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
          "SampleSearch.Clustering.Ambulance",
          "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == StandardEntityURN.FIRE_BRIGADE) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Fire",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
          "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == StandardEntityURN.POLICE_FORCE) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Police",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
          "SampleSearch.Clustering.Police",
          "adf.impl.module.algorithm.KMeansClustering");
    }

    // 注册模块
    registerModule(this.clustering);
    registerModule(this.pathPlanning);

    // 初始化优先级计算器（根据智能体类型）
    if (agentURN == StandardEntityURN.AMBULANCE_TEAM) {
      priorityCalculator = (building) -> {
        // 直接使用属性URN整数值
        IntProperty victimsProp = (IntProperty) building.getProperty(CASUALTIES_URN);
        int victims = (victimsProp != null && victimsProp.isDefined()) ? victimsProp.getValue() : 0;

        IntProperty fierynessProp = (IntProperty) building.getProperty(FIERYNESS_URN);
        int fieryness = (fierynessProp != null && fierynessProp.isDefined()) ? fierynessProp.getValue() : 0;

        return victims * 5.0 + fieryness * 0.2;
      };
    } else if (agentURN == StandardEntityURN.FIRE_BRIGADE) {
      priorityCalculator = (building) -> {
        // 直接使用属性URN整数值
        IntProperty fierynessProp = (IntProperty) building.getProperty(FIERYNESS_URN);
        int fieryness = (fierynessProp != null && fierynessProp.isDefined()) ? fierynessProp.getValue() : 0;

        // 判断是否重要建筑
        StandardEntityURN urn = building.getStandardURN();
        boolean isImportant = urn == StandardEntityURN.REFUGE ||
            urn == StandardEntityURN.AMBULANCE_CENTRE ||
            urn == StandardEntityURN.FIRE_STATION ||
            urn == StandardEntityURN.POLICE_OFFICE;

        return fieryness * 10.0 + (isImportant ? 50 : 0);
      };
    } else if (agentURN == StandardEntityURN.POLICE_FORCE) {
      priorityCalculator = (building) -> {
        // 直接使用属性URN整数值
        IntProperty gasProp = (IntProperty) building.getProperty(GAS_LEAK_URN);
        int gasLevel = (gasProp != null && gasProp.isDefined()) ? gasProp.getValue() : 0;

        return gasLevel > 0 ? 100 : 1;
      };
    }
  }

  // 修改集群更新
  @Override
  public Search updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    long currentTime = agentInfo.getTime();

    // 动态更新集群（每100步更新一次）
    if (currentTime - lastClusterUpdate > 100) {
      clustering.calc(); // 重新计算集群划分
      lastClusterUpdate = currentTime;
    }

    // 移除已变化建筑
    ChangeSet changed = worldInfo.getChanged();
    unsearchedBuildingIDs.removeAll(changed.getChangedEntities());
    searchedBuildings.removeAll(changed.getChangedEntities());

    // 检查当前结果是否仍然有效
    if (result != null) {
      if (!unsearchedBuildingIDs.contains(result) || searchedBuildings.contains(result)) {
        result = null;
      }
    }

    return this;
  }

  // 修改计算函数
  @Override
  public Search calc() {
    this.result = null;
    if (unsearchedBuildingIDs.isEmpty()) {
      reset();
      if (unsearchedBuildingIDs.isEmpty()) {
        logger.debug("No searchable buildings found");
        return this; // 无可用目标
      }
    }

    // 获取智能体当前位置
    EntityID currentPosition = agentInfo.getPosition();

    // 按优先级和距离综合排序建筑
    List<EntityID> sortedBuildings = unsearchedBuildingIDs.stream()
        .sorted(Comparator.<EntityID>comparingDouble(id -> -buildingPriorities.getOrDefault(id, 0.0)) // 降序排列
            .thenComparingDouble(id -> worldInfo.getDistance(currentPosition, id)) // 距离升序
        )
        .collect(Collectors.toList());

    logger.debug("Sorted buildings: " + sortedBuildings);

    // 选择最佳目标建筑
    EntityID bestBuilding = sortedBuildings.get(0);

    // 规划到建筑的路径
    pathPlanning.setFrom(currentPosition);
    pathPlanning.setDestination(bestBuilding);
    List<EntityID> path = pathPlanning.calc().getResult();

    // 安全路径检查（选择建筑入口点）
    if (path != null && !path.isEmpty()) {
      logger.debug("Path to target found: " + path);
      this.result = bestBuilding; // 直接以建筑为目标

      // 特殊状况：当路径终点不是建筑时
      Entity targetEntity = worldInfo.getEntity(result);
      Entity lastPathEntity = worldInfo.getEntity(path.get(path.size() - 1));

      if (lastPathEntity instanceof Building && !lastPathEntity.equals(targetEntity)) {
        logger.debug("Path ends at different building, adjusting target");
        result = path.get(path.size() - 1);
      }
    } else {
      logger.debug("No path found to target, skipping: " + bestBuilding);
      // 如果路径不可达，移除该建筑
      unsearchedBuildingIDs.remove(bestBuilding);
      // 递归尝试下一个目标
      calc();
    }

    return this;
  }

  // 修改重置方法
  private void reset() {
    // 清除现有数据
    unsearchedBuildingIDs.clear();
    buildingPriorities.clear();

    int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
    Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

    if (clusterEntities == null || clusterEntities.isEmpty()) {
      logger.warn("Cluster entities are empty, falling back to all buildings");
      // 使用全地图建筑作为备选
      clusterEntities = worldInfo.getEntitiesOfType(StandardEntityURN.BUILDING);
    }

    // 收集并优先级排序
    for (StandardEntity entity : clusterEntities) {
      if (entity instanceof Building) {
        Building building = (Building) entity;
        StandardEntityURN urn = building.getStandardURN();

        // 排除避难所和已搜索的建筑
        if (urn != StandardEntityURN.REFUGE &&
            urn != StandardEntityURN.AMBULANCE_CENTRE &&
            urn != StandardEntityURN.FIRE_STATION &&
            urn != StandardEntityURN.POLICE_OFFICE &&
            !searchedBuildings.contains(building.getID())) {

          // 计算优先级
          double priority = priorityCalculator.calculatePriority(building);
          unsearchedBuildingIDs.add(building.getID());
          buildingPriorities.put(building.getID(), priority);
        }
      }
    }

    logger.debug("Reset search with " + unsearchedBuildingIDs.size() + " buildings");
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }
}