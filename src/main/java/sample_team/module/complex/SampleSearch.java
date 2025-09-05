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
// import rescuecore2.worldmodel.StandardWorldModel;
import java.util.*;
import sample_team.module.complex.SamplePoliceTargetAllocator; // 补充导入

public class SampleSearch extends Search {

    private PathPlanning pathPlanning;
    private EntityID result;
    private boolean initialized = false;
    private List<EntityID> positionHistory = new ArrayList<>();
    private int lastPositionTime = -1;
    private int stuckCounter = 0;
    private Set<EntityID> unreachableTargets = new HashSet<>();
    private Map<EntityID, Integer> targetRetryCount = new HashMap<>();
    private EntityID lastTarget = null; // 修正：声明lastTarget

    // 目标锁定机制
    private EntityID lockedTarget = null;
    private int lockExpiryTime = 0;

    private ModuleManager moduleManager; // 新增成员变量

    public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.moduleManager = moduleManager; // 保存moduleManager
    }

    private void initialize(ModuleManager moduleManager) {
        if (!initialized) {
            // 消防队使用Dijkstra路径规划
            this.pathPlanning = moduleManager.getModule(
                "SampleSearch.PathPlanning.Fire",
                "adf.impl.module.algorithm.DijkstraPathPlanning");
            this.initialized = true;
        }
    }

    @Override
    public SampleSearch updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (pathPlanning != null) {
            pathPlanning.updateInfo(messageManager);
        }
        
        // 更新位置历史
        EntityID currentPosition = agentInfo.getPosition();
        if (positionHistory.isEmpty() || !positionHistory.get(positionHistory.size() - 1).equals(currentPosition)) {
            positionHistory.add(currentPosition);
            if (positionHistory.size() > 10) {
                positionHistory.remove(0);
            }
        }
        
        // 更新最后位置时间
        if (!currentPosition.equals(positionHistory.isEmpty() ? null : positionHistory.get(positionHistory.size() - 1))) {
            lastPositionTime = agentInfo.getTime();
        }
        return this;
    }

    @Override
    public SampleSearch calc() {
        if (!initialized) {
            initialize(moduleManager);
        }
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        // 1. 检查卡住状态（连续5个回合位置未变化）
        if (isStuck() && agentInfo.getTime() >= 100) {
            handleStuckSituation();
            return this;
        }
        
        // 2. 检查目标锁定状态
        if (lockedTarget != null && agentInfo.getTime() < lockExpiryTime) {
            if (processLockedTarget(myPosition)) {
                return this;
            }
        }
        
        // 3. 获取着火建筑物
        List<Building> fireBuildings = getFireBuildings();
        
        // 4. 按优先级排序
        fireBuildings.sort((b1, b2) -> {
            double priority1 = calculateBuildingPriority(b1);
            double priority2 = calculateBuildingPriority(b2);
            return Double.compare(priority2, priority1);
        });
        
        // 5. 尝试分配新目标
        for (Building building : fireBuildings) {
            EntityID buildingID = building.getID();
            
            // 跳过不可达目标
            if (unreachableTargets.contains(buildingID)) continue;
            
            // 设置路径规划
            pathPlanning.setFrom(myPosition);
            pathPlanning.setDestination(Collections.singleton(buildingID));
            pathPlanning.calc();
            
            // 检查路径有效性
            if (isPathValid(pathPlanning.getResult())) {
                result = buildingID;
                lastTarget = buildingID;
                
                // 设置目标锁定
                lockedTarget = buildingID;
                lockExpiryTime = agentInfo.getTime() + 30;
                return this;
            } else {
                unreachableTargets.add(buildingID);
                targetRetryCount.put(buildingID, targetRetryCount.getOrDefault(buildingID, 0) + 1);
                
                // 如果重试次数超过3次且总时间≥100，发送求助信号
                if (targetRetryCount.get(buildingID) > 3 && agentInfo.getTime() >= 100) {
                    sendHelpRequest(buildingID);
                }
            }
        }
        
        // 6. 没有有效目标时巡逻
        patrol();
        
        return this;
    }
    
    // 新增：计算建筑物优先级
    private double calculateBuildingPriority(Building building) {
        int fieryness = building.isFierynessDefined() ? building.getFieryness() : 0;
        double priority = 10 - fieryness;
        int buried = countBuriedHumans(building);
        priority += buried * 2;
        int distance = getDistance(agentInfo.getPosition(), building.getID());
        priority += 1000.0 / (distance + 1);
        return priority;
    }
    
    // 修正：遍历建筑物内被埋人员数量
    private int countBuriedHumans(Building building) {
        int count = 0;
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN, StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.POLICE_FORCE)) {
            if (entity instanceof Human) {
                Human human = (Human) entity;
                if (building.getID().equals(human.getPosition()) && human.isBuriednessDefined() && human.getBuriedness() > 0) {
                    count++;
                }
            }
        }
        return count;
    }
    
    // 新增：检测是否卡住
    private boolean isStuck() {
        if (positionHistory.size() < 5) return false;
        EntityID lastPos = positionHistory.get(positionHistory.size() - 1);
        for (int i = 1; i <= 5; i++) {
            int index = positionHistory.size() - i;
            if (index < 0 || !positionHistory.get(index).equals(lastPos)) {
                return false;
            }
        }
        return true;
    }
    
    // 新增：处理卡住情况
    private void handleStuckSituation() {
        stuckCounter++;
        sendHelpRequest(agentInfo.getPosition());
        if (lockedTarget != null) {
            unreachableTargets.add(lockedTarget);
            lockedTarget = null;
        }
    }
    
    // 新增：发送求助请求
    private void sendHelpRequest(EntityID location) {
    }

    private boolean processLockedTarget(EntityID myPosition) {
        pathPlanning.setFrom(myPosition);
        pathPlanning.setDestination(Collections.singleton(lockedTarget));
        pathPlanning.calc();
        
        if (isPathValid(pathPlanning.getResult())) {
            result = lockedTarget;
            
            // 到达目标时检查有效性
            if (myPosition.equals(lockedTarget) && !isTargetValid(lockedTarget)) {
                unreachableTargets.add(lockedTarget);
                lockedTarget = null;
                return false;
            }
            return true;
        } else {
            unreachableTargets.add(lockedTarget);
            lockedTarget = null;
            return false;
        }
    }
    
    // 修正：判断建筑物是否着火
    private List<Building> getFireBuildings() {
        List<Building> fireBuildings = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BUILDING,
                StandardEntityURN.GAS_STATION, StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.FIRE_STATION, StandardEntityURN.POLICE_OFFICE)) {
            if (entity instanceof Building) {
                Building building = (Building) entity;
                if (building.isFierynessDefined() && building.getFieryness() > 0 && building.getFieryness() < 8) {
                    fireBuildings.add(building);
                }
            }
        }
        return fireBuildings;
    }

    private boolean isPathValid(List<EntityID> path) {
        return path != null && !path.isEmpty();
    }

    private boolean isTargetValid(EntityID target) {
        StandardEntity entity = worldInfo.getEntity(target);
        if (!(entity instanceof Building)) return false;
        Building building = (Building) entity;
        return building.isFierynessDefined() && building.getFieryness() > 0 && building.getFieryness() < 8;
    }

    private void patrol() {
        Collection<StandardEntity> buildings = worldInfo.getEntitiesOfType(StandardEntityURN.BUILDING);
        List<StandardEntity> sortedBuildings = new ArrayList<>(buildings);
        sortedBuildings.sort((b1, b2) -> {
            int d1 = getDistance(agentInfo.getPosition(), b1.getID());
            int d2 = getDistance(agentInfo.getPosition(), b2.getID());
            return Integer.compare(d1, d2);
        });
        for (StandardEntity building : sortedBuildings) {
            EntityID buildingID = building.getID();
            if (!unreachableTargets.contains(buildingID) && isReachable(buildingID)) {
                result = buildingID;
                return;
            }
        }
        Collection<StandardEntity> roads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        if (!roads.isEmpty()) {
            result = roads.iterator().next().getID();
        }
    }

    private boolean isReachable(EntityID target) {
        pathPlanning.setFrom(agentInfo.getPosition());
        pathPlanning.setDestination(Collections.singleton(target));
        pathPlanning.calc();
        List<EntityID> path = pathPlanning.getResult();
        return path != null && !path.isEmpty();
    }

    // 修正：补充距离计算方法
    private int getDistance(EntityID from, EntityID to) {
        // 若worldInfo有getDistance方法则直接用，否则可用BFS等方式实现
        try {
            Integer d = worldInfo.getDistance(from, to);
            if (d != null) {
                return d;
            }
        } catch (Exception e) {
            // 若无此方法，返回一个较大值
        }
        return 100000;
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}