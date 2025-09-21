package sample_team.module.algorithm;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AStarPathPlanning extends PathPlanning {

    private Map<EntityID, Set<EntityID>> graph;
    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;

    public AStarPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.init();
    }

    private void init() {
        Map<EntityID, Set<EntityID>> neighbours = new HashMap<>();
        for (Entity next : this.worldInfo) {
            if (next instanceof Area) {
                Collection<EntityID> areaNeighbours = ((Area) next).getNeighbours();
                neighbours.computeIfAbsent(next.getID(), k -> new HashSet<>()).addAll(areaNeighbours);
            }
        }
        this.graph = neighbours;
    }

    @Override
    public List<EntityID> getResult() {
        return this.result;
    }

    @Override
    public AStarPathPlanning setFrom(EntityID id) {
        this.from = id;
        return this;
    }

    @Override
    public AStarPathPlanning setDestination(Collection<EntityID> targets) {
        this.targets = targets;
        return this;
    }

    @Override
    public AStarPathPlanning precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public AStarPathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public AStarPathPlanning preparate() {
        super.preparate();
        return this;
    }

    @Override
    public AStarPathPlanning calc() {
        // 使用优先级队列作为开放列表，提高效率
        PriorityQueue<SearchNode> openQueue = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::getEstimatedCost));
        
        // 使用HashMap作为关闭列表，提高查找效率
        Map<EntityID, SearchNode> closedMap = new HashMap<>();
        Map<EntityID, SearchNode> allNodes = new ConcurrentHashMap<>();
        
        // 初始化起始节点
        SearchNode startNode = new SearchNode(null, from, 0, calculateHeuristic(from, targets));
        openQueue.add(startNode);
        allNodes.put(from, startNode);
        
        SearchNode goalNode = null;
        
        while (!openQueue.isEmpty()) {
            // 获取估计代价最小的节点
            SearchNode currentNode = openQueue.poll();
            
            // 检查是否到达目标
            if (targets.contains(currentNode.getId())) {
                goalNode = currentNode;
                break;
            }
            
            // 将当前节点加入关闭列表
            closedMap.put(currentNode.getId(), currentNode);
            
            // 获取当前节点的所有邻居
            Set<EntityID> neighbors = graph.get(currentNode.getId());
            if (neighbors == null) continue;
            
            for (EntityID neighborId : neighbors) {
                // 跳过已在关闭列表中的节点
                if (closedMap.containsKey(neighborId)) {
                    continue;
                }
                
                // 计算从起始节点经过当前节点到达邻居的实际代价
                double newCost = currentNode.getActualCost() + 
                                 worldInfo.getDistance(currentNode.getId(), neighborId);
                
                // 检查是否已经存在到达此邻居的节点
                SearchNode existingNode = allNodes.get(neighborId);
                
                if (existingNode == null) {
                    // 新发现的节点
                    double heuristic = calculateHeuristic(neighborId, targets);
                    SearchNode newNode = new SearchNode(currentNode, neighborId, newCost, heuristic);
                    openQueue.add(newNode);
                    allNodes.put(neighborId, newNode);
                } else if (newCost < existingNode.getActualCost()) {
                    // 找到了一条更优路径
                    existingNode.setParent(currentNode);
                    existingNode.setActualCost(newCost);
                    
                    // 需要重新调整优先队列中的位置
                    openQueue.remove(existingNode);
                    openQueue.add(existingNode);
                }
            }
        }
        
        // 构建结果路径
        if (goalNode != null) {
            LinkedList<EntityID> path = new LinkedList<>();
            SearchNode node = goalNode;
            
            while (node != null) {
                path.addFirst(node.getId());
                node = node.getParent();
            }
            
            this.result = path;
        } else {
            this.result = null;
        }
        
        return this;
    }

    /**
     * 计算启发式代价（到最近目标的距离）
     */
    private double calculateHeuristic(EntityID from, Collection<EntityID> toTargets) {
        double minDistance = Double.MAX_VALUE;
        
        for (EntityID target : toTargets) {
            double distance = worldInfo.getDistance(from, target);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        
        return minDistance;
    }

    /**
     * 内部类：表示搜索节点
     */
    private static class SearchNode {
        private SearchNode parent;
        private EntityID id;
        private double actualCost;
        private double heuristicCost;
        
        public SearchNode(SearchNode parent, EntityID id, double actualCost, double heuristicCost) {
            this.parent = parent;
            this.id = id;
            this.actualCost = actualCost;
            this.heuristicCost = heuristicCost;
        }
        
        public EntityID getId() {
            return id;
        }
        
        public double getActualCost() {
            return actualCost;
        }
        
        public void setActualCost(double cost) {
            this.actualCost = cost;
        }
        
        public double getHeuristicCost() {
            return heuristicCost;
        }
        
        public double getEstimatedCost() {
            return actualCost + heuristicCost;
        }
        
        public SearchNode getParent() {
            return parent;
        }
        
        public void setParent(SearchNode parent) {
            this.parent = parent;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            SearchNode that = (SearchNode) obj;
            return Objects.equals(id, that.id);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}