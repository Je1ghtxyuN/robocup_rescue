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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import java.util.Map;
import java.util.Iterator;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;
  private Logger logger;
  // 新增成员变量（记录受阻目标及其关联障碍）
  private Map<EntityID, EntityID> recoverableTargets = new HashMap<>();

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

    // 新增：处理世界变化时恢复目标
    checkRecoverableTargets();
    return this;
  }


  @Override
  public Search calc() {
    this.result = null;
    if (unsearchedBuildingIDs.isEmpty())
      return this;

    logger.debug("unsearchedBuildingIDs: " + unsearchedBuildingIDs);
    this.pathPlanning.setFrom(this.agentInfo.getPosition());
    this.pathPlanning.setDestination(this.unsearchedBuildingIDs);
    List<EntityID> path = this.pathPlanning.calc().getResult();
    logger.debug("best path is: " + path);

    // 记录原始目标建筑（用于后续恢复）
    EntityID originalTarget = null;
    if (path != null && !path.isEmpty()) {
        originalTarget = path.get(path.size() - 1);
    }

    StandardEntityURN agentURN = agentInfo.me().getStandardURN();
    if (agentURN == POLICE_FORCE && path != null && path.size() > 1) {
      // 优先处理路径上的障碍物
      for (int i = 1; i < path.size(); i++) { // 从当前位置之后的下一个点开始
        EntityID eid = path.get(i);
        StandardEntity entity = worldInfo.getEntity(eid);
        if (entity instanceof Road) {
          Road road = (Road) entity;
          if (road.isBlockadesDefined() && road.getBlockades() != null && !road.getBlockades().isEmpty()) {
            // 路径上有障碍物，优先返回该道路
            this.result = road.getID();
            logger.debug("Police: chose blockade road: " + this.result);
            return this;
          }
        }
      }
      // 路径上无障碍物，按原逻辑返回目标建筑
      if (path.size() > 2) {
        this.result = path.get(path.size() - 3);
      } else {
        this.result = path.get(path.size() - 1);
      }
      logger.debug("Police: chose building: " + this.result);
      return this;
    }
    
    // 救护/消防：障碍物检测与重规划
    else if ((agentURN == AMBULANCE_TEAM || agentURN == FIRE_BRIGADE) 
             && path != null && path.size() > 1) {
        // 检测路径上的第一个障碍物
        EntityID blockedRoad = findFirstBlockedRoadInPath(path);
        
        if (blockedRoad != null) {
            logger.debug("Detected blockade at: " + blockedRoad);
            
            if (originalTarget != null) {
                // 1. 暂时移出当前目标（非永久移除）
                unsearchedBuildingIDs.remove(originalTarget);
                
                // 2. 添加到待恢复列表（障碍清除后可恢复）
                addToRecoverableTargets(originalTarget, blockedRoad);
                
                logger.debug("Temporarily removed target: " + originalTarget);
            }
            
            // 3. 重新规划到剩余目标
            if (!unsearchedBuildingIDs.isEmpty()) {
                this.pathPlanning.setFrom(this.agentInfo.getPosition());
                this.pathPlanning.setDestination(this.unsearchedBuildingIDs);
                path = this.pathPlanning.calc().getResult();
                logger.debug("Replanned path: " + path);
            } else {
                path = null; // 无有效目标
            }
          }
        }
        
    // 非警察或无路径，保持原逻辑
    if (path != null && path.size() > 2) {
      this.result = path.get(path.size() - 3);
    } else if (path != null && path.size() > 0) {
      this.result = path.get(path.size() - 1);
    }
    logger.debug("chose: " + result);

    checkRecoverableTargets();// 检查恢复条件（每次计算时执行）

    return this;
  }

  // 检测路径中的第一个障碍物
private EntityID findFirstBlockedRoadInPath(List<EntityID> path) {
    // 从当前位置之后的下一个点开始检查（跳过当前位置）
    for (int i = 1; i < path.size(); i++) {
        EntityID eid = path.get(i);
        StandardEntity entity = worldInfo.getEntity(eid);
        if (entity instanceof Road) {
            Road road = (Road) entity;
            if (road.isBlockadesDefined() && 
                !road.getBlockades().isEmpty()) {
                return road.getID(); // 返回受阻道路ID
            }
        }
    }
    return null; // 无阻碍
}

// 添加到待恢复列表
private void addToRecoverableTargets(EntityID target, EntityID blockage) {
    recoverableTargets.put(target, blockage);
}

// 检查可恢复目标
private void checkRecoverableTargets() {
    Iterator<Map.Entry<EntityID, EntityID>> it = recoverableTargets.entrySet().iterator();
    while (it.hasNext()) {
        Map.Entry<EntityID, EntityID> entry = it.next();
        EntityID target = entry.getKey();
        EntityID blockage = entry.getValue();
        
        // 检查障碍物是否已清除
        StandardEntity road = worldInfo.getEntity(blockage);
        if (road instanceof Road) {
            Road r = (Road) road;
            if (!r.isBlockadesDefined() || r.getBlockades().isEmpty()) {
                // 障碍已清除，恢复目标
                unsearchedBuildingIDs.add(target);
                it.remove();
                logger.debug("Recovered target: " + target);
            }
        }
    }
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
}