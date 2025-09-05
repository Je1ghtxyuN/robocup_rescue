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
import java.util.*;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleHumanDetector extends HumanDetector {

    private Clustering clustering;
    private PathPlanning pathPlanning;
    private EntityID result;
    private Logger logger;
    private Set<EntityID> unreachableTargets = new HashSet<>();
    private int stuckCounter = 0;
    private EntityID lastPosition;
    private EntityID lastTarget;
    private int lastPositionTime = -1;
    private Map<EntityID, Integer> targetRetryCount = new HashMap<>();

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
        
        // 更新位置历史
        EntityID currentPosition = agentInfo.getPosition();
        if (!currentPosition.equals(lastPosition)) {
            lastPosition = currentPosition;
            lastPositionTime = agentInfo.getTime();
        }
        
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
        
        // 检查卡住状态
        if (isStuck()) {
            handleStuckSituation();
            return this;
        }
        
        if (this.result != null) {
            Human target = (Human) this.worldInfo.getEntity(this.result);
            if (!isValidHuman(target) || !isReachable(target)) {
                logger.debug("Invalid or unreachable Human:" + target + " ==> reset target");
                this.result = null;
                if (target != null) {
                    unreachableTargets.add(target.getID());
                    targetRetryCount.put(target.getID(), 
                        targetRetryCount.getOrDefault(target.getID(), 0) + 1);
                    
                    // 如果重试次数过多，彻底放弃该目标
                    if (targetRetryCount.get(target.getID()) > 3) {
                        unreachableTargets.add(target.getID());
                    }
                }
            }
        }
        
        if (this.result == null) {
            this.result = calcTarget();
            this.lastTarget = this.result;
        }
        
        return this;
    }

    private boolean isStuck() {
        // 当前位置停留超过5秒（25步）视为卡住
        return (agentInfo.getTime() - lastPositionTime) > 25;
    }

    private void handleStuckSituation() {
        stuckCounter++;
        
        // 清除当前目标
        if (lastTarget != null) {
            unreachableTargets.add(lastTarget);
            lastTarget = null;
        }
        
        // 尝试随机移动解困
        if (stuckCounter % 5 == 0) {
            result = findRandomNearbyRoad();
        }
    }

    private EntityID calcTarget() {
        List<Human> rescueTargets = filterRescueTargets(
            this.worldInfo.getEntitiesOfType(CIVILIAN));
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        List<Human> targets = rescueTargetsInCluster.isEmpty() ? rescueTargets : rescueTargetsInCluster;
        
        // 过滤掉不可达目标
        targets.removeIf(h -> unreachableTargets.contains(h.getID()));
        
        logger.debug("Potential targets:" + targets);
        if (targets.isEmpty()) {
            // 如果没有被埋压的人类，前往避难所
            return findNearestRefuge();
        }

        // 按优先级排序
        targets.sort((h1, h2) -> {
            // 优先选择被埋压程度高且HP低的
            double priority1 = h1.getBuriedness() * 2 + (10000 - h1.getHP());
            double priority2 = h2.getBuriedness() * 2 + (10000 - h2.getHP());
            return Double.compare(priority2, priority1);
        });

        // 检查可达性
        for (Human candidate : targets) {
            if (isReachable(candidate)) {
                logger.debug("Selected reachable target:" + candidate);
                return candidate.getID();
            } else {
                // 标记为不可达
                unreachableTargets.add(candidate.getID());
                targetRetryCount.put(candidate.getID(), 
                    targetRetryCount.getOrDefault(candidate.getID(), 0) + 1);
                
                // 如果重试次数过多，彻底放弃该目标
                if (targetRetryCount.get(candidate.getID()) > 3) {
                    unreachableTargets.add(candidate.getID());
                }
            }
        }
        
        logger.warn("No reachable human targets found!");
        return findNearestRefuge();
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