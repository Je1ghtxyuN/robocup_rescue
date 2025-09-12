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

public class SampleRoadDetector extends RoadDetector {

    // 属性最大值常量（用于归一化计算）
    private static final int MAX_HP = 10000; // 最大生命值（用于计算生命值评分）
    private static final int MAX_DAMAGE = 200; // 最大伤害值（用于计算伤害评分）
    private static final int MAX_BURIEDNESS = 100; // 最大掩埋程度（用于计算掩埋程度评分）

    // 可调节的阈值参数（用于过滤无效目标）
    private static final int MIN_HP_THRESHOLD = 1000; // 最小生命值阈值：低于此值的伤员可能无需立即救援
    private static final int MIN_DAMAGE_THRESHOLD = 50; // 最小伤害阈值：低于此值的伤员可能伤势较轻
    private static final int MIN_BURIEDNESS_THRESHOLD = 30; // 最小掩埋程度阈值：低于此值的伤员可能未被掩埋或掩埋较浅

    // 权重配置（影响目标选择的优先级）
    private static final double DISTANCE_WEIGHT = 0.5; // 距离权重：距离越近的目标优先级越高（最高权重）
    private static final double HP_WEIGHT = 0.2; // 生命值权重：生命值越低的目标优先级越高
    private static final double BURIEDNESS_WEIGHT = 0.15; // 掩埋程度权重：掩埋程度越高的目标优先级越高
    private static final double DAMAGE_WEIGHT = 0.15; // 伤害权重：伤害值越高的目标优先级越高

    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Integer> lastProcessedTime = new HashMap<>();
    private int currentTime;
    private Set<EntityID> visibleAreas = new HashSet<>();
    
    // 新增：记录已接触过的伤员
    private Set<EntityID> contactedHumans = new HashSet<>();
    // 新增：当前目标伤员
    private EntityID currentTargetHuman = null;
    
    // 新增：防止警察聚集的共享状态
    private static final Map<EntityID, EntityID> reservedTargets = new HashMap<>();
    private static final Map<EntityID, Integer> reservationTimes = new HashMap<>();
    private static final int RESERVATION_TIMEOUT = 30; // 30时间单位后保留失效

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

    @Override
    public SampleRoadDetector calc() {
        EntityID position = agentInfo.getPosition();
        result = null;
        visibleAreas.clear();

        // 1. 获取当前位置所在区域
        StandardEntity currentArea = worldInfo.getEntity(position);
        if (currentArea instanceof rescuecore2.standard.entities.Area) {
            visibleAreas.add(currentArea.getID());
        }
        
        // 2. 获取视野范围内的区域
        int viewDistance = calculateViewDistance();
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, viewDistance);
        
        // 3. 筛选视野内的区域
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof rescuecore2.standard.entities.Area) {
                visibleAreas.add(entity.getID());
            }
        }
        
        // 4. 获取所有可见人类实体
        List<Human> rescueTargets = filterRescueTargets(
            worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN));
        
        // 5. 过滤在集群内的目标
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        List<Human> targets = rescueTargetsInCluster.isEmpty() ? rescueTargets : rescueTargetsInCluster;

        // 6. 如果没有目标，使用聚类方法
        if (targets.isEmpty()) {
            detectUsingClustering();
        } else {
            // 使用加权评分系统选择最佳目标
            targets.sort(new WeightedPrioritySorter(worldInfo, agentInfo.me()));
            
            // 从高优先级目标中选取第一个未接触过且未被其他警察保留的目标
            for (Human human : targets) {
                EntityID humanId = human.getID();
                if (!contactedHumans.contains(humanId) && !isReservedByOtherPolice(humanId)) {
                    // 保留这个目标
                    reserveTarget(humanId);
                    this.result = human.getPosition();
                    this.currentTargetHuman = humanId; // 设置当前目标伤员
                    break;
                }
            }
            
            // 如果所有目标都已接触过或被保留，选择优先级最高的可用目标
            if (this.result == null && !targets.isEmpty()) {
                for (Human human : targets) {
                    EntityID humanId = human.getID();
                    if (!isReservedByOtherPolice(humanId)) {
                        reserveTarget(humanId);
                        this.result = human.getPosition();
                        this.currentTargetHuman = humanId;
                        break;
                    }
                }
            }
            
            // 如果仍然没有找到目标，使用聚类方法
            if (this.result == null) {
                detectUsingClustering();
            }
        }
        
        return this;
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

    // 计算实际视野距离
    private int calculateViewDistance() {
        return 30000; // 基础视野距离
    }

    private void detectUsingClustering() {
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

        List<Human> clusterTargets = new ArrayList<>();
        for (StandardEntity entity : clusterEntities) {
            if (entity instanceof Human && entity.getStandardURN() == StandardEntityURN.CIVILIAN) {
                Human human = (Human) entity;
                if (isValidHuman(human) && !contactedHumans.contains(human.getID())) {
                    clusterTargets.add(human);
                }
            }
        }

        if (!clusterTargets.isEmpty()) {
            clusterTargets.sort(new WeightedPrioritySorter(worldInfo, agentInfo.me()));
            
            // 选择第一个未被其他警察保留的目标
            for (Human human : clusterTargets) {
                EntityID humanId = human.getID();
                if (!isReservedByOtherPolice(humanId)) {
                    reserveTarget(humanId);
                    this.result = human.getPosition();
                    this.currentTargetHuman = humanId;
                    return;
                }
            }
        }
        
        // 聚类区域没有目标或所有目标都被保留，选择最近的巡逻点
        selectPatrolPoint();
        currentTargetHuman = null; // 巡逻点不是伤员
    }
    
    private void selectPatrolPoint() {
        EntityID myPosition = agentInfo.getPosition();
        List<StandardEntity> patrolPoints = new ArrayList<>();
        
        // 获取所有可能的巡逻点（道路、建筑、避难所）
        for (StandardEntity entity : worldInfo.getEntitiesOfType(
                StandardEntityURN.ROAD, 
                StandardEntityURN.BUILDING,
                StandardEntityURN.REFUGE)) {
            patrolPoints.add(entity);
        }
        
        if (!patrolPoints.isEmpty()) {
            // 按距离排序
            patrolPoints.sort((e1, e2) -> {
                int d1 = worldInfo.getDistance(myPosition, e1.getID());
                int d2 = worldInfo.getDistance(myPosition, e2.getID());
                return Integer.compare(d1, d2);
            });
            
            // 选择最近的区域作为巡逻点
            this.result = patrolPoints.get(0).getID();
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }

    // 从HumanDetector移植的人员过滤逻辑
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

    // 从HumanDetector移植的加权优先级排序器
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