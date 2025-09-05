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

public class FireBrigadeSearch extends Search {

    private PathPlanning pathPlanning;
    private EntityID result;
    private Set<EntityID> unreachableTargets = new HashSet<>();
    private int stuckCounter = 0;
    private EntityID lastPosition;
    private EntityID lastTarget;
    private int lastPositionTime = -1;
    private Map<EntityID, Integer> targetRetryCount = new HashMap<>();
    private int stuckStartTime = -1;

    public FireBrigadeSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
        ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    private void initialize(ModuleManager moduleManager) {
        this.pathPlanning = moduleManager.getModule(
            "FireBrigadeSearch.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
    }

    @Override
    public FireBrigadeSearch updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (pathPlanning != null) {
            pathPlanning.updateInfo(messageManager);
        }
        
        // 更新位置历史
        EntityID currentPosition = agentInfo.getPosition();
        if (!currentPosition.equals(lastPosition)) {
            lastPosition = currentPosition;
            lastPositionTime = agentInfo.getTime();
            
            // 重置卡住状态
            stuckStartTime = -1;
        } else {
            // 更新卡住状态
            if (stuckStartTime == -1) {
                stuckStartTime = agentInfo.getTime();
            }
            
            // 如果卡住超过5回合且总时间>=100，发送求救信号
            if (agentInfo.getTime() - stuckStartTime > 5 && agentInfo.getTime() >= 100) {
                sendHelpRequest(messageManager);
            }
        }
        
        return this;
    }

    private void sendHelpRequest(MessageManager messageManager) {
        // 发送求救信号：HELP_REQUEST:消防ID:当前位置
        String message = "HELP_REQUEST:" + agentInfo.getID() + ":" + agentInfo.getPosition();
        messageManager.addMessage(message);
    }

    @Override
    public FireBrigadeSearch calc() {
        if (pathPlanning == null) {
            initialize(moduleManager);
        }
        
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        // 1. 检查卡住状态（5秒内位置未变化）
        if (isStuck()) {
            // 不再随机移动，等待警察支援
            return this;
        }
        
        // 2. 获取有效目标（着火建筑）
        List<Building> fireBuildings = getFireBuildings();
        
        // 3. 按优先级排序
        fireBuildings.sort((b1, b2) -> {
            // 优先级 = 火势严重程度 + 被困人数 - 距离
            int severity1 = b1.getFieryness();
            int trapped1 = worldInfo.getNumberOfBuried(b1.getID());
            int distance1 = worldInfo.getDistance(myPosition, b1.getID());
            
            int severity2 = b2.getFieryness();
            int trapped2 = worldInfo.getNumberOfBuried(b2.getID());
            int distance2 = worldInfo.getDistance(myPosition, b2.getID());
            
            double priority1 = severity1 * 100 + trapped1 * 50 - distance1 * 0.1;
            double priority2 = severity2 * 100 + trapped2 * 50 - distance2 * 0.1;
            
            return Double.compare(priority2, priority1);
        });

        // 4. 尝试分配目标
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
                return this;
            } else {
                unreachableTargets.add(buildingID);
                targetRetryCount.put(buildingID, 
                    targetRetryCount.getOrDefault(buildingID, 0) + 1);
                
                // 如果重试次数过多，彻底放弃该目标
                if (targetRetryCount.get(buildingID) > 3) {
                    unreachableTargets.add(buildingID);
                }
            }
        }
        
        // 5. 如果没有着火建筑，前往避难所补水
        if (result == null) {
            result = findNearestRefuge();
        }
        
        return this;
    }

    private List<Building> getFireBuildings() {
        List<Building> fireBuildings = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BUILDING)) {
            if (entity instanceof Building) {
                Building building = (Building) entity;
                if (building.isOnFire()) {
                    fireBuildings.add(building);
                }
            }
        }
        return fireBuildings;
    }
    
    private EntityID findNearestRefuge() {
        Collection<StandardEntity> refuges = worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE);
        EntityID myPosition = agentInfo.getPosition();
        EntityID nearestRefuge = null;
        int minDistance = Integer.MAX_VALUE;
        
        for (StandardEntity refuge : refuges) {
            int distance = worldInfo.getDistance(myPosition, refuge.getID());
            if (distance < minDistance) {
                minDistance = distance;
                nearestRefuge = refuge.getID();
            }
        }
        return nearestRefuge;
    }

    private boolean isStuck() {
        // 当前位置停留超过5秒（25步）视为卡住
        return (agentInfo.getTime() - lastPositionTime) > 25;
    }

    private boolean isPathValid(List<EntityID> path) {
        return path != null && !path.isEmpty();
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}