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
    private Map<EntityID, Integer> blockadeCostMap; // 存储每条道路的总障碍物修复成本
    private EntityID from;
    private Collection<EntityID> targets;
    private List<EntityID> result;
    private WorldInfo worldInfo;
   
    private Map<EntityID, Double> rescuePriorityCache = new HashMap<>();// 救援优先级缓存

    public PoliceAStarPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                 ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.init();
    }

    private void initBlockadeCostMap() {
        blockadeCostMap.clear();
        for (Entity next : this.worldInfo) {
            if (next instanceof Road) {
                Road road = (Road) next;
                int totalRepairCost = 0;
                if (road.isBlockadesDefined()) {
                    for (EntityID blockadeID : road.getBlockades()) {
                        StandardEntity entity = worldInfo.getEntity(blockadeID);
                        if (entity instanceof Blockade) {
                            Blockade blockade = (Blockade) entity;
                            if (blockade.isRepairCostDefined()) {
                                totalRepairCost += blockade.getRepairCost();
                            }
                        }
                    }
                }
                blockadeCostMap.put(road.getID(), totalRepairCost);
            }
        }
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
                neighbours.put(road.getID(), roadNeighbours);
                // 计算该道路的总障碍物修复成本
                int totalRepairCost = 0;
                if (road.isBlockadesDefined()) {
                    for (EntityID blockadeID : road.getBlockades()) {
                        StandardEntity entity = worldInfo.getEntity(blockadeID);
                        if (entity instanceof Blockade) {
                            Blockade blockade = (Blockade) entity;
                            if (blockade.isRepairCostDefined()) {
                                totalRepairCost += blockade.getRepairCost();
                            }
                        }
                    }
                }
                blockadeCostMap.put(road.getID(),totalRepairCost);
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
        
       // 将道路图转换为自定义字符串格式
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<EntityID, Set<EntityID>> entry : graph.entrySet()) {
            sb.append(entry.getKey().getValue()).append(":");
            for (EntityID neighbor : entry.getValue()) {
                sb.append(neighbor.getValue()).append(",");
            }
            sb.append(";");
        }
        precomputeData.setString("road_graph", sb.toString());

        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        // 从自定义字符串恢复道路图
        String graphStr = precomputeData.getString("road_graph");
        if (graphStr != null) {
            try {
                Map<EntityID, Set<EntityID>> newGraph = new HashMap<>();
                String[] entries = graphStr.split(";");
                for (String entry : entries) {
                    if (entry.isEmpty()) continue;
                    
                    String[] parts = entry.split(":");
                    EntityID roadId = new EntityID(Integer.parseInt(parts[0]));
                    
                    Set<EntityID> neighbors = new HashSet<>();
                    if (parts.length > 1) {
                        for (String neighborId : parts[1].split(",")) {
                            if (!neighborId.isEmpty()) {
                                neighbors.add(new EntityID(Integer.parseInt(neighborId)));
                            }
                        }
                    }
                    
                    newGraph.put(roadId, neighbors);
                }
                this.graph = newGraph;
            } catch (Exception e) {
                // 解析失败，回退到重新初始化
                init();
            }
        } else {
            // 没有预计算数据，重新初始化
            init();
        }
        // 初始化障碍物成本映射
        initBlockadeCostMap();
        return this;
    }

    @Override
    public PathPlanning preparate() {
        super.preparate();
        return this;
    }

    @Override
    public PathPlanning calc() {
        // 清空缓存
        rescuePriorityCache.clear();
        
        if (targets == null || targets.isEmpty()) {
            this.result = Collections.emptyList();
            return this;
        }
        
        if (targets.contains(from)) {
            this.result = Collections.singletonList(from);
            return this;
        }
        
        List<EntityID> open = new LinkedList<>();
        List<EntityID> close = new LinkedList<>();
        Map<EntityID, Node> nodeMap = new HashMap<>();
        
        open.add(this.from);
        nodeMap.put(this.from, new Node(null, this.from));
        close.clear();
        
        EntityID actualTarget = null;
        boolean foundPath = false;
        
        while (!open.isEmpty()) {
            Node current = getBestNode(open, nodeMap);
            EntityID currentId = current.getID();
            
            if (targets.contains(currentId)) {
                actualTarget = currentId;
                foundPath = true;
                break;
            }
            
            open.remove(currentId);
            close.add(currentId);
            
            processNeighbors(current, open, close, nodeMap);
        }
        
        if (foundPath && actualTarget != null) {
            buildPath(nodeMap, actualTarget);
        } else {
            findFallbackPath(open, close, nodeMap);
        }
        
        if (result == null || result.isEmpty()) {
            result = Collections.singletonList(from);
        }
        
        return this;
    }
    
    // 计算救援优先级
    private double calculateRescuePriority(EntityID roadId) {
        if (rescuePriorityCache.containsKey(roadId)) {
            return rescuePriorityCache.get(roadId);
        }
        
        double priority = 0.0;
        StandardEntity roadEntity = worldInfo.getEntity(roadId);
        if (!(roadEntity instanceof Road)) {
            return 0.0;
        }
        
        Road road = (Road) roadEntity;
        
        // 检查道路连接的建筑物
        for (EntityID neighbor : road.getNeighbours()) {
            StandardEntity neighborEntity = worldInfo.getEntity(neighbor);
            if (neighborEntity instanceof Building) {
                Building building = (Building) neighborEntity;
                
                // 如果建筑物有被困人员，增加优先级
                if (building.isOnFire() || worldInfo.getNumberOfBuried(building.getID()) > 0) {
                    priority += 2.0;
                    
                    // 检查是否有救援队伍在附近但无法进入
                    Collection<StandardEntity> nearbyRescuers = worldInfo.getObjectsInRange(roadId, 50000);
                    for (StandardEntity rescuer : nearbyRescuers) {
                        if (rescuer instanceof AmbulanceTeam || rescuer instanceof FireBrigade) {
                            priority += 1.5;
                            break;
                        }
                    }
                }
            }
        }
        
        rescuePriorityCache.put(roadId, priority);
        return priority;
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
            if (close.contains(neighborId)) continue;
            
            double newCost = current.getCost() + calculateMoveCost(currentId, neighborId);
            Node neighborNode = nodeMap.get(neighborId);
            
            if (neighborNode == null || newCost < neighborNode.getCost()) {
                if (neighborNode == null) {
                    neighborNode = new Node(currentId, neighborId);
                    nodeMap.put(neighborId, neighborNode);
                    open.add(neighborId);
                } else {
                    neighborNode.setParent(currentId);
                    neighborNode.setCost(newCost);
                }
                
                neighborNode.updateHeuristic(targets, worldInfo);
            }
        }
    }
    
    private double calculateMoveCost(EntityID from, EntityID to) {
        double cost = worldInfo.getDistance(from, to);
        
        // 获取救援优先级
        double rescuePriority = calculateRescuePriority(to);
        
        // 获取该道路的总障碍物修复成本
        Integer totalRepairCost = blockadeCostMap.get(to);
        if (totalRepairCost != null && totalRepairCost > 0) {
            // 根据救援优先级调整障碍物修复成本
            if (rescuePriority > 1.0) {
                // 高优先级区域，降低移动成本以鼓励清理
                cost += totalRepairCost * 0.3;
            } else {
                // 低优先级区域，增加移动成本以抑制清理
                cost += totalRepairCost * 0.8;
            }
        }

        int nearbyPolice = countNearbyPolice(to);
        cost += nearbyPolice * 20.0;
        
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
        return Math.max(0, count - 1);
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
        EntityID fallbackTarget = findNearestTargetInOpen(open, nodeMap);
        
        if (fallbackTarget == null) {
            fallbackTarget = findNearestTargetInClose(close, nodeMap);
        }
        
        if (fallbackTarget == null) {
            fallbackTarget = findReachableVisibleNeighbor(from);
        }
        
        if (fallbackTarget == null) {
            fallbackTarget = findNearestNeighbor();
        }
        
        if (fallbackTarget != null) {
            buildPath(nodeMap, fallbackTarget);
        } else {
            this.result = Collections.singletonList(from);
        }
    }
    
    private EntityID findReachableVisibleNeighbor(EntityID position) {
        int viewDistance = 50000;
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(position, viewDistance);
        
        EntityID bestNeighbor = null;
        double minDistance = Double.MAX_VALUE;
        
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                EntityID roadID = road.getID();
                
                if (roadID.equals(position)) continue;
                
                double distance = worldInfo.getDistance(position, roadID);
                if (distance < minDistance && distance > 0) {
                    minDistance = distance;
                    bestNeighbor = roadID;
                }
            }
        }
        
        return bestNeighbor;
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
            
            if (parent == null) {
                this.cost = 0;
            } else {
                this.cost = worldInfo.getDistance(parent, id);
            }
            
            updateHeuristic(null, worldInfo);
        }

        public void updateHeuristic(Collection<EntityID> targets, WorldInfo worldInfo) {
            if (targets == null || targets.isEmpty()) {
                this.heuristic = 0;
                return;
            }
            
            double minDistance = targets.stream()
            .mapToDouble(target -> worldInfo.getDistance(id, target))
            .min()
            .orElse(Double.MAX_VALUE);
            
            double valueHeuristic = 0;
            Integer totalRepairCost = blockadeCostMap.get(id);
            if (totalRepairCost != null && totalRepairCost > 0) {
                double rescuePriority = calculateRescuePriority(id);
                valueHeuristic = 1000.0 / (totalRepairCost + 1) * (1 + rescuePriority);
            }
            
            this.heuristic = Math.max(0, minDistance - valueHeuristic);
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