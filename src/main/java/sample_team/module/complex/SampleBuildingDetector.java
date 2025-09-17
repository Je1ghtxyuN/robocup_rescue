package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.apache.log4j.Logger;

import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

public class SampleBuildingDetector extends HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private Logger logger;

    // 属性最大值常量
    private static final int MAX_HP = 10000;
    private static final int MAX_DAMAGE = 200;
    private static final int MAX_BURIEDNESS = 100;

    // 可调节的阈值参数inValidHuman
    private static final int MIN_HP_THRESHOLD = 1000;    // HP低于此值认为无效
    private static final int MIN_DAMAGE_THRESHOLD = 50;  // 伤害低于此值认为无效
    private static final int MIN_BURIEDNESS_THRESHOLD = 30; // 掩埋程度高于此值认为无效

    // 新增：卡住检测相关字段
    private EntityID lastPosition;//上一回合位置
    private int stuckCount = 0;  // 被卡住的计数
    
    // 新增：协调字段 - 记录已发送请求的位置和时间
    private static final Map<EntityID, Integer> sentHelpRequests = new HashMap<>();
    private static final int REQUEST_COOLDOWN = 10; // 请求冷却时间

    public SampleBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                "adf.impl.module.algorithm.KMeansClustering");
        registerModule(this.clustering);
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        logger.debug("Time:" + agentInfo.getTime());
        super.updateInfo(messageManager);
        // 检查是否被卡住并发送求助信息
        checkStuckAndRequestHelp(messageManager);
        return this;
    }

    /**
     * 检查是否被卡住，如果卡住太久就发送求助信息给警察
     */
    private void checkStuckAndRequestHelp(MessageManager messageManager) {
        EntityID currentPosition = agentInfo.getPosition();
        logger.debug("上一次位置: " + lastPosition + " ,当前位置："+currentPosition);
        // 检查是否移动了
        if (lastPosition != null && lastPosition.equals(currentPosition)) {
            stuckCount++;
            logger.debug("智能体被卡住: " + stuckCount + " 次");
        } else {
            if (stuckCount > 0) {
                logger.debug("智能体重新开始移动，重置计数器");
            }
            stuckCount = 0;
        }
        
        // 如果被卡住超过2个时间步，就发送道路信息给警察求助
        if (stuckCount >= 2) {
            sendRoadHelpRequest(messageManager, currentPosition);
            stuckCount = 0;  // 重置计数器，避免重复发送
        }
        
        lastPosition = currentPosition;
        
        // 清理过期的请求记录
        cleanupOldRequests();
    }
    
    /**
     * 清理过期的请求记录
     */
    private void cleanupOldRequests() {
        int currentTime = agentInfo.getTime();
        sentHelpRequests.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > REQUEST_COOLDOWN);
    }

    /**
     * 发送道路求助请求给警察
     */
    private void sendRoadHelpRequest(MessageManager messageManager, EntityID position) {
        // 检查是否已有其他救援单位发送了相同位置的请求
        if (sentHelpRequests.containsKey(position)) {
            logger.debug("已有其他救援单位请求处理位置 " + position + "，跳过发送");
            return;
        }
        
        StandardEntity entity = worldInfo.getEntity(position);
        
        if (entity instanceof Road) {
            Road road = (Road) entity;
            
            // 检查道路上是否有障碍物
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                // 获取第一个障碍物信息
                EntityID blockadeID = road.getBlockades().iterator().next();
                Blockade blockade = (Blockade) worldInfo.getEntity(blockadeID);
                
                if (blockade != null) {
                    // 检查是否有消防员在同一位置
                    boolean hasFirefighterAtLocation = checkFirefighterAtLocation(position);
                    
                    // 如果有消防员在同一位置，让消防员处理
                    if (hasFirefighterAtLocation) {
                        logger.debug("位置 " + position + " 已有消防员处理，跳过发送");
                        return;
                    }
                    
                    // 记录已发送的请求
                    sentHelpRequests.put(position, agentInfo.getTime());
                    
                    // 创建道路信息消息，包含障碍物信息
                    MessageRoad roadMessage = new MessageRoad(
                        true,  // 使用无线电通道
                        StandardMessagePriority.HIGH,  // 高优先级
                        road,    // 道路信息
                        blockade, // 障碍物信息
                        false,   // 道路不可通行
                        true     // 发送障碍物位置信息
                    );
                    
                    // 发送消息
                    messageManager.addMessage(roadMessage);
                    
                    logger.warn("救护队发送求助信息: 道路 " + position + " 被障碍物 " + blockadeID + " 阻挡，请求警察清理！");
                }
            } else {
                logger.debug("道路 " + position + " 没有障碍物，可能是其他原因卡住");
            }
        }
    }
    
    /**
     * 检查是否有消防员在同一位置
     */
    private boolean checkFirefighterAtLocation(EntityID position) {
        for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
            // 确保实体是Human类型（FireBrigade是Human的子类）
            if (entity instanceof Human) {
                Human firefighter = (Human) entity;
                if (firefighter.isPositionDefined() && 
                    firefighter.getPosition().equals(position)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public HumanDetector calc() {
        Human transportHuman = this.agentInfo.someoneOnBoard();
        if (transportHuman != null) {
            logger.debug("someoneOnBoard:" + transportHuman);
            this.result = transportHuman.getID();
            return this;
        }
        if (this.result != null) {
            Human target = (Human) this.worldInfo.getEntity(this.result);
            if (!isValidHuman(target)) {
                logger.debug("Invalid Human:" + target + " ==>reset target");
                this.result = null;
            }
        }
        if (this.result == null) {
            this.result = calcTarget();
        }
        return this;
    }

    private EntityID calcTarget() {
        List<Human> rescueTargets = filterRescueTargets(
                this.worldInfo.getEntitiesOfType(CIVILIAN));
        List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
        List<Human> targets = rescueTargetsInCluster.isEmpty() ? rescueTargets : rescueTargetsInCluster;

        logger.debug("Potential targets count: " + targets.size());

        if (targets.isEmpty()) {
            return null;
        }

        // 使用加权评分系统选择最佳目标
        targets.sort(new WeightedPrioritySorter(this.worldInfo, this.agentInfo.me()));
        Human selected = targets.get(0);
        logger.debug("Selected target: " + selected + " with ID: " + selected.getID());

        return selected.getID();
    }

    // 新的加权优先级排序器
    private class WeightedPrioritySorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        // 权重配置 - 可以根据实际需求调整这些值
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

            // 分数越高表示优先级越高，所以按降序排列
            return Double.compare(scoreB, scoreA);
        }

        private double calculatePriorityScore(Human human) {
            // 计算距离分数（距离越近分数越高）
            double distanceScore = calculateDistanceScore(human);

            // 计算血量分数（血量越低分数越高）
            double hpScore = calculateHpScore(human);

            // 计算掩埋程度分数（掩埋程度越高分数越高）
            double buriednessScore = calculateBuriednessScore(human);

            // 计算伤害分数（伤害越高分数越高）
            double damageScore = calculateDamageScore(human);

            // 综合加权分数
            return (distanceScore * DISTANCE_WEIGHT) +
                    (hpScore * HP_WEIGHT) +
                    (buriednessScore * BURIEDNESS_WEIGHT) +
                    (damageScore * DAMAGE_WEIGHT);
        }

        private double calculateDistanceScore(Human human) {
            int distance = this.worldInfo.getDistance(this.reference, human);
            // 使用指数衰减函数，距离越近分数越高
            // 调整分母以控制衰减速度，这里使用50000作为基准值
            return Math.exp(-distance / 50000.0);
        }

        private double calculateHpScore(Human human) {
            if (!human.isHPDefined()) return 0;

            int hp = human.getHP();
            // 血量越低，优先级越高（使用线性函数）
            // HP范围0-10000，归一化到0-1范围
            return 1.0 - (hp / (double) MAX_HP);
        }

        private double calculateBuriednessScore(Human human) {
            if (!human.isBuriednessDefined()) return 0;

            int buriedness = human.getBuriedness();
            // 掩埋程度越高，优先级越高
            // 掩埋程度范围0-100，归一化到0-1范围
            return buriedness / (double) MAX_BURIEDNESS;
        }

        private double calculateDamageScore(Human human) {
            if (!human.isDamageDefined()) return 0;

            int damage = human.getDamage();
            // 伤害越高，优先级越高
            // 伤害范围0-10000，归一化到0-1范围
            return damage / (double) MAX_DAMAGE;
        }
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {
        List<Human> rescueTargets = new ArrayList<>();
        for (StandardEntity next : list) {
            if (!(next instanceof Human))
                continue;
            Human h = (Human) next;
            if (!isValidHuman(h))
                continue;
            if (h.getBuriedness() == 0)
                continue;
            rescueTargets.add(h);
        }
        return rescueTargets;
    }

    private List<Human> filterInCluster(Collection<? extends StandardEntity> entities) {
        int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
        List<Human> filter = new ArrayList<>();
        HashSet<StandardEntity> inCluster = new HashSet<>(
                clustering.getClusterEntities(clusterIndex));
        for (StandardEntity next : entities) {
            if (!(next instanceof Human))
                continue;
            Human h = (Human) next;
            if (!h.isPositionDefined())
                continue;
            StandardEntity position = this.worldInfo.getPosition(h);
            if (position == null)
                continue;
            if (!inCluster.contains(position))
                continue;
            filter.add(h);
        }
        return filter;
    }

    private boolean isValidHuman(StandardEntity entity) {
        if (entity == null)
            return false;
        if (!(entity instanceof Human))
            return false;

        Human target = (Human) entity;
        if (!target.isHPDefined() || target.getHP() == 0)
            return false;
        if (!target.isPositionDefined())
            return false;
        if (!target.isDamageDefined() || target.getDamage() == 0)
            return false;
        if (!target.isBuriednessDefined())
            return false;

        // 综合属性检查
        if (target.getDamage() < MIN_DAMAGE_THRESHOLD && target.getHP() < MIN_HP_THRESHOLD&& target.getBuriedness() > MIN_BURIEDNESS_THRESHOLD ){
            logger.debug("Invalid due to low damage and HP: " + target);
            return false;
        }

        if (target.getBuriedness() == 0){
            logger.debug("Invalid due to low buriedness: " + target);
            return false;
        }

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null)
            return false;


       if (position instanceof Building && hasBlockedEntrance((Building) position)) {
            logger.debug("Invalid due to blocked entrance: " + target);
            return false;
        }
        StandardEntityURN positionURN = position.getStandardURN();
        if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
            return false;

        return true;
    }

        // 新增方法：检查建筑物入口是否被阻挡
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

    // 新增方法：获取建筑物的入口边
    private List<Edge> getEntranceEdges(Building building) {
        List<Edge> entrances = new ArrayList<>();
        for (Edge edge : building.getEdges()) {
            if (edge.isPassable()) {
                entrances.add(edge);
            }
        }
        return entrances;
    }

    // 新增方法：检查障碍物是否覆盖边
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
}