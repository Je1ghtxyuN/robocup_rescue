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
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;


public class AmbulanceHumanDetector extends HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private Logger logger;

    // 属性最大值常量
    private static final int MAX_HP = 10000;
    private static final int MAX_DAMAGE = 200;
    private static final int MAX_BURIEDNESS = 100;

    // 可调节的阈值参数inValidHuman
    private static final int MIN_HP_THRESHOLD = 1000;    // HP低于此值认为无效
    private static final int MIN_DAMAGE_THRESHOLD = 50;  // 伤害低于此值认为无效
    private static final int MIN_BURIEDNESS_THRESHOLD = 30; // 掩埋程度低于此值认为无效

    public AmbulanceHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule("AmbulanceHumanDetector.Clustering",
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

        // 使用加权评分系统选择最佳目标 - 救护队更关注HP和伤害
        targets.sort(new AmbulancePrioritySorter(this.worldInfo, this.agentInfo.me()));
        Human selected = targets.get(0);
        logger.debug("Selected target: " + selected + " with ID: " + selected.getID());

        return selected.getID();
    }

    // 救护队特定的优先级排序器
    private class AmbulancePrioritySorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        // 救护队权重配置 - 更关注HP和伤害
        private static final double DISTANCE_WEIGHT = 0.5;
        private static final double HP_WEIGHT = 0.2;
        private static final double BURIEDNESS_WEIGHT = 0.15;
        private static final double DAMAGE_WEIGHT = 0.15;

        AmbulancePrioritySorter(WorldInfo wi, StandardEntity reference) {
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
            return (calculateDistanceScore(human) * DISTANCE_WEIGHT) +
                   (calculateHpScore(human) * HP_WEIGHT) +
                   (calculateBuriednessScore(human) * BURIEDNESS_WEIGHT) +
                   (calculateDamageScore(human) * DAMAGE_WEIGHT);
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

    // 以下方法与SampleHumanDetector相同
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
    // HP检查
    if (!target.isHPDefined() || target.getHP() == 0 )
        return false;
    // 位置检查
    if (!target.isPositionDefined())
        return false;
    // 伤害检查
    if (!target.isDamageDefined() || target.getDamage() == 0  )
        return false;
    // 掩埋程度检查
    if (!target.isBuriednessDefined() )
        return false;
    // 综合属性检查
    if (target.getDamage() < MIN_DAMAGE_THRESHOLD && target.getHP() < MIN_HP_THRESHOLD&& target.getBuriedness() > MIN_BURIEDNESS_THRESHOLD )
        return false;

    // 位置实体检查
    StandardEntity position = worldInfo.getPosition(target);
    if (position == null)
        return false;

    if (position instanceof Building && hasBlockedEntrance((Building) position)) {
            logger.debug("Invalid due to blocked entrance: " + target);
            return false;
    }

    // 位置类型检查
    StandardEntityURN positionURN = position.getStandardURN();
    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
        return false;

    return true;
}

// 新增方法：检查建筑物入口是否被阻挡
    private boolean hasBlockedEntrance(Building building) {
        for (Edge edge : getEntranceEdges(building)) {
            EntityID roadID = edge.getNeighbour();
            if (roadID == null) continue;
            
            StandardEntity road = worldInfo.getEntity(roadID);
            if (!(road instanceof Road)) continue;
            
            Road roadEntity = (Road) road;
            if (!roadEntity.isBlockadesDefined()) continue;
            
            for (EntityID blockadeID : roadEntity.getBlockades()) {
                StandardEntity entity = worldInfo.getEntity(blockadeID);
                if (!(entity instanceof Blockade)) continue;
                
                Blockade blockade = (Blockade) entity;
                if (coversEdge(blockade, edge)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 新增方法：获取建筑物的入口边
    private List<Edge> getEntranceEdges(Building building) {
        List<Edge> entrances = new ArrayList<>();
        for (Edge edge : building.getEdges()) {
            if (edge.isPassable()) {
                entrances.add(edge);
            }
        }
        return entrances;
    }

    // 新增方法：检查障碍物是否覆盖边
    private boolean coversEdge(Blockade blockade, Edge edge) {
        try {
            Shape blockadeShape = blockade.getShape();
            Area blockadeArea = new Area(blockadeShape);
            Line2D edgeLine = new Line2D.Double(
                edge.getStartX(), edge.getStartY(),
                edge.getEndX(), edge.getEndY()
            );
            return blockadeArea.intersects(edgeLine.getBounds2D());
        } catch (Exception e) {
            logger.error("Error checking edge coverage", e);
        }
        return false;
    }
}