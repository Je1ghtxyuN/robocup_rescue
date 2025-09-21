package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
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
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

import adf.core.agent.communication.standard.bundle.information.MessageCivilian; // 新增import
import java.util.LinkedList;
import java.util.Queue; 
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap; 


public class SampleHumanDetector extends HumanDetector {

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
    private static final int MIN_BURIEDNESS_THRESHOLD = 40; // 掩埋程度高于此值认为无效

    // 卡住检测相关字段
    private EntityID lastPosition;//上一回合位置
    private int stuckCount = 0;  // 被卡住的计数
    
    // 协调字段 - 记录已发送请求的位置和时间
    private static final Map<EntityID, Integer> sentHelpRequests = new HashMap<>();
    private static final int REQUEST_COOLDOWN = 10; // 请求冷却时间

    // 存储需要搬运的市民队列
    private Queue<EntityID> rescuedCivilians = new LinkedList<>();
    // 新增：存储警察报告的市民ID（用于去重）
    private Set<EntityID> policeReportedCivilianIds = new HashSet<>();

    // 存储最近选择的目标历史，用于协调多个消防员
    private static final Map<EntityID, Long> recentlyChosenTargets = new ConcurrentHashMap<>();
    private static final long TARGET_COOLDOWN = 20; // 目标冷却时间（时间步）
    private final Random random = new Random(); // 每个实例有自己的随机数生成器


    public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
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

        // 新增：清理过期的目标选择记录
        cleanupOldTargetChoices();

        // 处理来自消防队的市民消息和来自警察的市民消息
        processFireRescueMessages(messageManager);

        // 新增：处理来自警察的市民消息
        processPoliceCivilianMessages(messageManager);

