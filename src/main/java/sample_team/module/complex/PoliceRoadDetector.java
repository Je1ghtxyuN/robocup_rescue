package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.RoadDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class PoliceRoadDetector extends RoadDetector {

    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Integer> lastProcessedTime = new HashMap<>();
    private int currentTime;
    private Set<EntityID> visibleRoads = new HashSet<>();

    public PoliceRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                            ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clustering = moduleManager.getModule(
            "PoliceRoadDetector.Clustering",
            "adf.impl.module.algorithm.KMeansClustering");
    }

    @Override
    public PoliceRoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.currentTime = agentInfo.getTime();
        return this;
    }

    @Override
    public PoliceRoadDetector calc() {
        EntityID position = agentInfo.getPosition();
        result = null;
        visibleRoads.clear();

        StandardEntity currentArea = worldInfo.getEntity(position);
        if (currentArea instanceof Area) {
            visibleRoads.add(currentArea.getID());
        }
        
        int viewDistance = calculateViewDistance();
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, viewDistance);
        
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof Road) {
                visibleRoads.add(entity.getID());
            }
        }
        
        List<Blockade> validBlockades = getVisibleBlockades();
        
        if (!validBlockades.isEmpty()) {
            prioritizeAndSelectTarget(position, validBlockades);
        } else {
            detectUsingClustering();
        }
        
        if (result != null) {
            lastProcessedTime.put(result, currentTime);
        }
        
        return this;
    }

    

    private int calculateViewDistance() {
        return 30000;
    }

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
                    // 检查是否是救援路径上的关键障碍
                    if (isCriticalForRescue(roadId)) {
                        blockade.setRepairCost(blockade.getRepairCost() * 2); // 提高关键障碍的修复成本权重
                    }
                    validBlockades.add(blockade);
                }
            }
        }
        return validBlockades;
    }

    // 新增方法：检查是否是救援路径上的关键障碍
    private boolean isCriticalForRescue(EntityID roadId) {
        StandardEntity roadEntity = worldInfo.getEntity(roadId);
        if (!(roadEntity instanceof Road)) return false;
        
        Road road = (Road) roadEntity;
        int criticalCount = 0;
        
        // 检查道路连接的建筑物
        for (EntityID neighbor : road.getNeighbours()) {
            StandardEntity neighborEntity = worldInfo.getEntity(neighbor);
            if (neighborEntity instanceof Building) {
                Building building = (Building) neighborEntity;
                // 关键性因素1：建筑物有被困人员
                boolean hasBuried = worldInfo.getNumberOfBuried(building.getID()) > 0;
                // 关键性因素2：建筑物着火
                boolean isBurning = building.isOnFire();
                // 关键性因素3：唯一入口检查
                boolean isOnlyAccess = isOnlyAccessPoint(neighbor, roadId);
                // 关键性因素4：附近有救援队伍被阻挡
                boolean hasBlockedRescuers = hasBlockedRescuers(roadId);
            
                if (hasBuried || isBurning || isOnlyAccess || hasBlockedRescuers) {
                criticalCount++;
                
                // 特别紧急情况：有救援队伍被阻挡且建筑物有人
                if (hasBlockedRescuers && (hasBuried || isBurning)) {
                    return true; // 最高优先级
                }
            }
            }
        }
        return criticalCount > 1;
    }

    // 检查是否是建筑物的唯一入口
    private boolean isOnlyAccessPoint(EntityID buildingId, EntityID roadId) {
        Building building = (Building) worldInfo.getEntity(buildingId);
        int accessPoints = 0;
        
        for (EntityID neighbor : building.getNeighbours()) {
            if (worldInfo.getEntity(neighbor) instanceof Road) {
                accessPoints++;
            }
        }
        return accessPoints == 1; // 只有一条连接道路
    }

    // 检查是否有救援队伍被阻挡
    private boolean hasBlockedRescuers(EntityID roadId) {
        Collection<StandardEntity> nearbyRescuers = worldInfo.getObjectsInRange(roadId, 30000);
        int blockedCount = 0;
        
        for (StandardEntity entity : nearbyRescuers) {
            if ((entity instanceof AmbulanceTeam || entity instanceof FireBrigade)) {
                Human human = (Human) entity;
                // 检查救援队伍是否停滞
                if (isAgentStuck(human.getID())) {
                    blockedCount++;
                }
            }
        }
        return blockedCount > 0;
    }

    // 判断智能体是否停滞
    private boolean isAgentStuck(EntityID agentId) {
        Human agent = (Human) worldInfo.getEntity(agentId);
        if (!agent.isPositionDefined()) return false;
        
        // 获取位置历史（ADF扩展接口）
        int[] history = agent.getPositionHistory();
        if (history == null || history.length < 3) return false;
        
        // 检查最近3个时间点位置是否相同
        return history[history.length-1] == history[history.length-2] && 
            history[history.length-2] == history[history.length-3];
    }
    
    private void prioritizeAndSelectTarget(EntityID position, List<Blockade> blockades) {
        // 按综合优先级排序（紧急度+距离+救援关键性）
        blockades.sort((b1, b2) -> {
            double priority1 = calculatePriority(position, b1);
            double priority2 = calculatePriority(position, b2);
            return Double.compare(priority2, priority1);
        });
        
        this.result = blockades.get(0).getPosition();
    }

    private double calculatePriority(EntityID position, Blockade blockade) {
        int cost = blockade.getRepairCost();
        int distance = worldInfo.getDistance(position, blockade.getPosition());
        
        double severity = cost / 100.0;
        double distanceFactor = 0.5 + (1000.0 / (distance + 1));
        
        int lastProcessed = lastProcessedTime.getOrDefault(blockade.getPosition(), 0);
        double timeFactor = (currentTime - lastProcessed > 30) ? 1.5 : 1.0;
        
        double visibilityFactor = (visibleRoads.contains(blockade.getPosition())) ? 1.5 : 1.0;
        
        double humanEmergencyFactor = calculateHumanEmergencyFactor(blockade.getPosition());
        
        double rescueCriticalFactor = isCriticalForRescue(blockade.getPosition()) ? 4.0 : 1.0;
        
        return severity * distanceFactor * timeFactor * visibilityFactor * humanEmergencyFactor * rescueCriticalFactor;
    }

    private double calculateHumanEmergencyFactor(EntityID position) {
        double maxEmergency = 1.0;
        
        Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        for (StandardEntity entity : humans) {
            Human human = (Human) entity;
            if (position.equals(human.getPosition())) {
                int hp = human.isHPDefined() ? human.getHP() : 10000;
                int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
                
                double emergency = (10000 - hp) + (buriedness * 10);
                if (emergency > maxEmergency) {
                    maxEmergency = emergency;
                }
            }
        }
        
        return 1.0 + (maxEmergency / 2000.0);
    }

    private boolean isValidBlockade(Blockade blockade) {
        if (!blockade.isRepairCostDefined() || blockade.getRepairCost() <= 0) {
            return false;
        }
        
        EntityID position = blockade.getPosition();
        if (position == null) {
            return false;
        }
        
        StandardEntity road = worldInfo.getEntity(position);
        if (road instanceof Road) {
            Road r = (Road) road;
            if (r.isBlockadesDefined() && !r.getBlockades().contains(blockade.getID())) {
                return false;
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
            selectPatrolPoint();
        }
    }
    
    private void selectPatrolPoint() {
        EntityID myPosition = agentInfo.getPosition();
        List<Road> roads = new ArrayList<>();
        
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (entity instanceof Road) {
                roads.add((Road) entity);
            }
        }
        
        if (!roads.isEmpty()) {
            roads.sort((r1, r2) -> {
                int d1 = worldInfo.getDistance(myPosition, r1.getID());
                int d2 = worldInfo.getDistance(myPosition, r2.getID());
                return Integer.compare(d1, d2);
            });
            
            this.result = roads.get(0).getID();
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}