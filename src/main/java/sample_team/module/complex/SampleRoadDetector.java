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
import rescuecore2.worldmodel.Entity;


import java.util.*;
import java.util.stream.Collectors;
import java.awt.geom.Line2D;

import java.awt.Shape;

public class SampleRoadDetector extends RoadDetector {

    // 常量定义
    private static final int MAX_HP = 10000;
    private static final int MAX_DAMAGE = 200;
    private static final int MAX_BURIEDNESS = 100;
    private static final int MIN_HP_THRESHOLD = 1000;
    private static final int MIN_DAMAGE_THRESHOLD = 50;
    private static final int MIN_BURIEDNESS_THRESHOLD = 30;
    private static final double DISTANCE_WEIGHT = 0.5;
    private static final double HP_WEIGHT = 0.2;
    private static final double BURIEDNESS_WEIGHT = 0.15;
    private static final double DAMAGE_WEIGHT = 0.15;

    private static final double BASE_VISIBILITY_FACTOR = 1.5;
    private static final double BASE_REFUGE_FACTOR = 4.0;
    private static final double BASE_ENTRANCE_FACTOR = 3.0;
    private static final double BASE_MAIN_ROAD_FACTOR = 3.0;
    private static final double BASE_COORDINATION_FACTOR = 0.3;
    private static final int EMERGENCY_SERVICE_RANGE = 3000000;
    private static final double BASE_EMERGENCY_FACTOR = 3.0;

    private static final int RESERVATION_TIMEOUT = 30;
    private static final int LOCK_TIMEOUT = 20;

    // 属性定义
    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Integer> lastProcessedTime = new HashMap<>();
    private int currentTime;
    private Set<EntityID> visibleAreas = new HashSet<>();
    private Set<EntityID> visibleRoads = new HashSet<>();

    private Set<EntityID> contactedHumans = new HashSet<>();
    private EntityID currentTargetHuman = null;
    private static final Map<EntityID, EntityID> reservedTargets = new HashMap<>();
    private static final Map<EntityID, Integer> reservationTimes = new HashMap<>();

    private EntityID lockedTarget = null;
    private int lockStartTime = -1;
    private static final Map<EntityID, Integer> globalTargetLocks = new HashMap<>();

