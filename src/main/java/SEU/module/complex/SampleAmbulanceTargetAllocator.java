package SEU.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.*;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.AmbulanceTargetAllocator;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import SEU.module.complex.dcop.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import es.csic.iiia.bms.*;
import es.csic.iiia.bms.factors.*;
import es.csic.iiia.bms.factors.CardinalityFactor.CardinalityFunction;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;

public class SampleAmbulanceTargetAllocator extends AmbulanceTargetAllocator {

    private final static StandardEntityURN URL = AMBULANCE_CENTRE;
    private final static StandardEntityURN AGENT_URL = AMBULANCE_TEAM;
    private final static int ITERATIONS = 100;
    private final static double PENALTY = 600.0;
    private final static EntityID SEARCHING_TASK = new EntityID(-1);

    private final Map<EntityID, EntityID> result = new HashMap<>();
    private Set<EntityID> agents = new HashSet<>();
    private final Set<EntityID> tasks = new HashSet<>();
    private final Set<EntityID> ignored = new HashSet<>();

    private final Map<EntityID, Factor<EntityID>> nodes = new HashMap<>();
    private final SEU.module.complex.dcop.BufferedCommunicationAdapter adapter;

    private final Set<EntityID> received = new HashSet<>();

    public SampleAmbulanceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                                         ModuleManager moduleManager, DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        System.out.println("SampleAmbulanceTargetAllocator 被实例化了");
        this.adapter = new SEU.module.complex.dcop.BufferedCommunicationAdapter();
    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        return this.result;
    }

    @Override
