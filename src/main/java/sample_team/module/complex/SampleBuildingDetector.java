package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.BuildingDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleBuildingDetector extends BuildingDetector {

    private EntityID result;
    private Clustering clustering;
    private PathPlanning pathPlanning;
    private Logger logger;
    private Map<EntityID, Double> buildingEmergencyMap = new HashMap<>();

    public SampleBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                 ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule(
            "SampleBuildingDetector.Clustering",
            "adf.impl.module.algorithm.KMeansClustering");
        this.pathPlanning = moduleManager.getModule(
            "SampleBuildingDetector.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
        registerModule(this.clustering);
        registerModule(this.pathPlanning);
    }

    @Override
    public BuildingDetector updateInfo(MessageManager messageManager) {
        logger.debug("Time:" + agentInfo.getTime());
        super.updateInfo(messageManager);
        return this;
    }

    @Override
    public BuildingDetector calc() {
        this.result = this.calcTarget();
        return this;
    }

    private EntityID calcTarget() {
        Collection<StandardEntity> entities = this.worldInfo.getEntitiesOfType(
            StandardEntityURN.BUILDING, StandardEntityURN.GAS_STATION,
            StandardEntityURN.AMBULANCE_CENTRE, StandardEntityURN.FIRE_STATION,
            StandardEntityURN.POLICE_OFFICE);

        List<Building> fireyBuildings = filterFiery(entities);

        // 清空之前的紧急程度缓存
        buildingEmergencyMap.clear();
        
        // 计算建筑物内被困人员的紧急程度
        for (Building building : fireyBuildings) {
            double emergency = calculateBuildingEmergency(building);
            buildingEmergencyMap.put(building.getID(), emergency);
        }
        
       // 按紧急程度降序排序（最紧急的在前）
        fireyBuildings.sort((b1, b2) -> Double.compare(
            buildingEmergencyMap.getOrDefault(b2.getID(), 0.0),
            buildingEmergencyMap.getOrDefault(b1.getID(), 0.0)));

        List<Building> clusterBuildings = filterInCluster(fireyBuildings);
        List<Building> targets = clusterBuildings.isEmpty() ? fireyBuildings : clusterBuildings;
        
        logger.debug("Prioritized buildings:" + targets);
        if (targets.isEmpty()) return null;

        // 按紧急程度顺序检查可达性
        for (Building candidate : targets) {
            if (isReachable(candidate)) {
                logger.debug("Selected reachable building:" + candidate);
                return candidate.getID();
            } else {
                logger.debug("Unreachable building:" + candidate);
            }
        }
        logger.warn("No reachable buildings found!");
        return null;
    }

    // 计算建筑物内被困人员的紧急程度
    private double calculateBuildingEmergency(Building building) {
        double maxEmergency = 0.0;
        
        // 获取该建筑物内的所有人类
        Collection<StandardEntity> humans = worldInfo.getObjectsInRange(building.getID(), 10000);
        for (StandardEntity entity : humans) {
            if (!(entity instanceof Human)) continue;
            
            Human human = (Human) entity;
            if (!building.getID().equals(human.getPosition())) continue;
            
            // 计算个人紧急程度
            double humanEmergency = calculateHumanEmergency(human);
            if (humanEmergency > maxEmergency) {
                maxEmergency = humanEmergency;
            }
        }
        
        // 考虑建筑物本身的着火状态
        int fieryness = building.getFieryness();
        double buildingEmergency = 0.0;
        
        if (fieryness >= 3) { // 完全燃烧状态
            buildingEmergency = 1000.0;
        } else if (fieryness >= 1) { // 部分燃烧状态
            buildingEmergency = 500.0;
        }
        
        return maxEmergency + buildingEmergency;
    }

    // 计算个人紧急程度
    private double calculateHumanEmergency(Human human) {
        int hp = human.isHPDefined() ? human.getHP() : 10000;
        int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
        int damage = human.isDamageDefined() ? human.getDamage() : 0;
        
        // 核心紧急程度公式
        double emergency = (10000 - hp) * 2.5 + buriedness * 15 + damage;
        
        // 位置因素：如果在高层建筑中，增加紧急程度
        EntityID positionID = human.getPosition();
        if (positionID != null) {
        StandardEntity positionEntity = worldInfo.getEntity(positionID);
        if (positionEntity instanceof Building) {
            Building b = (Building) positionEntity;
            if (b.isFloorsDefined() && b.getFloors() > 3) {
                emergency *= 1.3;
        }
    }
}
        
        return emergency;
    }

    private boolean isReachable(Building target) {
        pathPlanning.setFrom(agentInfo.getPosition());
        pathPlanning.setDestination(Collections.singleton(target.getID()));
        pathPlanning.calc();
        
        List<EntityID> path = pathPlanning.getResult();
        return path != null && !path.isEmpty();
    }

    private List<Building> filterFiery(Collection<? extends StandardEntity> input) {
        ArrayList<Building> fireBuildings = new ArrayList<>();
        for (StandardEntity entity : input) {
            if (entity instanceof Building && ((Building) entity).isOnFire()) {
                fireBuildings.add((Building) entity);
            }
        }
        return fireBuildings;
    }

    private List<Building> filterInCluster(Collection<Building> targetAreas) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        List<Building> clusterTargets = new ArrayList<>();
        HashSet<StandardEntity> inCluster = new HashSet<>(clustering.getClusterEntities(clusterIndex));
        
        for (Building target : targetAreas) {
            if (inCluster.contains(target)) clusterTargets.add(target);
        }
        return clusterTargets;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }
}