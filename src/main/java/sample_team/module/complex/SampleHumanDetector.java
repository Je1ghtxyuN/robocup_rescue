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
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleHumanDetector extends HumanDetector {

    private Clustering clustering;
    private PathPlanning pathPlanning;
    private EntityID result;
    private Logger logger;
    private Map<EntityID, Double> humanPriorityMap = new HashMap<>();

    public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                             ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                "adf.impl.module.algorithm.KMeansClustering");
        this.pathPlanning = moduleManager.getModule("SampleHumanDetector.PathPlanning",
                "adf.impl.module.algorithm.DijkstraPathPlanning");
        registerModule(this.clustering);
        registerModule(this.pathPlanning);
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
            if (!isValidHuman(target) || !isReachable(target)) {
                logger.debug("Invalid or unreachable Human:" + target + " ==> reset target");
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

        // 清空之前的优先级缓存
        humanPriorityMap.clear();
        
         // 计算并存储每个目标的优先级
        for (Human target : targets) {
            double priority = calculateHumanEmergencyPriority(target);
            humanPriorityMap.put(target.getID(), priority);
        }
        
        // 按优先级降序排序（最紧急的在前）
        targets.sort((h1, h2) -> Double.compare(
            humanPriorityMap.getOrDefault(h2.getID(), 0.0),
            humanPriorityMap.getOrDefault(h1.getID(), 0.0)));
        
        logger.debug("Prioritized targets:" + targets);
        if (targets.isEmpty()) return null;

        // 按优先级顺序检查可达性
        for (Human candidate : targets) {
            if (isReachable(candidate)) {
                logger.debug("Selected reachable target:" + candidate);
                return candidate.getID();
            } else {
                logger.debug("Unreachable target:" + candidate);
            }
        }
        logger.warn("No reachable human targets found!");
        return null;
    }

    // 计算人类紧急程度优先级
    private double calculateHumanEmergencyPriority(Human human) {
        int hp = human.isHPDefined() ? human.getHP() : 10000;
        int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
        int damage = human.isDamageDefined() ? human.getDamage() : 0;
        
        // 核心优先级公式：HP越低优先级越高，被埋压程度越高优先级越高
        double priority = (10000 - hp) * 2.5 + buriedness * 15;
        
        // 额外因素：增加伤害权重
        priority += damage * 0.8;
        
        // 距离因素：距离越近优先级越高（但权重较低）
        int distance = worldInfo.getDistance(agentInfo.getPosition(), human.getPosition());
        priority /= (distance / 5000.0 + 1); // 距离每增加5000，优先级降低一半
        
        return priority;
    }

    private boolean isReachable(Human target) {
        if (!target.isPositionDefined()) return false;
        
        EntityID targetPosition = target.getPosition();
        pathPlanning.setFrom(agentInfo.getPosition());
        pathPlanning.setDestination(Collections.singleton(targetPosition));
        pathPlanning.calc();
        
        List<EntityID> path = pathPlanning.getResult();
        return path != null && !path.isEmpty();
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {
        List<Human> rescueTargets = new ArrayList<>();
        for (StandardEntity next : list) {
            if (!(next instanceof Human)) continue;
            Human h = (Human) next;
            if (!isValidHuman(h)) continue;
            if (h.getBuriedness() == 0) continue;
            rescueTargets.add(h);
        }
        return rescueTargets;
    }

    private List<Human> filterInCluster(Collection<? extends StandardEntity> entities) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        List<Human> filter = new ArrayList<>();
        HashSet<StandardEntity> inCluster = new HashSet<>(clustering.getClusterEntities(clusterIndex));
        
        for (StandardEntity next : entities) {
            if (!(next instanceof Human)) continue;
            Human h = (Human) next;
            if (!h.isPositionDefined()) continue;
            
            StandardEntity position = this.worldInfo.getPosition(h);
            if (position == null) continue;
            if (inCluster.contains(position)) filter.add(h);
        }
        return filter;
    }

    private boolean isValidHuman(StandardEntity entity) {
        if (entity == null) return false;
        if (!(entity instanceof Human)) return false;

        Human target = (Human) entity;
        if (!target.isHPDefined() || target.getHP() == 0) return false;
        if (!target.isPositionDefined()) return false;
        if (!target.isDamageDefined() || target.getDamage() == 0) return false;
        if (!target.isBuriednessDefined()) return false;

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null) return false;

        StandardEntityURN positionURN = position.getStandardURN();
        return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
    }
}