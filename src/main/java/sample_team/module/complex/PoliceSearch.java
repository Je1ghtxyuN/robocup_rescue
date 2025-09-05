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
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class PoliceSearch extends Search {

    private PathPlanning pathPlanning;
    private Clustering clustering;
    private EntityID result;
    private boolean initialized = false;
    private Set<EntityID> unreachableTargets = new HashSet<>();
    private int stuckCounter = 0;
    private EntityID lastPosition;
    private EntityID lastTarget;
    private int lastPositionTime = -1;
    private Map<EntityID, EntityID> followAssignments = new HashMap<>(); // 新增：跟随任务分配
    
    // 目标锁定机制
    private EntityID lockedTarget = null;
    private int lockExpiryTime = 0;

    public PoliceSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                       ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    private void initialize(ModuleManager moduleManager) {
        if (!initialized) {
            this.pathPlanning = moduleManager.getModule(
                "PoliceSearch.PathPlanning",
                "sample_team.module.complex.PoliceAStarPathPlanning");
            
            this.clustering = moduleManager.getModule(
                "PoliceSearch.Clustering",
                "adf.impl.module.algorithm.KMeansClustering");
            
            this.initialized = true;
        }
    }

    @Override
    public PoliceSearch updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (pathPlanning != null) {
            pathPlanning.updateInfo(messageManager);
        }
        
        // 定期重置不可达目标列表
        if (agentInfo.getTime() % 30 == 0) {
            unreachableTargets.clear();
        }
        
        // 更新位置历史
        EntityID currentPosition = agentInfo.getPosition();
        if (!currentPosition.equals(lastPosition)) {
            lastPosition = currentPosition;
            lastPositionTime = agentInfo.getTime();
        }
        
        // 获取跟随任务分配（从警察局分配器）
        followAssignments = getFollowAssignments();
        
        return this;
    }
    
    // 新增：获取警察局分配器的跟随任务
    private Map<EntityID, EntityID> getFollowAssignments() {
        // 在实际实现中，这里应该从警察局目标分配器获取分配信息
        // 简化版：返回空映射
        return new HashMap<>();
    }

    @Override
    public PoliceSearch calc() {
        if (!initialized) {
            initialize(moduleManager);
        }
        
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        // 1. 检查卡住状态（5秒内位置未变化）
        if (isStuck()) {
            handleStuckSituation();
            return this;
        }
        
        // 2. 检查是否已有跟随任务
        if (followAssignments.containsKey(myPosition)) {
            EntityID assignedAgent = followAssignments.get(myPosition);
            if (processFollowAssignment(assignedAgent)) {
                return this;
            }
        }
        
        // 3. 检查目标锁定状态
        if (lockedTarget != null && agentInfo.getTime() < lockExpiryTime) {
            if (processLockedTarget(myPosition)) {
                return this;
            }
        }
        
        // 4. 检查是否有紧急求助任务
        if (agentInfo.getTime() >= 100) {
            EntityID helpLocation = getHelpRequestLocation();
            if (helpLocation != null) {
                if (processHelpRequest(helpLocation)) {
                    return this;
                }
            }
        }
        
        // 5. 获取有效障碍物
        List<Blockade> blockades = getValidUnclearedBlockades();
        
        // 6. 尝试分配新目标
        if (!assignNewTarget(myPosition, blockades)) {
            // 7. 没有有效障碍物时巡逻
            patrolRegionRoad();
        }
        
        return this;
    }
    
    // 新增：处理跟随任务
    private boolean processFollowAssignment(EntityID assignedAgent) {
        StandardEntity agentEntity = worldInfo.getEntity(assignedAgent);
        if (agentEntity == null || !(agentEntity instanceof Human)) {
            return false; // 无效跟随目标
        }
        
        Human human = (Human) agentEntity;
        EntityID agentPosition = human.getPosition();
        if (agentPosition == null) {
            return false; // 目标位置不可用
        }
        
        // 设置路径规划到目标智能体位置
        pathPlanning.setFrom(agentInfo.getPosition());
        pathPlanning.setDestination(Collections.singleton(agentPosition));
        pathPlanning.calc();
        
        // 检查路径有效性
        if (isPathValid(pathPlanning.getResult())) {
            result = agentPosition;
            lastTarget = agentPosition;
            return true;
        }
        
        return false;
    }
    
    // 新增：处理紧急求助任务
    private boolean processHelpRequest(EntityID helpLocation) {
        // 设置路径规划到求助位置
        pathPlanning.setFrom(agentInfo.getPosition());
        pathPlanning.setDestination(Collections.singleton(helpLocation));
        pathPlanning.calc();
        
        // 检查路径有效性
        if (isPathValid(pathPlanning.getResult())) {
            result = helpLocation;
            lastTarget = helpLocation;
            return true;
        }
        
        return false;
    }
    
    // 新增：获取紧急求助位置（简化实现）
    private EntityID getHelpRequestLocation() {
        // 在实际实现中，这里应该从消息系统获取求助信息
        // 简化版：随机选择一个被困的消防/救护智能体
        for (StandardEntity entity : worldInfo.getEntitiesOfType(
            StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.AMBULANCE_TEAM)) {
            
            Human agent = (Human) entity;
            if (isAgentStuck(agent)) {
                return agent.getPosition();
            }
        }
        return null;
    }
    
    // 新增：检查智能体是否卡住
    private boolean isAgentStuck(Human agent) {
        // 在实际实现中，这里应该有更精确的卡住检测
        // 简化版：检查位置是否超过5回合未变化
        if (!agent.isPositionHistoryDefined()) return false;
        
        int[] history = agent.getPositionHistory();
        if (history.length < 5) return false;
        
        // 检查最近5个位置是否相同
        EntityID currentPosition = agent.getPosition();
        for (int i = 0; i < 5; i++) {
            if (history[history.length - 1 - i] != currentPosition.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean isStuck() {
        // 当前位置停留超过5秒（25步）视为卡住
        return (agentInfo.getTime() - lastPositionTime) > 25;
    }

    private void handleStuckSituation() {
        stuckCounter++;
        
        // 清除当前目标
        if (lastTarget != null) {
            unreachableTargets.add(lastTarget);
            lockedTarget = null;
            lastTarget = null;
        }
    }

    private boolean processLockedTarget(EntityID myPosition) {
        pathPlanning.setFrom(myPosition);
        pathPlanning.setDestination(Collections.singleton(lockedTarget));
        pathPlanning.calc();
        
        if (isPathValid(pathPlanning.getResult())) {
            result = lockedTarget;
            lastTarget = lockedTarget;
            
            // 到达目标时检查有效性
            if (myPosition.equals(lockedTarget) && !isTargetValid(lockedTarget)) {
                unreachableTargets.add(lockedTarget);
                lockedTarget = null;
                lastTarget = null;
                return false;
            }
            return true;
        } else {
            unreachableTargets.add(lockedTarget);
            lockedTarget = null;
            return false;
        }
    }

    private boolean assignNewTarget(EntityID myPosition, List<Blockade> blockades) {
        if (blockades.isEmpty()) return false;
        
        // 按优先级排序
        blockades.sort((b1, b2) -> {
            double p1 = calculatePriority(myPosition, b1);
            double p2 = calculatePriority(myPosition, b2);
            return Double.compare(p2, p1);
        });

        // 尝试所有有效目标
        for (Blockade targetBlockade : blockades) {
            EntityID roadID = targetBlockade.getPosition();
            
            // 跳过不可达目标
            if (unreachableTargets.contains(roadID)) continue;
            
            // 设置路径规划
            pathPlanning.setFrom(myPosition);
            pathPlanning.setDestination(Collections.singleton(roadID));
            pathPlanning.calc();
            
            // 检查路径有效性
            if (isPathValid(pathPlanning.getResult())) {
                result = roadID;
                lastTarget = roadID;
                
                // 设置目标锁定
                lockedTarget = roadID;
                lockExpiryTime = agentInfo.getTime() + 30;
                return true;
            } else {
                unreachableTargets.add(roadID);
            }
        }
        return false;
    }

    private double calculatePriority(EntityID position, Blockade blockade) {
        int cost = blockade.getRepairCost();
        int distance = worldInfo.getDistance(position, blockade.getPosition());
        
        // 基础分数
        double baseScore = (cost * 100.0) / (distance + 1);
        
        // 新增：人类紧急程度因子
        double humanEmergencyFactor = calculateHumanEmergencyFactor(blockade.getPosition());
        
        return baseScore * humanEmergencyFactor;
    }
    
    // 新增：计算道路位置的人类紧急程度
    private double calculateHumanEmergencyFactor(EntityID position) {
        double maxEmergency = 1.0; // 默认值
        
        // 获取该位置的所有人类（平民）
        Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        for (StandardEntity entity : humans) {
            Human human = (Human) entity;
            if (position.equals(human.getPosition())) {
                // 计算人类紧急程度：HP越低紧急度越高，被埋压程度越高紧急度越高
                int hp = human.isHPDefined() ? human.getHP() : 10000;
                int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
                
                // 紧急程度 = (10000 - HP) * 1.5 + buriedness * 2
                double emergency = (10000 - hp) * 1.5 + (buriedness * 2);
                if (emergency > maxEmergency) {
                    maxEmergency = emergency;
                }
            }
        }
        
        // 紧急程度因子 = 1.0 + emergency/150
        return 1.0 + (maxEmergency / 5000.0);
    }

    private boolean isPathValid(List<EntityID> path) {
        return path != null && !path.isEmpty();
    }

    private boolean isTargetValid(EntityID target) {
        // 检查目标是否仍然有效
        StandardEntity entity = worldInfo.getEntity(target);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
    }

    private List<Blockade> getValidUnclearedBlockades() {
        List<Blockade> blockades = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
            Blockade blockade = (Blockade) entity;
            
            // 有效性检查
            if (!blockade.isRepairCostDefined() || blockade.getRepairCost() <= 0) continue;
            if (blockade.getPosition() == null) continue;
            if (unreachableTargets.contains(blockade.getPosition())) continue;
            if (!isBlockadeActive(blockade)) continue;
                
            blockades.add(blockade);
        }
        return blockades;
    }

    private boolean isBlockadeActive(Blockade blockade) {
        EntityID position = blockade.getPosition();
        StandardEntity entity = worldInfo.getEntity(position);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        return road.isBlockadesDefined() && road.getBlockades().contains(blockade.getID());
    }

    private void patrolRegionRoad() {
        if (clustering == null) {
            patrolRandomRoad();
            return;
        }
        
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);
        
        List<EntityID> reachableRoads = getReachableRoads(clusterEntities);
        
        if (!reachableRoads.isEmpty()) {
            result = reachableRoads.get(new Random().nextInt(reachableRoads.size()));
            lastTarget = result;
        } else {
            patrolRandomRoad();
        }
    }
    
    private List<EntityID> getReachableRoads(Collection<StandardEntity> entities) {
        List<EntityID> roads = new ArrayList<>();
        for (StandardEntity entity : entities) {
            if (!(entity instanceof Road)) continue;
            if (unreachableTargets.contains(entity.getID())) continue;
            
            roads.add(entity.getID());
        }
        return roads;
    }
    
    private void patrolRandomRoad() {
        Collection<StandardEntity> allRoads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        List<EntityID> reachableRoads = getReachableRoads(allRoads);
        
        if (!reachableRoads.isEmpty()) {
            result = reachableRoads.get(new Random().nextInt(reachableRoads.size()));
        } else if (!allRoads.isEmpty()) {
            result = new ArrayList<>(allRoads).get(0).getID();
        }
        lastTarget = result;
    }
    
    private EntityID findRandomNearbyRoad() {
        EntityID current = agentInfo.getPosition();
        Collection<StandardEntity> nearby = worldInfo.getObjectsInRange(current, 50000);
        
        List<Road> roads = new ArrayList<>();
        for (StandardEntity entity : nearby) {
            if (entity instanceof Road) {
                roads.add((Road) entity);
            }
        }
        
        if (!roads.isEmpty()) {
            return roads.get(new Random().nextInt(roads.size())).getID();
        }
        return null;
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}