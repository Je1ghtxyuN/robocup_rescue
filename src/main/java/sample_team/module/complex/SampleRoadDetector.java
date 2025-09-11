package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.RoadDetector;
// 消防员
// 救护队
// 其他警察
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.Entity;

import java.util.*;
import java.util.stream.Collectors;
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

public class SampleRoadDetector extends RoadDetector {

    // 新增字段：当前锁定的目标及锁定时间
    private EntityID lockedTarget = null;
    private int lockStartTime = -1;
    private static final int LOCK_TIMEOUT = 20; // 锁定超时时间（秒）
    // 新增警察间协调字段
    private static final Map<EntityID, Integer> globalTargetLocks = new HashMap<>(); // <目标ID, 锁定结束时间>

    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Integer> lastProcessedTime = new HashMap<>();
    private int currentTime;
    private Set<EntityID> visibleRoads = new HashSet<>();
    private static final int EMERGENCY_SERVICE_RANGE = 3000000; // 3000米范围内的紧急服务
    private static final double BASE_EMERGENCY_FACTOR = 3.0; // 基础权重因子
    // 在类中添加缓存和更新机制
    // private Map<EntityID, EntityID> cachedFirePositions = new HashMap<>();
    // private Map<EntityID, EntityID> cachedAmbulancePositions = new HashMap<>();