    public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                              ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clustering = moduleManager.getModule(
            "SampleRoadDetector.Clustering",
            "sample_team.module.algorithm.KMeansClustering");
    }

    @Override
    public SampleRoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.currentTime = agentInfo.getTime();

        // 检查当前目标市民是否已接触
        if (currentTargetHuman != null) {
            StandardEntity entity = worldInfo.getEntity(currentTargetHuman);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                if (agentInfo.getPosition().equals(human.getPosition())) {
                    contactedHumans.add(currentTargetHuman);
                    releaseReservedTarget(currentTargetHuman);
                    currentTargetHuman = null;
                }
            }
        }

        // 检查锁定目标状态
        if (lockedTarget != null) {
            if (isTargetCleared(lockedTarget)) {
                lockedTarget = null;
                lockStartTime = -1;
            } else if (agentInfo.getTime() - lockStartTime > LOCK_TIMEOUT) {
                lockedTarget = null;
                lockStartTime = -1;
            }
        }

        cleanupExpiredReservations();
        cleanupExpiredLocks();

        return this;
    }

    @Override
    public SampleRoadDetector calc() {
        // 优先处理已锁定的目标
        if (lockedTarget != null && !isTargetCleared(lockedTarget)) {
            this.result = lockedTarget;
            return this;
        }

        // 检查是否有需要救援的市民
        List<Human> availableHumans = getAvailableHumans();
        if (!availableHumans.isEmpty()) {
            // 优先救援市民
            availableHumans.sort(new WeightedPrioritySorter(worldInfo, agentInfo.me()));
            for (Human human : availableHumans) {
                EntityID humanId = human.getID();
                if (!contactedHumans.contains(humanId) && !isReservedByOtherPolice(humanId)) {
                    reserveTarget(humanId);
                    this.result = human.getPosition();
                    this.currentTargetHuman = humanId;
                    this.lockedTarget = humanId;
                    this.lockStartTime = currentTime;
                    return this;
                }
            }
            // 如果所有市民都被保留，尝试选择未被其他警察保留的市民
            for (Human human : availableHumans) {
                EntityID humanId = human.getID();
                if (!isReservedByOtherPolice(humanId)) {
                    reserveTarget(humanId);
                    this.result = human.getPosition();
                    this.currentTargetHuman = humanId;
                    this.lockedTarget = humanId;
                    this.lockStartTime = currentTime;
                    return this;
                }
            }
        }

        // 没有市民需要救援时，清理道路障碍物
        List<Blockade> visibleBlockades = getVisibleBlockades();
        if (!visibleBlockades.isEmpty()) {
            prioritizeAndSelectBlockade(visibleBlockades);
        } else {
            detectUsingClusteringForBlockades();
        }

        if (this.result != null) {
            lastProcessedTime.put(result, currentTime);
            lockedTarget = this.result;
            lockStartTime = currentTime;
        }

        return this;
    }

    private List<Human> getAvailableHumans() {
        EntityID position = agentInfo.getPosition();
        visibleAreas.clear();
        StandardEntity currentArea = worldInfo.getEntity(position);
        if (currentArea instanceof rescuecore2.standard.entities.Area) {
            visibleAreas.add(currentArea.getID());
        }
        int viewDistance = calculateViewDistance();
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, viewDistance);
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof rescuecore2.standard.entities.Area) {
                visibleAreas.add(entity.getID());
            }
        }

        List<Human> rescueTargets = filterRescueTargets(
            worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN));
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        return rescueTargetsInCluster.isEmpty() ? rescueTargets : rescueTargetsInCluster;
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
                    validBlockades.add(blockade);
                }
            }
        }
        return validBlockades;
    }

    private void prioritizeAndSelectBlockade(List<Blockade> blockades) {
        EntityID position = agentInfo.getPosition();
        blockades.sort((b1, b2) -> {
            double priority1 = calculateBlockadePriority(position, b1);
            double priority2 = calculateBlockadePriority(position, b2);
            return Double.compare(priority2, priority1);
        });
        if (!blockades.isEmpty()) {
            EntityID selected = blockades.get(0).getPosition();
            globalTargetLocks.put(selected, currentTime + LOCK_TIMEOUT);
            this.result = selected;
        }
    }

    private double calculateBlockadePriority(EntityID position, Blockade blockade) {
        double visibilityFactor = (visibleRoads.contains(blockade.getPosition())) ? BASE_VISIBILITY_FACTOR : 1.0;
        double refugeFactor = isBlockingRefuge(blockade) ? BASE_REFUGE_FACTOR : 1.0;
        double entranceFactor = isAtBuildingEntrance(blockade) ? BASE_ENTRANCE_FACTOR : 1.0;
        double mainRoadFactor = isOnMainRoad(blockade) ? BASE_MAIN_ROAD_FACTOR : 1.0;
        double coordinationFactor = 1.0;
        EntityID roadID = blockade.getPosition();
        if (globalTargetLocks.containsKey(roadID) && globalTargetLocks.get(roadID) > currentTime) {
            coordinationFactor = BASE_COORDINATION_FACTOR;
        }
        double emergencyServiceFactor = calculateEmergencyServiceFactor(blockade.getPosition());
        return visibilityFactor * refugeFactor * entranceFactor * mainRoadFactor * coordinationFactor * emergencyServiceFactor;
    }

    private void detectUsingClusteringForBlockades() {
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
            prioritizeAndSelectBlockade(clusterBlockades);
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

    private int calculateViewDistance() {
        return 30000;
    }

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
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        List<Human> filter = new ArrayList<>();
        HashSet<StandardEntity> inCluster = new HashSet<>(clustering.getClusterEntities(clusterIndex));
        for (StandardEntity next : entities) {
            if (!(next instanceof Human)) continue;
            Human h = (Human) next;
            if (!h.isPositionDefined()) continue;
            StandardEntity position = worldInfo.getPosition(h);
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

    private boolean isTargetCleared(EntityID targetID) {
        StandardEntity entity = worldInfo.getEntity(targetID);
        if (entity instanceof Road) {
            Road road = (Road) entity;
            return !road.isBlockadesDefined() || road.getBlockades().isEmpty();
        }
        return true;
    }

    private boolean isReservedByOtherPolice(EntityID humanId) {
        EntityID reservingPolice = reservedTargets.get(humanId);
        return reservingPolice != null && 
               !reservingPolice.equals(agentInfo.getID()) && 
               !isReservationExpired(humanId);
    }

    private void reserveTarget(EntityID humanId) {
        reservedTargets.put(humanId, agentInfo.getID());
        reservationTimes.put(humanId, currentTime);
    }

    private void releaseReservedTarget(EntityID humanId) {
        if (agentInfo.getID().equals(reservedTargets.get(humanId))) {
            reservedTargets.remove(humanId);
            reservationTimes.remove(humanId);
        }
    }

    private boolean isReservationExpired(EntityID humanId) {
        Integer reservationTime = reservationTimes.get(humanId);
        if (reservationTime == null) return true;
        return (currentTime - reservationTime) > RESERVATION_TIMEOUT;
    }

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

    private void cleanupExpiredLocks() {
        Iterator<Map.Entry<EntityID, Integer>> it = globalTargetLocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityID, Integer> entry = it.next();
            if (entry.getValue() < currentTime) {
                it.remove();
            }
        }
    }

    // 以下方法来自第二个代码，用于障碍物优先级计算
    private boolean isBlockingRefuge(Blockade blockade) {
        if (blockade == null || !blockade.isPositionDefined()) {
            return false;
        }
        EntityID blockPos = blockade.getPosition();
        if (blockPos == null) {
            return false;
        }
        Collection<StandardEntity> refuges = worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE);
        for (StandardEntity entity : refuges) {
            if (!(entity instanceof rescuecore2.standard.entities.Area)) continue;
            rescuecore2.standard.entities.Area refuge = (rescuecore2.standard.entities.Area) entity;
            if (refuge.getID().equals(blockPos)) {
                return true;
            }
            List<EntityID> neighbours = refuge.getNeighbours();
            if (neighbours != null && neighbours.contains(blockPos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtBuildingEntrance(Blockade blockade) {
        EntityID positionId = blockade.getPosition();
        if (positionId == null) return false;
        StandardEntity positionEntity = worldInfo.getEntity(positionId);
        if (positionEntity instanceof Building) {
            Building building = (Building) positionEntity;
            return isBlockadeAtEntrance(blockade, building, worldInfo);
        } else if (positionEntity instanceof Road) {
            Road road = (Road) positionEntity;
            if (!road.isEdgesDefined()) return false;
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

    private boolean isBlockadeAtEntrance(Blockade blockade, Building building, WorldInfo worldInfo) {
        if (!isBlockadeInBuilding(blockade, building)) return false;
        List<Edge> entrances = getEntranceEdges(building, worldInfo);
        if (entrances.isEmpty()) return false;
        return entrances.stream().anyMatch(edge -> coversEdge(blockade, edge));
    }

    private boolean isBlockadeInBuilding(Blockade blockade, Building building) {
        return blockade.isPositionDefined() && blockade.getPosition().equals(building.getID());
    }

    private List<Edge> getEntranceEdges(Building building, WorldInfo worldInfo) {
        return building.getEdges().stream()
            .filter(Edge::isPassable)
            .filter(edge -> {
                EntityID neighbourID = edge.getNeighbour();
                Entity neighbour = worldInfo.getEntity(neighbourID);
                return neighbour instanceof Road;
            })
            .collect(Collectors.toList());
    }

    private boolean coversEdge(Blockade blockade, Edge edge) {
        Shape blockadeShape = blockade.getShape();
        java.awt.geom.Area blockadeArea = new java.awt.geom.Area(blockadeShape);
        Line2D edgeLine = new Line2D.Double(
            edge.getStartX(), edge.getStartY(),
            edge.getEndX(), edge.getEndY()
        );
        return blockadeArea.intersects(edgeLine.getBounds2D());
    }

    private boolean isOnMainRoad(Blockade blockade) {
        if (!blockade.isPositionDefined()) return false;
        EntityID roadId = blockade.getPosition();
        StandardEntity roadEntity = worldInfo.getEntity(roadId);
        if (!(roadEntity instanceof Road)) return false;
        return isMainRoad((Road) roadEntity, worldInfo);
    }

    private boolean isMainRoad(Road road, WorldInfo worldInfo) {
        int neighborThreshold = calculateNeighborThreshold(worldInfo);
        if (road.getNeighbours().size() >= neighborThreshold) {
            return true;
        }
        return isConnectedToCriticalBuildings(road, worldInfo);
    }

    private int calculateNeighborThreshold(WorldInfo worldInfo) {
        Collection<StandardEntity> roads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        double avgNeighbors = roads.stream()
            .mapToInt(road -> ((Road) road).getNeighbours().size())
            .average()
            .orElse(3);
        return (int) Math.ceil(avgNeighbors * 1.5);
    }

    private boolean isConnectedToCriticalBuildings(Road road, WorldInfo worldInfo) {
        Set<StandardEntityURN> criticalTypes = new HashSet<>(Arrays.asList(
            StandardEntityURN.FIRE_STATION,
            StandardEntityURN.POLICE_OFFICE,
            StandardEntityURN.AMBULANCE_CENTRE,
            StandardEntityURN.REFUGE
        ));
        for (StandardEntityURN type : criticalTypes) {
            for (StandardEntity building : worldInfo.getEntitiesOfType(type)) {
                if (!road.getEdgesTo(building.getID()).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private double calculateEmergencyServiceFactor(EntityID blockadePosition) {
        double emergencyFactor = 1.0;
        Map<EntityID, EntityID> fireBrigadePositions = getFireBrigadePositions();
        for (EntityID firePosition : fireBrigadePositions.values()) {
            int distance = worldInfo.getDistance(blockadePosition, firePosition);
            if (distance >= 0 && distance <= EMERGENCY_SERVICE_RANGE) {
                double proximityFactor = 1.0 + (1.0 - (distance / (double)EMERGENCY_SERVICE_RANGE));
                emergencyFactor = Math.max(emergencyFactor, BASE_EMERGENCY_FACTOR * proximityFactor);
            }
        }
        Map<EntityID, EntityID> ambulancePositions = getAmbulancePositions();
        for (EntityID ambulancePosition : ambulancePositions.values()) {
            int distance = worldInfo.getDistance(blockadePosition, ambulancePosition);
            if (distance >= 0 && distance <= EMERGENCY_SERVICE_RANGE) {
                double proximityFactor = 1.0 + (1.0 - (distance / (double)EMERGENCY_SERVICE_RANGE));
                emergencyFactor = Math.max(emergencyFactor, (BASE_EMERGENCY_FACTOR + 1.0) * proximityFactor);
            }
        }
        return emergencyFactor;
    }

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

    @Override
    public EntityID getTarget() {
        return result;
    }

    // 来自第一个代码的加权优先级排序器
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
            return Double.compare(scoreB, scoreA);
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