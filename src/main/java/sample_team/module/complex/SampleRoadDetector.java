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
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

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
            "sample_team.module.algorithm.KMeansClustering");
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
        if (currentArea instanceof rescuecore2.standard.entities.Area) {
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
        // 按综合优先级排序
        blockades.sort((b1, b2) -> {
            double priority1 = calculatePriority(position, b1);
            double priority2 = calculatePriority(position, b2);
            return Double.compare(priority2, priority1); // 降序
        });
        
        // 选择最紧急的障碍物
        this.result = blockades.get(0).getPosition();
    }

    private double calculatePriority(EntityID position, Blockade blockade) { 
        // 可见性因子（当前视野内优先级更高）
        double visibilityFactor = (visibleRoads.contains(blockade.getPosition())) ? 2.0 : 1.0;

        // 避难所阻挡因子（新增）: 如果障碍物阻挡避难所，则赋予更高权重
        double refugeFactor = isBlockingRefuge(blockade) ? 3.0 : 1.0;

        // 入口因子（在建筑物入口处优先级更高）
        double entranceFactor = isAtBuildingEntrance(blockade) ? 3.0 : 1.0;

        // 聚集惩罚因子
        double concentrationPenalty = calculateConcentrationPenalty(position);
        
        return visibilityFactor * entranceFactor * refugeFactor * concentrationPenalty;
    }

    // ===== 新增方法：计算聚集惩罚因子 =====
    private double calculateConcentrationPenalty(EntityID myPosition) {
        // 1. 获取周围警察数量
        int nearbyPolice = getNearbyPoliceCount(myPosition, 30000); // 30米范围内
        
        // 2. 计算聚集惩罚（警察越多惩罚越大）
        // 公式：1.0 / (1 + e^(0.5 * (count - 2))) 
        // 解释：当周围警察>2人时，惩罚开始明显；5人时惩罚约为0.1
        return 1.0 / (1 + Math.exp(0.5 * (nearbyPolice - 2)));
    }

    // ===== 新增方法：获取周围警察数量 =====
    private int getNearbyPoliceCount(EntityID myPosition, int radius) {
        int count = 0;
        
        // 1. 获取所有警察实体
        Collection<StandardEntity> allPolice = worldInfo.getEntitiesOfType(
            StandardEntityURN.POLICE_FORCE
        );
        
        // 2. 检查每个警察是否在范围内（排除自己）
        for (StandardEntity police : allPolice) {
            // 跳过自己
            if (police.getID().equals(agentInfo.getID())) continue;
            
            // 计算距离
            int distance = worldInfo.getDistance(myPosition, police.getID());
            
            // 如果在范围内则计数
            if (distance <= radius && distance >= 0) {
                count++;
            }
        }
        
        return count;
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




    // 检测障碍物是否在建筑物入口处
    private boolean isAtBuildingEntrance(Blockade blockade) {
        // 获取障碍物所在区域
        EntityID positionId = blockade.getPosition();
        if (positionId == null) return false;
        
        StandardEntity positionEntity = worldInfo.getEntity(positionId);
        
        // 如果障碍物直接在建筑上
        if (positionEntity instanceof Building) {
            Building building = (Building) positionEntity;
            return isBlockadeAtEntrance(blockade, building, worldInfo);
        }
        // 如果障碍物在道路上，检查邻近建筑
        else if (positionEntity instanceof Road) {
            Road road = (Road) positionEntity;
            if (!road.isEdgesDefined()) return false;
            
            // 检查所有邻近建筑的入口
            for (Edge edge : road.getEdges()) {
                if (edge.isPassable()) {
                    EntityID neighbourId = edge.getNeighbour();
                    if (neighbourId == null) continue;
                    
                    StandardEntity neighbour = worldInfo.getEntity(neighbourId);
                    if (neighbour instanceof Building) {
                        Building building = (Building) neighbour;
                        if (isBlockadeAtEntrance(blockade, building, worldInfo)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    //尝试加入判断障碍物是否在建筑入口
    public boolean isBlockadeAtEntrance(Blockade blockade, Building building, WorldInfo WorldOInfo) {
    // 步骤1：检查障碍物是否在该建筑内
    if (!isBlockadeInBuilding(blockade, building)) return false;
    
    // 步骤2：获取建筑的所有入口边
    List<Edge> entrances = getEntranceEdges(building, worldInfo);
     if (entrances.isEmpty()) return false;
    
    // 步骤3：检查是否覆盖任一入口边
    return entrances.stream()
        .anyMatch(edge -> coversEdge(blockade, edge));
    }

    // 检查障碍物是否在该建筑内
    public boolean isBlockadeInBuilding(Blockade blockade, Building building) {
    return blockade.isPositionDefined() && 
        blockade.getPosition().equals(building.getID());
    }

    // 获取建筑的所有入口边
    public List<Edge> getEntranceEdges(Building building, WorldInfo WorldOInfo) {
    return building.getEdges().stream()
        .filter(edge -> edge.isPassable()) // 可通行的边
        .filter(edge -> {
            EntityID neighbourID = edge.getNeighbour();
            Entity neighbour = WorldOInfo.getEntity(neighbourID);
            return neighbour instanceof Road; // 邻居是道路
        })
        .collect(Collectors.toList());
    }

    
    // 检查是否覆盖任一入口边
    public boolean coversEdge(Blockade blockade, Edge edge) {
        Shape blockadeShape = blockade.getShape(); // 障碍物多边形
        Area blockadeArea = new Area(blockadeShape);
        
        // 将入口边转为Line2D
        Line2D edgeLine = new Line2D.Double(
            edge.getStartX(), edge.getStartY(),
            edge.getEndX(), edge.getEndY()
        );
        
        // 检测多边形是否与线段相交
        return blockadeArea.intersects(edgeLine.getBounds2D());
    }












        public boolean isBlockingRefuge(Blockade blockade) {
        if (blockade == null || !blockade.isPositionDefined()) {
            return false; // 障碍物无效或位置未定义，直接返回false
        }
        
        EntityID blockPos = blockade.getPosition(); // 获取障碍物所在的区域ID
        if (blockPos == null) {
            return false;
        }
        
        // 获取所有避难所实体
        Collection<StandardEntity> refuges = worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE);
        for (StandardEntity entity : refuges) {
            if (!(entity instanceof rescuecore2.standard.entities.Area)) {
                continue; // 跳过非Area实体（理论上不会发生，但安全处理）
            }
            rescuecore2.standard.entities.Area refuge = (rescuecore2.standard.entities.Area) entity; // 避难所是Area的子类
            
            // 情况1：障碍物直接位于避难所上
            if (refuge.getID().equals(blockPos)) {
                return true;
            }
            
            // 情况2：障碍物位于避难所的直接邻居区域上
            // 获取避难所的可通行邻居列表（通过getNeighbours()，基于可通行边缘）
            List<EntityID> neighbours = refuge.getNeighbours();
            if (neighbours != null && neighbours.contains(blockPos)) {
                return true;
            }
        }
        return false; // 未找到匹配，障碍物不影响避难所
    }
}