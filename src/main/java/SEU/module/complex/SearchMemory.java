package SEU.module.complex;
import SEU.module.complex.dcop.DebugLogger;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import org.apache.log4j.LogMF;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SearchMemory {
    private AgentInfo agentInfo; //对应Agent认知行为的一些类
    private WorldInfo worldInfo;
    private ScenarioInfo scenarioInfo;
    private Clustering clustering;
    private PathPlanning pathPlanning;
    private int clusteringIndex;
    private Set<EntityID> allBuildings; //（以建筑物为搜索目标）所有的建筑物
    private Set<EntityID> clusteringBuildings; //聚类内的建筑物（但我不会聚类啊）
    private Set<EntityID> searchedBuildings; //自己去过的建筑物
    private Set<EntityID> allSearchedBuildings; //所有Agent去过的地方，考虑信道丢包率原则上优先级更低
    private Set<EntityID> unreachableBuildings; //到不了的地方，不知道怎么量度
    private List<EntityID> historyPosition; //历史位置，用于判断堵塞
    private Set<EntityID> heardCivilianPossibleBuildings; //根据听到的位置判断可能有civilian的建筑

    public SearchMemory(AgentInfo ai, WorldInfo wi, ScenarioInfo si, Clustering c, PathPlanning pp) {
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;
        this.clustering = c;
        this.pathPlanning = pp;

        this.clusteringIndex = -1;
        this.allBuildings = new HashSet<>();
        this.clusteringBuildings = new HashSet<>(); //由于这个不会，所以先搞无聚类策略
        this.searchedBuildings = new HashSet<>();
        this.allSearchedBuildings = new HashSet<>();
        this.unreachableBuildings = new HashSet<>();
        this.historyPosition = new ArrayList<>();
        this.heardCivilianPossibleBuildings = new HashSet<>();

        this.allBuildings.addAll(wi.getEntityIDsOfType(FIRE_STATION, POLICE_OFFICE, AMBULANCE_CENTRE, REFUGE, BUILDING, GAS_STATION));
    }

    //仿照主函数中updateInfo函数，功能类似
    public void updateInfo(MessageManager messageManager, EntityID result) {
        updateMessage(messageManager);
        updateClusteringBuildings();
        updateSearchedBuildings();
        updateAllSearchedBuildings(messageManager);
        updateUnreachableBuildings(result);
        updateHistoryPosition();
        updateHeardCivilianPossibleBuildings();
    }

    private void updateClusteringBuildings() {
        int currentIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
        if (currentIndex != this.clusteringIndex) {
            clusteringBuildings.clear();
            Collection<StandardEntity> inCluster = this.clustering.getClusterEntities(currentIndex);
            for (EntityID buildingID : this.allBuildings) {
                if (inCluster.contains(this.worldInfo.getEntity(buildingID))) {
                    clusteringBuildings.add(buildingID);
                }
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
            if (message.getClass() == MessageBuilding.class) {
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
                for (EntityID e : this.worldInfo.getEntityIDsOfType(CIVILIAN)) {
                    Human human = (Human) this.worldInfo.getEntity(e);
                    if (human != null && human.getPosition().equals(this.agentInfo.getPosition())) {
                        this.unreachableBuildings.clear();
                        break;
                    }
                }
            } else if (!(currentPosition.equals(lastPosition)) && !(lastPosition.equals(lastLastPosition))) {
                this.unreachableBuildings.clear();
            }
        }

    }

    private void updateHistoryPosition() {
        EntityID currentPosition = this.agentInfo.getPosition();
        this.historyPosition.add(currentPosition);
    }

    private void updateHeardCivilianPossibleBuildings() {
        for (Command command : Objects.requireNonNull(this.agentInfo.getHeard())) {
            if (command instanceof AKSpeak && ((AKSpeak) command).getChannel() == 0 && command.getAgentID() != this.agentInfo.getID()) {
                byte[] receivedData = ((AKSpeak) command).getContent();
                String voiceString = new String(receivedData);
                if ("Help".equalsIgnoreCase(voiceString) || "Ouch".equalsIgnoreCase(voiceString)) {
                    int range = this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range") / 2;
                    Collection<StandardEntity> possibleBuildings = this.worldInfo.getObjectsInRange(this.agentInfo.getID(), range);
                    for (StandardEntity possibleBuilding : possibleBuildings) {
                        if (possibleBuilding instanceof Building) {
                            this.heardCivilianPossibleBuildings.add(possibleBuilding.getID());
                        }
                    }
                }
            }
        }
    }

    /**
     * 更新并发送消息到消息管理器
     * 该方法根据当前世界状态发送两种类型的消息：
     * 1. 当无法到达的建筑物过多时，请求警察清理道路
     * 2. 当发现有效平民在智能体当前位置时，发送平民信息
     * @param messageManager 消息管理器，用于分发各类消息
     */
    private void updateMessage(MessageManager messageManager) {
        // 检查无法到达的建筑物数量，如果超过2个，则请求警察清理道路
        if (this.unreachableBuildings.size() > 2) {
            // 通过无线电发送高优先级的警察清理命令
            messageManager.addMessage(new CommandPolice(true, StandardMessagePriority.HIGH, null, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
            // 不通过无线电发送高优先级的警察清理命令
            messageManager.addMessage(new CommandPolice(false, StandardMessagePriority.HIGH, null, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
        }

        // 遍历世界中所有平民类型的实体
        for (EntityID e : this.worldInfo.getEntityIDsOfType(CIVILIAN)) {
            // 获取实体对象
            StandardEntity se = this.worldInfo.getEntity(e);

            // 检查是否为有效的人类实体
            if (isValidHuman(se)) {
                // 将实体转换为平民类型
                Civilian civilian = (Civilian) se;

                // 检查条件：
                // 1. 平民位置与智能体当前位置相同
                // 2. 排除救护车且平民未被掩埋的情况（因为这种情况不需要额外处理）
                if (civilian.getPosition().equals(this.agentInfo.getPosition()) && !(this.agentInfo.me() instanceof AmbulanceTeam && civilian.getBuriedness() == 0)) {
                    // 通过无线电发送高优先级的平民信息
                    messageManager.addMessage(new MessageCivilian(true, StandardMessagePriority.HIGH, civilian));
                    // 不通过无线电发送高优先级的平民信息
                    // 参数说明：
                    // - false: 不通过无线电发送消息
                    // - StandardMessagePriority.HIGH: 设置消息优先级为高
                    // - civilian: 包含平民详细信息的对象
                    messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH, civilian));
                }

                // 新增逻辑：当智能体是消防员，且同一位置有平民，且视线内和同一位置都没有救护车时，请求救护车
                if (this.agentInfo.me() instanceof FireBrigade && civilian.getPosition().equals(this.agentInfo.getPosition())) {
                    boolean hasAmbulanceInSight = false;
                    boolean hasAmbulanceAtSamePosition = false;

                    /*// 检查视线内是否有救护车
                    for (StandardEntity entity : this.worldInfo.getEntitiesOfType(AmbulanceTeam.class)) {
                        if (this.worldInfo.isVisible(entity.getID())) {
                            hasAmbulanceInSight = true;
                            break;
                        }
                    }

                    // 检查同一位置是否有救护车
                    for (StandardEntity entity : this.worldInfo.getEntitiesOfType(AmbulanceTeam.class)) {
                        if (entity.getPosition().equals(this.agentInfo.getPosition())) {
                            hasAmbulanceAtSamePosition = true;
                            break;
                        }
                    }

 */

                    // 如果视线内和同一位置都没有救护车并且平民未被掩埋还受伤，则请求救护车支援
                    if (!hasAmbulanceInSight && !hasAmbulanceAtSamePosition&&civilian.getBuriedness() == 0&&civilian.getDamage() != 0) {
                        // 发送请求救护车的命令
                        // 参数说明：
                        // - true: 通过无线电发送
                        // - StandardMessagePriority.HIGH: 高优先级
                        // - null: 广播给所有救护车
                        // - civilian.getID(): 目标平民ID
                        // - ACTION_RESCUE: 执行救援动作
                        messageManager.addMessage(new CommandAmbulance(true, StandardMessagePriority.HIGH, null, civilian.getID(), CommandAmbulance.ACTION_RESCUE));
                        // 同时通过非无线电方式发送
                        messageManager.addMessage(new CommandAmbulance(false, StandardMessagePriority.HIGH, null, civilian.getID(), CommandAmbulance.ACTION_RESCUE));

                        DebugLogger.log("FireBrigade","-----已请求救护车支援----- 时间: " + this.agentInfo.getTime() + " 目标平民ID: " + civilian.getID().getValue() + " 位置: " + civilian.getPosition().getValue());
                    }
                }

            }
        }
    }

    public Set<EntityID> getAllBuildings() {
        return this.allBuildings;
    }

    public Set<EntityID> getClusteringBuildings() {
        return this.clusteringBuildings;
    }

    public Set<EntityID> getSearchedBuildings() {
        return this.searchedBuildings;
    }

    public Set<EntityID> getAllSearchedBuildings() {
        return this.allSearchedBuildings;
    }

    public Set<EntityID> getUnreachableBuildings() {
        return this.unreachableBuildings;
    }

    public List<EntityID> getHistoryPosition() {
        return this.historyPosition;
    }

    public Set<EntityID> getHeardCivilianPossibleBuildings() {
        return this.heardCivilianPossibleBuildings;
    }

    public void clearAll() {
        this.searchedBuildings.clear();
        this.allSearchedBuildings.clear();
        this.unreachableBuildings.clear();
        this.heardCivilianPossibleBuildings.clear();
    }

    private boolean isValidHuman(StandardEntity entity) {
        if (entity == null)
            return false;
        if (!(entity instanceof Human target))
            return false;

        if (!target.isHPDefined() || target.getHP()==0)
            return false;
        if (!target.isPositionDefined())
            return false;
        if (!target.isDamageDefined() || target.getDamage() == 0)
           return false;
        if (!target.isBuriednessDefined())
            return false;
        //抛弃血量太低，短时间内就会死掉的人
        if(target.getHP()/target.getDamage()<5)
            return false;
        StandardEntity position = worldInfo.getPosition(target);
        if (position == null)
            return false;

        StandardEntityURN positionURN = position.getStandardURN();
        return positionURN != REFUGE && positionURN != AMBULANCE_TEAM;
    }
}
