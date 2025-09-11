package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

public class PoliceHumanDetector extends HumanDetector {
    private Clustering clustering;
    private EntityID result;
    private Logger logger;

    // 警察特有的参数：跟随紧急服务的权重
    private static final double EMERGENCY_SERVICE_FOLLOW_WEIGHT = 2.0;
    private static final int FOLLOW_RANGE = 50000; // 50米范围内的紧急服务

    public PoliceHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                             ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = Logger.getLogger(this.getClass());
        this.clustering = moduleManager.getModule(
            "PoliceHumanDetector.Clustering",
            "sample_team.module.algorithm.KMeansClustering");
        registerModule(this.clustering);
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }

    @Override
    public HumanDetector calc() {
        // 优先跟随紧急服务（消防员或救护队）
        EntityID emergencyTarget = findNearbyEmergencyService();
        if (emergencyTarget != null) {
            this.result = emergencyTarget;
            return this;
        }

        // 如果没有紧急服务可跟随，则寻找需要帮助的市民
        List<Human> potentialTargets = findRescueTargets();
        
        if (!potentialTargets.isEmpty()) {
            // 使用加权评分系统选择最佳目标
            potentialTargets.sort(new PolicePrioritySorter(this.worldInfo, this.agentInfo.me()));
            this.result = potentialTargets.get(0).getID();
        } else {
            this.result = null;
        }
        
        return this;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    // 寻找附近的紧急服务（消防员或救护队）
    private EntityID findNearbyEmergencyService() {
        EntityID myPosition = agentInfo.getPosition();
        List<Human> emergencyPersonnel = new ArrayList<>();
        
        // 获取所有消防员和救护队员
        for (StandardEntity entity : worldInfo.getEntitiesOfType(FIRE_BRIGADE, AMBULANCE_TEAM)) {
            if (entity instanceof Human) {
                Human human = (Human) entity;
                if (human.isPositionDefined()) {
                    int distance = worldInfo.getDistance(myPosition, human.getPosition());
                    if (distance <= FOLLOW_RANGE) {
                        emergencyPersonnel.add(human);
                    }
                }
            }
        }
        
        if (!emergencyPersonnel.isEmpty()) {
            // 选择最近的紧急服务人员
            emergencyPersonnel.sort(Comparator.comparingInt(
                h -> worldInfo.getDistance(myPosition, h.getPosition())));
            return emergencyPersonnel.get(0).getPosition();
        }
        
        return null;
    }

    // 寻找需要救援的目标
    private List<Human> findRescueTargets() {
        List<Human> targets = new ArrayList<>();
        
        // 获取所有市民
        Collection<StandardEntity> civilians = worldInfo.getEntitiesOfType(CIVILIAN);
        for (StandardEntity entity : civilians) {
            if (!(entity instanceof Human)) continue;
            
            Human human = (Human) entity;
            if (isValidRescueTarget(human)) {
                targets.add(human);
            }
        }
        
        return targets;
    }

    // 检查是否为有效的救援目标（警察版本）
    private boolean isValidRescueTarget(Human human) {
        if (!human.isHPDefined() || human.getHP() <= 0) return false;
        if (!human.isPositionDefined()) return false;
        if (!human.isBuriednessDefined() || human.getBuriedness() <= 0) return false;
        
        // 检查位置是否可达（没有警察特有的限制）
        StandardEntity position = worldInfo.getPosition(human);
        if (position == null) return false;
        
        // 排除已经在避难所的目标
        StandardEntityURN positionURN = position.getStandardURN();
        return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
    }

    // 警察专用的优先级排序器
    private class PolicePrioritySorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        // 警察特有的权重配置
        private static final double DISTANCE_WEIGHT = 0.4;
        private static final double HP_WEIGHT = 0.2;
        private static final double BURIEDNESS_WEIGHT = 0.2;
        private static final double ACCESSIBILITY_WEIGHT = 0.2; // 可达性权重

        PolicePrioritySorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        @Override
        public int compare(StandardEntity a, StandardEntity b) {
            double scoreA = calculatePriorityScore((Human) a);
            double scoreB = calculatePriorityScore((Human) b);
            return Double.compare(scoreB, scoreA); // 降序
        }

        private double calculatePriorityScore(Human human) {
            // 计算距离分数
            double distanceScore = calculateDistanceScore(human);
            // 计算血量分数
            double hpScore = calculateHpScore(human);
            // 计算掩埋程度分数
            double buriednessScore = calculateBuriednessScore(human);
            // 计算可达性分数（警察特有：考虑道路通畅程度）
            double accessibilityScore = calculateAccessibilityScore(human);

            return (distanceScore * DISTANCE_WEIGHT) +
                   (hpScore * HP_WEIGHT) +
                   (buriednessScore * BURIEDNESS_WEIGHT) +
                   (accessibilityScore * ACCESSIBILITY_WEIGHT);
        }

        private double calculateDistanceScore(Human human) {
            int distance = this.worldInfo.getDistance(this.reference, human);
            return Math.exp(-distance / 50000.0);
        }

        private double calculateHpScore(Human human) {
            return 1.0 - (human.getHP() / 10000.0);
        }

        private double calculateBuriednessScore(Human human) {
            return human.getBuriedness() / 100.0;
        }

        private double calculateAccessibilityScore(Human human) {
            // 警察特有逻辑：评估通往目标的道路通畅程度
            // 返回1.0表示道路通畅，0.0表示道路完全阻塞
            EntityID targetPosition = human.getPosition();
            if (targetPosition == null) return 0.0;
            
            // 简单实现：检查路径上的障碍物数量
            List<EntityID> path = calculatePath(reference.getID(), targetPosition);
            if (path == null) return 0.0;
            
            int blockadeCount = 0;
            for (EntityID roadId : path) {
                StandardEntity road = worldInfo.getEntity(roadId);
                if (road instanceof Road) {
                    Road r = (Road) road;
                    if (r.isBlockadesDefined() && !r.getBlockades().isEmpty()) {
                        blockadeCount++;
                    }
                }
            }
            
            double blockageRatio = (double) blockadeCount / path.size();
            return 1.0 - blockageRatio;
        }
        
        private List<EntityID> calculatePath(EntityID from, EntityID to) {
            // 这里需要路径规划模块，简化实现返回空列表
            return Collections.emptyList();
        }
    }

    // 新增方法：获取当前选中的市民目标（建筑物位置）
    public EntityID getRescueTarget() {
        return this.result;
    }
}