public AmbulanceTargetAllocator calc() {
    DebugLogger.log("救护车分配器", "calc方法开始执行");
    this.result.clear();
    if (this.agents.isEmpty()) {
        DebugLogger.log("救护车分配器", "初始化智能体集合");
        this.initializeAgents();
    }
    if (!this.have2allocate()) {
        DebugLogger.log("救护车分配器", "跳过分配，条件不满足");
        return this;
    }

    DebugLogger.log("救护车分配器", "开始任务分配计算");
    this.initializeTasks();
    this.initializeFactorGraph();
    
    DebugLogger.log("救护车分配器", "开始因子图迭代计算，迭代次数=" + ITERATIONS);
    for (int i = 0; i < ITERATIONS; ++i) {
        this.nodes.values().stream().forEach(Factor::run);
        this.adapter.execute(this.nodes);
    }

    for (EntityID agent : this.agents) {
        final Factor<EntityID> node = this.nodes.get(agent);
        EntityID task = selectTask((ProxyFactor<EntityID>) node);
        if (task.equals(SEARCHING_TASK)) {
            task = null;
        }
        this.result.put(agent, task);
    }

    // 调试信息输出
    int n = 0;
    for (EntityID id : this.agents) {
        if (this.result.get(id) != null) {
            ++n;
        }
    }
    DebugLogger.log("救护车分配器", "本次分配完成，实际分配任务智能体数 = " + n + " / " + agents.size());
    DebugLogger.logAllocationResult("救护车分配器", this.result, this.agents, this.tasks);

    return this;
}

    @Override
    public AmbulanceTargetAllocator updateInfo(MessageManager mm) {
        super.updateInfo(mm);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        this.received.clear();

        // 处理各类消息
        processCivilianMessages(mm);
        processFireBrigadeMessages(mm);
        processPoliceForceMessages(mm);
        processAmbulanceTeamMessages(mm);
        processReportMessages(mm);

        return this;
    }

    private void processCivilianMessages(MessageManager mm) {
        final Collection<CommunicationMessage> messages = 
            mm.getReceivedMessageList(MessageCivilian.class);
        for (CommunicationMessage tmp : messages) {
            MessageCivilian message = (MessageCivilian) tmp;
            MessageUtil.reflectMessage(this.worldInfo, message);
            updateHumanPosition(message.getAgentID());
        }
    }

    private void processAmbulanceTeamMessages(MessageManager mm) {
        final Collection<CommunicationMessage> messages = 
            mm.getReceivedMessageList(MessageAmbulanceTeam.class);
        for (CommunicationMessage tmp : messages) {
            MessageAmbulanceTeam message = (MessageAmbulanceTeam) tmp;
            MessageUtil.reflectMessage(this.worldInfo, message);
            final EntityID id = message.getAgentID();
            this.received.add(id);
            updateHumanPosition(id);
        }
    }

    private void updateHumanPosition(EntityID humanId) {
        Human human = (Human) this.worldInfo.getEntity(humanId);
        human.undefineX();
        human.undefineY();
    }

    private boolean have2allocate() {
    if (!this.allCentersExists()) {
        DebugLogger.log("救护车分配器", "分配条件不满足：不是所有中心都存在");
        return false;
    }
    // final int nAgents = this.agents.size();
    // if (this.received.size() != nAgents) {
    //     DebugLogger.log("救护车分配器", 
    //         "分配条件不满足：接收消息数(" + this.received.size() + ") ≠ 智能体数(" + nAgents + ")");
    //     return false;
    // }

    final int lowest = this.worldInfo.getEntityIDsOfType(URL)
        .stream()
        .mapToInt(EntityID::getValue)
        .min().orElse(-1);

    final int me = this.agentInfo.getID().getValue();
    final int time = this.agentInfo.getTime();
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    
    boolean result = time >= ignored && me == lowest;
    DebugLogger.log("救护车分配器", 
        "分配条件检查：时间=" + time + ", 忽略时间=" + ignored + 
        ", 最低ID=" + lowest + ", 我的ID=" + me + ", 结果=" + result);
    
    return result;
}

    private boolean allCentersExists() {
        final int fss = this.scenarioInfo.getScenarioAgentsFs();
        final int pos = this.scenarioInfo.getScenarioAgentsPo();
        final int acs = this.scenarioInfo.getScenarioAgentsAc();
        return fss > 0 && pos > 0 && acs > 0;
    }

    private void initializeAgents() {
        final Collection<EntityID> tmp = 
            this.worldInfo.getEntityIDsOfType(AGENT_URL);
        this.agents = new HashSet<>(tmp);
    }

    private void initializeTasks() {
        this.tasks.clear();

        final Stream<EntityID> tmp = this.worldInfo.getEntitiesOfType(CIVILIAN)
            .stream()
            .map(Human.class::cast)
            .filter(h -> h.isPositionDefined() &&
                this.worldInfo.getEntity(h.getPosition()).getStandardURN() == BUILDING)
            .filter(h -> h.isDamageDefined() && h.getDamage() > 0)
            .map(StandardEntity::getID);
        this.tasks.addAll(tmp.collect(toSet()));

        this.tasks.removeAll(this.ignored);
        this.tasks.add(SEARCHING_TASK);

        DebugLogger.log("救护车分配器", "任务初始化完成，有效任务共 " + tasks.size() + " 个（含搜索任务）");
    }

    private void initializeFactorGraph() {
        this.initializeVariableNodes(this.agents);
        this.initializeFactorNodes(this.tasks);
        this.connectNodes(this.agents, this.tasks);
    }

    private void initializeVariableNodes(Collection<EntityID> ids) {
        for (EntityID id : ids) {
            final Factor<EntityID> tmp = new BMSSelectorFactor<>();
            final WeightingFactor<EntityID> vnode = new WeightingFactor<>(tmp);
            vnode.setMaxOperator(new Minimize());
            vnode.setIdentity(id);
            vnode.setCommunicationAdapter(this.adapter);
            this.nodes.put(id, vnode);
        }
    }

    private void initializeFactorNodes(Collection<EntityID> ids) {
        for (EntityID id : ids) {
            final CardinalityFactor<EntityID> fnode = new SEU.module.complex.dcop.BMSCardinalityFactor<>();
            final CardinalityFunction func = new CardinalityFunction() {
                @Override
                public double getCost(int nActiveVariables) {
                    return SampleAmbulanceTargetAllocator.this.computePenalty(id, nActiveVariables);
                }
            };
            fnode.setFunction(func);
            fnode.setMaxOperator(new Minimize());
            fnode.setIdentity(id);
            fnode.setCommunicationAdapter(this.adapter);
            this.nodes.put(id, fnode);
        }
    }

    private void connectNodes(Collection<EntityID> vnodeids, Collection<EntityID> fnodeids) {
        for (EntityID vnodeid : vnodeids) {
            final List<EntityID> closer = fnodeids.stream()
                .sorted((i1, i2) -> compareTaskPriority(i1, i2, vnodeid))
                .collect(toList());

            for (int i = 0; i < Math.min(4, closer.size()); ++i) {
                final EntityID fnodeid = closer.get(i);
                connectSingleNode(vnodeid, fnodeid);
            }
            DebugLogger.log("救护车分配器", "变量节点与因子节点连接完成，每个智能体最多连接 4 个任务");
        }
    }

    private int compareTaskPriority(EntityID i1, EntityID i2, EntityID agent) {
        if (i1.equals(SEARCHING_TASK)) return Integer.compare(0, 1);
        if (i2.equals(SEARCHING_TASK)) return Integer.compare(1, 0);
        final double d1 = this.worldInfo.getDistance(i1, agent);
        final double d2 = this.worldInfo.getDistance(i2, agent);
        return Double.compare(d1, d2);
    }

    private void connectSingleNode(EntityID agentId, EntityID taskId) {
        WeightingFactor<EntityID> vnode = (WeightingFactor<EntityID>) this.nodes.get(agentId);
        vnode.addNeighbor(taskId);

        Factor<EntityID> fnode = this.nodes.get(taskId);
        fnode.addNeighbor(agentId);

        final double penalty = this.computePenalty(agentId, taskId);
        vnode.setPotential(taskId, penalty);
    }

    private static EntityID selectTask(ProxyFactor<EntityID> proxy) {
        final SelectorFactor<EntityID> selector = (SelectorFactor<EntityID>) proxy.getInnerFactor();
        return selector.select();
    }

    private double computePenalty(EntityID agent, EntityID task) {
        if (task.equals(SEARCHING_TASK)) return 0.0;
        final double d = this.worldInfo.getDistance(agent, task);
        return d / (42000.0 / 1.5);
    }

    private double computePenalty(EntityID task, int nAgents) {
        if (task.equals(SEARCHING_TASK)) return 0.0;

        final Civilian entity = (Civilian) this.worldInfo.getEntity(task);
        if (nAgents == 0) return PENALTY;

        final int hp = entity.getHP();
        final int damage = entity.getDamage();
        int buriedness = entity.isBuriednessDefined() ? entity.getBuriedness() : 0;

        final double remaining = (double) hp / (double) damage;
        final double nRequested = (double) buriedness / remaining;
        final double nLeasts = Math.ceil(nRequested + 1.0);
        final double ratio = Math.min((double) nAgents, nLeasts) / nLeasts;

        return PENALTY * (1.0 - Math.pow(ratio, 2.0));
    }

    // 简化其他消息处理方法
    private void processFireBrigadeMessages(MessageManager mm) {
        // 实现类似processCivilianMessages
    }

    private void processPoliceForceMessages(MessageManager mm) {
        // 实现类似processCivilianMessages
    }

    private void processReportMessages(MessageManager mm) {
        final Collection<CommunicationMessage> messages = 
            mm.getReceivedMessageList(MessageReport.class);
        for (CommunicationMessage tmp : messages) {
            MessageReport message = (MessageReport) tmp;
            if (message.isFromIDDefined()) {
                this.ignored.add(message.getFromID());
            }
        }
    }
}