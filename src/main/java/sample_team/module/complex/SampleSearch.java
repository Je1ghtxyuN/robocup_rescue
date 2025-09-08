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

// 导入自制kmeans聚类
//import sample_team.module.algorithm.KMeansClustering;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleSearch extends Search {

    private PathPlanning pathPlanning;
    private Clustering clustering;
     
    // 警察巡逻字段
    private Queue<EntityID> patrolTargets = new LinkedList<>(); // 巡逻点队列
    // private static final int PATROL_RADIUS = 50000; // 巡逻范围(毫米)

    private EntityID result;
    private Collection<EntityID> unsearchedBuildingIDs;
    private EntityID currentTargetBuilding; // 当前正在前往的建筑
    private Set<EntityID> searchedBuildings = new HashSet<>();
    private Set<EntityID> processedBlockades = new HashSet<>(); // 障碍物处理缓存
    private Logger logger;

    public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.unsearchedBuildingIDs = new HashSet<>();

        StandardEntityURN agentURN = ai.me().getStandardURN();
        if (agentURN == AMBULANCE_TEAM) {
            this.pathPlanning = moduleManager.getModule(
                    "SampleSearch.PathPlanning.Ambulance",
                    "adf.impl.module.algorithm.AStarPathPlanning");
            this.clustering = moduleManager.getModule(
                    "SampleSearch.Clustering.Ambulance",
                    "sample_team.module.algorithm.KMeansClustering");
        } else if (agentURN == FIRE_BRIGADE) {
            this.pathPlanning = moduleManager.getModule(
                    "SampleSearch.PathPlanning.Fire",
                    "adf.impl.module.algorithm.DijkstraPathPlanning");
            this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                    "sample_team.module.algorithm.KMeansClustering");
        } else if (agentURN == POLICE_FORCE) {
            this.pathPlanning = moduleManager.getModule(
                    "SampleSearch.PathPlanning.Police",
                    "adf.impl.module.algorithm.DijkstraPathPlanning");
            this.clustering = moduleManager.getModule(
                    "SampleSearch.Clustering.Police",
                    "sample_team.module.algorithm.KMeansClustering");
        }
        registerModule(this.clustering);
        registerModule(this.pathPlanning);
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        logger.debug("Time:" + agentInfo.getTime());
        super.updateInfo(messageManager);

        EntityID currentPosition = agentInfo.getPosition();
        StandardEntity currentEntity = worldInfo.getEntity(currentPosition);
        
        // 记录当前位置实体
        if (currentEntity != null) {
            searchedBuildings.add(currentEntity.getID());
        }
        
        // 记录当前位置所在区域
        if (currentEntity instanceof Area) {
            Area currentArea = (Area) currentEntity;
            for (Edge edge : currentArea.getEdges()) {
                if (edge.isPassable()) {
                    EntityID neighbourID = edge.getNeighbour();
                    if (neighbourID != null) {
                        searchedBuildings.add(neighbourID);
                    }
                }
            }
        }

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

        updateSearchProgress();
        
        return this;
    }

    // 更新搜索进度
    private void updateSearchProgress() {
        // 动态移除已搜索或无效建筑
        Iterator<EntityID> iterator = unsearchedBuildingIDs.iterator();
        while (iterator.hasNext()) {
            EntityID id = iterator.next();
            StandardEntity entity = worldInfo.getEntity(id);
            if (entity instanceof Building) {
                //Building building = (Building) entity;
                // 条件1: 建筑已完全探索
                // 条件2: 建筑已毁坏无需搜索
                // 条件3: 其他单位已报告完成搜索
                // if (shouldRemoveFromSearchList(building)) {
                //     iterator.remove();
                //     logger.debug("Removed building from search list: " + id);
                // }
            }
        }
    }

    @Override
    public Search calc() {
        this.result = null;
        StandardEntityURN agentURN = agentInfo.me().getStandardURN(); // 获取代理类型
        
        // 非警察智能体保持原始逻辑
        if (agentURN != POLICE_FORCE) {
            this.currentTargetBuilding = null; // 重置当前目标建筑
            
            if (unsearchedBuildingIDs.isEmpty()) {
                return this;
            }

            logger.debug("unsearchedBuildingIDs: " + unsearchedBuildingIDs);
            
            // 2. 寻找可达目标
            Set<EntityID> unreachableTargets = new HashSet<>();
            List<EntityID> path = null;
            EntityID selectedTarget = null;
            
            for (EntityID candidate : new ArrayList<>(unsearchedBuildingIDs)) {
                this.pathPlanning.setFrom(this.agentInfo.getPosition());
                this.pathPlanning.setDestination(Collections.singleton(candidate));
                path = this.pathPlanning.calc().getResult();
                
                if (path != null && !path.isEmpty()) {
                    selectedTarget = candidate;
                    break;
                } else {
                    unreachableTargets.add(candidate);
                    logger.debug("Target unreachable: " + candidate);
                }
            }
            
            // 3. 处理找到的可达目标
            if (selectedTarget != null) {
                unsearchedBuildingIDs.remove(selectedTarget);
                this.currentTargetBuilding = selectedTarget; // 记录当前目标建筑
                logger.debug("Reachable target found: " + selectedTarget);
                
                this.pathPlanning.setFrom(this.agentInfo.getPosition());
                this.pathPlanning.setDestination(Collections.singleton(selectedTarget));
                path = this.pathPlanning.calc().getResult();
                
                if (path != null && !path.isEmpty()) {
                    // 5. 设置最终目标点
                    if (path.size() > 2) {
                        this.result = path.get(path.size() - 3);
                    } else {
                        this.result = path.get(path.size() - 1);
                    }
                    logger.debug("Path to target: " + path);
                }
            }
            
            // 6. 处理不可达目标
            unsearchedBuildingIDs.removeAll(unreachableTargets);
            
            if (this.result == null && unsearchedBuildingIDs.isEmpty()) {
                logger.debug("No reachable targets - resetting search list");
                this.reset();
            }
            
            logger.debug("Selected target point: " + result);
            return this;
        }
        
        // 当智能体为警察时
        if (agentInfo.me().getStandardURN() == POLICE_FORCE) {
            // 1. 优先处理高优先级障碍
            EntityID blockadeTarget = findPriorityBlockade();
            if (blockadeTarget != null) {
                this.result = blockadeTarget;
                return this;
            }

            // 2. 没有障碍物时执行巡逻
            if (this.patrolTargets.isEmpty()) {
                generatePatrolRoute(); // 生成巡逻路线
            }

            // 3. 获取下一个巡逻点
            EntityID nextPatrolPoint = this.patrolTargets.poll();
            if (nextPatrolPoint != null) {
                this.patrolTargets.add(nextPatrolPoint); // 循环队列
                this.result = nextPatrolPoint;
            }
        }

        return this;
    }

    // 生成环形巡逻路线
    private void generatePatrolRoute() {
        // 1. 获取聚类中心点
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());  
        
        // 2. 获取聚类区域内所有道路和建筑
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

        List<Road> roads = clusterEntities.stream()
            .filter(e -> e instanceof Road)
            .map(e -> (Road)e)
            .collect(Collectors.toList());

        List<Building> buildings = clusterEntities.stream()
        .filter(e -> e instanceof Building && e.getStandardURN() != REFUGE)
        .map(e -> (Building) e)
        .collect(Collectors.toList());    
        
        // 3. 按探索价值排序（优先未搜索区域）
        roads.sort(Comparator.comparingInt(
            r -> calculateExplorationValue(r.getID())
        ));

        buildings.sort(Comparator.comparingInt(
            b -> calculateExplorationValue(b.getID())
        ));
        
        // 4. 创建混合巡逻点列表（道路和建筑）
        List<EntityID> patrolPoints = new ArrayList<>();
    
        // 添加未搜索的建筑（最多4个）
        buildings.stream()
            .filter(b -> !searchedBuildings.contains(b.getID()))
            .limit(4)
            .map(Building::getID)
            .forEach(patrolPoints::add);
        
        // 添加价值最高的道路（最多8个，包括远近不同的道路）
        int roadCount = Math.min(8, roads.size());
        for (int i = 0; i < roadCount; i++) {
            // 选择不同距离的道路：前2个最近，中间4个中等距离，最后2个最远
            int index;
            if (i < 2) index = i; // 最近
            else if (i < 6) index = i * roads.size() / 6; // 中等
            else index = roads.size() - (roadCount - i); // 最远
            
            if (index < roads.size()) {
                patrolPoints.add(roads.get(index).getID());
            }
        }
        
        // 5. 随机打乱顺序避免固定模式
        Collections.shuffle(patrolPoints);
        
        // 6. 加入巡逻队列
        patrolTargets.clear();
        patrolTargets.addAll(patrolPoints);
        
        // 7. 日志记录
        logger.debug("Generated expanded patrol route: " + patrolTargets);
    }

    // 计算区域的探索价值（数值越低表示越需要探索）
    private int calculateExplorationValue(EntityID areaID) {
        int value = 0;
        
        // 1. 优先未搜索区域：从未搜索过的区域价值最低
        if (!searchedBuildings.contains(areaID)) {
            value -= 1000; // 大幅降低值，使其排在前列
        }
        
        // 2. 考虑距离：较远的区域价值稍低
        int distance = worldInfo.getDistance(agentInfo.getPosition(), areaID);
        value += distance / 100; // 每100米增加1点值
        
        // 3. 上次访问时间：长时间未访问的区域价值低
        // （这里简化处理，实际可以记录每个区域的最后访问时间）
        
        return value;
    }



    // 查找高优先级障碍
    private EntityID findPriorityBlockade() {
        // 1. 检查通往避难所的路径
        Collection<EntityID> refugeIDs = worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
        if (!refugeIDs.isEmpty()) {
            pathPlanning.setFrom(agentInfo.getPosition());
            pathPlanning.setDestination(refugeIDs);
            List<EntityID> refugePath = pathPlanning.calc().getResult();
            
            EntityID blockade = findBlockadeInPath(refugePath);
            if (blockade != null) return blockade;
        }
        
        // 2. 检查通往未搜索建筑的路径
        for (EntityID buildingID : unsearchedBuildingIDs) {
            pathPlanning.setFrom(agentInfo.getPosition());
            pathPlanning.setDestination(Collections.singleton(buildingID));
            List<EntityID> path = pathPlanning.calc().getResult();
            
            EntityID blockade = findBlockadeInPath(path);
            if (blockade != null) return blockade;
        }

        // 新增：检查巡逻路径上的障碍
        for (EntityID patrolPoint : patrolTargets) {
            pathPlanning.setFrom(agentInfo.getPosition());
            pathPlanning.setDestination(patrolPoint);
            List<EntityID> path = pathPlanning.calc().getResult();
            EntityID blockade = findBlockadeInPath(path);
            if (blockade != null) return blockade;
        }
        
        return null;
    }
    
    // [新增] 路径障碍检查
    private EntityID findBlockadeInPath(List<EntityID> path) {
        if (path == null) return null;
        
        for (EntityID eid : path) {
            StandardEntity entity = worldInfo.getEntity(eid);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && 
                    !road.getBlockades().isEmpty() &&
                    !processedBlockades.contains(road.getID())) {
                    return road.getID();
                }
            }
        }
        return null;
    }

    private void reset() {
        this.unsearchedBuildingIDs.clear();
        this.searchedBuildings.clear(); // 重置已搜索记录
        this.processedBlockades.clear(); // 清除障碍物缓存
        
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

        if (agentInfo.me().getStandardURN() == POLICE_FORCE) {
        generatePatrolRoute(); // 初始化巡逻路线
    }
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }
}