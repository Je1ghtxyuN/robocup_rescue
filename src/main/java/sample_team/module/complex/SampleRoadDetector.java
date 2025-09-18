package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.RoadDetector;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.Entity;
import adf.core.debug.DefaultLogger;

import java.util.*;
import java.util.stream.Collectors;
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

public class SampleRoadDetector extends RoadDetector {

    // 属性最大值常量
    private static final int MAX_HP = 10000;
    private static final int MAX_DAMAGE = 200;
    private static final int MAX_BURIEDNESS = 100;

    // 可调节的阈值参数
    private static final int MIN_HP_THRESHOLD = 1000;
    private static final int MIN_DAMAGE_THRESHOLD = 50;
    private static final int MIN_BURIEDNESS_THRESHOLD = 30;

    // 权重配置
    private static final double DISTANCE_WEIGHT = 0.5;
    private static final double HP_WEIGHT = 0.2;
    private static final double BURIEDNESS_WEIGHT = 0.15;
    private static final double DAMAGE_WEIGHT = 0.15;

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
 
    // 因子权重参数
    private static final double BASE_VISIBILITY_FACTOR = 1.5;//可见性
    private static final double BASE_REFUGE_FACTOR = 4.0;//避难所
    private static final double BASE_ENTRANCE_FACTOR = 3.0;//门
    private static final double BASE_MAIN_ROAD_FACTOR = 3.0;//主干道
    private static final double BASE_COORDINATION_FACTOR = 0.3;
    private static final int EMERGENCY_SERVICE_RANGE = 3000000; // 3000米范围内的紧急服务
    private static final double BASE_EMERGENCY_FACTOR = 3.0; // 跟随救护消防（似乎没用）

    // 新增：求助请求列表
    private List<EntityID> helpRequests = new ArrayList<>();  // 收到的求助请求列表
    
    // 新增：记录已处理的请求位置，防止重复处理
    private static final Map<EntityID, Integer> processedHelpRequests = new HashMap<>();
    private static final int PROCESSED_COOLDOWN = 15; // 处理冷却时间

    // 新增：记录已接触过的伤员
    private Set<EntityID> contactedHumans = new HashSet<>();
    // 新增：当前目标伤员
    private EntityID currentTargetHuman = null;
    
    // 新增：防止警察聚集的共享状态
    private static final Map<EntityID, EntityID> reservedTargets = new HashMap<>();
    private static final Map<EntityID, Integer> reservationTimes = new HashMap<>();
    private static final int RESERVATION_TIMEOUT = 30; // 30时间单位后保留失效
    
    // 新增：避难所入口卡住求助相关字段
    private static final Map<EntityID, Integer> refugeEntranceRequests = new HashMap<>();
    private static final int REFUGE_ENTRANCE_REQUEST_COOLDOWN = 20;

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
        
        // 新增：处理来自救护队的道路求助消息
        processRoadHelpRequests(messageManager);
        
        // 新增：处理来自救护队的避难所入口卡住求助
        processRefugeEntranceHelpRequests(messageManager);
        
        // 清理过期的处理记录
        cleanupOldProcessedRequests();
        
        // 清理过期的避难所入口求助
        cleanupOldRefugeEntranceRequests();
        
