package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_OFFICE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import rescuecore2.standard.entities.Blockade;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import adf.core.debug.DefaultLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

import sample_team.module.message.MessageSay;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;

public class SampleSearch extends Search {

    private PathPlanning pathPlanning;
    private Clustering clustering;
     
    // 警察巡逻字段
    private Queue<EntityID> patrolTargets = new LinkedList<>(); // 巡逻点队列

    private EntityID result;
    private Collection<EntityID> unsearchedBuildingIDs;
    private EntityID currentTargetBuilding; // 当前正在前往的建筑
    private Set<EntityID> searchedBuildings = new HashSet<>();
    private Set<EntityID> processedBlockades = new HashSet<>(); // 障碍物处理缓存
    private Logger logger;

    private Map<EntityID, EntityID> recoverableTargets = new HashMap<>();  // 记录受阻目标及其关联障碍

    private EntityID currentRescueCivilianID; // 当前正在救援的平民ID
    private Set<EntityID> rescuedByFireBrigade; // 已被消防队接管的平民ID集合
    private MessageManager messageManager; // 用于发送消息


    public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.unsearchedBuildingIDs = new HashSet<>();
        this.rescuedByFireBrigade = new HashSet<>();

        StandardEntityURN agentURN = ai.me().getStandardURN();
        if (agentURN == AMBULANCE_TEAM) {
            this.pathPlanning = moduleManager.getModule(
                    "SampleSearch.PathPlanning.Ambulance",
                    "adf.impl.module.algorithm.AStarPathPlanning");
            this.clustering = moduleManager.getModule(
                    "SampleSearch.Clustering.Ambulance",
                    "sample_team.module.algorithm.KMeansClustering");
        } else if (agentURN == FIRE_BRIGADE) {
            this.pathPlanning = moduleManager.getModule(
                    "SampleSearch.PathPlanning.Fire",
                    "adf.impl.module.algorithm.DijkstraPathPlanning");
            this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                    "sample_team.module.algorithm.KMeansClustering");
        } else if (agentURN == POLICE_FORCE) {
            this.pathPlanning = moduleManager.getModule(
                    "SampleSearch.PathPlanning.Police",
                    "adf.impl.module.algorithm.DijkstraPathPlanning");
            this.clustering = moduleManager.getModule(
                    "SampleSearch.Clustering.Police",
                    "sample_team.module.algorithm.KMeansClustering");
        }
        registerModule(this.clustering);
        registerModule(this.pathPlanning);
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        logger.debug("[Search] updateInfo at time " + agentInfo.getTime() + 
                    ", Position: " + agentInfo.getPosition() + 
                    ", Searched buildings: " + searchedBuildings.size() + 
                    ", Unsearched buildings: " + unsearchedBuildingIDs.size() +
                    ", Patrol targets: " + patrolTargets.size() +
                    ", Recoverable targets: " + recoverableTargets.size());
        super.updateInfo(messageManager);

        EntityID currentPosition = agentInfo.getPosition();
        StandardEntity currentEntity = worldInfo.getEntity(currentPosition);
        
        // 记录当前位置实体
        if (currentEntity != null) {
            searchedBuildings.add(currentEntity.getID());
        }
        
        // 记录当前位置所在区域
        if (currentEntity instanceof rescuecore2.standard.entities.Area) {
            rescuecore2.standard.entities.Area currentArea = (rescuecore2.standard.entities.Area) currentEntity;
            for (Edge edge : currentArea.getEdges()) {
                if (edge.isPassable()) {
                    EntityID neighbourID = edge.getNeighbour();
                    if (neighbourID != null) {
                        searchedBuildings.add(neighbourID);
                    }
                }
            }
        }

        this.unsearchedBuildingIDs
                .removeAll(this.worldInfo.getChanged().getChangedEntities());
        if (this.unsearchedBuildingIDs.isEmpty()) {
            this.reset();
            this.unsearchedBuildingIDs
                    .removeAll(this.worldInfo.getChanged().getChangedEntities());
        }
        // 检测是否到达建筑
        if (currentTargetBuilding != null && 
                currentTargetBuilding.equals(agentInfo.getPosition())) {
            searchedBuildings.add(currentTargetBuilding);
            unsearchedBuildingIDs.remove(currentTargetBuilding);
            currentTargetBuilding = null;
            logger.info("Marked building as searched: " + currentTargetBuilding);
        }

        updateSearchProgress();

        checkRecoverableTargets();
        
        // 处理接收到的消息
        List<CommunicationMessage> messages = messageManager.getReceivedMessageList();
        for (CommunicationMessage message : messages) {
            if (message instanceof MessageSay) {
                MessageSay sayMessage = (MessageSay) message;
                String text = sayMessage.getMessage();
                // 检查是否为消防队发来的救援消息
                if (text.startsWith("RESCUED:")) {
                    try {
                        int civilianIdValue = Integer.parseInt(text.substring(8));
                        EntityID civilianID = new EntityID(civilianIdValue);
                        rescuedByFireBrigade.add(civilianID);
                        logger.debug("Received RESCUED message for civilian: " + civilianID);
                        // 如果当前救援的平民是这个ID，则重置当前目标
                        if (currentRescueCivilianID != null && currentRescueCivilianID.equals(civilianID)) {
                            currentRescueCivilianID = null;
                            currentTargetBuilding = null;
                            result = null;
                            logger.info("Abandoning civilian due to fire brigade rescue: " + civilianID);
                        }
                    } catch (NumberFormatException e) {
                        logger.error("Failed to parse RESCUED message: " + text, e);
                    }
                }
            }
        }

        return this;
    }

    // 更新搜索进度
    private void updateSearchProgress() {
        // 动态移除已搜索或无效建筑
        Iterator<EntityID> iterator = unsearchedBuildingIDs.iterator();
        while (iterator.hasNext()) {
            EntityID id = iterator.next();
            StandardEntity entity = worldInfo.getEntity(id);
            if (entity instanceof Building) {
                //Building building = (Building) entity;
                // 条件1: 建筑已完全探索
                // 条件2: 建筑已毁坏无需搜索
                // 条件3: 其他单位已报告完成搜索
                // if (shouldRemoveFromSearchList(building)) {
                //     iterator.remove();
                //     logger.debug("Removed building from search list: " + id);
                // }
            }
        }
    }

    @Override
    public Search calc() {
        logger.debug("[Search] calc started at time " + agentInfo.getTime() + 
                    ", Current target: " + currentTargetBuilding);

        this.result = null;
        if (unsearchedBuildingIDs.isEmpty())
            return this;

        StandardEntityURN agentURN = agentInfo.me().getStandardURN(); // 获取代理类型

        // 新增：如果是消防员且确定了救援目标，发送RESCUED消息
        if (agentURN == FIRE_BRIGADE && currentRescueCivilianID != null) {
            sendRescuedMessage(currentRescueCivilianID);
        }
        logger.debug("unsearchedBuildingIDs: " + unsearchedBuildingIDs);
        this.pathPlanning.setFrom(this.agentInfo.getPosition());
        this.pathPlanning.setDestination(this.unsearchedBuildingIDs);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        logger.debug("best path is: " + path);

        // 记录原始目标建筑（用于后续恢复）
        EntityID originalTarget = null;
        if (path != null && !path.isEmpty()) {
            originalTarget = path.get(path.size() - 1);
        }

        logger.debug("[Search] Path planning result: " + (path != null ? path.size() + " steps" : "null"));
        
        // 当智能体为消防员和救护车时
        if ((agentURN == AMBULANCE_TEAM || agentURN == FIRE_BRIGADE) 
             && path != null && path.size() > 1) {
        // 检测路径上的第一个障碍物
        EntityID blockedRoad = findFirstBlockedRoadInPath(path);
        
        if (blockedRoad != null) {
            logger.debug("Detected blockade at: " + blockedRoad);
            
            if (originalTarget != null) {
                // 1. 暂时移出当前目标（非永久移除）
                unsearchedBuildingIDs.remove(originalTarget);
                
                // 2. 添加到待恢复列表（障碍清除后可恢复）
                addToRecoverableTargets(originalTarget, blockedRoad);
                
                logger.debug("Temporarily removed target: " + originalTarget);
            }
            
            // 3. 重新规划到剩余目标
            if (!unsearchedBuildingIDs.isEmpty()) {
                this.pathPlanning.setFrom(this.agentInfo.getPosition());
                this.pathPlanning.setDestination(this.unsearchedBuildingIDs);
                path = this.pathPlanning.calc().getResult();
                logger.debug("Replanned path: " + path);
            } else {
                path = null; // 无有效目标
            }
          }
        }

        // 非警察或无路径，保持原逻辑
        if (path != null && path.size() > 2) {
            this.result = path.get(path.size() - 3);
        } else if (path != null && path.size() > 0) {
            this.result = path.get(path.size() - 1);
        }
        logger.debug("chose: " + result);

        checkRecoverableTargets();// 检查恢复条件（每次计算时执行）
        
        // 当智能体为警察时
        if (agentURN == POLICE_FORCE) {

            // 优先处理视野内障碍物
            EntityID visibleBlockade = findVisibleBlockade();
            if (visibleBlockade != null) {
                logger.info("[Police] Processing visible blockade: " + visibleBlockade);
                this.result = visibleBlockade;
                processedBlockades.add(visibleBlockade); // 记录已处理
                return this;
            }

            // 1. 优先处理高优先级障碍
            EntityID blockadeTarget = findPriorityBlockade();
            if (blockadeTarget != null) {
                logger.info("[Police] Processing priority blockade: " + blockadeTarget);
                this.result = blockadeTarget;
                return this;
            }

            // 2. 没有障碍物时执行搜救任务
            EntityID rescueTarget = findRescueTarget();
            if (rescueTarget != null) {
                logger.info("[Police] Rescue target selected: " + rescueTarget);
                this.result = rescueTarget;
                return this;
            }

            // 3. 没有障碍物和搜救目标时执行巡逻
            if (this.patrolTargets.isEmpty()) {
                logger.debug("[Police] Generating new patrol route");
                generatePatrolRoute(); // 生成巡逻路线
            }

            // 4. 获取下一个巡逻点
            EntityID nextPatrolPoint = this.patrolTargets.poll();
            logger.debug("[Police] Next patrol point: " + nextPatrolPoint + 
                         " (Queue size: " + patrolTargets.size() + ")");
            if (nextPatrolPoint != null) {
                this.patrolTargets.add(nextPatrolPoint); // 循环队列
                this.result = nextPatrolPoint;
                logger.debug("[Police] Moving to patrol point: " + nextPatrolPoint);
            }else {
                logger.warn("[Police] No patrol points available!");
            }
        }

        logger.debug("[Search] Final target selected: " + result);

        return this;
    }

    // 发送RESCUED消息的方法
    private void sendRescuedMessage(EntityID civilianID) {
        try {
            String message = "RESCUED:" + civilianID.getValue();
            MessageSay sayMessage = new MessageSay(true, StandardMessagePriority.HIGH, message);
            messageManager.addMessage(sayMessage);
            logger.debug("Sent RESCUED message for civilian: " + civilianID);
        } catch (Exception e) {
            logger.error("Failed to send RESCUED message", e);
        }
    }

    // 寻找需要救援的人类目标
    private EntityID findRescueTarget() {
        // 1. 获取所有平民
        Collection<StandardEntity> civilians = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
        List<Human> validTargets = new ArrayList<>();
        
        // 2. 筛选有效目标
        for (StandardEntity entity : civilians) {
            if (!(entity instanceof Human)) continue;
            
            Human human = (Human) entity;
            
            // 使用与HumanDetector类似的条件判断
            if (!human.isHPDefined() || human.getHP() == 0) continue;
            if (!human.isPositionDefined()) continue;
            if (!human.isDamageDefined() || human.getDamage() == 0) continue;
            if (!human.isBuriednessDefined()) continue;
            
            // 排除已处理或无效的目标
            if (human.getBuriedness() == 0) continue;

            // 检查是否已被消防队接管
            if (rescuedByFireBrigade.contains(human.getID())) {
                logger.debug("Skipping civilian already rescued by fire brigade: " + human.getID());
                continue;
            }
            
            // 检查目标是否在聚类区域内
            StandardEntity position = worldInfo.getPosition(human);
            if (position == null) continue;
            
            int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
            Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);
            if (!clusterEntities.contains(position)) continue;
            
            // 检查目标是否在建筑物内且入口被阻挡（这种情况下警察需要先清理障碍）
            if (position instanceof Building) {
                Building building = (Building) position;
                if (hasBlockedEntrance(building)) {
                    // 如果入口被阻挡，警察应该优先清理障碍
                    continue;
                }
            }
            
            validTargets.add(human);
        }
        
        // 3. 按优先级排序
        if (!validTargets.isEmpty()) {
            validTargets.sort(new WeightedPrioritySorter(worldInfo, agentInfo.me()));
            Human selected = validTargets.get(0);
            // 发送消息给消防队
            sendRescueMessage(selected.getID(), selected.getPosition());
            currentRescueCivilianID = selected.getID(); // 记录当前救援的平民
            return selected.getPosition(); // 返回人类所在位置
        }
        
        return null;
    }

    // 发送救援消息给消防队
    private void sendRescueMessage(EntityID civilianID, EntityID positionID) {
        try {
            String message = "NEED_RESCUE:" + civilianID.getValue() + ":" + positionID.getValue();
            MessageSay sayMessage = new MessageSay(true, StandardMessagePriority.HIGH, message);
            messageManager.addMessage(sayMessage);
            logger.debug("Sent NEED_RESCUE message for civilian: " + civilianID);
        } catch (Exception e) {
            logger.error("Failed to send message", e);
        }
    }

    // 检查建筑物入口是否被阻挡（从SampleHumanDetector复制）
    private boolean hasBlockedEntrance(Building building) {
        for (Edge edge : getEntranceEdges(building)) {
            EntityID roadID = edge.getNeighbour();
            if (roadID == null) continue;
            
            StandardEntity road = worldInfo.getEntity(roadID);
            if (!(road instanceof Road)) continue;
            
            Road roadEntity = (Road) road;
            if (!roadEntity.isBlockadesDefined()) continue;
            
            for (EntityID blockadeID : roadEntity.getBlockades()) {
                StandardEntity entity = worldInfo.getEntity(blockadeID);
                if (!(entity instanceof Blockade)) continue;
                
                Blockade blockade = (Blockade) entity;
                if (coversEdge(blockade, edge)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 获取建筑物的入口边（从SampleHumanDetector复制）
    private List<Edge> getEntranceEdges(Building building) {
        List<Edge> entrances = new ArrayList<>();
        for (Edge edge : building.getEdges()) {
            if (edge.isPassable()) {
                entrances.add(edge);
            }
        }
        return entrances;
    }

    // 检查障碍物是否覆盖边（从SampleHumanDetector复制）
    private boolean coversEdge(Blockade blockade, Edge edge) {
        try {
            Shape blockadeShape = blockade.getShape();
            Area blockadeArea = new Area(blockadeShape);
            Line2D edgeLine = new Line2D.Double(
                edge.getStartX(), edge.getStartY(),
                edge.getEndX(), edge.getEndY()
            );
            return blockadeArea.intersects(edgeLine.getBounds2D());
        } catch (Exception e) {
            logger.error("Error checking edge coverage", e);
        }
        return false;
    }

    // 加权优先级排序器（从SampleHumanDetector复制）
    private class WeightedPrioritySorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        // 权重配置
        private static final double DISTANCE_WEIGHT = 0.5;
        private static final double HP_WEIGHT = 0.2;
        private static final double BURIEDNESS_WEIGHT = 0.15;
        private static final double DAMAGE_WEIGHT = 0.15;

        WeightedPrioritySorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        @Override
        public int compare(StandardEntity a, StandardEntity b) {
            double scoreA = calculatePriorityScore((Human) a);
            double scoreB = calculatePriorityScore((Human) b);
            return Double.compare(scoreB, scoreA);
        }

        private double calculatePriorityScore(Human human) {
            double distanceScore = calculateDistanceScore(human);
            double hpScore = calculateHpScore(human);
            double buriednessScore = calculateBuriednessScore(human);
            double damageScore = calculateDamageScore(human);

            return (distanceScore * DISTANCE_WEIGHT) +
                    (hpScore * HP_WEIGHT) +
                    (buriednessScore * BURIEDNESS_WEIGHT) +
                    (damageScore * DAMAGE_WEIGHT);
        }

        private double calculateDistanceScore(Human human) {
            int distance = this.worldInfo.getDistance(this.reference, human);
            return Math.exp(-distance / 50000.0);
        }

        private double calculateHpScore(Human human) {
            if (!human.isHPDefined()) return 0;
            int hp = human.getHP();
            return 1.0 - (hp / 10000.0);
        }

        private double calculateBuriednessScore(Human human) {
            if (!human.isBuriednessDefined()) return 0;
            int buriedness = human.getBuriedness();
            return buriedness / 100.0;
        }

        private double calculateDamageScore(Human human) {
            if (!human.isDamageDefined()) return 0;
            int damage = human.getDamage();
            return damage / 200.0;
        }
    }

    // 检测路径中的第一个障碍物
    private EntityID findFirstBlockedRoadInPath(List<EntityID> path) {
        // 从当前位置之后的下一个点开始检查（跳过当前位置）
        for (int i = 1; i < path.size(); i++) {
            EntityID eid = path.get(i);
            StandardEntity entity = worldInfo.getEntity(eid);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && 
                    !road.getBlockades().isEmpty()) {
                    return road.getID(); // 返回受阻道路ID
                }
            }
        }
        return null; // 无阻碍
    }

    // 添加到待恢复列表
    private void addToRecoverableTargets(EntityID target, EntityID blockage) {
        recoverableTargets.put(target, blockage);
    }

    // 检查可恢复目标
    private void checkRecoverableTargets() {
        Iterator<Map.Entry<EntityID, EntityID>> it = recoverableTargets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<EntityID, EntityID> entry = it.next();
            EntityID target = entry.getKey();
            EntityID blockage = entry.getValue();
            
            // 检查障碍物是否已清除
            StandardEntity road = worldInfo.getEntity(blockage);
            if (road instanceof Road) {
                Road r = (Road) road;
                if (!r.isBlockadesDefined() || r.getBlockades().isEmpty()) {
                    // 障碍已清除，恢复目标
                    unsearchedBuildingIDs.add(target);
                    it.remove();
                    logger.debug("Recovered target: " + target);
                }
            }
        }
    }

    // 检测视野内障碍物
    private EntityID findVisibleBlockade() {
        // 1. 定义视野范围（30米）
        int viewRange = 30000;
        
        // 2. 获取视野范围内的实体
        Collection<StandardEntity> visibleEntities = worldInfo.getObjectsInRange(
            agentInfo.getPosition(), viewRange);
        
        // 3. 筛选可见的障碍物
        for (StandardEntity entity : visibleEntities) {
            if (entity instanceof Road) {
                Road road = (Road) entity;
                // 检查道路是否有未处理的障碍物
                if (road.isBlockadesDefined() && 
                    !road.getBlockades().isEmpty() &&
                    !processedBlockades.contains(road.getID())) {
                    return road.getID();
                }
            }
        }
        return null;
    }

    // 生成环形巡逻路线
    private void generatePatrolRoute() {
        // 1. 获取聚类中心点
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());  
        
        // 2. 获取聚类区域内所有道路和建筑
        Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);

        List<Road> roads = clusterEntities.stream()
            .filter(e -> e instanceof Road)
            .map(e -> (Road)e)
            .collect(Collectors.toList());

        List<Building> buildings = clusterEntities.stream()
        .filter(e -> e instanceof Building && e.getStandardURN() != REFUGE)
        .map(e -> (Building) e)
        .collect(Collectors.toList());    
        
        // 3. 按探索价值排序（优先未搜索区域）
        roads.sort(Comparator.comparingInt(
            r -> calculateExplorationValue(r.getID())
        ));

        buildings.sort(Comparator.comparingInt(
            b -> calculateExplorationValue(b.getID())
        ));
        
        // 4. 创建混合巡逻点列表（道路和建筑）
        List<EntityID> patrolPoints = new ArrayList<>();
    
        // 添加未搜索的建筑（最多4个）
        buildings.stream()
            .filter(b -> !searchedBuildings.contains(b.getID()))
            .limit(4)
            .map(Building::getID)
            .forEach(patrolPoints::add);
        
        // 添加价值最高的道路（最多8个，包括远近不同的道路）
        int roadCount = Math.min(8, roads.size());
        for (int i = 0; i < roadCount; i++) {
            // 选择不同距离的道路：前2个最近，中间4个中等距离，最后2个最远
            int index;
            if (i < 2) index = i; // 最近
            else if (i < 6) index = i * roads.size() / 6; // 中等
            else index = roads.size() - (roadCount - i); // 最远
            
            if (index < roads.size()) {
                patrolPoints.add(roads.get(index).getID());
            }
        }
        
        // 5. 随机打乱顺序避免固定模式
        Collections.shuffle(patrolPoints);
        
        // 6. 加入巡逻队列
        patrolTargets.clear();
        patrolTargets.addAll(patrolPoints);
        
        // 7. 日志记录
        logger.debug("Generated expanded patrol route: " + patrolTargets);
    }

    // 计算区域的探索价值（数值越低表示越需要探索）
    private int calculateExplorationValue(EntityID areaID) {
        int value = 0;
        
        // 1. 优先未搜索区域：从未搜索过的区域价值最低
        if (!searchedBuildings.contains(areaID)) {
            value -= 1000; // 大幅降低值，使其排在前列
        }
        
        // 2. 考虑距离：较远的区域价值稍低
        int distance = worldInfo.getDistance(agentInfo.getPosition(), areaID);
        value += distance / 100; // 每100米增加1点值
        
        // 3. 上次访问时间：长时间未访问的区域价值低
        // （这里简化处理，实际可以记录每个区域的最后访问时间）
        
        return value;
    }



    // 查找高优先级障碍
    private EntityID findPriorityBlockade() {
        // 1. 检查通往避难所的路径
        Collection<EntityID> refugeIDs = worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
        if (!refugeIDs.isEmpty()) {
            pathPlanning.setFrom(agentInfo.getPosition());
            pathPlanning.setDestination(refugeIDs);
            List<EntityID> refugePath = pathPlanning.calc().getResult();
            
            EntityID blockade = findBlockadeInPath(refugePath);
            if (blockade != null) return blockade;
        }
        
        // 2. 检查通往未搜索建筑的路径
        for (EntityID buildingID : unsearchedBuildingIDs) {
            pathPlanning.setFrom(agentInfo.getPosition());
            pathPlanning.setDestination(Collections.singleton(buildingID));
            List<EntityID> path = pathPlanning.calc().getResult();
            
            EntityID blockade = findBlockadeInPath(path);
            if (blockade != null) return blockade;
        }

        // 新增：检查巡逻路径上的障碍
        for (EntityID patrolPoint : patrolTargets) {
            pathPlanning.setFrom(agentInfo.getPosition());
            pathPlanning.setDestination(patrolPoint);
            List<EntityID> path = pathPlanning.calc().getResult();
            EntityID blockade = findBlockadeInPath(path);
            if (blockade != null) return blockade;
        }
        
        return null;
    }
    
    // 路径障碍检查
    private EntityID findBlockadeInPath(List<EntityID> path) {
        if (path == null) return null;
        
        for (EntityID eid : path) {
            StandardEntity entity = worldInfo.getEntity(eid);
            if (entity instanceof Road) {
                Road road = (Road) entity;
                if (road.isBlockadesDefined() && 
                    !road.getBlockades().isEmpty() &&
                    !processedBlockades.contains(road.getID())) {
                    return road.getID();
                }
            }
        }
        return null;
    }

    private void reset() {
        this.unsearchedBuildingIDs.clear();
        this.searchedBuildings.clear(); // 重置已搜索记录
        this.processedBlockades.clear(); // 清除障碍物缓存
        
        int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> clusterEntities = this.clustering.getClusterEntities(clusterIndex);
        
        if (clusterEntities != null && !clusterEntities.isEmpty()) {
            for (StandardEntity entity : clusterEntities) {
                if (entity instanceof Building && entity.getStandardURN() != REFUGE
                    && !searchedBuildings.contains(entity.getID())) { // 排除已搜索
                    this.unsearchedBuildingIDs.add(entity.getID());
                }
            }
        } else {
            // 原始逻辑，但排除已搜索建筑
            for (EntityID id : this.worldInfo.getEntityIDsOfType(BUILDING, GAS_STATION, 
                    AMBULANCE_CENTRE, FIRE_STATION, POLICE_OFFICE)) {
                if (!searchedBuildings.contains(id)) {
                    this.unsearchedBuildingIDs.add(id);
                }
            }
        }

        if (agentInfo.me().getStandardURN() == POLICE_FORCE) {
        generatePatrolRoute(); // 初始化巡逻路线
    }
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }
}