package SEU.module.complex;

import adf.core.agent.Agent;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.StandardCommunicationModule;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.ChannelSubscriber;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.standard.messages.AKTell;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUSearch extends Search {

  private PathPlanning pathPlanning;
  private Clustering clustering;

  private EntityID result;

  private EntityID currentTarget;
  private Logger logger;
  private SearchMemory searchMemory;
  private Map<EntityID, Integer> areaCost = new HashMap<>();
  private Set<EntityID> finalSearchSet = new HashSet<>();

  public SEUSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());

    StandardEntityURN agentURN = ai.me().getStandardURN();
    if (agentURN == AMBULANCE_TEAM) {
      this.pathPlanning = moduleManager.getModule(
              "SampleSearch.PathPlanning.Ambulance",
              "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
              "SampleSearch.Clustering.Ambulance",
              "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == FIRE_BRIGADE) {
      this.pathPlanning = moduleManager.getModule(
              "SampleSearch.PathPlanning.Fire",
              "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
              "adf.impl.module.algorithm.KMeansClustering");
    } else if (agentURN == POLICE_FORCE) {
      this.pathPlanning = moduleManager.getModule(
              "SampleSearch.PathPlanning.Police",
              "adf.impl.module.algorithm.DijkstraPathPlanning");
      this.clustering = moduleManager.getModule(
              "SampleSearch.Clustering.Police",
              "adf.impl.module.algorithm.KMeansClustering");
    }
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
    this.currentTarget = null;
    this.searchMemory = new SearchMemory(ai, wi, si, this.clustering, this.pathPlanning);
  }


  @Override
  public Search updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    //StandardCommunicationModule scm = new StandardCommunicationModule();

    messageManager.subscribe(this.agentInfo, this.worldInfo, this.scenarioInfo);
    super.updateInfo(messageManager);
    this.searchMemory.updateInfo(messageManager, this.result);
    updateMessage(messageManager);
    messageManager.coordinateMessages(this.agentInfo, this.worldInfo, this.scenarioInfo);

    for(CommunicationMessage message : messageManager.getReceivedMessageList()) {
      //logger.debug("收到信息了");
      StandardMessage standardMessage = (StandardMessage) message;
      if (standardMessage.getSendingPriority() == StandardMessagePriority.LOW) {
//        logger.debug("LOW priority message from " + standardMessage.getSenderID());
      } else if (standardMessage.getSendingPriority() == StandardMessagePriority.NORMAL) {
//        logger.debug("NORMAL priority message from " + standardMessage.getSenderID());
      } else if (standardMessage.getSendingPriority() == StandardMessagePriority.HIGH) {
//        logger.debug("HIGH priority message from " + standardMessage.getSenderID());
      }
    }

    Set<EntityID> searchSet = new HashSet<>();
    this.finalSearchSet = new HashSet<>();
    Set<EntityID> searchSet1 = new HashSet<>(this.searchMemory.getHeardCivilianPossibleBuildings());
    searchSet1.removeAll(this.searchMemory.getAllSearchedBuildings());
    searchSet1.removeAll(this.searchMemory.getUnreachableBuildings());
    Set<EntityID> searchSet2 = new HashSet<>(this.searchMemory.getClusteringBuildings());
    searchSet2.removeAll(this.searchMemory.getAllSearchedBuildings());
    searchSet2.removeAll(this.searchMemory.getUnreachableBuildings());
    Set<EntityID> searchSet3 = new HashSet<>(this.searchMemory.getAllBuildings());
    searchSet3.removeAll(this.searchMemory.getAllSearchedBuildings());
    searchSet3.removeAll(this.searchMemory.getUnreachableBuildings());
    Set<EntityID> searchSet4 = new HashSet<>(this.searchMemory.getAllBuildings());
    searchSet4.removeAll(this.searchMemory.getSearchedBuildings());
    searchSet4.removeAll(this.searchMemory.getUnreachableBuildings());
    if (!searchSet1.isEmpty()) {
      searchSet.addAll(searchSet1);
//      logger.debug("set1");
    } else if (!searchSet2.isEmpty()) {
      searchSet.addAll(searchSet2);
//      logger.debug("set2");
    } else if (!searchSet3.isEmpty()) {
      searchSet.addAll(searchSet3);
//      logger.debug("set3");
    } else {
      searchSet.addAll(searchSet4);
//      logger.debug("set4");
    }
    if (searchSet.isEmpty()) {
      reset();
      return this;
    }

