package SEU.module.complex.SEUAT;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import java.util.*;

import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

public class SampleHumanDetector extends HumanDetector {

  private Clustering clustering;
  private EntityID result;
  private MessageManager messageManager;
  private Logger logger;
  private Set<Human> wrongCivilians;


  public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
    this.wrongCivilians = new HashSet<>();

  }


  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);
    undateWrongCivilians();

    //信息
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
    for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
      Class<? extends CommunicationMessage> messageClass = message.getClass();
      if (messageClass == MessageCivilian.class) {
        MessageCivilian messageCivilian = (MessageCivilian)message;
        if (!changedEntities.contains(messageCivilian.getAgentID())) {
          MessageUtil.reflectMessage(this.worldInfo, messageCivilian);
        }
      } 
    }

    if (this.messageManager==null){
      this.messageManager = messageManager;
    }
    
    return this;
  }

  private void undateWrongCivilians() {
    if (this.agentInfo.getExecutedAction(agentInfo.getTime()-1) instanceof ActionLoad &&
			this.agentInfo.getExecutedAction(agentInfo.getTime()-2) instanceof ActionLoad){
        ActionLoad actionLoad1 = (ActionLoad) this.agentInfo.getExecutedAction(agentInfo.getTime()-1);
        ActionLoad actionLoad2 = (ActionLoad) this.agentInfo.getExecutedAction(agentInfo.getTime()-2);
        if (actionLoad1.getTarget().equals(actionLoad2.getTarget())) {
          if (this.worldInfo.getEntity(actionLoad1.getTarget()) instanceof Human) {
            Human badHuman = (Human) this.worldInfo.getEntity(actionLoad1.getTarget());
            logger.debug("badHuman:" + badHuman);
            wrongCivilians.add(badHuman);
          }
        }
      }
  }

  @Override
  public HumanDetector calc() {

    logger.debug("----- HumanDetector start -----");
    // human can transport
    Human transportHuman = this.agentInfo.someoneOnBoard();
    if (transportHuman != null) {
      logger.debug("someoneOnBoard:" + transportHuman);
      this.result = transportHuman.getID();
      return this;
    }

    // human can load or wait
    EntityID agentPosition = this.agentInfo.getPosition();
    for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
      if (isValidHuman(standardEntity)) {
        Human h = (Human) standardEntity;
        if (!(wrongCivilians.contains(h))) {
          if (agentPosition.equals(h.getPosition()) && h.getBuriedness()<15 && !hasATWait(h)) {
            this.result = h.getID();
            logger.debug("----- can load human: " + this.result);
            return this;
          }
        }
      }
    }

    // calc the target
    this.result = calcTarget();
    logger.debug("the result:" + this.result);
    return this;
  }


  private EntityID calcTarget() {
    logger.debug("----- Human calc targets start -----");
    List<Human> rescueTargets = filterRescueTargets(
        this.worldInfo.getEntitiesOfType(CIVILIAN));
    List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
    List<Human> targets = rescueTargetsInCluster;
    if (targets.isEmpty())
      targets = rescueTargets;
    if (!targets.isEmpty()) {
      targets.sort(new DistanceSorter(this.worldInfo, this.agentInfo.me()));
      Human selected = targets.get(0);
      return selected.getID();
    }
    return null;
  }


  @Override
  public EntityID getTarget() {
    return this.result;
  }


  /*
   * 全部目标
   */
  private List<Human>
      filterRescueTargets(Collection<? extends StandardEntity> list) {
    List<Human> rescueTargets = new ArrayList<>();
    for (StandardEntity next : list) {
      if (!(next instanceof Human))
        continue;
      Human h = (Human) next;
      if (!isValidHuman(h))
        continue;
      if (hasATWait(h))
        continue;
      if (wrongCivilians.contains(h)) {
        continue;
      }

      if (hasATinCluster(h))
        continue;
      if (!shouldWait(h)) {
        messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH,(Civilian)h));
        continue;
      }


      rescueTargets.add(h);
    }

    return rescueTargets;
  }

  /*
   * 聚类中的目标
   */
  private List<Human>
      filterInCluster(Collection<? extends StandardEntity> entities) {

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

      // if (hasATinCluster(h))
      //   continue;

      filter.add(h);
    }
    return filter;
  }

  private class DistanceSorter implements Comparator<StandardEntity> {

    private StandardEntity reference;
    private WorldInfo worldInfo;

    DistanceSorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    public int compare(StandardEntity a, StandardEntity b) {
      int d1 = this.worldInfo.getDistance(this.reference, a);
      int d2 = this.worldInfo.getDistance(this.reference, b);
      return d1 - d2;
    }
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

    StandardEntity position = worldInfo.getPosition(target);
    if (position == null)
      return false;

    StandardEntityURN positionURN = position.getStandardURN();
    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
      return false;

    return true;
  }


  /*
   * 以下部分依赖通信，部分内容需要用通信相关重写
   */
  
  public boolean shouldWait(Human human) {
    if (isValidHuman(human) && human.getBuriedness()<15) {
      return true;
    }
    int numOfFireBrigade2 = 0;
    for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)){
      MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
      if (messageFireBrigade.getAction()==MessageFireBrigade.ACTION_RESCUE&&messageFireBrigade.getTargetID().equals(human.getID())){
          numOfFireBrigade2++;
      }
    }
    if (numOfFireBrigade2!=0&&human.isBuriednessDefined()&&human.getBuriedness()/numOfFireBrigade2<20){
      return true;
    }
    return false;
  }


  // 已经有救护员等待
  public boolean hasATWait(Human human){
    int numOfHuman = 0;
    int numOfAmbulanceTeam = 0;
    for (StandardEntity se : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
      Civilian civilian = (Civilian)se;
      if (civilian.getPosition().equals(human.getPosition())) {
        numOfHuman++;
      }
    }
    if (this.agentInfo.getPosition().equals(human.getPosition())) {
      for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)){
        AmbulanceTeam ambulanceTeam = (AmbulanceTeam) standardEntity;

        if (this.agentInfo.getID().getValue() >= ambulanceTeam.getID().getValue()) {
          continue;
        }
        if (ambulanceTeam.getPosition().equals(human.getPosition())){
            numOfAmbulanceTeam++;
        }
      }
    }else{
      for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
        AmbulanceTeam ambulanceTeam = (AmbulanceTeam) standardEntity;
        if (ambulanceTeam.getPosition().equals(human.getPosition())){
          numOfAmbulanceTeam++;
        }
      }
    }

    if (numOfAmbulanceTeam>=numOfHuman){
      logger.debug("----- 已经有救护员等待 -----");
        return true;
    }
    return false;
  }

  // 已经是别的救护员的目标
  // 没有效果
  public boolean hasATinCluster(Human human) {
    int human_index = clustering.getClusterIndex(human.getPosition());
    int me_index = clustering.getClusterIndex(this.agentInfo.getPosition());
    if (human_index != me_index) {
      int counter = 0;
      for(CommunicationMessage communicationMessage: messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)){
        MessageAmbulanceTeam mat = (MessageAmbulanceTeam) communicationMessage;
        int mat_index = clustering.getClusterIndex(mat.getPosition());
        // logger.debug("======Message " + mat + "的目标是：" + mat.getTargetID());
        if (mat.getTargetID().equals(human.getPosition()) || mat.getTargetID().equals(human.getID())) {
          if (mat_index == human_index) {
            counter++;
            // logger.debug("------别的救护员的目标-----");
            // return true;
          }
          if (counter>2) {
            logger.debug("------别的救护员的目标-----");
            return true;
          }
        }
      }
    }else{
      int counter =0;
      for(CommunicationMessage communicationMessage: messageManager.getReceivedMessageList(MessageAmbulanceTeam.class)) {
        MessageAmbulanceTeam mat = (MessageAmbulanceTeam) communicationMessage;
        if (!(this.worldInfo.getEntity(mat.getTargetID()) instanceof Human)) {
          continue;
        }
        // logger.debug("======Message " + mat + "的目标是：" + mat.getTargetID());
        int mat_index = clustering.getClusterIndex(mat.getPosition());
        if (mat_index != human_index) {
          continue;
        }
        if (!(mat.getTargetID().equals(human.getPosition()) || mat.getTargetID().equals(human.getID()))) {
          continue;
        }
        if (mat.getAgentID().getValue()>this.agentInfo.getID().getValue()) {
          counter++;

        }
        if (counter>3) {
          logger.debug("------别的救护员的目标-----");
          return true;
        }
      }
    }
    return false;
  }

}