    public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                            ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clustering = moduleManager.getModule(
            "SampleRoadDetector.Clustering",
            "sample_team.module.algorithm.KMeansClustering");
    }

    @Override
    public SampleRoadDetector updateInfo(MessageManager messageManager) {
        // 检查锁定目标状态
        if (lockedTarget != null) {
            // 情况1：目标已消失（被其他警察处理）
            if (isTargetCleared(lockedTarget)) {
                lockedTarget = null;
                lockStartTime = -1;
            }
            // 情况2：锁定超时（处理时间过长）
            else if (agentInfo.getTime() - lockStartTime > LOCK_TIMEOUT) {
                lockedTarget = null;
                lockStartTime = -1;
            }
        }
        super.updateInfo(messageManager);
        this.currentTime = agentInfo.getTime();
        
        return this;
    }
    
     // 新增目标状态检查方法
    private boolean isTargetCleared(EntityID targetID) {
        StandardEntity entity = worldInfo.getEntity(targetID);
        if (entity instanceof Road) {
            Road road = (Road) entity;
            return !road.isBlockadesDefined() || road.getBlockades().isEmpty();
        }
        return true;
    }

    @Override
    public SampleRoadDetector calc() {
        // 优先处理已锁定的目标
        if (lockedTarget != null && !isTargetCleared(lockedTarget)) {
            this.result = lockedTarget;
            return this;
        }
        
        EntityID position = agentInfo.getPosition();
        result = null;
        visibleRoads.clear();

        // 1. 获取当前位置所在道路
        StandardEntity currentArea = worldInfo.getEntity(position);
        if (currentArea instanceof rescuecore2.standard.entities.Area) {
            visibleRoads.add(currentArea.getID());
        }
        
        // 2. 获取视野范围内的道路
        int viewDistance = calculateViewDistance();
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, viewDistance);
        
        // 3. 筛选视野内的道路
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof Road) {
                visibleRoads.add(entity.getID());
            }
        }
        
        // 4. 获取这些道路上的有效障碍物
        List<Blockade> validBlockades = getVisibleBlockades();
        
        // 5. 按优先级排序并选择目标
        if (!validBlockades.isEmpty()) {
            prioritizeAndSelectTarget(position, validBlockades);
        } 
        // 6. 没有可见障碍物时使用聚类方法
        else {
            detectUsingClustering();
        }
        
        // 7. 更新处理时间
        if (result != null) {
            lastProcessedTime.put(result, currentTime);
        }
        
        // 当选择新目标时进行锁定
        if (this.result != null) {
            lockedTarget = this.result;
            lockStartTime = agentInfo.getTime();
        }
        
        
        return this;
    }

    // 计算实际视野距离
    private int calculateViewDistance() {
        return 30000; // 基础视野距离
    }

    // 获取视野内道路上的障碍物
    private List<Blockade> getVisibleBlockades() {
        List<Blockade> validBlockades = new ArrayList<>();
        
        for (EntityID roadId : visibleRoads) {
            StandardEntity roadEntity = worldInfo.getEntity(roadId);
            if (!(roadEntity instanceof Road)) continue;
            
            Road road = (Road) roadEntity;
            if (!road.isBlockadesDefined()) continue;
            
            for (EntityID blockadeId : road.getBlockades()) {
                StandardEntity entity = worldInfo.getEntity(blockadeId);
                if (!(entity instanceof Blockade)) continue;
                
                Blockade blockade = (Blockade) entity;
                if (isValidBlockade(blockade)) {
                    validBlockades.add(blockade);
                }
            }
        }
        return validBlockades;
    }

    private void prioritizeAndSelectTarget(EntityID position, List<Blockade> blockades) {
        // 按综合优先级排序
        blockades.sort((b1, b2) -> {
            double priority1 = calculatePriority(position, b1);
            double priority2 = calculatePriority(position, b2);
            return Double.compare(priority2, priority1); // 降序
        });
     
        // 选择最紧急的障碍物
        this.result = blockades.get(0).getPosition();
        
        if (!blockades.isEmpty()) {
            EntityID selected = blockades.get(0).getPosition();
            // 更新全局锁定状态
            globalTargetLocks.put(selected, agentInfo.getTime() + LOCK_TIMEOUT);
            this.result = selected;
        }
    }


    private double calculatePriority(EntityID position, Blockade blockade) { 
        // 可见性因子（当前视野内优先级更高）
        double visibilityFactor = (visibleRoads.contains(blockade.getPosition())) ? 1.5: 1.0;

        // 避难所阻挡因子（新增）: 如果障碍物阻挡避难所，则赋予更高权重
        double refugeFactor = isBlockingRefuge(blockade) ? 4.0 : 1.0;

        // 入口因子（在建筑物入口处优先级更高）
        double entranceFactor = isAtBuildingEntrance(blockade) ? 3.0 : 1.0;

        // 主干道因子: 如果障碍物在主干道上，优先级更高
        double mainRoadFactor = isOnMainRoad(blockade) ? 3.0 : 1.0;

       // 新增协调因子：已被其他警察锁定的目标降级
        double coordinationFactor = 1.0;
        EntityID roadID = blockade.getPosition();
        if (globalTargetLocks.containsKey(roadID) && 
            globalTargetLocks.get(roadID) > agentInfo.getTime()) {
            coordinationFactor = 0.3; // 被锁定的目标优先级降低
        }
        
        // 紧急因子
        double emergencyServiceFactor = calculateEmergencyServiceFactor(blockade.getPosition());

        return visibilityFactor * entranceFactor * refugeFactor * 
           mainRoadFactor * coordinationFactor* emergencyServiceFactor;
           
    }
    
    

   


    private boolean isValidBlockade(Blockade blockade) {
        // 基础有效性检查
        if (!blockade.isRepairCostDefined() || blockade.getRepairCost() <= 0) {
            return false;
        }
        
        // 位置有效性检查
        EntityID position = blockade.getPosition();
        if (position == null) {
            return false;
        }
        
        // 检查是否已被处理
        StandardEntity road = worldInfo.getEntity(position);
        if (road instanceof Road) {
            Road r = (Road) road;
            if (r.isBlockadesDefined() && !r.getBlockades().contains(blockade.getID())) {
                return false; // 障碍物已被清除
            }
        }
        
        return true;
    }

    private void detectUsingClustering() {
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

        List<Blockade> clusterBlockades = new ArrayList<>();
        for (StandardEntity entity : clusterEntities) {
            if (!(entity instanceof Road)) continue;
            
            Road road = (Road) entity;
            if (!road.isBlockadesDefined()) continue;
            
            for (EntityID blockadeID : road.getBlockades()) {
                StandardEntity blockadeEntity = worldInfo.getEntity(blockadeID);
                if (blockadeEntity instanceof Blockade && isValidBlockade((Blockade) blockadeEntity)) {
                    clusterBlockades.add((Blockade) blockadeEntity);
                }
            }
        }

        if (!clusterBlockades.isEmpty()) {
            EntityID myPosition = agentInfo.getPosition();
            clusterBlockades.sort((b1, b2) -> {
                double p1 = calculatePriority(myPosition, b1);
                double p2 = calculatePriority(myPosition, b2);
                return Double.compare(p2, p1);
            });
            
            this.result = clusterBlockades.get(0).getPosition();
        } else {
            // 聚类区域没有障碍物，选择最近的巡逻点
            selectPatrolPoint();
        }
    }
    
    private void selectPatrolPoint() {
        EntityID myPosition = agentInfo.getPosition();
        List<Road> roads = new ArrayList<>();
        
        // 获取所有道路
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (entity instanceof Road) {
                roads.add((Road) entity);
            }
        }
        
        if (!roads.isEmpty()) {
            // 按距离排序
            roads.sort((r1, r2) -> {
                int d1 = worldInfo.getDistance(myPosition, r1.getID());
                int d2 = worldInfo.getDistance(myPosition, r2.getID());
                return Integer.compare(d1, d2);
            });
            
            // 选择最近的道路作为巡逻点
            this.result = roads.get(0).getID();
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }




    // 检测障碍物是否在建筑物入口处
    private boolean isAtBuildingEntrance(Blockade blockade) {
        // 获取障碍物所在区域
        EntityID positionId = blockade.getPosition();
        if (positionId == null) return false;
        
        StandardEntity positionEntity = worldInfo.getEntity(positionId);
        
        // 如果障碍物直接在建筑上
        if (positionEntity instanceof Building) {
            Building building = (Building) positionEntity;
            return isBlockadeAtEntrance(blockade, building, worldInfo);
        }
        // 如果障碍物在道路上，检查邻近建筑
        else if (positionEntity instanceof Road) {
            Road road = (Road) positionEntity;
            if (!road.isEdgesDefined()) return false;
            
            // 检查所有邻近建筑的入口
            for (Edge edge : road.getEdges()) {
                if (edge.isPassable()) {
                    EntityID neighbourId = edge.getNeighbour();
                    if (neighbourId == null) continue;
                    
                    StandardEntity neighbour = worldInfo.getEntity(neighbourId);
                    if (neighbour instanceof Building) {
                        Building building = (Building) neighbour;
                        if (isBlockadeAtEntrance(blockade, building, worldInfo)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    //尝试加入判断障碍物是否在建筑入口
    public boolean isBlockadeAtEntrance(Blockade blockade, Building building, WorldInfo WorldOInfo) {
    // 步骤1：检查障碍物是否在该建筑内
    if (!isBlockadeInBuilding(blockade, building)) return false;
    
    // 步骤2：获取建筑的所有入口边
    List<Edge> entrances = getEntranceEdges(building, worldInfo);
     if (entrances.isEmpty()) return false;
    
    // 步骤3：检查是否覆盖任一入口边
    return entrances.stream()
        .anyMatch(edge -> coversEdge(blockade, edge));
    }

    // 检查障碍物是否在该建筑内
    public boolean isBlockadeInBuilding(Blockade blockade, Building building) {
    return blockade.isPositionDefined() && 
        blockade.getPosition().equals(building.getID());
    }

    // 获取建筑的所有入口边
    public List<Edge> getEntranceEdges(Building building, WorldInfo WorldOInfo) {
    return building.getEdges().stream()
        .filter(edge -> edge.isPassable()) // 可通行的边
        .filter(edge -> {
            EntityID neighbourID = edge.getNeighbour();
            Entity neighbour = WorldOInfo.getEntity(neighbourID);
            return neighbour instanceof Road; // 邻居是道路
        })
        .collect(Collectors.toList());
    }

    
    // 检查是否覆盖任一入口边
    public boolean coversEdge(Blockade blockade, Edge edge) {
        Shape blockadeShape = blockade.getShape(); // 障碍物多边形
        Area blockadeArea = new Area(blockadeShape);
        
        // 将入口边转为Line2D
        Line2D edgeLine = new Line2D.Double(
            edge.getStartX(), edge.getStartY(),
            edge.getEndX(), edge.getEndY()
        );
        
        // 检测多边形是否与线段相交
        return blockadeArea.intersects(edgeLine.getBounds2D());
    }












        public boolean isBlockingRefuge(Blockade blockade) {
        if (blockade == null || !blockade.isPositionDefined()) {
            return false; // 障碍物无效或位置未定义，直接返回false
        }
        
        EntityID blockPos = blockade.getPosition(); // 获取障碍物所在的区域ID
        if (blockPos == null) {
            return false;
        }
        
        // 获取所有避难所实体
        Collection<StandardEntity> refuges = worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE);
        for (StandardEntity entity : refuges) {
            if (!(entity instanceof rescuecore2.standard.entities.Area)) {
                continue; // 跳过非Area实体（理论上不会发生，但安全处理）
            }
            rescuecore2.standard.entities.Area refuge = (rescuecore2.standard.entities.Area) entity; // 避难所是Area的子类
            
            // 情况1：障碍物直接位于避难所上
            if (refuge.getID().equals(blockPos)) {
                return true;
            }
            
            // 情况2：障碍物位于避难所的直接邻居区域上
            // 获取避难所的可通行邻居列表（通过getNeighbours()，基于可通行边缘）
            List<EntityID> neighbours = refuge.getNeighbours();
            if (neighbours != null && neighbours.contains(blockPos)) {
                return true;
            }
        }
        return false; // 未找到匹配，障碍物不影响避难所
    }








   // 新增主干道判断方法
    private boolean isOnMainRoad(Blockade blockade) {
        if (!blockade.isPositionDefined()) return false;
        
        // 获取障碍物所在的道路
        EntityID roadId = blockade.getPosition();
        StandardEntity roadEntity = worldInfo.getEntity(roadId);
        
        if (!(roadEntity instanceof Road)) return false;
        
        return isMainRoad((Road) roadEntity, worldInfo);
    }

    // 主干道判断核心逻辑
    private boolean isMainRoad(Road road, WorldInfo worldInfo) {
        // 1. 高连通性检测
        int neighborThreshold = calculateNeighborThreshold(worldInfo);
        if (road.getNeighbours().size() >= neighborThreshold) {
            return true;
        }
        
        // 2. 连接关键建筑检测
        return isConnectedToCriticalBuildings(road, worldInfo);
    }

    // 计算动态邻居阈值
    private int calculateNeighborThreshold(WorldInfo worldInfo) {
        // 获取所有道路
        Collection<StandardEntity> roads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        
        // 计算平均邻居数
        double avgNeighbors = roads.stream()
            .mapToInt(road -> ((Road) road).getNeighbours().size())
            .average()
            .orElse(3);
        
        // 返回平均值的1.5倍作为阈值
        return (int) Math.ceil(avgNeighbors * 1.5);
    }

    // 检查是否连接关键建筑
    private boolean isConnectedToCriticalBuildings(Road road, WorldInfo worldInfo) {
        // 关键建筑类型
        Set<StandardEntityURN> criticalTypes = new HashSet<>(Arrays.asList(
            StandardEntityURN.FIRE_STATION,
            StandardEntityURN.POLICE_OFFICE,
            StandardEntityURN.AMBULANCE_CENTRE,
            StandardEntityURN.REFUGE
        ));
        
        // 检查道路是否连接任何关键建筑
        for (StandardEntityURN type : criticalTypes) {
            for (StandardEntity building : worldInfo.getEntitiesOfType(type)) {
                if (!road.getEdgesTo(building.getID()).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    // 获取所有可见消防员位置
    private Map<EntityID, EntityID> getFireBrigadePositions() {
        Map<EntityID, EntityID> positions = new HashMap<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
            Human fb = (Human) entity;
            if (fb.isPositionDefined()) {
                positions.put(fb.getID(), fb.getPosition());
            }
        }
        return positions;
    }

    // 获取所有可见救护队位置
    private Map<EntityID, EntityID> getAmbulancePositions() {
        Map<EntityID, EntityID> positions = new HashMap<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
            Human at = (Human) entity;
            if (at.isPositionDefined()) {
                positions.put(at.getID(), at.getPosition());
            }
        }
        return positions;
    }

    // 获取其他警察位置（排除自己）
    private Map<EntityID, EntityID> getOtherPolicePositions() {
        Map<EntityID, EntityID> positions = new HashMap<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
            PoliceForce pf = (PoliceForce) entity;
            // 排除自己
            if (!pf.getID().equals(agentInfo.getID()) && pf.isPositionDefined()) {
                positions.put(pf.getID(), pf.getPosition());
            }
        }
        return positions;
    }


    // 计算紧急服务权重因子
    private double calculateEmergencyServiceFactor(EntityID blockadePosition) {
    double emergencyFactor = 1.0; // 默认权重
    
    // 检查消防员位置
    Map<EntityID, EntityID> fireBrigadePositions = getFireBrigadePositions();
    for (EntityID firePosition : fireBrigadePositions.values()) {
        int distance = worldInfo.getDistance(blockadePosition, firePosition);
        if (distance >= 0 && distance <= EMERGENCY_SERVICE_RANGE) {
            // 距离越近权重越高
            double proximityFactor = 1.0 + (1.0 - (distance / (double)EMERGENCY_SERVICE_RANGE));
            emergencyFactor = Math.max(emergencyFactor, BASE_EMERGENCY_FACTOR * proximityFactor);
        }
    }
    
    // 检查救护队员位置
    Map<EntityID, EntityID> ambulancePositions = getAmbulancePositions();
    for (EntityID ambulancePosition : ambulancePositions.values()) {
        int distance = worldInfo.getDistance(blockadePosition, ambulancePosition);
        if (distance >= 0 && distance <= EMERGENCY_SERVICE_RANGE) {
            // 救护队员的优先级比消防员更高
            double proximityFactor = 1.0 + (1.0 - (distance / (double)EMERGENCY_SERVICE_RANGE));
            emergencyFactor = Math.max(emergencyFactor, (BASE_EMERGENCY_FACTOR + 1.0) * proximityFactor);
        }
    }
    
    return emergencyFactor;
    }

    // 获取与紧急服务相关的障碍物
    private List<Blockade> getEmergencyServiceBlockades(List<Blockade> allBlockades) {
        List<Blockade> emergencyBlockades = new ArrayList<>();
        
        // 获取所有紧急服务位置
        Map<EntityID, EntityID> firePositions = getFireBrigadePositions();
        Map<EntityID, EntityID> ambulancePositions = getAmbulancePositions();
        
        // 合并所有紧急服务位置
        Set<EntityID> allEmergencyPositions = new HashSet<>();
        allEmergencyPositions.addAll(firePositions.values());
        allEmergencyPositions.addAll(ambulancePositions.values());
        
        // 查找这些位置附近的障碍物
        for (EntityID emergencyPos : allEmergencyPositions) {
            for (Blockade blockade : allBlockades) {
                int distance = worldInfo.getDistance(emergencyPos, blockade.getPosition());
                if (distance >= 0 && distance <= EMERGENCY_SERVICE_RANGE) {
                    emergencyBlockades.add(blockade);
                }
            }
        }
        
        return emergencyBlockades;
    }


}