//    logger.debug("searchSet: " + searchSet);
    this.areaCost = new HashMap<>();
    for (EntityID singleBuilding : searchSet) {
      if (!hasSeachedinCluster(singleBuilding, messageManager)) {
        int distance = this.worldInfo.getDistance(singleBuilding, this.agentInfo.me().getID());
        areaCost.put(singleBuilding, distance);
        finalSearchSet.add(singleBuilding);
      }
    }
//    logger.debug("sub" + messageManager.getIsSubscribed());
//    logger.debug(messageManager.getChannels());
    return this;
  }


  @Override
  public Search calc() {
    this.result = null;

    ArrayList<EntityID> target = new ArrayList<>();
    List<Map.Entry<EntityID, Integer>> cost = new ArrayList<>(areaCost.entrySet());
    cost.sort(Comparator.comparingInt(Map.Entry::getValue));
    this.currentTarget = cost.get(0).getKey();
    target.add(this.currentTarget);
    /*if (cost.size() >= 5) {
      target.add(cost.get((int) (Math.random() * 5)).getKey());
    } else {
      target.add(cost.get((int) (Math.random() * cost.size())).getKey());
    }*/
    this.pathPlanning.setFrom(this.agentInfo.getPosition());
    this.pathPlanning.setDestination(finalSearchSet);
    List<EntityID> path = this.pathPlanning.calc().getResult();
//    logger.debug("best path is: " + path);
    //if (path != null && path.size() > 2) {
    //  this.result = path.get(path.size() - 3);
    //} else
    if (path != null && !path.isEmpty()) {
      this.result = path.get(path.size() - 1);
    }
//    logger.debug("chose: " + result);
    return this;
  }


  private void reset() {
    this.searchMemory.clearAll();
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  private void updateMessage(MessageManager messageManager)
  {
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    changedEntities.add(this.agentInfo.getID());
    int time = this.agentInfo.getTime();

    for (Command command : Objects.requireNonNull(this.agentInfo.getHeard())) {
      if (command instanceof AKSpeak && ((AKSpeak) command).getChannel() == 0 && command.getAgentID() != this.agentInfo.getID()) {
        byte[] receivedData = ((AKSpeak) command).getContent();
        //logger.debug("speak: " + Arrays.toString(receivedData));
      }
    }

    for (Command command : Objects.requireNonNull(this.agentInfo.getHeard())) {
      if (command instanceof AKTell && command.getAgentID() != this.agentInfo.getID()) {
        byte[] receivedData = ((AKTell) command).getContent();
        //logger.debug("tell: " + Arrays.toString(receivedData));
      }
    }

    for (List<CommunicationMessage> lm : messageManager.getSendMessageList()) {
      //logger.debug("new channel: ");
      for (CommunicationMessage m : lm) {
        StandardMessage standardMessage = (StandardMessage) m;
        if (standardMessage.getSendingPriority() == StandardMessagePriority.LOW) {
//          logger.debug("send LOW priority message");
        } else if (standardMessage.getSendingPriority() == StandardMessagePriority.NORMAL) {
//          logger.debug("send NORMAL priority message");
        } else if (standardMessage.getSendingPriority() == StandardMessagePriority.HIGH) {
//          logger.debug("send HIGH priority message");
        }
      }
    }

    for(CommunicationMessage message : messageManager.getReceivedMessageList()) {
      //logger.debug("收到信息了");
      StandardMessage standardMessage = (StandardMessage) message;
      if (standardMessage.getSendingPriority() == StandardMessagePriority.LOW) {
        //logger.debug("LOW priority message from" + standardMessage.getSenderID());
      } else if (standardMessage.getSendingPriority() == StandardMessagePriority.NORMAL) {
        //logger.debug("NORMAL priority message from" + standardMessage.getSenderID());
      } else if (standardMessage.getSendingPriority() == StandardMessagePriority.HIGH) {
        //logger.debug("HIGH priority message from" + standardMessage.getSenderID());
      }
      Class<? extends CommunicationMessage> messageClass = message.getClass();
      if(messageClass == MessageBuilding.class) {
        MessageBuilding mb = (MessageBuilding)message;
        // logger.debug("received MessageBuilding: " + mb.getSendingPriority());
        if(!changedEntities.contains(mb.getBuildingID())) {
          MessageUtil.reflectMessage(this.worldInfo, mb);
        }
      } else if(messageClass == MessageRoad.class) {
        MessageRoad mr = (MessageRoad)message;
        // logger.debug("received MessageRoad" + mr.getSendingPriority());
        if(mr.isBlockadeDefined() && !changedEntities.contains(mr.getBlockadeID())) {
          MessageUtil.reflectMessage(this.worldInfo, mr);
        }

      } else if(messageClass == MessageCivilian.class) {
        MessageCivilian mc = (MessageCivilian) message;
        // logger.debug("received MessageCivilian" + mc.getSendingPriority());
        if(!changedEntities.contains(mc.getAgentID())){
          MessageUtil.reflectMessage(this.worldInfo, mc);
        }

      } else if(messageClass == MessageAmbulanceTeam.class) {
        MessageAmbulanceTeam mat = (MessageAmbulanceTeam)message;
        // logger.debug("received MessageAmbulanceTeam" + mat.getSendingPriority());
        if(!changedEntities.contains(mat.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, mat);
        }

      } else if(messageClass == MessageFireBrigade.class) {
        MessageFireBrigade mfb = (MessageFireBrigade) message;
        // logger.debug("received MessageFireBrigade" + mfb.getSendingPriority());
        if(!changedEntities.contains(mfb.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, mfb);
        }

      } else if(messageClass == MessagePoliceForce.class) {
        MessagePoliceForce mpf = (MessagePoliceForce) message;
        // logger.debug("received MessagePoliceForce" + mpf.getSendingPriority());
        if(!changedEntities.contains(mpf.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, mpf);
        }

      }
    }
  }

  public boolean hasSeachedinCluster(EntityID entityID, MessageManager messageManager) {
    int human_index = clustering.getClusterIndex(entityID);
    int me_index = clustering.getClusterIndex(this.agentInfo.getPosition());
    if (human_index != me_index) {
      for(CommunicationMessage communicationMessage: messageManager.getReceivedMessageList(MessageBuilding.class)){
        MessageBuilding mb = (MessageBuilding) communicationMessage;
        int mat_index = clustering.getClusterIndex(mb.getBuildingID());
        // logger.debug("======Message " + mat + "的目标是：" + mat.getTargetID());
        if (Objects.equals(mb.getBuildingID(), entityID)) {
          if (mat_index == human_index) {
            logger.debug("------" + entityID + "是" + "别的救护员" + "的目标-----");
            return true;
          }
        }
      }
    } else {
      for (CommunicationMessage communicationMessage: messageManager.getReceivedMessageList(MessageBuilding.class)) {
        MessageBuilding mb = (MessageBuilding) communicationMessage;
        if (!(this.worldInfo.getEntity(mb.getBuildingID()) instanceof Building)) {
          continue;
        }
        // logger.debug("======Message " + mat + "的目标是：" + mat.getTargetID());
        int mat_index = clustering.getClusterIndex(mb.getBuildingID());
        if (mat_index != human_index) {
          continue;
        }
        if (!(mb.getBuildingID().equals(entityID))) {
          continue;
        }
        if (mb.getSenderID().getValue()>this.agentInfo.getID().getValue()) {
          logger.debug("------" + entityID + "是"+"别的救护员"+"的目标-----");
          return true;
        }
      }
    }
    return false;
  }

}