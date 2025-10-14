package SEU.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.algorithm.StaticClustering;
import adf.core.component.module.complex.RoadDetector;
import adf.core.debug.DefaultLogger;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.*;

/**
 * SEU 骨架 + AIT 决策粒度（tasks 1~8 级）+ 7 种优先级源
 * 协作机制保持 SEU 原样：仅 MessagePoliceForce 互斥 + Voice-Help 响应
 * 长期探索 & 防重复逻辑与 SEU 完全一致
 */
public class TestRoadDetector extends RoadDetector {

    /* -------------------- 运行期状态 -------------------- */
    private Logger logger = DefaultLogger.getLogger(agentInfo.me());

    private EntityID result;                // 最终选定的目标 Area
    private int count = 0;                  // 在同一位置停留计数（SEU 防堵）
    private EntityID longTermTarget;        // 远程开荒建筑
    private boolean isLongTermSearch = false;

    /* -------------------- 长期探索 & 防重复 --------------- */
    private Set<EntityID> openedAreas = new HashSet<>();
    private Set<Integer> historyClusters = new HashSet<>();

    /* -------------------- 7 种优先级源 ------------------- */
    private Map<EntityID, Integer> tasks = new HashMap<>(); // key=AreaID, value=优先级 1~8（1最高）
    private Set<EntityID> priorityRoads = new HashSet<>();  // SEU 的原始 priorityRoads
    private Set<EntityID> targetAreas = new HashSet<>();    // 当前堵塞区域
    private Set<EntityID> refugeLocation = new HashSet<>(); // 与 Refuge 相邻堵塞道路

    /* -------------------- 依赖模块（仅2个）---------------- */
    private PathPlanning pathPlanning;
    private Clustering clustering;

