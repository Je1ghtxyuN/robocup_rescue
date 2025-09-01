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
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.ChangeSet;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  // 新增优先级因子计算器
  private interface PriorityCalculator {
    double calculatePriority(Building building, AgentInfo agentInfo);
  }

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;
  private Logger logger;

  // 新增私有成员变量
  private final Map<EntityID, Double> buildingPriorities = new HashMap<>();
  private final Set<EntityID> searchedBuildings = new HashSet<>();
  private long lastClusterUpdate = -1;
  private PriorityCalculator priorityCalculator;

  public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);

    logger = DefaultLogger.getLogger(agentInfo.me());
    this.unsearchedBuildingIDs = new HashSet<>();

    // 增加初始化优先级计算器（根据智能体类型）
    StandardEntityURN agentURN = ai.me().getStandardURN();
    if (agentURN == AMBULANCE_TEAM) {
      if (agentURN == AMBULANCE_TEAM) {
        priorityCalculator = (building, info) -> {
          // 根据伤员数量、伤势程度、时间因素计算优先级
          return building.getTotalVictims() * 5.0
              + building.getFieryness() * 0.2;
        };
      } else if (agentURN == FIRE_BRIGADE) {
        priorityCalculator = (building, info) -> {
          // 根据火势强度、建筑价值、蔓延风险计算
          return building.getFieryness() * 10.0
              + (building.isImportant() ? 50 : 0);
        };
      } else {
        priorityCalculator = (building, info) -> building.isGasLeaking() ? 100 : 1; // 警察优先处理燃气泄漏
      }
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

    // 将到达的建筑标记为已搜索
    if (result != null) {
      StandardEntity entity = worldInfo.getEntity(result);
      if (entity instanceof Building) {
        searchedBuildings.add(result);
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
      if (unsearchedBuildingIDs.isEmpty())
        return this; // 无可用目标
    }

    // 获取智能体当前位置
    EntityID currentPosition = agentInfo.getPosition();

    // 按优先级和距离综合排序建筑
    List<EntityID> sortedBuildings = unsearchedBuildingIDs.stream()
        .sorted(Comparator.<EntityID>comparingDouble(id -> -buildingPriorities.get(id)) // 降序排列
            .thenComparingDouble(id -> worldInfo.getDistance(currentPosition, id)) // 距离升序
        )
        .collect(Collectors.toList());

    // 选择最佳目标建筑
    EntityID bestBuilding = sortedBuildings.get(0);

    // 规划到建筑的路径
    pathPlanning.setFrom(currentPosition);
    pathPlanning.setDestination(bestBuilding);
    List<EntityID> path = pathPlanning.calc().getResult();

    // 安全路径检查（选择建筑入口点）
    if (path != null && !path.isEmpty()) {
      this.result = bestBuilding; // 直接以建筑为目标

      // 特殊状况：当路径终点不是建筑时
      if (!worldInfo.getEntity(result).equals(worldInfo.getEntity(path.get(path.size() - 1)))) {
        result = path.get(path.size() - 1); // 使用路径终点
      }
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

    // 收集并优先级排序
    for (StandardEntity entity : clusterEntities) {
      if (entity instanceof Building &&
          entity.getStandardURN() != REFUGE &&
          !searchedBuildings.contains(entity.getID())) {

        Building building = (Building) entity;
        double priority = priorityCalculator.calculatePriority(building, agentInfo);
        unsearchedBuildingIDs.add(building.getID());
        buildingPriorities.put(building.getID(), priority);
      }
    }
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }
}