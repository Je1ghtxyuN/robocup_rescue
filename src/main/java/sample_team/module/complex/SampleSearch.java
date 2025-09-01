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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Comparator;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import org.apache.log4j.Logger;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.worldmodel.Entity;

public class SampleSearch extends Search {

    private PathPlanning pathPlanning;
    private Clustering clustering;
    private EntityID result;
    private Collection<EntityID> unsearchedBuildingIDs;
    private Collection<EntityID> importantTargets;
    private Logger logger;
    private Map<EntityID, Integer> buildingImportance;

    public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.unsearchedBuildingIDs = new HashSet<>();
        this.importantTargets = new HashSet<>();
        this.buildingImportance = new HashMap<>();

        // 根据代理类型初始化模块
        StandardEntityURN agentURN = ai.me().getStandardURN();
        String pathPlanningModule = "adf.impl.module.algorithm.AStarPathPlanning";
        String clusteringModule = "adf.impl.module.algorithm.KMeansClustering";

        if (agentURN == StandardEntityURN.AMBULANCE_TEAM) {
            this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance", pathPlanningModule);
            this.clustering = moduleManager.getModule("SampleSearch.Clustering.Ambulance", clusteringModule);
        } else if (agentURN == StandardEntityURN.FIRE_BRIGADE) {
            this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire", pathPlanningModule);
            this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire", clusteringModule);
        } else if (agentURN == StandardEntityURN.POLICE_FORCE) {
            this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police", pathPlanningModule);
            this.clustering = moduleManager.getModule("SampleSearch.Clustering.Police", clusteringModule);
        }

        // 初始化所有模块
        registerModule(this.clustering);
        registerModule(this.pathPlanning);
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        logger.debug("Time:" + agentInfo.getTime());
        
        // 从世界状态变化中移除已改变或已搜索的建筑物
        ChangeSet changed = this.worldInfo.getChanged();
        if (changed != null) {
            Collection<EntityID> changedEntities = changed.getChangedEntities();
            this.unsearchedBuildingIDs.removeAll(changedEntities);
            this.importantTargets.removeAll(changedEntities);
        }

        // 动态更新建筑物重要性评分
        updateBuildingImportance();

        // 如果待搜索列表为空，重置
        if (this.unsearchedBuildingIDs.isEmpty() && this.importantTargets.isEmpty()) {
            this.reset();
        }

