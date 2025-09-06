package sample_team.module.complex;

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.StaticClustering;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.Blockade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.module.ModuleManager;
import java.util.*;

public class KMeans extends StaticClustering {

    private static final int MAX_ITERATIONS = 50;
    private static final double CONVERGENCE_THRESHOLD = 1.0;
    
    private int clusterCount;
    private Map<Integer, List<StandardEntity>> clusters;
    private List<StandardEntity> centers;
    private Map<EntityID, Integer> entityToClusterMap;
    
    public KMeans(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.clusterCount = determineClusterCount(ai, si);
        this.clusters = new HashMap<>();
        this.centers = new ArrayList<>();
        this.entityToClusterMap = new HashMap<>();
    }

        // 新增方法：获取所有聚类
    public Map<Integer, List<StandardEntity>> getClusters() {
        return Collections.unmodifiableMap(clusters);
    }

    // 新增方法：获取指定聚类的中心
    public StandardEntity getClusterCenter(int clusterIndex) {
        if (clusterIndex >= 0 && clusterIndex < centers.size()) {
            return centers.get(clusterIndex);
        }
        return null;
    }

    @Override
    public Clustering calc() {
        Collection<StandardEntity> entities = getRelevantEntities();
        if (entities.isEmpty()) {
            return this;
        }
        
        // 1. 初始化聚类中心 - 优先选择有障碍物的道路
        this.centers = selectInitialCenters(entities);
        
        boolean converged = false;
        int iterations = 0;
        
        while (!converged && iterations < MAX_ITERATIONS) {
            // 2. 分配实体到最近的聚类中心
            Map<Integer, List<StandardEntity>> newClusters = assignToClusters(centers, entities);
            
            // 3. 重新计算聚类中心
            List<StandardEntity> newCenters = calculateNewCenters(newClusters);
            
            // 4. 检查是否收敛
            converged = checkConvergence(centers, newCenters);
            
            centers = newCenters;
            clusters = newClusters;
            iterations++;
        }
        
        // 5. 确保所有聚类区域都包含道路
        ensureRoadCoverage();
        
        // 6. 构建实体到聚类的映射
        buildEntityToClusterMap();
        
        return this;
    }

    @Override
    public int getClusterNumber() {
        return clusterCount;
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        return getClusterIndex(entity.getID());
    }

    @Override
    public int getClusterIndex(EntityID id) {
        Integer index = entityToClusterMap.get(id);
        return index != null ? index : -1;
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
        return clusters.getOrDefault(index, Collections.emptyList());
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int index) {
        List<EntityID> result = new ArrayList<>();
        for (StandardEntity entity : getClusterEntities(index)) {
            result.add(entity.getID());
        }
        return result;
    }

    private int determineClusterCount(AgentInfo agentInfo, ScenarioInfo scenarioInfo) {
        // 根据智能体类型确定聚类数量
        switch (agentInfo.me().getStandardURN()) {
            case AMBULANCE_TEAM:
                return scenarioInfo.getScenarioAgentsAt();
            case FIRE_BRIGADE:
                return scenarioInfo.getScenarioAgentsFb();
            case POLICE_FORCE:
                return scenarioInfo.getScenarioAgentsPf();
            default:
                return 5; // 默认值
        }
    }

    private Collection<StandardEntity> getRelevantEntities() {
        // 获取所有相关实体（道路、建筑物等）
        return worldInfo.getEntitiesOfType(
            StandardEntityURN.ROAD,
            StandardEntityURN.HYDRANT,
            StandardEntityURN.BUILDING,
            StandardEntityURN.REFUGE,
            StandardEntityURN.GAS_STATION,
            StandardEntityURN.AMBULANCE_CENTRE,
            StandardEntityURN.FIRE_STATION,
            StandardEntityURN.POLICE_OFFICE
        );
    }

