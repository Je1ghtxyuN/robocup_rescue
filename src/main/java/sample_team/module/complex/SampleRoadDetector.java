package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
//import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import rescuecore2.standard.entities.Road;
//import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
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
  private static class ScoredTarget {
    final Area area;
    final int score;
    
    ScoredTarget(Area area, int score) {
        this.area = area;
        this.score = score;
    }
}

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
      if (positionID == null) {
          logger.error("Invalid current position");
          this.result = null;
          return this;
      }

      // 检查当前位置障碍物
      StandardEntity currentEntity = worldInfo.getEntity(positionID);
      if (currentEntity instanceof Road) {
          Road currentRoad = (Road) currentEntity;
          if (currentRoad.isBlockadesDefined() && !currentRoad.getBlockades().isEmpty()) {
              logger.debug("Processing current blockade: " + positionID);
              this.result = positionID;
              return this;
          }
      }

      // 到达目标时重置
      if (positionID.equals(result)) {
          logger.debug("Reached target, resetting");
          openedAreas.add((Area) currentEntity);
          this.result = null;
      }

      // 仅当无目标时处理
      if (this.result == null) {
          // 获取原始目标集
          HashSet<Area> currentTargets = calcTargets();
          logger.debug("Initial targets: " + currentTargets.size());
          
          // 创建带优先级的目标列表
          List<ScoredTarget> scoredTargets = new ArrayList<>();
          
          // 预先收集关键建筑ID（消防站、避难所等）
          Set<EntityID> keyBuildingIDs = new HashSet<>();
          for (StandardEntity e : this.worldInfo.getEntitiesOfType(
              StandardEntityURN.FIRE_STATION,
              StandardEntityURN.REFUGE,
              StandardEntityURN.POLICE_OFFICE,
              StandardEntityURN.AMBULANCE_CENTRE)) {
              keyBuildingIDs.add(e.getID());
          }
          
          for (Area area : currentTargets) {
              if (area instanceof Road) {
                  Road road = (Road) area;
                  Collection<Blockade> blockades = worldInfo.getBlockades(road.getID());
                  
                  if (blockades != null && !blockades.isEmpty()) {
                      int score = 0;
                      
                      // 1. 基础分数：阻塞严重程度
                      int severity = blockades.stream()
                              .mapToInt(b -> b.isRepairCostDefined() ? b.getRepairCost() : 100)
                              .sum();
                      score += severity;
                      
                      // 2. 主干道识别：使用邻居数量作为代理指标
                      // Area类没有宽度属性，改用邻居数量判断重要性
                      if (road.isEdgesDefined()) {
                          int neighbourCount = road.getNeighbours().size();
                          score += neighbourCount * 5; // 邻居越多越可能是主干道
                      }
                      
                      // 3. 关键建筑入口识别
                      // 使用实际存在的edges属性检查连接关系
                      if (road.isEdgesDefined()) {
                          for (Edge edge : road.getEdges()) {
                              EntityID neighbourID = edge.getNeighbour();
                              if (keyBuildingIDs.contains(neighbourID)) {
                                  // 关键建筑入口加高优先级
                                  score += 200;
                                  logger.debug("Key building connected: " + neighbourID);
                              }
                          }
                      }
                      
                      scoredTargets.add(new ScoredTarget(area, score));
                  }
              }
          }
          
          // 按优先级排序（降序）
          scoredTargets.sort((a, b) -> b.score - a.score);
          
          // 选择前5个最高优先级目标
          Collection<Area> highPriorityTargets = new HashSet<>();
          int targetCount = Math.min(5, scoredTargets.size());
          for (int i = 0; i < targetCount; i++) {
              highPriorityTargets.add(scoredTargets.get(i).area);
          }
          
          // 使用高优先级目标或回退到原始目标
          final Collection<Area> finalTargets = !highPriorityTargets.isEmpty() ? 
              highPriorityTargets : currentTargets;
              
          logger.debug("Final targets: " + finalTargets.size());
          
          if (finalTargets.isEmpty()) {
              // 避免发呆：重置区域缓存
              logger.warn("No targets, resetting opened areas");
              openedAreas.clear();
              this.result = null;
              return this;
          }

          // 路径规划
          this.pathPlanning.setFrom(positionID);
          this.pathPlanning.setDestination(toEntityIds(finalTargets));
          List<EntityID> path = this.pathPlanning.calc().getResult();
          
          // 结果处理
          if (path != null && !path.isEmpty()) {
              // 从当前位置开始扫描路径障碍
              for (int i = 0; i < path.size(); i++) {
                  EntityID candidate = path.get(i);
                  StandardEntity entity = worldInfo.getEntity(candidate);
                  if (entity instanceof Road) {
                      Road road = (Road) entity;
                      if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                          this.result = candidate;
                          logger.debug("Selecting path blockade: " + candidate);
                          return this;
                      }
                  }
              }
              
              // 没有障碍物则选择最终目标
              this.result = path.get(path.size() - 1);
              logger.debug("New target: " + this.result);
          } else {
              // 避免死循环：标记不可达目标
              logger.warn("Unreachable targets, marking as opened");
              openedAreas.addAll(finalTargets);
              this.result = null;
          }
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


  private HashSet<Area> calcTargets() {
    HashSet<Area> targetAreas = new HashSet<>();
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(REFUGE,
        GAS_STATION)) {
      targetAreas.add((Area) e);
    }
    for (StandardEntity e : this.worldInfo.getEntitiesOfType(CIVILIAN,
        AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)) {
      if (isValidHuman(e)) {
        Human h = (Human) e;
        targetAreas.add((Area) worldInfo.getEntity(h.getPosition()));
      }
    }
    HashSet<Area> inClusterTarget = filterInCluster(targetAreas);
    inClusterTarget.removeAll(openedAreas);
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