        // 检查是否接触到了当前目标伤员
        if (currentTargetHuman != null) {
            StandardEntity entity = worldInfo.getEntity(currentTargetHuman);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                
                // 检查是否在伤员所在的位置
                if (agentInfo.getPosition().equals(human.getPosition())) {
                    contactedHumans.add(currentTargetHuman);
                    // 释放目标保留
                    releaseReservedTarget(currentTargetHuman);
                    currentTargetHuman = null; // 清除当前目标
                }
            }
        }
        
        // 清理过期的目标保留
        cleanupExpiredReservations();
        
        return this;
    }
    
    /**
     * 清理过期的处理记录
     */
    private void cleanupOldProcessedRequests() {
        int currentTime = agentInfo.getTime();
        processedHelpRequests.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > PROCESSED_COOLDOWN);
    }
    
    /**
     * 清理过期的避难所入口求助
     */
    private void cleanupOldRefugeEntranceRequests() {
        int currentTime = agentInfo.getTime();
        refugeEntranceRequests.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > REFUGE_ENTRANCE_REQUEST_COOLDOWN);
    }
    
    /**
     * 处理来自救护队的道路求助消息
     */
    private void processRoadHelpRequests(MessageManager messageManager) {
        // 获取收到的道路消息
        List<CommunicationMessage> roadMessages = 
            messageManager.getReceivedMessageList(MessageRoad.class);
        
        for (CommunicationMessage msg : roadMessages) {
            MessageRoad roadMsg = (MessageRoad) msg;
            
            // 检查是否是求助消息（有障碍物且不可通行）
            if (roadMsg.isBlockadeDefined() && 
                roadMsg.isPassable() != null && 
                !roadMsg.isPassable()) {
                
                EntityID roadID = roadMsg.getRoadID();
                
                // 检查是否已被其他警察处理
                if (processedHelpRequests.containsKey(roadID)) {
                    DefaultLogger.getLogger(agentInfo.me()).debug("忽略已处理的求助请求: " + roadID);
                    continue;
                }
                
                // 避免重复处理同一个求助请求
                if (!helpRequests.contains(roadID)) {
                    helpRequests.add(roadID);
                    // 记录日志
                    DefaultLogger.getLogger(agentInfo.me()).info("警察收到求助消息: 道路 " + roadID + 
                        " 需要清理障碍物 " + roadMsg.getBlockadeID());
                }
            }
        }
    }
    
    /**
     * 新增：处理来自救护队的避难所入口卡住求助
     */
    private void processRefugeEntranceHelpRequests(MessageManager messageManager) {
        // 获取收到的道路消息
        List<CommunicationMessage> roadMessages = 
            messageManager.getReceivedMessageList(MessageRoad.class);
        
        for (CommunicationMessage msg : roadMessages) {
            MessageRoad roadMsg = (MessageRoad) msg;
            
            // 检查是否是避难所入口卡住求助（障碍物为null，不可通行）
            if (!roadMsg.isBlockadeDefined() && 
                roadMsg.isPassable() != null && 
                !roadMsg.isPassable()) {
                
                // 使用反射检查自定义字段
                try {
                    java.lang.reflect.Field customField = roadMsg.getClass().getDeclaredField("custom");
                    customField.setAccessible(true);
                    String customValue = (String) customField.get(roadMsg);
                    
                    if ("refuge_entrance_stuck".equals(customValue)) {
                        EntityID roadID = roadMsg.getRoadID();
                        
                        // 检查是否已被其他警察处理
                        if (refugeEntranceRequests.containsKey(roadID)) {
                            DefaultLogger.getLogger(agentInfo.me()).debug("忽略已处理的避难所入口求助: " + roadID);
                            continue;
                        }
                        
                        // 记录求助信息
                        refugeEntranceRequests.put(roadID, agentInfo.getTime());
                        DefaultLogger.getLogger(agentInfo.me()).info("警察收到避难所入口卡住求助: 位置 " + roadID);
                    }
                } catch (Exception e) {
                    DefaultLogger.getLogger(agentInfo.me()).error("处理避难所入口求助时出错", e);
                }
            }
        }
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
        
        // 新增：最优先处理市民救援
        EntityID civilianTarget = findCivilianTarget();
        if (civilianTarget != null) {
            this.result = civilianTarget;
            return this;
        }
        
        // 新增：优先处理避难所入口卡住求助
        EntityID refugeEntranceTarget = selectRefugeEntranceHelpTarget();
        if (refugeEntranceTarget != null) {
            this.result = refugeEntranceTarget;
            DefaultLogger.getLogger(agentInfo.me()).info("警察优先处理避难所入口卡住求助，目标位置: " + refugeEntranceTarget);
            return this;
        }
        
        // 新增：优先处理求助请求
        if (!helpRequests.isEmpty()) {
            EntityID helpTarget = selectHelpTarget();
            if (helpTarget != null) {
                this.result = helpTarget;
                // 标记为已处理
                processedHelpRequests.put(helpTarget, agentInfo.getTime());
                // 记录日志
                DefaultLogger.getLogger(agentInfo.me()).info("警察优先处理求助请求，目标位置: " + helpTarget);
                return this;
            }
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

    /**
     * 新增：选择最近的避难所入口求助目标
     */
    private EntityID selectRefugeEntranceHelpTarget() {
        if (refugeEntranceRequests.isEmpty()) {
            return null;
        }
        
        EntityID currentPosition = agentInfo.getPosition();
        EntityID nearestRefugeEntrance = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID requestLocation : refugeEntranceRequests.keySet()) {
            int distance = worldInfo.getDistance(currentPosition, requestLocation);
            if (distance < minDistance) {
                minDistance = distance;
                nearestRefugeEntrance = requestLocation;
            }
        }
        
        return nearestRefugeEntrance;
    }

    // 新增：寻找市民目标（最高优先级）
    private EntityID findCivilianTarget() {
        // 1. 获取所有可见人类实体
        List<Human> rescueTargets = filterRescueTargets(
            worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN));
        
        // 2. 过滤在集群内的目标
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        List<Human> targets = rescueTargetsInCluster.isEmpty() ? rescueTargets : rescueTargetsInCluster;

        // 3. 如果没有目标，返回null
        if (targets.isEmpty()) {
            return null;
        }

        // 4. 使用加权评分系统选择最佳目标
        targets.sort(new WeightedPrioritySorter(worldInfo, agentInfo.me()));
        
        // 5. 从高优先级目标中选取第一个未接触过且未被其他警察保留的目标
        for (Human human : targets) {
            EntityID humanId = human.getID();
            if (!contactedHumans.contains(humanId) && !isReservedByOtherPolice(humanId)) {
                // 保留这个目标
                reserveTarget(humanId);
                this.currentTargetHuman = humanId; // 设置当前目标伤员
                return human.getPosition(); // 返回市民所在位置
            }
        }
        
        // 6. 如果所有目标都已接触过或被保留，选择优先级最高的可用目标
        for (Human human : targets) {
            EntityID humanId = human.getID();
            if (!isReservedByOtherPolice(humanId)) {
                reserveTarget(humanId);
                this.currentTargetHuman = humanId;
                return human.getPosition();
            }
        }
        
        return null;
    }

    // 新增：检查目标是否被其他警察保留
    private boolean isReservedByOtherPolice(EntityID humanId) {
        EntityID reservingPolice = reservedTargets.get(humanId);
        return reservingPolice != null && 
               !reservingPolice.equals(agentInfo.getID()) && 
               !isReservationExpired(humanId);
    }

    // 新增：保留目标
    private void reserveTarget(EntityID humanId) {
        reservedTargets.put(humanId, agentInfo.getID());
        reservationTimes.put(humanId, currentTime);
    }

    // 新增：释放目标保留
    private void releaseReservedTarget(EntityID humanId) {
        if (agentInfo.getID().equals(reservedTargets.get(humanId))) {
            reservedTargets.remove(humanId);
            reservationTimes.remove(humanId);
        }
    }

    // 新增：检查保留是否过期
    private boolean isReservationExpired(EntityID humanId) {
        Integer reservationTime = reservationTimes.get(humanId);
        if (reservationTime == null) return true;
        return (currentTime - reservationTime) > RESERVATION_TIMEOUT;
    }

    // 新增：清理过期的目标保留
    private void cleanupExpiredReservations() {
        Iterator<Map.Entry<EntityID, Integer>> it = reservationTimes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityID, Integer> entry = it.next();
            if (currentTime - entry.getValue() > RESERVATION_TIMEOUT) {
                reservedTargets.remove(entry.getKey());
                it.remove();
            }
        }
    }

    /**
     * 选择最近的求助目标
     */
    private EntityID selectHelpTarget() {
        EntityID currentPosition = agentInfo.getPosition();
        EntityID nearestHelp = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (EntityID helpTarget : helpRequests) {
            // 检查是否已被其他警察处理
            if (processedHelpRequests.containsKey(helpTarget)) {
                continue;
            }
            
            int distance = worldInfo.getDistance(currentPosition, helpTarget);
            if (distance < minDistance) {
                minDistance = distance;
                nearestHelp = helpTarget;
            }
        }
        
        return nearestHelp;
    }

    // 计算实际视野距离
    private int calculateViewDistance() {
        return 100000; // 基础视野距离
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
        double visibilityFactor = (visibleRoads.contains(blockade.getPosition())) ? BASE_VISIBILITY_FACTOR : 1.0;

        // 避难所阻挡因子（新增）: 如果障碍物阻挡避难所，则赋予更高权重
        double refugeFactor = isBlockingRefuge(blockade) ? BASE_REFUGE_FACTOR : 1.0;

        // 入口因子（在建筑物入口处优先级更高）
        double entranceFactor = isAtBuildingEntrance(blockade) ? BASE_ENTRANCE_FACTOR : 1.0;

        // 主干道因子: 如果障碍物在主干道上，优先级更高
        double mainRoadFactor = isOnMainRoad(blockade) ? BASE_MAIN_ROAD_FACTOR : 1.0;

       // 新增协调因子：已被其他警察锁定的目标降级
        double coordinationFactor = 1.0;
        EntityID roadID = blockade.getPosition();
        if (globalTargetLocks.containsKey(roadID) && 
            globalTargetLocks.get(roadID) > agentInfo.getTime()) {
            coordinationFactor = BASE_COORDINATION_FACTOR; // 被锁定的目标优先级降低
        }
        
        // 紧急因子
        double emergencyServiceFactor = calculateEmergencyServiceFactor(blockade.getPosition());

        return visibilityFactor * entranceFactor * refugeFactor * 
           mainRoadFactor * coordinationFactor * emergencyServiceFactor;
           
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
        if (entity instanceof Human) {
            Human fb = (Human) entity;
            if (fb.isPositionDefined()) {
                positions.put(fb.getID(), fb.getPosition());
            }
        }
    }
    return positions;
}

