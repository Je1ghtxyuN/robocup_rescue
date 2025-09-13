package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import sample_team.module.message.MessageSay;

import java.awt.geom.Line2D;
import java.awt.geom.Area;
import java.awt.Shape;

public class SampleHumanDetector extends HumanDetector {

    private Clustering clustering;
    private EntityID result;
    private Logger logger;

    private MessageManager messageManager; // 新增：用于发送消息
    private Set<EntityID> reportedThisStep = new HashSet<>(); // 新增：记录当前时间步已报告的建筑物

    // 属性最大值常量
    private static final int MAX_HP = 10000;
    private static final int MAX_DAMAGE = 200;
    private static final int MAX_BURIEDNESS = 100;

    // 可调节的阈值参数inValidHuman
    private static final int MIN_HP_THRESHOLD = 1000;    // HP低于此值认为无效
    private static final int MIN_DAMAGE_THRESHOLD = 50;  // 伤害低于此值认为无效
    private static final int MIN_BURIEDNESS_THRESHOLD = 30; // 掩埋程度高于此值认为无效

    private Map<EntityID, EntityID> neededRescueCivilians = new HashMap<>(); // 警察发现的需要救援的平民
    private Set<EntityID> blockedBuildings = new HashSet<>(); // 有障碍物的建筑
    private Map<EntityID, Integer> fireAlerts = new HashMap<>(); // 火情警报

    public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        logger = DefaultLogger.getLogger(agentInfo.me());
        this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                "adf.impl.module.algorithm.KMeansClustering");
        registerModule(this.clustering);
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        this.messageManager = messageManager; // 保存MessageManager
        reportedThisStep.clear(); // 每步清除已报告集合
        logger.debug("Time:" + agentInfo.getTime());
        super.updateInfo(messageManager);

        // 处理接收到的信息
        List<CommunicationMessage> messages = messageManager.getReceivedMessageList();
        for (CommunicationMessage message : messages) {
            // 检查是否是AKSay消息
            if (message instanceof MessageSay) {
                MessageSay sayMessage = (MessageSay) message;
                String text = sayMessage.getMessage();
                
                // 处理警察发送的NEED_RESCUE信息
                if (text.startsWith("NEED_RESCUE:")) {
                    try {
                        String[] parts = text.split(":");
                        int civilianIdValue = Integer.parseInt(parts[1]);
                        int positionIdValue = Integer.parseInt(parts[2]);
                        EntityID civilianID = new EntityID(civilianIdValue);
                        EntityID positionID = new EntityID(positionIdValue);
                        
                        // 记录警察发现的需要救援的平民
                        neededRescueCivilians.put(civilianID, positionID);
                        logger.debug("Received NEED_RESCUE from police for civilian: " + civilianID);
                    } catch (Exception e) {
                        logger.error("Failed to parse NEED_RESCUE message: " + text, e);
                    }
                }

                // 处理救护车发送的BLOCKADE信息
                else if (text.startsWith("BLOCKADE:")) {
                    try {
                        int buildingIdValue = Integer.parseInt(text.substring(9));
                        EntityID buildingID = new EntityID(buildingIdValue);
                        
                        // 记录有障碍物的建筑，避免前往
                        blockedBuildings.add(buildingID);
                        logger.debug("Received BLOCKADE alert for building: " + buildingID);
                    } catch (NumberFormatException e) {
                        logger.error("Failed to parse BLOCKADE message: " + text, e);
                    }
                }

                // 消防队之间的协调信息
                else if (text.startsWith("FIRE_ALERT:")) {
                    try {
                        String[] parts = text.split(":");
                        int buildingIdValue = Integer.parseInt(parts[1]);
                        int fireLevel = Integer.parseInt(parts[2]);
                        EntityID buildingID = new EntityID(buildingIdValue);
                        
                        // 处理火情警报
                        handleFireAlert(buildingID, fireLevel);
                    } catch (Exception e) {
                        logger.error("Failed to parse FIRE_ALERT message: " + text, e);
                    }
                }
            }
        }

        return this;
    }

    // 处理火情警报
    private void handleFireAlert(EntityID buildingID, int fireLevel) {
        fireAlerts.put(buildingID, fireLevel);
        logger.debug("Received fire alert for building: " + buildingID + ", level: " + fireLevel);
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
        private Map<EntityID, EntityID> neededRescueCivilians; // 警察报告的需救援平民

        // 权重配置 - 可以根据实际需求调整这些值
        private static final double DISTANCE_WEIGHT = 0.5;
        private static final double HP_WEIGHT = 0.2;
        private static final double BURIEDNESS_WEIGHT = 0.15;
        private static final double DAMAGE_WEIGHT = 0.15;
        private static final double POLICE_REPORT_WEIGHT = 1.8;

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

             // 新增：检查是否是警察报告的需救援平民
            double policeReportBonus = neededRescueCivilians.containsKey(human.getID()) ? 
                                    POLICE_REPORT_WEIGHT : 0.0;

            // 综合加权分数
            return  policeReportBonus + (distanceScore * DISTANCE_WEIGHT) +
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
            // 新增：发送广播消息
            if (!reportedThisStep.contains(position.getID())) {
                sendBlockadeMessage(position.getID());
                reportedThisStep.add(position.getID());
            }
            logger.debug("Invalid due to blocked entrance: " + target);
            return false;
        }

        StandardEntityURN positionURN = position.getStandardURN();
        if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
            return false;

        return true;
    }

     // 新增方法：发送障碍物广播消息
    private void sendBlockadeMessage(EntityID buildingID) {
        try {
            String message = "BLOCKADE:" + buildingID.getValue();
            MessageSay sayMessage = new MessageSay(true, message);
            messageManager.addMessage(sayMessage);
            logger.debug("Sent blockade alert for building: " + buildingID);
        } catch (Exception e) {
            logger.error("Failed to send message", e);
        }
    }

    // 检查建筑物入口是否被阻挡
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