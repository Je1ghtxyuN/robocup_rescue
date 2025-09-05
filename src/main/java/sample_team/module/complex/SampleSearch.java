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
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  private EntityID result;
  private Collection<EntityID> unsearchedBuildingIDs;
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

    // 非警察或无路径，保持原逻辑
    if (path != null && path.size() > 2) {
      this.result = path.get(path.size() - 3);
    } else if (path != null && path.size() > 0) {
      this.result = path.get(path.size() - 1);
    }
    logger.debug("chose: " + result);
    return this;
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