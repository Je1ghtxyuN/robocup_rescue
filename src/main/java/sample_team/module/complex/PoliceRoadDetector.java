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

public class PoliceRoadDetector extends RoadDetector {

    private Clustering clustering;
    private EntityID result;
    private Map<EntityID, Long> lastProcessedTime = new HashMap<>();
    private int currentTime;

    public PoliceRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                            ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clustering = moduleManager.getModule(
            "PoliceRoadDetector.Clustering",
            "adf.impl.module.algorithm.KMeansClustering");
    }

    @Override
    public PoliceRoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.currentTime = agentInfo.getTime();
        return this;
    }

    @Override
    public PoliceRoadDetector calc() {
        EntityID position = agentInfo.getPosition();
        result = null;

        // 获取当前警察可视范围内的障碍物
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, 50000);
        
        List<Blockade> validBlockades = new ArrayList<>();
        for (StandardEntity entity : visibleEntities) {
            if (entity.getStandardURN() == StandardEntityURN.BLOCKADE) {
                Blockade blockade = (Blockade) entity;
                
                // 有效性检查
                if (isValidBlockade(blockade)) {
                    validBlockades.add(blockade);
                }
            }
        }

        if (!validBlockades.isEmpty()) {
            // 按综合优先级排序（紧急度+距离）
            validBlockades.sort((b1, b2) -> {
                double priority1 = calculatePriority(position, b1);
                double priority2 = calculatePriority(position, b2);
                return Double.compare(priority2, priority1); // 降序
            });
            
            // 选择最紧急的障碍物
            this.result = validBlockades.get(0).getPosition();
        } else {
            // 没有可见障碍物时使用聚类方法
            detectUsingClustering();
        }
        
        return this;
    }

    private double calculatePriority(EntityID position, Blockade blockade) {
        int cost = blockade.getRepairCost();
        int distance = worldInfo.getDistance(position, blockade.getPosition());
        
        // 紧急度 = 修复成本 / 100
        double severity = cost / 100.0;
        
        // 距离因子（1-100米为高优先级）
        double distanceFactor = (distance < 1000) ? 2.0 : 1.0;
        
        // 时间因子（超过30秒未处理提升优先级）
        long lastProcessed = lastProcessedTime.getOrDefault(blockade.getID(), 0L);
        double timeFactor = (currentTime - lastProcessed > 30) ? 1.5 : 1.0;
        
        return severity * distanceFactor * timeFactor;
    }

    private boolean isValidBlockade(Blockade blockade) {
        // 1. 修复成本有效性检查
        if (!blockade.isRepairCostDefined() || blockade.getRepairCost() <= 0) {
            return false;
        }
        
        // 2. 位置有效性检查
        EntityID position = blockade.getPosition();
        if (position == null) {
            return false;
        }
        
        return true;
    }

    private void detectUsingClustering() {
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

        List<Blockade> clusterBlockades = new ArrayList<>();
        for (StandardEntity entity : clusterEntities) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined()) {
                    for (EntityID blockadeID : road.getBlockades()) {
                        StandardEntity blockadeEntity = worldInfo.getEntity(blockadeID);
                        if (blockadeEntity instanceof Blockade && isValidBlockade((Blockade) blockadeEntity)) {
                            clusterBlockades.add((Blockade) blockadeEntity);
                        }
                    }
                }
            }
        }

        if (!clusterBlockades.isEmpty()) {
            // 按综合优先级排序
            EntityID myPosition = agentInfo.getPosition();
            clusterBlockades.sort((b1, b2) -> {
                double p1 = calculatePriority(myPosition, b1);
                double p2 = calculatePriority(myPosition, b2);
                return Double.compare(p2, p1);
            });
            
            this.result = clusterBlockades.get(0).getPosition();
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}