    public TestRoadDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
                                 ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.pathPlanning = moduleManager.getModule(
                "SampleRoadDetector.PathPlanning");
        this.clustering = moduleManager.getModule(
                "SampleRoadDetector.Clustering");
        registerModule(this.pathPlanning);
        registerModule(this.clustering);
        this.result = null;
    }

    /* **********************************************************************
     * 主决策入口：保持 SEU 的“长期探索+防重复”骨架，只是把【选目标】换成 AIT 式
     * **********************************************************************/
    @Override
    public RoadDetector calc() {
        logger.debug("priorityRoads=" + priorityRoads);
        logger.debug("targetAreas=" + targetAreas);
        logger.debug("openedAreas=" + openedAreas);

        EntityID positionID = this.agentInfo.getPosition();
        Human me = (Human) this.agentInfo.me();

        /* ---- 1. 长期探索到达判定 ---- */
        if (positionID.equals(longTermTarget)) {
            isLongTermSearch = false;
        }
        if (longTermTarget != null && isLongTermSearch) {
            pathPlanning.setFrom(positionID);
            pathPlanning.setDestination(longTermTarget);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && !path.isEmpty()) {
                result = path.get(path.size() - 1);
            }
            return this;
        }

        /* ---- 2. 血量低回家 / 巡逻 ---- */
        if (me.getHP() <= 2000) {
            result = nearestRefuge(positionID);
            return this;
        }
        if (me.getHP() <= 5000) {
            result = null; // 巡逻
            return this;
        }

        /* ---- 3. 防重复：同一位置停留6次强制换区 ---- */
        if (result != null) count++;
        if (count == 6) {
            count = 0;
            targetAreas.remove(positionID);
            result = nearestInSet(positionID, targetAreas);
            return this;
        }

        /* ---- 4. 目标为空时重新生成 AIT 式任务池 ---- */
        if (result == null) {
            count = 0;
            rebuildTasks();          // ← 这里换成 AIT 的 7 种源
            result = selectBestTask(); // ← AIT 式：先优先级再距离
            if (result == null) {      // 任务池也空 → 远程开荒
                result = selectLongTermTarget(positionID);
                isLongTermSearch = (result != null);
            }
        }
        return this;
    }

    /* -------------------- 对外接口 -------------------- */
    @Override
    public EntityID getTarget() {
        return result;
    }

    /* **********************************************************************
     * updateInfo：保持 SEU 原有协作 + 语音 Help + 互斥逻辑
     * **********************************************************************/
    @Override
    public RoadDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) return this;

        /* ---- 第1步：初始化3个集合（与 SEU 完全一致）---- */
        if (agentInfo.getTime() == 1) {
            initPriorityAndTarget();
        } else {
            refreshTargetAreas();          // 动态堵塞
            refreshPriorityRoads();        // 动态优先
            addCivilianPaths();            //  civilian → nearest refuge 路径
            addVoiceHelp();                //  "Help"/"Ouch" 语音
        }

        /* ---- 第2步：SEU 原始消息协作（互斥 + CommandPolice）---- */
        handleCollaboration(messageManager);

        /* ---- 第3步：清理已完工区域（SEU 原逻辑）---- */
        for (EntityID id : openedAreas) {
            priorityRoads.remove(id);
            targetAreas.remove(id);
            tasks.remove(id);
        }
        openedAreas.clear();

        /* ---- 第4步：当前 Road 若已无障碍则本周期任务直接完成 ---- */
        StandardEntity pos = this.worldInfo.getEntity(agentInfo.getPosition());
        if (pos instanceof Road road) {
            if (road.isBlockadesDefined() && road.getBlockades().isEmpty()) {
                tasks.remove(road.getID());
            }
        }
        return this;
    }

    /* ========================================================================
     * 以下全部是私有工具方法，中文注释已写死，可直接阅读
     * ======================================================================== */

    /* ---------------- 1. 7 种优先级源生成 ---------------- */
    private void rebuildTasks() {
        tasks.clear();
        int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
        Collection<EntityID> myCluster = clustering.getClusterEntityIDs(clusterIndex);

        /* 1. 自身 cluster 内所有 Area 优先级 8 */
        myCluster.forEach(id -> putTask(id, 8));

        /* 2. 与 Refuge 相邻且堵塞的 Road 优先级 7 */
        refugeLocation.forEach(id -> putTask(id, 7));

        /* 3. 与 Building(GasStation 包含在内) 相邻且堵塞的 Road 优先级 6 */
        for (StandardEntity e : worldInfo.getEntitiesOfType(BUILDING, GAS_STATION)) {
            for (EntityID n : ((Building) e).getNeighbours()) {
                StandardEntity ne = worldInfo.getEntity(n);
                if (ne instanceof Road road && road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    putTask(n, 6);
                }
            }
        }

        /* 4. 高速道路（若配置了 highways 模块）优先级 5 */
        // 这里用 StaticClustering 示例，若未配置则跳过
        try {
            StaticClustering highways = (StaticClustering) moduleManager.getModule("SEU.RoadDetector.Highways");
            highways.getClusterEntityIDs(0).forEach(id -> putTask(id, 5));
        } catch (Exception ignore) {/* 没配就跳过 */}

        /* 5. 其他 AT/FB 初始建筑相邻堵塞 Road 优先级 4 */
        for (StandardEntity e : worldInfo.getEntitiesOfType(FIRE_BRIGADE, AMBULANCE_TEAM)) {
            EntityID pos = e.getID();
            if (worldInfo.getEntity(pos) instanceof Building b) {
                for (EntityID n : b.getNeighbours()) {
                    StandardEntity ne = worldInfo.getEntity(n);
                    if (ne instanceof Road road && road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                        putTask(n, 4);
                    }
                }
            }
        }

        /* 6. 当前堵塞区域 targetAreas 优先级 3 */
        targetAreas.forEach(id -> putTask(id, 3));

        /* 7. 优先级道路 priorityRoads 优先级 2 */
        priorityRoads.forEach(id -> putTask(id, 2));

        /* 8. Voice-Help 响应（动态加入）优先级 1 最高 */
        addVoiceHelp();
    }

    private void putTask(EntityID area, int priority) {
        tasks.merge(area, priority, (oldVal, newVal) -> Math.min(oldVal, newVal));
    }

    /* ---------------- 2. AIT 式双比较器选目标 ---------------- */
    private EntityID selectBestTask() {
        if (tasks.isEmpty()) return null;

        EntityID position = agentInfo.getPosition();
        /* 先按优先级升序，再按真实路径距离升序，取 Top5 再比一次距离 */
        return tasks.keySet().stream()
            .sorted(Comparator.comparingInt((EntityID id) -> tasks.get(id))
                    .thenComparingDouble(id -> computePathDistance(position, id)))
            .limit(5)
            .min(Comparator.comparingDouble(id -> computePathDistance(position, id)))
            .orElse(null);
    }

    private double computePathDistance(EntityID from, EntityID to) {
        pathPlanning.setFrom(from);
        pathPlanning.setDestination(to);
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path == null || path.isEmpty()) return Double.MAX_VALUE;
        double dist = 0;
        for (int i = 1; i < path.size(); i++) {
            dist += worldInfo.getDistance(path.get(i - 1), path.get(i));
        }
        return dist;
    }

    /* ---------------- 3. 长期探索（SEU 原版）---------------- */
    private EntityID selectLongTermTarget(EntityID positionID) {
        List<Integer> allClusters = new ArrayList<>();
        for (int i = 0; i < clustering.getClusterNumber(); i++) allClusters.add(i);
        allClusters.removeAll(historyClusters);

        Collection<EntityID> allBuildings = new ArrayList<>();
        if (!allClusters.isEmpty()) {
            for (int i : allClusters) {
                for (EntityID e : clustering.getClusterEntityIDs(i)) {
                    if (worldInfo.getEntity(e) instanceof Building) allBuildings.add(e);
                }
            }
        } else {
            allBuildings.addAll(worldInfo.getEntityIDsOfType(BUILDING));
        }
        allBuildings.removeAll(openedAreas);
        if (allBuildings.isEmpty()) return null;

        List<EntityID> list = new ArrayList<>(allBuildings);
        longTermTarget = list.get((int) (Math.random() * list.size()));
        pathPlanning.setFrom(positionID);
        pathPlanning.setDestination(longTermTarget);
        List<EntityID> path = pathPlanning.calc().getResult();
        return (path == null || path.isEmpty()) ? null : path.get(path.size() - 1);
    }

    private EntityID nearestRefuge(EntityID from) {
        return worldInfo.getEntityIDsOfType(REFUGE).stream()
                .min(Comparator.comparingDouble(id -> worldInfo.getDistance(from, id)))
                .orElse(null);
    }

    private EntityID nearestInSet(EntityID from, Set<EntityID> set) {
        if (set.isEmpty()) return null;
        return set.stream()
                .min(Comparator.comparingDouble(id -> worldInfo.getDistance(from, id)))
                .orElse(null);
    }

    /* ---------------- 4. updateInfo 里的 SEU 协作逻辑（保持原样）---------------- */
    private void handleCollaboration(MessageManager mm) {
        Set<EntityID> changed = worldInfo.getChanged().getChangedEntities();
        for (CommunicationMessage msg : mm.getReceivedMessageList()) {
            if (msg instanceof MessagePoliceForce mpf &&
                    mpf.getAction() == MessagePoliceForce.ACTION_CLEAR &&
                    !mpf.getAgentID().equals(agentInfo.getID())) {
                EntityID tid = mpf.isTargetDefined() ? mpf.getTargetID() : null;
                if (tid != null) {
                    StandardEntity ent = worldInfo.getEntity(tid);
                    EntityID areaId = (ent instanceof Blockade b && b.isPositionDefined())
                            ? b.getPosition() : tid;
                    tasks.remove(areaId);
                    if (result != null && result.equals(areaId) &&
                            agentInfo.getID().getValue() < mpf.getAgentID().getValue()) {
                        result = null;          // 小 ID 让路
                    }
                }
            } else if (msg instanceof CommandPolice cp &&
                    cp.getAction() == CommandPolice.ACTION_CLEAR) {
                if ((cp.isToIDDefined() && cp.getToID().equals(agentInfo.getID())) || cp.isBroadcast()) {
                    EntityID t = cp.getTargetID();
                    if (t != null) {
                        StandardEntity e = worldInfo.getEntity(t);
                        EntityID aid = (e instanceof Blockade b && b.isPositionDefined())
                                ? b.getPosition() : t;
                        putTask(aid, 1);        // 中心下达最高优先级
                    }
                }
            }
        }
    }

    private void initPriorityAndTarget() {
        priorityRoads = new HashSet<>();
        targetAreas = new HashSet<>();
        refugeLocation = new HashSet<>();
        for (StandardEntity e : worldInfo.getEntitiesOfType(REFUGE)) {
            for (EntityID n : ((Building) e).getNeighbours()) {
                StandardEntity ne = worldInfo.getEntity(n);
                if (ne instanceof Road road) {
                    priorityRoads.add(n);
                    targetAreas.add(n);
                    if (road.isBlockadesDefined() && !road.getBlockades().isEmpty())
                        refugeLocation.add(n);
                    for (EntityID nn : road.getNeighbours()) {
                        StandardEntity nne = worldInfo.getEntity(nn);
                        if (nne instanceof Road) {
                            priorityRoads.add(n);
                            targetAreas.add(n);
                        }
                    }
                }
            }
        }
    }

    private void refreshTargetAreas() {
        targetAreas.clear();
        for (StandardEntity e : worldInfo.getEntitiesOfType(REFUGE, GAS_STATION, BUILDING)) {
            for (EntityID n : ((Building) e).getNeighbours()) {
                StandardEntity ne = worldInfo.getEntity(n);
                if (ne instanceof Road road && road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    targetAreas.add(n);
                    for (EntityID nn : road.getNeighbours()) {
                        StandardEntity nne = worldInfo.getEntity(nn);
                        if (nne instanceof Road r && r.isBlockadesDefined() && !r.getBlockades().isEmpty())
                            targetAreas.add(nn);
                    }
                }
            }
        }
    }

    private void refreshPriorityRoads() {
        priorityRoads.clear();
        for (StandardEntity e : worldInfo.getEntitiesOfType(REFUGE)) {
            for (EntityID n : ((Building) e).getNeighbours()) {
                StandardEntity ne = worldInfo.getEntity(n);
                if (ne instanceof Road road && road.isBlockadesDefined() && !road.getBlockades().isEmpty()) {
                    priorityRoads.add(n);
                    for (EntityID nn : road.getNeighbours()) {
                        StandardEntity nne = worldInfo.getEntity(nn);
                        if (nne instanceof Road r && r.isBlockadesDefined() && !r.getBlockades().isEmpty())
                            priorityRoads.add(nn);
                    }
                }
            }
        }
    }

    private void addCivilianPaths() {
        for (StandardEntity se : worldInfo.getEntitiesOfType(CIVILIAN)) {
            if (!isValidCivilian(se)) continue;
            Civilian cv = (Civilian) se;
            EntityID cvPos = cv.getPosition();
            StandardEntity nearestRefuge = worldInfo.getEntityIDsOfType(REFUGE).stream()
                    .min(Comparator.comparingDouble(r -> worldInfo.getDistance(cv.getPosition(), r)))
                    .map(worldInfo::getEntity).orElse(null);
            if (nearestRefuge == null) continue;
            pathPlanning.setFrom(cvPos);
            pathPlanning.setDestination(nearestRefuge.getID());
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null) path.forEach(id -> putTask(id, 2)); // 优先级2
        }
    }

    private void addVoiceHelp() {
        for (Command cmd : Objects.requireNonNull(agentInfo.getHeard())) {
            if (cmd instanceof AKSpeak speak && speak.getChannel() == 0
                    && !speak.getAgentID().equals(agentInfo.getID())) {
                String voice = new String(speak.getContent());
                if ("Help".equalsIgnoreCase(voice) || "Ouch".equalsIgnoreCase(voice)) {
                    int range = scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
                    worldInfo.getObjectsInRange(agentInfo.getID(), range).stream()
                            .filter(e -> e instanceof Building)
                            .map(e -> (Building) e)
                            .forEach(b -> b.getNeighbours().stream()
                                    .map(worldInfo::getEntity)
                                    .filter(r -> r instanceof Road)
                                    .forEach(r -> putTask(r.getID(), 1))); // 最高优先级1
                }
            }
        }
    }

    private boolean isValidCivilian(StandardEntity e) {
        if (!(e instanceof Civilian c)) return false;
        return c.isHPDefined() && c.getHP() > 0
                && c.isPositionDefined()
                && !(worldInfo.getPosition(c) instanceof Refuge);
    }
}