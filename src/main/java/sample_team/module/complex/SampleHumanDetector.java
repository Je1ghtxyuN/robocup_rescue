package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleHumanDetector extends HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private Logger logger;

    // 属性最大值常量
    private static final int MAX_HP = 10000;
    private static final int MAX_DAMAGE = 200;
    private static final int MAX_BURIEDNESS = 100;

    public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                "adf.impl.module.algorithm.KMeansClustering");
        registerModule(this.clustering);
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        logger.debug("Time:" + agentInfo.getTime());
        super.updateInfo(messageManager);
        return this;
    }

    @Override
    public HumanDetector calc() {
        Human transportHuman = this.agentInfo.someoneOnBoard();
        if (transportHuman != null) {
            logger.debug("someoneOnBoard:" + transportHuman);
            this.result = transportHuman.getID();
            return this;
        }
        if (this.result != null) {
            Human target = (Human) this.worldInfo.getEntity(this.result);
            if (!isValidHuman(target)) {
                logger.debug("Invalid Human:" + target + " ==>reset target");
                this.result = null;
            }
        }
        if (this.result == null) {
            this.result = calcTarget();
        }
        return this;
    }

    private EntityID calcTarget() {
        List<Human> rescueTargets = filterRescueTargets(
                this.worldInfo.getEntitiesOfType(CIVILIAN));
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        List<Human> targets = rescueTargetsInCluster.isEmpty() ? rescueTargets : rescueTargetsInCluster;

        logger.debug("Potential targets count: " + targets.size());

        if (targets.isEmpty()) {
            return null;
        }

        // 使用加权评分系统选择最佳目标
        targets.sort(new WeightedPrioritySorter(this.worldInfo, this.agentInfo.me()));
        Human selected = targets.get(0);
        logger.debug("Selected target: " + selected + " with ID: " + selected.getID());

        return selected.getID();
    }

    // 新的加权优先级排序器
    private class WeightedPrioritySorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        // 权重配置 - 可以根据实际需求调整这些值
        private static final double DISTANCE_WEIGHT = 0.1;
        private static final double HP_WEIGHT = 0.3;
        private static final double BURIEDNESS_WEIGHT = 0.5;
        private static final double DAMAGE_WEIGHT = 0.3;

        WeightedPrioritySorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        @Override
        public int compare(StandardEntity a, StandardEntity b) {
            double scoreA = calculatePriorityScore((Human) a);
            double scoreB = calculatePriorityScore((Human) b);

            // 分数越高表示优先级越高，所以按降序排列
            return Double.compare(scoreB, scoreA);
        }

        private double calculatePriorityScore(Human human) {
            // 计算距离分数（距离越近分数越高）
            double distanceScore = calculateDistanceScore(human);

            // 计算血量分数（血量越低分数越高）
            double hpScore = calculateHpScore(human);

            // 计算掩埋程度分数（掩埋程度越高分数越高）
            double buriednessScore = calculateBuriednessScore(human);

            // 计算伤害分数（伤害越高分数越高）
            double damageScore = calculateDamageScore(human);

            // 综合加权分数
            return (distanceScore * DISTANCE_WEIGHT) +
                    (hpScore * HP_WEIGHT) +
                    (buriednessScore * BURIEDNESS_WEIGHT) +
                    (damageScore * DAMAGE_WEIGHT);
        }

        private double calculateDistanceScore(Human human) {
            int distance = this.worldInfo.getDistance(this.reference, human);
            // 使用指数衰减函数，距离越近分数越高
            // 调整分母以控制衰减速度，这里使用50000作为基准值
            return Math.exp(-distance / 50000.0);
        }

        private double calculateHpScore(Human human) {
            if (!human.isHPDefined()) return 0;

            int hp = human.getHP();
            // 血量越低，优先级越高（使用线性函数）
            // HP范围0-10000，归一化到0-1范围
            return 1.0 - (hp / (double) MAX_HP);
        }

        private double calculateBuriednessScore(Human human) {
            if (!human.isBuriednessDefined()) return 0;

            int buriedness = human.getBuriedness();
            // 掩埋程度越高，优先级越高
            // 掩埋程度范围0-100，归一化到0-1范围
            return buriedness / (double) MAX_BURIEDNESS;
        }

        private double calculateDamageScore(Human human) {
            if (!human.isDamageDefined()) return 0;

            int damage = human.getDamage();
            // 伤害越高，优先级越高
            // 伤害范围0-10000，归一化到0-1范围
            return damage / (double) MAX_DAMAGE;
        }
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {
        List<Human> rescueTargets = new ArrayList<>();
        for (StandardEntity next : list) {
            if (!(next instanceof Human))
                continue;
            Human h = (Human) next;
            if (!isValidHuman(h))
                continue;
            if (h.getBuriedness() == 0)
                continue;
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
            if (!(next instanceof Human))
                continue;
            Human h = (Human) next;
            if (!h.isPositionDefined())
                continue;
            StandardEntity position = this.worldInfo.getPosition(h);
            if (position == null)
                continue;
            if (!inCluster.contains(position))
                continue;
            filter.add(h);
        }
        return filter;
    }

    private boolean isValidHuman(StandardEntity entity) {
        if (entity == null)
            return false;
        if (!(entity instanceof Human))
            return false;

        Human target = (Human) entity;
        if (!target.isHPDefined() || target.getHP() == 0)
            return false;
        if (!target.isPositionDefined())
            return false;
        if (!target.isDamageDefined() || target.getDamage() == 0)
            return false;
        if (!target.isBuriednessDefined())
            return false;

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null)
            return false;

        StandardEntityURN positionURN = position.getStandardURN();
        if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
            return false;

        return true;
    }
}