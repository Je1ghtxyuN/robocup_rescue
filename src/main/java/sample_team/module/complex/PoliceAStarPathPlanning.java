package sample_team.module.complex;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.misc.collections.LazyMap;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.Pair;

import java.util.*;

public class PoliceAStarPathPlanning extends PathPlanning {

    private Map<EntityID, Set<EntityID>> graph;
    private Map<EntityID, Blockade> blockadeInfo = new HashMap<>();
    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;
    private WorldInfo worldInfo;
    
    // 新增方向优化字段
    private int bestDirection = -1;
    private EntityID bestPosition;
    private Map<EntityID, Integer> directionCache = new HashMap<>();
    private Set<EntityID> buildingConnections = new HashSet<>();

    public PoliceAStarPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                 ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.init();
    }

    private void init() {
        Map<EntityID, Set<EntityID>> neighbours = new LazyMap<EntityID, Set<EntityID>>() {
            @Override
            public Set<EntityID> createValue() {
                return new HashSet<>();
            }
        };
        
        // 构建道路网络图并识别建筑物连接点
        for (Entity next : this.worldInfo) {
            if (next instanceof Road) {
                Road road = (Road) next;
                Set<EntityID> roadNeighbours = new HashSet<>(road.getNeighbours());
                
                // 检查是否是建筑物连接点
                for (EntityID neighborId : roadNeighbours) {
                    StandardEntity neighbor = worldInfo.getEntity(neighborId);
                    if (neighbor instanceof Building) {
                        buildingConnections.add(road.getID());
                        break;
                    }
                }
                
                // 处理障碍物信息
                if (road.isBlockadesDefined()) {
                    for (EntityID blockadeID : road.getBlockades()) {
                        StandardEntity entity = worldInfo.getEntity(blockadeID);
                        if (entity instanceof Blockade) {
                            Blockade blockade = (Blockade) entity;
                            blockadeInfo.put(road.getID(), blockade);
                        }
                    }
                }
                
                neighbours.put(road.getID(), roadNeighbours);
            }
        }
        this.graph = neighbours;
    }

    @Override
    public List<EntityID> getResult() {
        return this.result;
    }
    
    public int getBestDirection() {
        return this.bestDirection;
    }
    
    public EntityID getBestPosition() {
        return this.bestPosition;
    }

    @Override
    public PathPlanning setFrom(EntityID id) {
        this.from = id;
        return this;
    }

    @Override
    public PathPlanning setDestination(Collection<EntityID> targets) {
        this.targets = targets;
        return this;
    }

    @Override
    public PathPlanning calc() {
        // 重置方向优化字段
        this.bestDirection = -1;
        this.bestPosition = null;
        
        // 1. 如果目标为空，返回空路径
        if (targets == null || targets.isEmpty()) {
            this.result = Collections.emptyList();
            return this;
        }
        
        // 2. 如果当前位置就是目标，直接返回当前位置
        if (targets.contains(from)) {
            this.result = Collections.singletonList(from);
            this.bestPosition = from;
            this.bestDirection = calculateOptimalDirection(from);
            return this;
        }
        
        // 3. 执行A*算法
        List<EntityID> open = new LinkedList<>();
        List<EntityID> close = new LinkedList<>();
        Map<EntityID, Node> nodeMap = new HashMap<>();
        
        // 4. 初始化
        open.add(this.from);
        nodeMap.put(this.from, new Node(null, this.from));
        close.clear();
        
        EntityID actualTarget = null;
        boolean foundPath = false;
        
        while (!open.isEmpty()) {
            // 5. 获取开放列表中最优节点
            Node current = getBestNode(open, nodeMap);
            EntityID currentId = current.getID();
            
            // 6. 检查是否到达目标
            if (targets.contains(currentId)) {
                actualTarget = currentId;
                foundPath = true;
                break;
            }
            
            // 7. 移动当前节点到关闭列表
            open.remove(currentId);
            close.add(currentId);
            
            // 8. 处理邻居节点
            processNeighbors(current, open, close, nodeMap);
        }
        
        // 9. 构建结果路径
        if (foundPath && actualTarget != null) {
            buildPath(nodeMap, actualTarget);
            this.bestDirection = calculateOptimalDirection(actualTarget);
            this.bestPosition = actualTarget;
        } 
        // 10. 如果没有找到路径，尝试寻找次优路径
        else {
            findFallbackPath(open, close, nodeMap);
        }
        
        // 11. 添加当前位置（如果路径为空）
        if (result.isEmpty()) {
            result.add(from);
            this.bestPosition = from;
        }
        
        return this;
    }
    
    // 计算最佳清理方向（核心优化）
    private int calculateOptimalDirection(EntityID targetPosition) {
        // 检查缓存
        if (directionCache.containsKey(targetPosition)) {
            return directionCache.get(targetPosition);
        }
        
        Blockade blockade = blockadeInfo.get(targetPosition);
        if (blockade == null || !blockade.isApexesDefined()) {
            return -1; // 无效方向
        }
        
        // 计算障碍物中心点
        int[] apexes = blockade.getApexes();
        int centerX = 0, centerY = 0;
        for (int i = 0; i < apexes.length; i += 2) {
            centerX += apexes[i];
            centerY += apexes[i + 1];
        }
        centerX /= (apexes.length / 2);
        centerY /= (apexes.length / 2);
        
        // 获取道路中心位置
        Pair<Integer, Integer> roadPos = worldInfo.getLocation(targetPosition);
        if (roadPos == null) return -1;
        
        // 计算方向向量
        int dx = centerX - roadPos.first();
        int dy = centerY - roadPos.second();
        
        // 计算角度（0-360度）
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;
        
        int direction = (int) Math.round(angle);
        
        // 如果是建筑物连接点，调整方向朝向建筑物
        if (buildingConnections.contains(targetPosition)) {
            // 找到最近的建筑物
            StandardEntity building = findNearestBuilding(targetPosition);
            if (building != null) {
                Pair<Integer, Integer> buildingPos = worldInfo.getLocation(building.getID());
                if (buildingPos != null) {
                    dx = buildingPos.first() - roadPos.first();
                    dy = buildingPos.second() - roadPos.second();
                    angle = Math.toDegrees(Math.atan2(dy, dx));
                    if (angle < 0) angle += 360;
                    direction = (int) Math.round(angle);
                }
            }
        }
        
        // 缓存结果
        directionCache.put(targetPosition, direction);
        return direction;
    }
    
    private StandardEntity findNearestBuilding(EntityID roadId) {
        StandardEntity road = worldInfo.getEntity(roadId);
        if (!(road instanceof Road)) return null;
        
        Road r = (Road) road;
        for (EntityID neighborId : r.getNeighbours()) {
            StandardEntity neighbor = worldInfo.getEntity(neighborId);
            if (neighbor instanceof Building) {
                return neighbor;
            }
        }
        return null;
    }
    
    private Node getBestNode(List<EntityID> open, Map<EntityID, Node> nodeMap) {
        Node best = null;
        for (EntityID id : open) {
            Node node = nodeMap.get(id);
            if (best == null || node.estimate() < best.estimate()) {
                best = node;
            }
        }
        return best;
    }
    
    private void processNeighbors(Node current, List<EntityID> open, List<EntityID> close, 
                                  Map<EntityID, Node> nodeMap) {
        EntityID currentId = current.getID();
        Collection<EntityID> neighbors = this.graph.get(currentId);
        if (neighbors == null) return;
        
        for (EntityID neighborId : neighbors) {
            // 跳过已经在关闭列表中的节点
            if (close.contains(neighborId)) continue;
            
            // 计算新路径成本
            double newCost = current.getCost() + calculateMoveCost(currentId, neighborId);
            Node neighborNode = nodeMap.get(neighborId);
            
            // 如果邻居节点不在开放列表中，或者新路径更好
            if (neighborNode == null || newCost < neighborNode.getCost()) {
                // 创建/更新节点
                if (neighborNode == null) {
                    neighborNode = new Node(currentId, neighborId);
                    nodeMap.put(neighborId, neighborNode);
                    open.add(neighborId);
                } else {
                    neighborNode.setParent(currentId);
                    neighborNode.setCost(newCost);
                }
                
                // 更新启发式值
                neighborNode.updateHeuristic(targets, worldInfo);
            }
        }
    }
    
    private double calculateMoveCost(EntityID from, EntityID to) {
        // 基础移动成本
        double cost = worldInfo.getDistance(from, to);
        
        // 增加障碍物的移动成本（鼓励清理障碍）
        Blockade blockade = blockadeInfo.get(to);
        if (blockade != null && blockade.isRepairCostDefined()) {
            // 障碍物修复成本越高，移动成本越高（鼓励优先清除）
            cost += blockade.getRepairCost() * 0.5;
            
            // 如果是建筑物连接点，进一步降低移动成本（优先清理）
            if (buildingConnections.contains(to)) {
                cost *= 0.7; // 降低30%成本
            }
        }

        // 增加警察负担成本（避免过多警察聚集）
        int nearbyPolice = countNearbyPolice(to);
        cost += nearbyPolice * 20.0; // 每个附近警察增加20点成本
        
        return cost;
    }
    
    private int countNearbyPolice(EntityID position) {
        int count = 0;
        Collection<StandardEntity> entities = worldInfo.getObjectsInRange(position, 30000);
        for (StandardEntity entity : entities) {
            if (entity instanceof PoliceForce) {
                count++;
            }
        }
        return Math.max(0, count - 1); // 不包括自己
    }

    
    private void buildPath(Map<EntityID, Node> nodeMap, EntityID target) {
        List<EntityID> path = new LinkedList<>();
        Node node = nodeMap.get(target);
        
        while (node != null) {
            path.add(0, node.getID());
            node = nodeMap.get(node.getParent());
        }
        
        this.result = path;
    }
    
    private void findFallbackPath(List<EntityID> open, List<EntityID> close, 
                                 Map<EntityID, Node> nodeMap) {
        // 尝试1：从开放列表中寻找最近目标的节点
        EntityID fallbackTarget = findNearestTargetInOpen(open, nodeMap);
        
        // 尝试2：如果开放列表中没有合适目标，从关闭列表中寻找
        if (fallbackTarget == null) {
            fallbackTarget = findNearestTargetInClose(close, nodeMap);
        }
        
        // 尝试3：如果还是找不到，选择最近的邻居
        if (fallbackTarget == null) {
            fallbackTarget = findNearestNeighbor();
        }
        
        // 构建回退路径
        if (fallbackTarget != null) {
            buildPath(nodeMap, fallbackTarget);
            this.bestDirection = calculateOptimalDirection(fallbackTarget);
            this.bestPosition = fallbackTarget;
        } else {
            this.result = Collections.singletonList(from);
            this.bestPosition = from;
        }
    }
    
    private EntityID findNearestTargetInOpen(List<EntityID> open, Map<EntityID, Node> nodeMap) {
        EntityID best = null;
        double minHeuristic = Double.MAX_VALUE;
        
        for (EntityID id : open) {
            Node node = nodeMap.get(id);
            if (node != null && node.getHeuristic() < minHeuristic) {
                minHeuristic = node.getHeuristic();
                best = id;
            }
        }
        
        return best;
    }
    
    private EntityID findNearestTargetInClose(List<EntityID> close, Map<EntityID, Node> nodeMap) {
        EntityID best = null;
        double minHeuristic = Double.MAX_VALUE;
        
        for (EntityID id : close) {
            Node node = nodeMap.get(id);
            if (node != null && node.getHeuristic() < minHeuristic) {
                minHeuristic = node.getHeuristic();
                best = id;
            }
        }
        
        return best;
    }
    
    private EntityID findNearestNeighbor() {
        EntityID best = null;
        double minDistance = Double.MAX_VALUE;
        
        Collection<EntityID> neighbors = graph.get(from);
        if (neighbors != null) {
            for (EntityID neighbor : neighbors) {
                double distance = worldInfo.getDistance(from, neighbor);
                if (distance < minDistance) {
                    minDistance = distance;
                    best = neighbor;
                }
            }
        }
        
        return best;
    }

    private class Node {
        private EntityID id;
        private EntityID parent;
        private double cost;
        private double heuristic;

        public Node(EntityID parent, EntityID id) {
            this.id = id;
            this.parent = parent;
            
            // 计算初始成本
            if (parent == null) {
                this.cost = 0;
            } else {
                this.cost = worldInfo.getDistance(parent, id);
            }
            
            // 计算初始启发式值
            updateHeuristic(null, worldInfo);
        }

        public void updateHeuristic(Collection<EntityID> targets, WorldInfo worldInfo) {
            if (targets == null || targets.isEmpty()) {
                this.heuristic = 0;
                return;
            }
            
            // 计算到最近目标的距离
            double minDistance = Double.MAX_VALUE;
            for (EntityID target : targets) {
                double distance = worldInfo.getDistance(id, target);
                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
            
            // 添加目标价值启发式（鼓励选择高价值目标）
            double valueHeuristic = 0;
            Blockade blockade = blockadeInfo.get(id);
            if (blockade != null && blockade.isRepairCostDefined()) {
                valueHeuristic = 1000.0 / (blockade.getRepairCost() + 1); // 修复成本越高，价值越高
            }
            
            this.heuristic = minDistance - valueHeuristic;
        }

        public EntityID getID() {
            return id;
        }

        public EntityID getParent() {
            return parent;
        }
        
        public void setParent(EntityID parent) {
            this.parent = parent;
        }

        public double getCost() {
            return cost;
        }
        
        public void setCost(double cost) {
            this.cost = cost;
        }
        
        public double getHeuristic() {
            return heuristic;
        }

        public double estimate() {
            return cost + heuristic;
        }
    }
}