    private List<StandardEntity> selectInitialCenters(Collection<StandardEntity> entities) {
        List<StandardEntity> centers = new ArrayList<>();
        List<StandardEntity> candidates = new ArrayList<>(entities);
        
        // 优先选择有障碍物的道路作为初始中心
        for (StandardEntity entity : entities) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    centers.add(entity);
                    candidates.remove(entity);
                    if (centers.size() >= clusterCount) {
                        return centers;
                    }
                }
            }
        }
        
        // 如果没有足够的有障碍物的道路，使用K-Means++算法选择初始中心
        if (centers.isEmpty()) {
            // 随机选择第一个中心
            if (!candidates.isEmpty()) {
                centers.add(candidates.remove(new Random().nextInt(candidates.size())));
            }
            
            // 使用K-Means++选择剩余中心
            while (centers.size() < clusterCount && !candidates.isEmpty()) {
                // 计算每个候选点与最近中心的距离
                double[] distances = new double[candidates.size()];
                double sum = 0;
                
                for (int i = 0; i < candidates.size(); i++) {
                    StandardEntity candidate = candidates.get(i);
                    double minDist = Double.MAX_VALUE;
                    
                    for (StandardEntity center : centers) {
                        double dist = worldInfo.getDistance(candidate, center);
                        if (dist < minDist) {
                            minDist = dist;
                        }
                    }
                    
                    distances[i] = minDist * minDist; // 平方距离
                    sum += distances[i];
                }
                
                // 按概率选择下一个中心
                double r = Math.random() * sum;
                double runningSum = 0;
                int selectedIndex = 0;
                
                for (int i = 0; i < distances.length; i++) {
                    runningSum += distances[i];
                    if (runningSum >= r) {
                        selectedIndex = i;
                        break;
                    }
                }
                
                centers.add(candidates.remove(selectedIndex));
            }
        }
        
        return centers;
    }

    private Map<Integer, List<StandardEntity>> assignToClusters(List<StandardEntity> centers, 
            Collection<StandardEntity> entities) {
        Map<Integer, List<StandardEntity>> clusters = new HashMap<>();
        for (int i = 0; i < centers.size(); i++) {
            clusters.put(i, new ArrayList<>());
        }
        
        for (StandardEntity entity : entities) {
            int nearestCluster = findNearestCluster(centers, entity);
            clusters.get(nearestCluster).add(entity);
        }
        
        return clusters;
    }

    private int findNearestCluster(List<StandardEntity> centers, StandardEntity entity) {
        int nearest = 0;
        double minDistance = Double.MAX_VALUE;
        
        for (int i = 0; i < centers.size(); i++) {
            double distance = worldInfo.getDistance(entity, centers.get(i));
            if (distance < minDistance) {
                minDistance = distance;
                nearest = i;
            }
        }
        
        return nearest;
    }

    private List<StandardEntity> calculateNewCenters(Map<Integer, List<StandardEntity>> clusters) {
        List<StandardEntity> newCenters = new ArrayList<>();
        
        for (Map.Entry<Integer, List<StandardEntity>> entry : clusters.entrySet()) {
            List<StandardEntity> cluster = entry.getValue();
            if (cluster.isEmpty()) {
                continue;
            }
            
            // 计算几何中心
            double sumX = 0, sumY = 0;
            for (StandardEntity entity : cluster) {
                Pair<Integer, Integer> location = worldInfo.getLocation(entity);
                sumX += location.first();
                sumY += location.second();
            }
            
            double centerX = sumX / cluster.size();
            double centerY = sumY / cluster.size();
            
            // 找到距离中心最近的道路
            StandardEntity nearestRoad = findNearestRoad(cluster, centerX, centerY);
            if (nearestRoad != null) {
                newCenters.add(nearestRoad);
            } else {
                // 如果没有道路，选择最近的实体
                newCenters.add(findNearestEntity(cluster, centerX, centerY));
            }
        }
        
        return newCenters;
    }

    private StandardEntity findNearestRoad(List<StandardEntity> entities, double x, double y) {
        StandardEntity nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (StandardEntity entity : entities) {
            if (entity instanceof Road) {
                Pair<Integer, Integer> location = worldInfo.getLocation(entity);
                double distance = Math.hypot(location.first() - x, location.second() - y);
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = entity;
                }
            }
        }
        
        return nearest;
    }

    private StandardEntity findNearestEntity(List<StandardEntity> entities, double x, double y) {
        StandardEntity nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (StandardEntity entity : entities) {
            Pair<Integer, Integer> location = worldInfo.getLocation(entity);
            double distance = Math.hypot(location.first() - x, location.second() - y);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = entity;
            }
        }
        
        return nearest;
    }

    private boolean checkConvergence(List<StandardEntity> oldCenters, List<StandardEntity> newCenters) {
        if (oldCenters.size() != newCenters.size()) {
            return false;
        }
        
        for (int i = 0; i < oldCenters.size(); i++) {
            double distance = worldInfo.getDistance(oldCenters.get(i), newCenters.get(i));
            if (distance > CONVERGENCE_THRESHOLD) {
                return false;
            }
        }
        
        return true;
    }

    private void ensureRoadCoverage() {
        // 确保每个聚类至少包含一条道路
        for (Map.Entry<Integer, List<StandardEntity>> entry : clusters.entrySet()) {
            boolean hasRoad = false;
            for (StandardEntity entity : entry.getValue()) {
                if (entity instanceof Road) {
                    hasRoad = true;
                    break;
                }
            }
            
            if (!hasRoad) {
                // 如果没有道路，找到最近的未分配道路并添加到该聚类
                Road nearestRoad = findNearestUnassignedRoad();
                if (nearestRoad != null) {
                    entry.getValue().add(nearestRoad);
                }
            }
        }
    }

    private Road findNearestUnassignedRoad() {
        // 收集所有已分配的道路
        Set<StandardEntity> assignedRoads = new HashSet<>();
        for (List<StandardEntity> cluster : clusters.values()) {
            for (StandardEntity entity : cluster) {
                if (entity instanceof Road) {
                    assignedRoads.add(entity);
                }
            }
        }
        
        // 找到最近的未分配道路
        Road nearest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.ROAD)) {
            if (!assignedRoads.contains(entity)) {
                Pair<Integer, Integer> location = worldInfo.getLocation(entity);
                double distance = Math.hypot(location.first(), location.second());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = (Road) entity;
                }
            }
        }
        
        return nearest;
    }

    private void buildEntityToClusterMap() {
        entityToClusterMap.clear();
        for (Map.Entry<Integer, List<StandardEntity>> entry : clusters.entrySet()) {
            for (StandardEntity entity : entry.getValue()) {
                entityToClusterMap.put(entity.getID(), entry.getKey());
            }
        }
    }
}