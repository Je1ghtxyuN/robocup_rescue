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
    
    // 目标锁定机制
    private EntityID lockedTarget = null;
    private int lockExpiryTime = 0;
    
    // 卡死检测时间阈值（秒）
    private static final int STUCK_TIME_THRESHOLD = 60; // 60秒 = 300步
    private static final int MAX_STUCK_COUNT = 5;

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
            stuckCounter = 0; // 移动时重置卡死计数器
        }
        
        return this;
    }

    @Override
    public PoliceSearch calc() {
        if (!initialized) {
            initialize(moduleManager);
        }
        
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        // 1. 检查卡住状态（60秒内位置未变化 且 目标无效）
        if (isStuck()) {
            handleStuckSituation();
            return this;
        }
        
        // 2. 检查目标锁定状态
        if (lockedTarget != null && agentInfo.getTime() < lockExpiryTime) {
            if (processLockedTarget(myPosition)) {
                return this;
            }
        }
        
        // 3. 获取有效障碍物
        List<Blockade> blockades = getValidUnclearedBlockades();
        
        // 4. 尝试分配新目标
        if (!assignNewTarget(myPosition, blockades)) {
            // 5. 没有有效障碍物时巡逻
            patrolRegionRoad();
        }
        
        return this;
    }

    // 新增方法：检查消防员和救护车聚集区域
    private List<Blockade> prioritizeRescueAreas(List<Blockade> blockades) {
        List<Blockade> prioritized = new ArrayList<>(blockades);
        
        // 为每个障碍物计算救援区域因子
        Map<Blockade, Double> rescueFactors = new HashMap<>();
        for (Blockade blockade : blockades) {
            double factor = calculateRescueTeamDensityFactor(blockade.getPosition());
            rescueFactors.put(blockade, factor);
        }
        
        // 根据救援区域因子排序
        prioritized.sort((b1, b2) -> {
            double factor1 = rescueFactors.getOrDefault(b1, 0.0);
            double factor2 = rescueFactors.getOrDefault(b2, 0.0);
            return Double.compare(factor2, factor1); // 降序排序
        });
        
        return prioritized;
    }
    
    // 新增方法：计算消防员和救护车聚集区域的密度因子
    private double calculateRescueTeamDensityFactor(EntityID position) {
        double densityFactor = 0.0;
        int radius = 50000; // 50米范围内
        
        // 获取位置附近的实体
        Collection<StandardEntity> nearbyEntities = worldInfo.getObjectsInRange(position, radius);
        
        // 统计附近的消防员和救护车数量
        int fireBrigadeCount = 0;
        int ambulanceTeamCount = 0;
        
        for (StandardEntity entity : nearbyEntities) {
            if (entity instanceof FireBrigade) {
                fireBrigadeCount++;
            } else if (entity instanceof AmbulanceTeam) {
                ambulanceTeamCount++;
            }
        }
        
        // 计算密度因子：每增加1个救援队伍，增加0.2的权重
        densityFactor = (fireBrigadeCount + ambulanceTeamCount) * 0.2;
        
        // 如果有3个以上的救援队伍，额外增加权重
        if (fireBrigadeCount + ambulanceTeamCount >= 3) {
            densityFactor *= 1.5;
        }
        
        return densityFactor;
    }

    private boolean isStuck() {
        // 当前位置停留超过阈值时间 且 目标无效
        boolean timeExpired = (agentInfo.getTime() - lastPositionTime) > STUCK_TIME_THRESHOLD;
        boolean targetInvalid = lockedTarget == null || !isTargetValid(lockedTarget);
        return timeExpired && targetInvalid;
    }

    private void handleStuckSituation() {
        stuckCounter++;
        
        // 清除当前目标
        if (lastTarget != null) {
            unreachableTargets.add(lastTarget);
            unlockTarget();
        }
        
        // 强制解锁和随机移动
        unlockTarget();
        
        if (stuckCounter > MAX_STUCK_COUNT) {
            // 多次卡死时选择完全随机位置
            result = findRandomRoadAnywhere();
        } else {
            // 尝试随机移动解困
            result = findRandomNearbyRoad();
        }
        
        System.out.println("Police " + agentInfo.getID() + " stuck! Counter: " + stuckCounter);
    }
    
    private void unlockTarget() {
        lockedTarget = null;
        lockExpiryTime = 0;
    }

    private boolean processLockedTarget(EntityID myPosition) {
        // 在计算路径前检查目标有效性
        if (!isTargetValid(lockedTarget)) {
            unlockTarget();
            return false;
        }
        
        pathPlanning.setFrom(myPosition);
        pathPlanning.setDestination(Collections.singleton(lockedTarget));
        pathPlanning.calc();
        
        if (isPathValid(pathPlanning.getResult())) {
            result = lockedTarget;
            lastTarget = lockedTarget;
            
            // 到达目标时检查有效性
            if (myPosition.equals(lockedTarget) && !isTargetValid(lockedTarget)) {
                unreachableTargets.add(lockedTarget);
                unlockTarget();
                return false;
            }
            return true;
        } else {
            unreachableTargets.add(lockedTarget);
            unlockTarget();
            return false;
        }
    }

    private boolean assignNewTarget(EntityID myPosition, List<Blockade> blockades) {
        if (blockades.isEmpty()) return false;
        
        // 新增：优先处理消防员和救护车聚集区域
        blockades = prioritizeRescueAreas(blockades);
        
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
        
        // 人类紧急程度因子（统一算法）
        double humanEmergencyFactor = calculateHumanEmergencyFactor(blockade.getPosition());
        
        return baseScore * humanEmergencyFactor;
    }
    
    // 统一人类紧急因子计算方法
    private double calculateHumanEmergencyFactor(EntityID position) {
        double maxEmergency = 1.0; // 默认值
        final int BASE_HP = 10000;
        
        // 获取该位置的所有人类（平民）
        Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        for (StandardEntity entity : humans) {
            Human human = (Human) entity;
            if (position.equals(human.getPosition())) {
                // 计算人类紧急程度：HP越低紧急度越高，被埋压程度越高紧急度越高
                int hp = human.isHPDefined() ? human.getHP() : BASE_HP;
                int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
                
                // 紧急程度 = (10000 - HP) * 0.8 + buriedness * 15
                double emergency = (BASE_HP - hp) * 0.8 + (buriedness * 15);
                if (emergency > maxEmergency) {
                    maxEmergency = emergency;
                }
            }
        }
        
        // 紧急程度因子 = 1.0 + emergency/5000.0
        return 1.0 + (maxEmergency / 5000.0);
    }

    private boolean isPathValid(List<EntityID> path) {
        return path != null && !path.isEmpty() && path.size() > 1;
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
        // 优先检查救援热点区域
        EntityID hotspot = findRescueHotspot();
        if (hotspot != null) {
            result = hotspot;
            lastTarget = result;
            return;
        }
        
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
    
    // 新增方法：查找救援热点区域
    private EntityID findRescueHotspot() {
        double maxDensity = 0.0;
        EntityID bestHotspot = null;
        
        // 获取所有道路
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (!(entity instanceof Road)) continue;
            Road road = (Road) entity;
            EntityID roadID = road.getID();
            
            // 计算该位置的救援队伍密度
            double density = calculateRescueTeamDensityFactor(roadID);
            
            if (density > maxDensity) {
                maxDensity = density;
                bestHotspot = roadID;
            }
        }
        
        // 只返回密度大于阈值的区域
        return maxDensity > 0.5 ? bestHotspot : null;
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
    
    private EntityID findRandomRoadAnywhere() {
        Collection<StandardEntity> allRoads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        if (!allRoads.isEmpty()) {
            List<StandardEntity> roadList = new ArrayList<>(allRoads);
            return roadList.get(new Random().nextInt(roadList.size())).getID();
        }
        return null;
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}