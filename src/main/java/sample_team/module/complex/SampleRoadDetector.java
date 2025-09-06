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
import java.util.*;

public class SampleRoadDetector extends RoadDetector {

    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Integer> lastProcessedTime = new HashMap<>();
    private int currentTime;
    private Set<EntityID> visibleRoads = new HashSet<>();

    public SampleRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                            ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clustering = moduleManager.getModule(
            "SampleRoadDetector.Clustering",
            "adf.impl.module.algorithm.KMeansClustering");
    }

    @Override
    public SampleRoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.currentTime = agentInfo.getTime();
        return this;
    }

    @Override
    public SampleRoadDetector calc() {
        EntityID position = agentInfo.getPosition();
        result = null;
        visibleRoads.clear();

        // 1. 获取当前位置所在道路
        StandardEntity currentArea = worldInfo.getEntity(position);
        if (currentArea instanceof Area) {
            visibleRoads.add(currentArea.getID());
        }
        
        // 2. 获取视野范围内的道路
        int viewDistance = calculateViewDistance();
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, viewDistance);
        
        // 3. 筛选视野内的道路
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof Road) {
                visibleRoads.add(entity.getID());
            }
        }
        
        // 4. 获取这些道路上的有效障碍物
        List<Blockade> validBlockades = getVisibleBlockades();
        
        // 5. 按优先级排序并选择目标
        if (!validBlockades.isEmpty()) {
            prioritizeAndSelectTarget(position, validBlockades);
        } 
        // 6. 没有可见障碍物时使用聚类方法
        else {
            detectUsingClustering();
        }
        
        // 7. 更新处理时间
        if (result != null) {
            lastProcessedTime.put(result, currentTime);
        }
        
        return this;
    }

    // 计算实际视野距离
    private int calculateViewDistance() {
        return 30000; // 基础视野距离
    }

    // 获取视野内道路上的障碍物
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

    private void prioritizeAndSelectTarget(EntityID position, List<Blockade> blockades) {
        // 按综合优先级排序（紧急度+距离）
        blockades.sort((b1, b2) -> {
            double priority1 = calculatePriority(position, b1);
            double priority2 = calculatePriority(position, b2);
            return Double.compare(priority2, priority1); // 降序
        });
        
        // 选择最紧急的障碍物
        this.result = blockades.get(0).getPosition();
    }

    private double calculatePriority(EntityID position, Blockade blockade) {
        int cost = blockade.getRepairCost();
        int distance = worldInfo.getDistance(position, blockade.getPosition());
        
        // 紧急度 = 修复成本 / 100
        double severity = cost / 100.0;
        
        // 距离因子（近距离优先）
        double distanceFactor = 1.0 + (1000.0 / (distance + 1));
        
        // 时间因子（超过30秒未处理提升优先级）
        int lastProcessed = lastProcessedTime.getOrDefault(blockade.getPosition(), 0);
        double timeFactor = (currentTime - lastProcessed > 30) ? 1.5 : 1.0;
        
        // 可见性因子（当前视野内优先级更高）
        double visibilityFactor = (visibleRoads.contains(blockade.getPosition())) ? 1.5 : 1.0;
        
        // 人类紧急程度因子（HP低或被埋压严重的优先）
        double humanEmergencyFactor = calculateHumanEmergencyFactor(blockade.getPosition());
        
        return severity * distanceFactor * timeFactor * visibilityFactor * humanEmergencyFactor;
    }

    // 计算道路位置的人类紧急程度
    private double calculateHumanEmergencyFactor(EntityID position) {
        double maxEmergency = 1.0; // 默认值
        
        // 获取该位置的所有人类（平民）
        Collection<StandardEntity> humans = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        for (StandardEntity entity : humans) {
            Human human = (Human) entity;
            if (position.equals(human.getPosition())) {
                // 计算人类紧急程度：HP越低紧急度越高，被埋压程度越高紧急度越高
                int hp = human.isHPDefined() ? human.getHP() : 10000;
                int buriedness = human.isBuriednessDefined() ? human.getBuriedness() : 0;
                
                // 紧急程度 = (10000 - HP) + buriedness * 2
                double emergency = (10000 - hp) + (buriedness * 2);
                if (emergency > maxEmergency) {
                    maxEmergency = emergency;
                }
            }
        }
        
        // 紧急程度因子 = 1.0 + emergency/100
        return 1.0 + (maxEmergency / 10000.0);
    }

    private boolean isValidBlockade(Blockade blockade) {
        // 基础有效性检查
        if (!blockade.isRepairCostDefined() || blockade.getRepairCost() <= 0) {
            return false;
        }
        
        // 位置有效性检查
        EntityID position = blockade.getPosition();
        if (position == null) {
            return false;
        }
        
        // 检查是否已被处理
        StandardEntity road = worldInfo.getEntity(position);
        if (road instanceof Road) {
            Road r = (Road) road;
            if (r.isBlockadesDefined() && !r.getBlockades().contains(blockade.getID())) {
                return false; // 障碍物已被清除
            }
        }
        
        return true;
    }

    private void detectUsingClustering() {
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
            EntityID myPosition = agentInfo.getPosition();
            clusterBlockades.sort((b1, b2) -> {
                double p1 = calculatePriority(myPosition, b1);
                double p2 = calculatePriority(myPosition, b2);
                return Double.compare(p2, p1);
            });
            
            this.result = clusterBlockades.get(0).getPosition();
        } else {
            // 聚类区域没有障碍物，选择最近的巡逻点
            selectPatrolPoint();
        }
    }
    
    private void selectPatrolPoint() {
        EntityID myPosition = agentInfo.getPosition();
        List<Road> roads = new ArrayList<>();
        
        // 获取所有道路
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (entity instanceof Road) {
                roads.add((Road) entity);
            }
        }
        
        if (!roads.isEmpty()) {
            // 按距离排序
            roads.sort((r1, r2) -> {
                int d1 = worldInfo.getDistance(myPosition, r1.getID());
                int d2 = worldInfo.getDistance(myPosition, r2.getID());
                return Integer.compare(d1, d2);
            });
            
            // 选择最近的道路作为巡逻点
            this.result = roads.get(0).getID();
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}