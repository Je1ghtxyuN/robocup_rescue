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

import java.util.*;

public class PoliceAStarPathPlanning extends PathPlanning {

    private Map<EntityID, Set<EntityID>> graph;
    private Map<EntityID, Blockade> blockadeInfo = new HashMap<>();
    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;
    private WorldInfo worldInfo;

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
        
        // 构建道路网络图
        for (Entity next : this.worldInfo) {
            if (next instanceof Road) {
                Road road = (Road) next;
                Set<EntityID> roadNeighbours = new HashSet<>(road.getNeighbours());
                
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
    public PathPlanning precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public PathPlanning preparate() {
        super.preparate();
        return this;
    }

    @Override
    public PathPlanning calc() {
        // 1. 如果目标为空，返回空路径
        if (targets == null || targets.isEmpty()) {
            this.result = Collections.emptyList();
            return this;
        }
        
        // 2. 如果当前位置就是目标，直接返回当前位置
        if (targets.contains(from)) {
            this.result = Collections.singletonList(from);
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
        } 
        // 10. 如果没有找到路径，尝试寻找次优路径
        else {
            findFallbackPath(open, close, nodeMap);
        }
        
        // 11. 添加当前位置（如果路径为空）
        if (result.isEmpty()) {
            result.add(from);
        }
        
        return this;
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
        } else {
            this.result = Collections.singletonList(from); // 最终回退：留在原地
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