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
    
    private EntityID lockedTarget = null;
    private int lockExpiryTime = 0;
    
    private static final int STUCK_TIME_THRESHOLD = 60;
    private static final int MAX_STUCK_COUNT = 5;

    private Map<EntityID, Integer> roadLastProcessedTime = new HashMap<>();
    private int currentTime;

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
        currentTime = agentInfo.getTime();  
        if (pathPlanning != null) {
            pathPlanning.updateInfo(messageManager);
        }
        
        if (agentInfo.getTime() % 30 == 0) {
            unreachableTargets.clear();
        }
        
        EntityID currentPosition = agentInfo.getPosition();
        if (!currentPosition.equals(lastPosition)) {
            lastPosition = currentPosition;
            lastPositionTime = agentInfo.getTime();
            stuckCounter = 0;
        }
        
        return this;
    }

    @Override
    public PoliceSearch calc() {
        currentTime = agentInfo.getTime();

        if (!initialized) {
            initialize(moduleManager);
        }
        
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        if (isStuck()) {
            handleStuckSituation();
            return this;
        }
        
        if (lockedTarget != null && agentInfo.getTime() < lockExpiryTime) {
            if (processLockedTarget(myPosition)) {
                return this;
            }
        }
        
        List<Blockade> blockades = getValidUnclearedBlockades();
        
        if (!assignNewTarget(myPosition, blockades)) {
            patrolRegionRoad();
        }
        
        return this;
    }

     // 道路访问时间更新方法
    private void updateRoadProcessedTime(EntityID roadId) {
        roadLastProcessedTime.put(roadId, currentTime);
    }

    private List<Blockade> prioritizeRescueAreas(List<Blockade> blockades) {
        List<Blockade> prioritized = new ArrayList<>(blockades);
        
        Map<Blockade, Double> rescueFactors = new HashMap<>();
        for (Blockade blockade : blockades) {
            double factor = calculateRescueTeamDensityFactor(blockade.getPosition());
            rescueFactors.put(blockade, factor);
        }
        
        prioritized.sort((b1, b2) -> {
            double factor1 = rescueFactors.getOrDefault(b1, 0.0);
            double factor2 = rescueFactors.getOrDefault(b2, 0.0);
            return Double.compare(factor2, factor1);
        });
        
        return prioritized;
    }
    
    private double calculateRescueTeamDensityFactor(EntityID position) {
        double densityFactor = 0.0;
        int radius = 50000;
        
        Collection<StandardEntity> nearbyEntities = worldInfo.getObjectsInRange(position, radius);
        
        int fireBrigadeCount = 0;
        int ambulanceTeamCount = 0;
        
        for (StandardEntity entity : nearbyEntities) {
            if (entity instanceof FireBrigade) {
                fireBrigadeCount++;
            } else if (entity instanceof AmbulanceTeam) {
                ambulanceTeamCount++;
            }
        }
        
        densityFactor = (fireBrigadeCount + ambulanceTeamCount) * 0.2;
        
        if (fireBrigadeCount + ambulanceTeamCount >= 3) {
            densityFactor *= 1.5;
        }
        
        return densityFactor;
    }

    private boolean isStuck() {
        boolean timeExpired = (agentInfo.getTime() - lastPositionTime) > STUCK_TIME_THRESHOLD;
        boolean targetInvalid = lockedTarget == null || !isTargetValid(lockedTarget);
        return timeExpired && targetInvalid;
    }

    private void handleStuckSituation() {
        stuckCounter++;
        
        if (lastTarget != null) {
            unreachableTargets.add(lastTarget);
            unlockTarget();
        }
        
        unlockTarget();
        
        if (stuckCounter > MAX_STUCK_COUNT) {
            result = findRandomRoadAnywhere();
        } else {
            result = findRandomNearbyRoad();
        }
    }
    
    private void unlockTarget() {
        lockedTarget = null;
        lockExpiryTime = 0;
    }

    private boolean processLockedTarget(EntityID myPosition) {
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
        
        blockades = prioritizeRescueAreas(blockades);
        
        blockades.sort((b1, b2) -> {
            double p1 = calculatePriority(myPosition, b1);
            double p2 = calculatePriority(myPosition, b2);
            return Double.compare(p2, p1);
        });

        for (Blockade targetBlockade : blockades) {
            EntityID roadID = targetBlockade.getPosition();
            
            if (unreachableTargets.contains(roadID)) continue;
            
            pathPlanning.setFrom(myPosition);
            pathPlanning.setDestination(Collections.singleton(roadID));
            pathPlanning.calc();
            
            if (isPathValid(pathPlanning.getResult())) {
                result = roadID;
                lastTarget = roadID;
                updateRoadProcessedTime(roadID);
                
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
        
        double baseScore = (cost * 100.0) / (distance + 1);
        
        double humanEmergencyFactor = calculateHumanEmergencyFactor(blockade.getPosition());
        
        // 新增：检查是否是救援关键路径
        boolean isCritical = isCriticalForRescue(blockade.getPosition());
        double criticalFactor = isCritical ? 2.0 : 1.0;
        
        return baseScore * humanEmergencyFactor * criticalFactor;
    }
    
    // 新增方法：检查是否是救援关键路径
    private boolean isCriticalForRescue(EntityID roadId) {
        StandardEntity roadEntity = worldInfo.getEntity(roadId);
        if (!(roadEntity instanceof Road)) return false;
        
        Road road = (Road) roadEntity;
        
        for (EntityID neighbor : road.getNeighbours()) {
            StandardEntity neighborEntity = worldInfo.getEntity(neighbor);
            if (neighborEntity instanceof Building) {
                Building building = (Building) neighborEntity;
                
                if (building.isOnFire() || worldInfo.getNumberOfBuried(building.getID()) > 0) {
                    Collection<StandardEntity> nearbyRescuers = worldInfo.getObjectsInRange(roadId, 50000);
                    for (StandardEntity rescuer : nearbyRescuers) {
                        if (rescuer instanceof AmbulanceTeam || rescuer instanceof FireBrigade) {
                            Human human = (Human) rescuer;
                            if (human.getPosition().equals(roadId)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
    
    private double calculateHumanEmergencyFactor(EntityID position) {
        double maxEmergency = 1.0;
        final int BASE_HP = 10000;
        
        Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        for (StandardEntity entity : humans) {
            Human human = (Human) entity;
            if (position.equals(human.getPosition())) {
                int hp = human.isHPDefined() ? human.getHP() : BASE_HP;
                int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
                
                double emergency = (BASE_HP - hp) * 0.8 + (buriedness * 15);
                if (emergency > maxEmergency) {
                    maxEmergency = emergency;
                }
            }
        }
        
        return 1.0 + (maxEmergency / 2000.0);
    }

    private boolean isPathValid(List<EntityID> path) {
        return path != null && !path.isEmpty() && path.size() > 1;
    }

    private boolean isTargetValid(EntityID target) {
        StandardEntity entity = worldInfo.getEntity(target);
        if (!(entity instanceof Road)) return false;
        
        Road road = (Road) entity;
        return road.isBlockadesDefined() && !road.getBlockades().isEmpty();
    }

    private List<Blockade> getValidUnclearedBlockades() {
        List<Blockade> blockades = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
            Blockade blockade = (Blockade) entity;
            
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
        // 1. 优先选择救援热点区域
        EntityID hotspot = findRescueHotspot();
        if (hotspot != null) {
            result = hotspot;
            updateRoadProcessedTime(result);
            return;
        }
        
         // 2. 选择集群内未探索区域
        if (clustering != null) {
            int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
            Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);
            List<EntityID> unexplored = getUnexploredRoads(clusterEntities);
            
            if (!unexplored.isEmpty()) {
                result = unexplored.get(new Random().nextInt(unexplored.size()));
                updateRoadProcessedTime(result);
                return;
            }
        }

        // 3. 全局搜索高优先级区域
        List<EntityID> highPriorityRoads = findHighPriorityRoads();
        if (!highPriorityRoads.isEmpty()) {
            result = highPriorityRoads.get(0);
            updateRoadProcessedTime(result);
            return;
        }
        
        // 4. 最后选择随机道路
        patrolRandomRoad();
    }

    // 获取未探索道路
    private List<EntityID> getUnexploredRoads(Collection<StandardEntity> entities) {
        List<EntityID> unexplored = new ArrayList<>();
        
        for (StandardEntity entity : entities) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                EntityID roadId = road.getID();

                // 获取最后处理时间（如果不存在则为0）
                int lastProcessed = roadLastProcessedTime.getOrDefault(roadId, 0);
                
                // 最近30秒内未被访问
                if (lastProcessed < currentTime - 30) {
                    unexplored.add(roadId);
                }
            }
        }
        return unexplored;
    }

    // 查找高优先级道路
    private List<EntityID> findHighPriorityRoads() {
        List<EntityID> highPriority = new ArrayList<>();
        
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (!(entity instanceof Road)) continue;
            Road road = (Road) entity;
            
            // 根据连接建筑物状态计算优先级
            double priority = calculateRoadPriority(road);
            
            if (priority > 2.0) { // 高优先级阈值
                highPriority.add(road.getID());
            }
        }
        
        // 按优先级降序排序
        highPriority.sort((id1, id2) -> 
            Double.compare(calculateRoadPriority((Road)worldInfo.getEntity(id2)), 
                        calculateRoadPriority((Road)worldInfo.getEntity(id1))));
        return highPriority;
    }

    // 计算道路优先级
    private double calculateRoadPriority(Road road) {
        double priority = 0.0;
        
        // 连接建筑物的紧急程度
        for (EntityID neighbor : road.getNeighbours()) {
            StandardEntity entity = worldInfo.getEntity(neighbor);
            if (entity instanceof Building) {
                Building building = (Building) entity;
                if (building.isOnFire()) priority += 1.5;
                if (worldInfo.getNumberOfBuried(building.getID()) > 0) priority += 2.0;
            }
        }
        
        // 附近救援队伍数量
        Collection<StandardEntity> rescuers = worldInfo.getObjectsInRange(road.getID(), 50000);
        for (StandardEntity rescuer : rescuers) {
            if (rescuer instanceof AmbulanceTeam || rescuer instanceof FireBrigade) {
                priority += 0.5;
            }
        }
        
        return priority;
    }
    
    private EntityID findRescueHotspot() {
        double maxDensity = 0.0;
        EntityID bestHotspot = null;
        
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (!(entity instanceof Road)) continue;
            Road road = (Road) entity;
            EntityID roadID = road.getID();
            
            double density = calculateRescueTeamDensityFactor(roadID);
            
            if (density > maxDensity) {
                maxDensity = density;
                bestHotspot = roadID;
            }
        }
        
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