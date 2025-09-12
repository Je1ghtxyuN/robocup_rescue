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

    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Integer> lastProcessedTime = new HashMap<>();
    private int currentTime;
    private Set<EntityID> visibleAreas = new HashSet<>();
    
    // 新增：记录已接触过的伤员
    private Set<EntityID> contactedHumans = new HashSet<>();
    // 新增：当前目标伤员
    private EntityID currentTargetHuman = null;

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
                    currentTargetHuman = null; // 清除当前目标
                }
            }
        }
        
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
            
            // 从高优先级目标中选取第一个未接触过的
            for (Human human : targets) {
                if (!contactedHumans.contains(human.getID())) {
                    this.result = human.getPosition();
                    this.currentTargetHuman = human.getID(); // 设置当前目标伤员
                    break;
                }
            }
            
            // 如果所有目标都已接触过，选择优先级最高的
            if (this.result == null && !targets.isEmpty()) {
                this.result = targets.get(0).getPosition();
                this.currentTargetHuman = targets.get(0).getID();
            }
        }
        
        return this;
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
            this.result = clusterTargets.get(0).getPosition();
            this.currentTargetHuman = clusterTargets.get(0).getID();
        } else {
            // 聚类区域没有目标，选择最近的巡逻点
            selectPatrolPoint();
            currentTargetHuman = null; // 巡逻点不是伤员
        }
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