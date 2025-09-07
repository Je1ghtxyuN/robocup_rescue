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

import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Road;
//import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;
  private EntityID currentTargetBuilding; // 当前正在前往的建筑
  private Set<EntityID> searchedBuildings = new HashSet<>();
  private Logger logger;

  public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
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
    // 检测是否到达建筑
    if (currentTargetBuilding != null && 
        currentTargetBuilding.equals(agentInfo.getPosition())) {
        searchedBuildings.add(currentTargetBuilding);
        unsearchedBuildingIDs.remove(currentTargetBuilding);
        currentTargetBuilding = null;
        logger.info("Marked building as searched: " + currentTargetBuilding);
    }
    
    return this;
  }


  @Override
public Search calc() {
    // 1. 重置结果和当前目标
    this.result = null;
    this.currentTargetBuilding = null;  // 新增：重置当前目标建筑

    // 2. 如果没有待搜索建筑，直接返回
    if (unsearchedBuildingIDs.isEmpty()) {
        return this;
    }

    logger.debug("unsearchedBuildingIDs: " + unsearchedBuildingIDs);
    
    // 3. 路径规划
    this.pathPlanning.setFrom(this.agentInfo.getPosition());
    this.pathPlanning.setDestination(this.unsearchedBuildingIDs);
    List<EntityID> path = this.pathPlanning.calc().getResult();
    logger.debug("best path is: " + path);

    // 4. 路径有效性检查
    if (path == null || path.isEmpty()) {
        logger.warn("No valid path to targets, marking as unreachable");
        // 移除所有无法到达的建筑
        unsearchedBuildingIDs.clear();
        return this;
    }

    // 5. 获取最终目标建筑 (路径终点)
    EntityID targetBuilding = path.get(path.size() - 1);
    this.currentTargetBuilding = targetBuilding;  // 记录当前目标建筑
    
    // 6. 警察特殊处理：优先清理路径障碍
    StandardEntityURN agentURN = agentInfo.me().getStandardURN();
    if (agentURN == POLICE_FORCE) {
        // 检查路径上是否有障碍物
        for (int i = 0; i < path.size(); i++) {  // 从当前位置开始检查
            EntityID eid = path.get(i);
            StandardEntity entity = worldInfo.getEntity(eid);
            
            // 发现道路障碍物
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && road.getBlockades() != null && !road.getBlockades().isEmpty()) {
                    this.result = road.getID();
                    logger.debug("Police: chose blockade road: " + this.result);
                    return this;
                }
            }
        }
    }
    
    // 7. 设置最终目标
    // 直接选择路径终点（建筑本身）
    this.result = targetBuilding;
    logger.debug("Set target building: " + this.result);
    
    return this;
}
  private void reset() {
    this.unsearchedBuildingIDs.clear();
    this.searchedBuildings.clear(); // 重置已搜索记录
    
    int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
    Collection<StandardEntity> clusterEntities = this.clustering.getClusterEntities(clusterIndex);
    
    if (clusterEntities != null && !clusterEntities.isEmpty()) {
        for (StandardEntity entity : clusterEntities) {
            if (entity instanceof Building && entity.getStandardURN() != REFUGE
                && !searchedBuildings.contains(entity.getID())) { // 排除已搜索
                this.unsearchedBuildingIDs.add(entity.getID());
            }
        }
    } else {
        // 原始逻辑，但排除已搜索建筑
        for (EntityID id : this.worldInfo.getEntityIDsOfType(BUILDING, GAS_STATION, 
                AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE)) {
            if (!searchedBuildings.contains(id)) {
                this.unsearchedBuildingIDs.add(id);
            }
        }
    }
}


  @Override
  public EntityID getTarget() {
    return this.result;
  }
}