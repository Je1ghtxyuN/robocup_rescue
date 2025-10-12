package SEU.module.complex;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
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

    private void updateMessage(MessageManager messageManager) {
        if (this.unreachableBuildings.size() > 2) {
            messageManager.addMessage(new CommandPolice(true, StandardMessagePriority.HIGH, null, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
            messageManager.addMessage(new CommandPolice(false, StandardMessagePriority.HIGH, null, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
        }
        for (EntityID e : this.worldInfo.getEntityIDsOfType(CIVILIAN)) {
            StandardEntity se = this.worldInfo.getEntity(e);
            if (isValidHuman(se)) {
                Civilian civilian = (Civilian) se;
                if (civilian.getPosition().equals(this.agentInfo.getPosition()) && !(this.agentInfo.me() instanceof AmbulanceTeam && civilian.getBuriedness() == 0)) {
                    messageManager.addMessage(new MessageCivilian(true, StandardMessagePriority.HIGH, civilian));
                    messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH, civilian));
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
