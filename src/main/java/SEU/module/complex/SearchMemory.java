package SEU.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import adf.core.component.communication.CommunicationMessage;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SearchMemory {
    private AgentInfo agentInfo;
    private WorldInfo worldInfo;
    private ScenarioInfo scenarioInfo;
    private Clustering clustering;
    private PathPlanning pathPlanning;
    
    // 基础数据结构
    private Set<EntityID> allBuildings;
    private Set<EntityID> clusteringBuildings;
    private Set<EntityID> searchedBuildings;
    private Set<EntityID> allSearchedBuildings;
    private Set<EntityID> unreachableBuildings;
    private List<EntityID> historyPosition;
    private Set<EntityID> heardCivilianPossibleBuildings;
    
    // AIT风格的任务管理
    private Map<EntityID, Integer> taskPriorities = new HashMap<>(); // 任务优先级映射
    private Set<EntityID> completedTasks = new HashSet<>(); // 已完成任务
    private Set<EntityID> cluster = new HashSet<>(); // 集群范围
    
    // AIT风格的配置常量
    private static final int HIGH_PRIORITY = 2;
    private static final int MEDIUM_PRIORITY = 5;
    private static final int LOW_PRIORITY = 8;
    
    public SearchMemory(AgentInfo ai, WorldInfo wi, ScenarioInfo si, Clustering c, PathPlanning pp) {
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;
        this.clustering = c;
        this.pathPlanning = pp;
        
        initialize();
    }
    
    private void initialize() {
        this.allBuildings = new HashSet<>();
        this.clusteringBuildings = new HashSet<>();
        this.searchedBuildings = new HashSet<>();
        this.allSearchedBuildings = new HashSet<>();
        this.unreachableBuildings = new HashSet<>();
        this.historyPosition = new ArrayList<>();
        this.heardCivilianPossibleBuildings = new HashSet<>();
        
        // 初始化所有建筑物
        this.allBuildings.addAll(worldInfo.getEntityIDsOfType(
            FIRE_STATION, POLICE_OFFICE, AMBULANCE_CENTRE, REFUGE, BUILDING, GAS_STATION));
        
        // 初始化集群
        initializeCluster();
    }
    
    // AIT风格：初始化集群
    private void initializeCluster() {
        this.clustering.calc();
        EntityID me = this.agentInfo.getID();
        int index = this.clustering.getClusterIndex(me);
        Collection<EntityID> clusterEntities = this.clustering.getClusterEntityIDs(index);
        
        this.cluster.clear();
        this.cluster.addAll(clusterEntities.stream()
            .map(this.worldInfo::getEntity)
            .filter(e -> e instanceof Building)
            .map(StandardEntity::getID)
            .collect(Collectors.toSet()));
    }
    
    public void updateInfo(MessageManager messageManager, EntityID result) {
        updateMessage(messageManager);
        updateClusteringBuildings();
        updateSearchedBuildings();
        updateAllSearchedBuildings(messageManager);
        updateUnreachableBuildings(result);
        updateHistoryPosition();
        updateHeardCivilianPossibleBuildings();
        updateTaskPriorities(); // AIT风格：更新任务优先级
    }
    
    private void updateClusteringBuildings() {
        int currentIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
        Collection<StandardEntity> inCluster = this.clustering.getClusterEntities(currentIndex);
        
        this.clusteringBuildings.clear();
        for (EntityID buildingID : this.allBuildings) {
            if (inCluster.contains(this.worldInfo.getEntity(buildingID))) {
                this.clusteringBuildings.add(buildingID);
            }
        }
    }
    
    private void updateSearchedBuildings() {
        EntityID currentPosition = this.agentInfo.getPosition();
        if (this.worldInfo.getEntity(currentPosition) instanceof Building) {
            this.searchedBuildings.add(currentPosition);
        }
    }
    
    private void updateAllSearchedBuildings(MessageManager messageManager) {
        EntityID currentPosition = this.agentInfo.getPosition();
        if (this.worldInfo.getEntity(currentPosition) instanceof Building) {
            this.allSearchedBuildings.add(currentPosition);
        }
        
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            if (message instanceof MessageBuilding) {
                MessageBuilding mb = (MessageBuilding) message;
                this.allSearchedBuildings.add(mb.getBuildingID());
            }
        }
    }
    
    private void updateUnreachableBuildings(EntityID result) {
        EntityID currentPosition = this.agentInfo.getPosition();
        if (this.historyPosition.size() > 1) {
            EntityID lastPosition = this.historyPosition.get(this.historyPosition.size() - 1);
            EntityID lastLastPosition = this.historyPosition.get(this.historyPosition.size() - 2);
            
            if (currentPosition.equals(lastPosition) && currentPosition.equals(lastLastPosition) && result != null) {
                this.unreachableBuildings.add(result);
                
                // 检查当前位置是否有需要救援的平民
                boolean hasCivilian = false;
                for (EntityID e : this.worldInfo.getEntityIDsOfType(CIVILIAN)) {
                    Human human = (Human) this.worldInfo.getEntity(e);
                    if (human != null && human.getPosition().equals(this.agentInfo.getPosition())) {
                        hasCivilian = true;
                        break;
                    }
                }
                
                if (!hasCivilian) {
                    this.unreachableBuildings.clear();
                }
            } else if (!currentPosition.equals(lastPosition) && !lastPosition.equals(lastLastPosition)) {
                this.unreachableBuildings.clear();
            }
        }
    }
    
    private void updateHistoryPosition() {
        EntityID currentPosition = this.agentInfo.getPosition();
        this.historyPosition.add(currentPosition);
        
        // 保持历史位置列表的大小合理
        if (this.historyPosition.size() > 10) {
            this.historyPosition.remove(0);
        }
    }
    
    private void updateHeardCivilianPossibleBuildings() {
        for (Command command : this.agentInfo.getHeard()) {
            if (command instanceof AKSpeak && ((AKSpeak) command).getChannel() == 0 && 
                !command.getAgentID().equals(this.agentInfo.getID())) {
                
                byte[] receivedData = ((AKSpeak) command).getContent();
                String voiceString = new String(receivedData);
                
                if ("Help".equalsIgnoreCase(voiceString) || "Ouch".equalsIgnoreCase(voiceString)) {
                    int range = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range") / 2;
                    Collection<StandardEntity> possibleBuildings = this.worldInfo.getObjectsInRange(
                        this.agentInfo.getID(), range);
                    
                    for (StandardEntity possibleBuilding : possibleBuildings) {
                        if (possibleBuilding instanceof Building) {
                            this.heardCivilianPossibleBuildings.add(possibleBuilding.getID());
                        }
                    }
                }
            }
        }
    }
    
    // AIT风格：更新任务优先级
    private void updateTaskPriorities() {
        this.taskPriorities.clear();
        
        // 基于集群的任务分配
        renewTasksWithCluster(MEDIUM_PRIORITY);
        
        // 基于听到的声音提高优先级
        renewTasksWithVoice(HIGH_PRIORITY);
        
        // 基于搜索状态调整优先级
        renewTasksWithSearchStatus();
    }
    
    // AIT风格：基于集群更新任务
    private void renewTasksWithCluster(int priority) {
        for (EntityID building : this.cluster) {
            if (!this.completedTasks.contains(building) && !this.unreachableBuildings.contains(building)) {
                putTask(building, priority);
            }
        }
    }
    
    // AIT风格：基于声音更新任务
    private void renewTasksWithVoice(int priority) {
        for (EntityID building : this.heardCivilianPossibleBuildings) {
            if (this.cluster.contains(building)) {
                putTask(building, priority);
            }
        }
    }
    
    // AIT风格：基于搜索状态更新任务
    private void renewTasksWithSearchStatus() {
        for (EntityID building : this.allSearchedBuildings) {
            if (this.taskPriorities.containsKey(building)) {
                // 已搜索的建筑降低优先级
                putTask(building, this.taskPriorities.get(building) + 2);
            }
        }
    }
    
    // AIT风格：添加任务
    private void putTask(EntityID task, int priority) {
        int current = this.taskPriorities.getOrDefault(task, Integer.MAX_VALUE);
        this.taskPriorities.put(task, Math.min(current, priority));
    }
    
    private void updateMessage(MessageManager messageManager) {
        if (this.unreachableBuildings.size() > 2) {
            messageManager.addMessage(new CommandPolice(
                true, StandardMessagePriority.HIGH, null, 
                this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
            messageManager.addMessage(new CommandPolice(
                false, StandardMessagePriority.HIGH, null, 
                this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
        }
        
        // 报告发现的平民
        for (EntityID e : this.worldInfo.getEntityIDsOfType(CIVILIAN)) {
            StandardEntity se = this.worldInfo.getEntity(e);
            if (isValidHuman(se)) {
                Civilian civilian = (Civilian) se;
                if (civilian.getPosition().equals(this.agentInfo.getPosition()) && 
                    !(this.agentInfo.me() instanceof AmbulanceTeam && civilian.getBuriedness() == 0)) {
                    
                    messageManager.addMessage(new MessageCivilian(
                        true, StandardMessagePriority.HIGH, civilian));
                    messageManager.addMessage(new MessageCivilian(
                        false, StandardMessagePriority.HIGH, civilian));
                }
            }
        }
    }
    
    // AIT风格：获取最高优先级任务
    public EntityID getHighestPriorityTask() {
        if (this.taskPriorities.isEmpty()) {
            return null;
        }
        
        return this.taskPriorities.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);
    }
    
    // AIT风格：标记任务完成
    public void markTaskCompleted(EntityID task) {
        this.completedTasks.add(task);
    }
    
    // AIT风格：扩展集群（当当前集群任务完成时）
    public void expandCluster() {
        EntityID me = this.agentInfo.getID();
        int n = this.clustering.getClusterNumber();
        int index = this.clustering.getClusterIndex(me);
        
        int size = this.cluster.size();
        for (int i = 1; i < n && size == this.cluster.size(); ++i) {
            Collection<EntityID> ids = this.clustering.getClusterEntityIDs((index + i) % n);
            this.cluster.addAll(ids.stream()
                .map(this.worldInfo::getEntity)
                .filter(e -> e instanceof Building)
                .map(StandardEntity::getID)
                .collect(Collectors.toSet()));
        }
    }
    
    // AIT风格：检查是否需要扩展集群
    public boolean needToExpandCluster() {
        return this.completedTasks.size() >= this.cluster.size() * 0.9;
    }
    
    // Getter方法
    public Set<EntityID> getAllBuildings() { return Collections.unmodifiableSet(this.allBuildings); }
    public Set<EntityID> getClusteringBuildings() { return Collections.unmodifiableSet(this.clusteringBuildings); }
    public Set<EntityID> getSearchedBuildings() { return Collections.unmodifiableSet(this.searchedBuildings); }
    public Set<EntityID> getAllSearchedBuildings() { return Collections.unmodifiableSet(this.allSearchedBuildings); }
    public Set<EntityID> getUnreachableBuildings() { return Collections.unmodifiableSet(this.unreachableBuildings); }
    public List<EntityID> getHistoryPosition() { return Collections.unmodifiableList(this.historyPosition); }
    public Set<EntityID> getHeardCivilianPossibleBuildings() { return Collections.unmodifiableSet(this.heardCivilianPossibleBuildings); }
    public Map<EntityID, Integer> getTaskPriorities() { return Collections.unmodifiableMap(this.taskPriorities); }
    public Set<EntityID> getCluster() { return Collections.unmodifiableSet(this.cluster); }
    
    public void clearAll() {
        this.searchedBuildings.clear();
        this.allSearchedBuildings.clear();
        this.unreachableBuildings.clear();
        this.heardCivilianPossibleBuildings.clear();
        this.taskPriorities.clear();
        this.completedTasks.clear();
        initializeCluster();
    }
    
    private boolean isValidHuman(StandardEntity entity) {
        if (entity == null) return false;
        if (!(entity instanceof Human)) return false;
        
        Human target = (Human) entity;
        if (!target.isHPDefined() || target.getHP() == 0) return false;
        if (!target.isPositionDefined()) return false;
        if (!target.isDamageDefined() || target.getDamage() == 0) return false;
        if (!target.isBuriednessDefined()) return false;
        
        // 抛弃血量太低，短时间内就会死掉的人
        if (target.getHP() / target.getDamage() < 5) return false;
        
        StandardEntity position = worldInfo.getPosition(target);
        if (position == null) return false;
        
        StandardEntityURN positionURN = position.getStandardURN();
        return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
    }
}