        return this;
    }

    /**
     * 新增：处理来自警察的市民消息
     */
    private void processPoliceCivilianMessages(MessageManager messageManager) {
        // 获取收到的MessageCivilian消息
        List<CommunicationMessage> messages = messageManager.getReceivedMessageList(MessageCivilian.class);
        for (CommunicationMessage msg : messages) {
            MessageCivilian civilianMsg = (MessageCivilian) msg;
            EntityID civilianID = civilianMsg.getAgentID();
            // 记录警察报告的市民ID（用于后续去重）
            policeReportedCivilianIds.add(civilianID);
        }
    }

    // 清理过期的目标选择记录
    private void cleanupOldTargetChoices() {
        long currentTime = agentInfo.getTime();
        recentlyChosenTargets.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > TARGET_COOLDOWN);
    }

    // 检查是否被卡住，如果卡住太久就发送求助信息给警察
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
    
    // 清理过期的请求记录
    private void cleanupOldRequests() {
        int currentTime = agentInfo.getTime();
        sentHelpRequests.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > REQUEST_COOLDOWN);
    }

    // 发送道路求助请求给警察
    private void sendRoadHelpRequest(MessageManager messageManager, EntityID position) {
        
        StandardEntity entity = worldInfo.getEntity(position);
        
        if (entity instanceof Road) {
            Road road = (Road) entity;
            
            // 检查道路上是否有障碍物
            if (road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                // 获取第一个障碍物信息
                EntityID blockadeID = road.getBlockades().iterator().next();
                Blockade blockade = (Blockade) worldInfo.getEntity(blockadeID);
                
                if (blockade != null) {
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
                    
                    logger.debug("救护队发送求助信息: 道路 " + position + " 被障碍物 " + blockadeID + " 阻挡，请求警察清理！");
                }
            } else {
                logger.debug("道路 " + position + " 没有障碍物，可能是其他原因卡住");
            }
        }
    }

    /**
     * 处理来自消防队的市民消息
     */
    private void processFireRescueMessages(MessageManager messageManager) {
        // 获取收到的MessageCivilian消息
        List<CommunicationMessage> messages = messageManager.getReceivedMessageList(MessageCivilian.class);
        for (CommunicationMessage msg : messages) {
            MessageCivilian civilianMsg = (MessageCivilian) msg;
            EntityID civilianID = civilianMsg.getAgentID();
            StandardEntity entity = worldInfo.getEntity(civilianID);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                // 检查市民是否有效且掩埋度为0
                if (isValidHuman(human) && human.isBuriednessDefined() && human.getBuriedness() == 0) {
                    // 避免重复添加
                    if (!rescuedCivilians.contains(civilianID)) {
                        rescuedCivilians.add(civilianID);
                        logger.debug("收到消防队救援消息，市民 " + civilianID + " 已获救，需要搬运");
                    }
                }
            }
        }
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

        // 优先处理来自消防队消息的市民
        if (this.result == null && !rescuedCivilians.isEmpty()) {
            EntityID nextCivilian = rescuedCivilians.poll();
            // 再次检查市民是否有效
            StandardEntity entity = worldInfo.getEntity(nextCivilian);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                if (isValidHuman(human) && human.isBuriednessDefined() && human.getBuriedness() == 0) {
                    this.result = nextCivilian;
                    logger.debug("选择来自消防队消息的市民: " + nextCivilian);
                }
            }
        }

        if (this.result == null) {
            this.result = calcTarget();
        }
        return this;
    }

    private EntityID calcTarget() {
        // 如果是消防员，并且当前位置是建筑物，则优先处理同一建筑物内的伤员
        if (agentInfo.me().getStandardURN() == StandardEntityURN.FIRE_BRIGADE) {
            StandardEntity positionEntity = worldInfo.getEntity(agentInfo.getPosition());
            if (positionEntity instanceof Building) {
                Building currentBuilding = (Building) positionEntity;
                List<Human> civiliansInBuilding = getCiviliansInBuilding(currentBuilding);
                List<Human> validCiviliansInBuilding = filterRescueTargets(civiliansInBuilding); // 过滤有效伤员
                if (!validCiviliansInBuilding.isEmpty()) {
                    // 使用加权评分系统选择建筑物内的最佳目标
                    validCiviliansInBuilding.sort(new WeightedPrioritySorter(this.worldInfo, this.agentInfo.me()));
                    Human selected = coordinatedTargetSelection(validCiviliansInBuilding);
                    logger.debug("消防在建筑: " + currentBuilding.getID() + ", 选择市民: " + selected.getID());
                    return selected.getID();
                }
            }
        }

        //处理全局目标
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
        Human selected = coordinatedTargetSelection(targets);
        logger.debug("选择目标: " + selected + " with ID: " + selected.getID());

        return selected.getID();
    }

    // 新增方法：协调目标选择，避免所有消防员选择同一个目标
    private Human coordinatedTargetSelection(List<Human> sortedTargets) {
        if (sortedTargets.isEmpty()) {
            return null;
        }
        
        // 如果只有一个目标，直接返回
        if (sortedTargets.size() == 1) {
            return sortedTargets.get(0);
        }
        
        // 获取当前时间
        long currentTime = agentInfo.getTime();
        
        // 检查前两个目标是否最近被其他消防员选择过
        Human firstChoice = sortedTargets.get(0);
        Human secondChoice = sortedTargets.get(1);
        
        boolean firstRecentlyChosen = recentlyChosenTargets.containsKey(firstChoice.getID());
        boolean secondRecentlyChosen = recentlyChosenTargets.containsKey(secondChoice.getID());
        
        // 如果两个目标最近都被选择过，则随机选择一个
        if (firstRecentlyChosen && secondRecentlyChosen) {
            int randomIndex = random.nextInt(sortedTargets.size());
            Human selected = sortedTargets.get(randomIndex);
            recentlyChosenTargets.put(selected.getID(), currentTime);
            return selected;
        }
        // 如果只有第一个目标最近被选择过，有50%概率选择第二个目标
        else if (firstRecentlyChosen) {
            if (random.nextDouble() < 0.5) {
                recentlyChosenTargets.put(secondChoice.getID(), currentTime);
                return secondChoice;
            } else {
                recentlyChosenTargets.put(firstChoice.getID(), currentTime);
                return firstChoice;
            }
        }
        // 如果只有第二个目标最近被选择过，有50%概率选择第一个目标
        else if (secondRecentlyChosen) {
            if (random.nextDouble() < 0.5) {
                recentlyChosenTargets.put(firstChoice.getID(), currentTime);
                return firstChoice;
            } else {
                recentlyChosenTargets.put(secondChoice.getID(), currentTime);
                return secondChoice;
            }
        }
        // 如果两个目标都没有被最近选择过，有80%概率选择第一个，20%概率选择第二个
        else {
            if (random.nextDouble() < 0.8) {
                recentlyChosenTargets.put(firstChoice.getID(), currentTime);
                return firstChoice;
            } else {
                recentlyChosenTargets.put(secondChoice.getID(), currentTime);
                return secondChoice;
            }
        }
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
        // 新增：添加警察报告且damage>0的市民
        for (EntityID civilianID : policeReportedCivilianIds) {
            StandardEntity entity = worldInfo.getEntity(civilianID);
            if (entity instanceof Human) {
                Human human = (Human) entity;
                // 检查市民是否有效且damage>0
                if (isValidHuman(human) && human.isDamageDefined() && human.getDamage() > 0) {
                    // 避免重复添加
                    boolean alreadyExists = false;
                    for (Human existing : rescueTargets) {
                        if (existing.getID().equals(civilianID)) {
                            alreadyExists = true;
                            break;
                        }
                    }
                    if (!alreadyExists) {
                        rescueTargets.add(human);
                        logger.debug("将警察报告的市民添加到救援目标列表: " + civilianID);
                    }
                }
            }
        }
        logger.debug("过滤后有效目标数量: " + rescueTargets.size());
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
        if (!target.isHPDefined() || target.getHP() == 0){        
            logger.debug("无效目标: " + target + " HP未定义或为0");
            return false;
        }

        if (!target.isPositionDefined())
            return false;
        if (!target.isDamageDefined() || target.getDamage() == 0)
            return false;
        if (!target.isBuriednessDefined())
            return false;

        // 综合属性检查
        if (target.getDamage() < MIN_DAMAGE_THRESHOLD && target.getHP() < MIN_HP_THRESHOLD&& target.getBuriedness() > MIN_BURIEDNESS_THRESHOLD ){
            logger.debug("不值得救援的目标: " + target);
            return false;
        }

        // 只有消防队才检查掩埋程度是否为0
        if (agentInfo.me().getStandardURN() == StandardEntityURN.FIRE_BRIGADE) {
            if (target.getBuriedness() == 0) {
                logger.debug("掩埋程度为0,消防队可以走了 " + target);
            return false;
            }
        }

        StandardEntity position = worldInfo.getPosition(target);
        if (position == null)
            return false;


       if (position instanceof Building && hasBlockedEntrance((Building) position)) {
            logger.debug("建筑物入口挡着了： " + target);
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

    // 新增方法：获取建筑物内的市民列表
    private List<Human> getCiviliansInBuilding(Building building) {
    List<Human> civilians = new ArrayList<>();
    Collection<StandardEntity> civiliansEntities = worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN);
    for (StandardEntity entity : civiliansEntities) {
        Human human = (Human) entity;
        if (human.isPositionDefined() && human.getPosition().equals(building.getID())) {
            civilians.add(human);
        }
    }
    logger.debug("建筑物 " + building.getID() + " 内的市民数量: " + civilians.size());
    return civilians;
}
}