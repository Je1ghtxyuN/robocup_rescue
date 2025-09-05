package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class PoliceSearch extends Search {

    private PathPlanning pathPlanning;
    private Clustering clustering; // 新增聚类模块
    private EntityID result;
    private boolean initialized = false;
    private Set<EntityID> unreachableTargets = new HashSet<>();
    private int stuckCounter = 0;
    private EntityID lastTarget;
    
    // 新增目标锁定机制
    private EntityID lockedTarget = null;
    private int lockExpiryTime = 0;

    public PoliceSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                       ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
    }

    private void initialize(ModuleManager moduleManager) {
        if (!initialized) {
            this.pathPlanning = moduleManager.getModule(
                "PoliceSearch.PathPlanning",
                "sample_team.module.algorithm.PoliceAStarPathPlanning");
            
            // 获取聚类模块
            this.clustering = moduleManager.getModule(
                "PoliceSearch.Clustering",
                "adf.impl.module.algorithm.KMeansClustering");
            
            this.initialized = true;
        }
    }

    @Override
    public PoliceSearch updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (pathPlanning != null) {
            pathPlanning.updateInfo(messageManager);
        }
        
        // 每30步重置不可达目标列表
        if (agentInfo.getTime() % 30 == 0) {
            unreachableTargets.clear();
        }
        
        return this;
    }

    @Override
    public PoliceSearch calc() {
        if (!initialized) {
            initialize(moduleManager);
        }
        
        result = null;
        EntityID myPosition = agentInfo.getPosition();
        
        // 检查目标锁定状态
        if (lockedTarget != null && agentInfo.getTime() < lockExpiryTime) {
            pathPlanning.setFrom(myPosition);
            pathPlanning.setDestination(Collections.singleton(lockedTarget));
            pathPlanning.calc();
            
            if (isPathValid(pathPlanning.getResult())) {
                result = lockedTarget;
                lastTarget = lockedTarget;
                return this;
            } else {
                // 锁定目标不可达，解除锁定
                lockedTarget = null;
            }
        }
        
        // 检测是否卡住
        if (lastTarget != null && myPosition.equals(lastTarget)) {
            stuckCounter++;
            
            // 动态阈值：距离越远容忍时间越长
            int distance = worldInfo.getDistance(myPosition, lastTarget);
            int dynamicThreshold = 10 + (int)(distance / 50000); // 每50米增加1步容忍度
            
            if (stuckCounter > dynamicThreshold) {
                unreachableTargets.add(lastTarget);
                stuckCounter = 0;
                lastTarget = null;
            }
        } else {
            stuckCounter = 0;
        }
        
        // 获取有效的未清理障碍物
        List<Blockade> blockades = getValidUnclearedBlockades();
        
        if (!blockades.isEmpty()) {
            // 按优先级排序
            blockades.sort((b1, b2) -> {
                double p1 = calculatePriority(myPosition, b1);
                double p2 = calculatePriority(myPosition, b2);
                return Double.compare(p2, p1);
            });

            // 尝试所有有效目标，而不仅是前3个
            for (Blockade targetBlockade : blockades) {
                EntityID roadID = targetBlockade.getPosition();
                
                // 跳过不可达目标
                if (unreachableTargets.contains(roadID)) {
                    continue;
                }
                
                // 设置路径规划
                pathPlanning.setFrom(myPosition);
                pathPlanning.setDestination(Collections.singleton(roadID));
                pathPlanning.calc();
                
                // 检查路径是否有效
                if (isPathValid(pathPlanning.getResult())) {
                    result = roadID;
                    lastTarget = roadID;
                    
                    // 设置目标锁定
                    lockedTarget = roadID;
                    lockExpiryTime = agentInfo.getTime() + 30; // 锁定30步
                    
                    return this;
                } else {
                    unreachableTargets.add(roadID);
                }
            }
        }
        
        // 没有有效障碍物时基于聚类区域巡逻
        patrolRegionRoad();
        return this;
    }

    private double calculatePriority(EntityID position, Blockade blockade) {
        int cost = blockade.getRepairCost();
        int distance = worldInfo.getDistance(position, blockade.getPosition());
        return (cost * 100.0) / (distance + 1); // 成本优先，同时考虑距离
    }

    private boolean isPathValid(List<EntityID> path) {
        return path != null && !path.isEmpty();
    }

    private List<Blockade> getValidUnclearedBlockades() {
        List<Blockade> blockades = new ArrayList<>();
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
            Blockade blockade = (Blockade) entity;
            
            // 有效性检查
            if (blockade.isRepairCostDefined() && 
                blockade.getRepairCost() > 0 &&
                blockade.getPosition() != null &&
                !unreachableTargets.contains(blockade.getPosition())) {
                
                blockades.add(blockade);
            }
        }
        return blockades;
    }

    private void patrolRegionRoad() {
        if (clustering == null) {
            // 回退到随机巡逻
            patrolRandomRoad();
            return;
        }
        
        // 获取警察所属聚类区域
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);
        
        List<EntityID> reachableRoads = new ArrayList<>();
        
        for (StandardEntity entity : clusterEntities) {
            if (entity instanceof Road) {
                EntityID roadID = entity.getID();
                
                // 跳过不可达道路
                if (unreachableTargets.contains(roadID)) {
                    continue;
                }
                
                // 快速距离检查
                if (worldInfo.getDistance(agentInfo.getPosition(), roadID) < 100000) {
                    reachableRoads.add(roadID);
                }
            }
        }
        
        if (!reachableRoads.isEmpty()) {
            // 在所属区域内随机选择道路
            result = reachableRoads.get(new Random().nextInt(reachableRoads.size()));
            lastTarget = result;
        } else {
            // 区域内无合适道路，回退到全局随机
            patrolRandomRoad();
        }
    }
    
    private void patrolRandomRoad() {
        Collection<StandardEntity> roads = worldInfo.getEntitiesOfType(StandardEntityURN.ROAD);
        List<EntityID> reachableRoads = new ArrayList<>();
        
        for (StandardEntity road : roads) {
            EntityID roadID = road.getID();
            
            // 跳过不可达道路
            if (unreachableTargets.contains(roadID)) {
                continue;
            }
            
            // 快速距离检查
            if (worldInfo.getDistance(agentInfo.getPosition(), roadID) < 100000) {
                reachableRoads.add(roadID);
            }
        }
        
        if (!reachableRoads.isEmpty()) {
            // 随机选择可达道路
            result = reachableRoads.get(new Random().nextInt(reachableRoads.size()));
            lastTarget = result;
        } else if (!roads.isEmpty()) {
            // 如果无可达道路，则随机选择
            result = new ArrayList<>(roads).get(0).getID();
            lastTarget = result;
        }
    }

    @Override
    public EntityID getTarget() {
        return result;
    }
}