        return this;
    }

    @Override
    public Search calc() {
        this.result = null;

        // 优先处理高优先级目标（已知有人的建筑物）
        if (!this.importantTargets.isEmpty()) {
            this.pathPlanning.setFrom(this.agentInfo.getPosition());
            this.pathPlanning.setDestination(this.importantTargets);
            List<EntityID> path = this.pathPlanning.calc().getResult();
            if (path != null && !path.isEmpty()) {
                this.result = getFinalTarget(path);
                return this;
            }
        }

        // 最后处理普通未搜索建筑物
        if (!this.unsearchedBuildingIDs.isEmpty()) {
            // 选择最近或最易到达的建筑物
            EntityID nearest = findNearestBuilding(this.unsearchedBuildingIDs);
            if (nearest != null) {
                this.pathPlanning.setFrom(this.agentInfo.getPosition());
                this.pathPlanning.setDestination(nearest);
                List<EntityID> path = this.pathPlanning.calc().getResult();
                if (path != null && !path.isEmpty()) {
                    this.result = getFinalTarget(path);
                }
            }
        }

        return this;
    }

    /**
     * 根据路径计算最终目标点
     * 选择路径中倒数第三个点作为目标，以便更早开始准备救援操作
     */
    private EntityID getFinalTarget(List<EntityID> path) {
        if (path.size() > 2) {
            return path.get(path.size() - 3);
        } else if (!path.isEmpty()) {
            return path.get(path.size() - 1);
        }
        return null;
    }

    /**
     * 找出最近的未搜索建筑物
     */
    private EntityID findNearestBuilding(Collection<EntityID> buildingIDs) {
        EntityID currentPosition = agentInfo.getPosition();
        EntityID nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (EntityID buildingID : buildingIDs) {
            StandardEntity entity = worldInfo.getEntity(buildingID);
            if (entity != null) {
                int distance = worldInfo.getDistance(currentPosition, buildingID);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = buildingID;
                }
            }
        }
        return nearest;
    }

    /**
     * 动态更新建筑物重要性评分
     * 有人的建筑物、火势严重的建筑物等获得更高优先级
     */
    private void updateBuildingImportance() {
        buildingImportance.clear();
        
        // 收集所有人类实体
        Collection<EntityID> humanIDs = worldInfo.getEntityIDsOfType(
            StandardEntityURN.CIVILIAN, 
            StandardEntityURN.FIRE_BRIGADE,
            StandardEntityURN.POLICE_FORCE,
            StandardEntityURN.AMBULANCE_TEAM
        );
        
        // 创建人→位置映射
        Map<EntityID, EntityID> humanLocations = new HashMap<>();
        for (EntityID humanID : humanIDs) {
            Entity entity = worldInfo.getEntity(humanID);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                if (human.isPositionDefined()) {
                    humanLocations.put(humanID, human.getPosition());
                }
            }
        }
        
        for (EntityID buildingID : unsearchedBuildingIDs) {
            Building building = (Building) worldInfo.getEntity(buildingID);
            int score = 0;
            
            // 基础分数：建筑物本身的重要性
            if (building.isOnFire()) {
                score += 50; // 着火建筑物优先级高
            }
            
            // 检查建筑物内是否可能有人员
            EntityID buildingLocation = buildingID;
            for (EntityID humanID : humanLocations.keySet()) {
                EntityID location = humanLocations.get(humanID);
                if (location.equals(buildingLocation)) {
                    Entity entity = worldInfo.getEntity(humanID);
                    if (entity instanceof Civilian) {
                        score += 100; // 有平民的建筑物优先级最高
                        importantTargets.add(buildingID);
                    } else {
                        score += 80; // 有其他人员的建筑物优先级高
                        importantTargets.add(buildingID);
                    }
                }
            }
            
            // 考虑建筑物类型：居民楼、医院等更重要
            if (building.getStandardURN() == StandardEntityURN.BUILDING) {
                score += 20;
            } else if (building.getStandardURN() == StandardEntityURN.REFUGE) {
                score -= 30; // 避难所不需要搜索
            }
            
            buildingImportance.put(buildingID, score);
        }
        
        // 根据评分对重要目标排序
        List<EntityID> sortedTargets = new ArrayList<>(importantTargets);
        Collections.sort(sortedTargets, new Comparator<EntityID>() {
            @Override
            public int compare(EntityID a, EntityID b) {
                int scoreA = buildingImportance.getOrDefault(a, 0);
                int scoreB = buildingImportance.getOrDefault(b, 0);
                return Integer.compare(scoreB, scoreA); // 降序
            }
        });
        
        importantTargets = new LinkedHashSet<>(sortedTargets);
    }

    private void reset() {
        this.unsearchedBuildingIDs.clear();
        this.importantTargets.clear();
        
        int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> clusterEntities = this.clustering.getClusterEntities(clusterIndex);
        
        if (clusterEntities != null && !clusterEntities.isEmpty()) {
            for (StandardEntity entity : clusterEntities) {
                if (entity instanceof Building && entity.getStandardURN() != StandardEntityURN.REFUGE) {
                    this.unsearchedBuildingIDs.add(entity.getID());
                }
            }
        } else {
            // 添加所有类型的建筑物，但排除避难所等不需要搜索的地点
            this.unsearchedBuildingIDs.addAll(this.worldInfo.getEntityIDsOfType(
                StandardEntityURN.BUILDING, 
                StandardEntityURN.GAS_STATION, 
                StandardEntityURN.AMBULANCE_CENTRE, 
                StandardEntityURN.FIRE_STATION, 
                StandardEntityURN.POLICE_OFFICE
            ));
        }
        
        // 初始化建筑物重要性评分
        updateBuildingImportance();
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }
}