// 获取所有可见救护队位置
private Map<EntityID, EntityID> getAmbulancePositions() {
    Map<EntityID, EntityID> positions = new HashMap<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
        if (entity instanceof Human) {
            Human at = (Human) entity;
            if (at.isPositionDefined()) {
                positions.put(at.getID(), at.getPosition());
            }
        }
    }
    return positions;
}

// 获取其他警察位置（排除自己）
private Map<EntityID, EntityID> getOtherPolicePositions() {
    Map<EntityID, EntityID> positions = new HashMap<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)) {
        if (entity instanceof PoliceForce) {
            PoliceForce pf = (PoliceForce) entity;
            // 排除自己
            if (!pf.getID().equals(agentInfo.getID()) && pf.isPositionDefined()) {
                positions.put(pf.getID(), pf.getPosition());
            }
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
            emergencyFactor = Math.max(emergencyFactor, (


BASE_EMERGENCY_FACTOR + 1.0) * proximityFactor);
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

    // 从第一个文件移植的人员过滤逻辑
    private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {
        List<Human> rescueTargets = new ArrayList<>();
        for (StandardEntity next : list) {
            if (!(next instanceof Human)) continue;
            Human h = (Human) next;
            if (!isValidHuman(h)) continue;
            rescueTargets.add(h);
        }
        return rescueTargets;
    }

    private List<Human> filterInCluster(Collection<? extends StandardEntity> entities) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        List<Human> filter = new ArrayList<>();
        HashSet<StandardEntity> inCluster = new HashSet<>(
                clustering.getClusterEntities(clusterIndex));
        for (StandardEntity next : entities) {
            if (!(next instanceof Human)) continue;
            Human h = (Human) next;
            if (!h.isPositionDefined()) continue;
            StandardEntity position = this.worldInfo.getPosition(h);
            if (position == null) continue;
            if (!inCluster.contains(position)) continue;
            filter.add(h);
        }
        return filter;
    }

    private boolean isValidHuman(StandardEntity entity) {
        if (entity == null) return false;
        if (!(entity instanceof Human)) return false;

        Human target = (Human) entity;
        if (!target.isHPDefined() || target.getHP() == 0) return false;
        if (!target.isPositionDefined()) return false;
        if (!target.isDamageDefined()) return false;
        if (!target.isBuriednessDefined()) return false;

        // 综合属性检查
        if (target.getDamage() < MIN_DAMAGE_THRESHOLD && 
            target.getHP() < MIN_HP_THRESHOLD && 
            target.getBuriedness() > MIN_BURIEDNESS_THRESHOLD) {
            return false;
        }

        if (target.getBuriedness() == 0) return false;

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null) return false;

        StandardEntityURN positionURN = position.getStandardURN();
        if (positionURN == StandardEntityURN.REFUGE || 
            positionURN == StandardEntityURN.AMBULANCE_TEAM) {
            return false;
        }

        return true;
    }

    // 从第一个文件移植的加权优先级排序器
    private class WeightedPrioritySorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        WeightedPrioritySorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        @Override
        public int compare(StandardEntity a, StandardEntity b) {
            double scoreA = calculatePriorityScore((Human) a);
            double scoreB = calculatePriorityScore((Human) b);
            return Double.compare(scoreB, scoreA); // 降序排列
        }

        private double calculatePriorityScore(Human human) {
            double distanceScore = calculateDistanceScore(human);
            double hpScore = calculateHpScore(human);
            double buriednessScore = calculateBuriednessScore(human);
            double damageScore = calculateDamageScore(human);

            return (distanceScore * DISTANCE_WEIGHT) +
                   (hpScore * HP_WEIGHT) +
                   (buriednessScore * BURIEDNESS_WEIGHT) +
                   (damageScore * DAMAGE_WEIGHT);
        }

        private double calculateDistanceScore(Human human) {
            int distance = this.worldInfo.getDistance(this.reference, human);
            return Math.exp(-distance / 50000.0);
        }

        private double calculateHpScore(Human human) {
            if (!human.isHPDefined()) return 0;
            int hp = human.getHP();
            return 1.0 - (hp / (double) MAX_HP);
        }

        private double calculateBuriednessScore(Human human) {
            if (!human.isBuriednessDefined()) return 0;
            int buriedness = human.getBuriedness();
            return buriedness / (double) MAX_BURIEDNESS;
        }

        private double calculateDamageScore(Human human) {
            if (!human.isDamageDefined()) return 0;
            int damage = human.getDamage();
            return damage / (double) MAX_DAMAGE;
        }
    }
}