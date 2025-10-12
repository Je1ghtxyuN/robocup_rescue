package SEU.module.complex.SEUFB;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.util.*;

import org.apache.log4j.Logger;

import SEU.module.algorithm.PathPlanning.PathHelper;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
/*
 * final5
 */
public class SampleBuildingDetector extends HumanDetector {

  private Clustering clustering;

  private EntityID result;

  private Logger logger;
  private MessageManager messageManager;
  private PathPlanning pathPlanning;
  private PathHelper pathHelper;
  private Set<Human> badHumans;
  private int lastBuriedness = 0;

  public SampleBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    this.pathPlanning = moduleManager.getModule(
        "SampleRoadDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.clustering);
    registerModule(this.pathPlanning);
    this.pathHelper = new PathHelper(ai, wi);
    this.badHumans = new HashSet<>();
  }


  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);
    updateBadHumans();
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
    this.pathHelper.updateInfo(messageManager);
    return this;
  }

  private void updateBadHumans(){
    if (this.agentInfo.getExecutedAction(agentInfo.getTime()-1) instanceof ActionRescue &&
    this.agentInfo.getExecutedAction(agentInfo.getTime()-2) instanceof ActionRescue){
      logger.debug("rescuing-----");
      ActionRescue actionLoad1 = (ActionRescue) this.agentInfo.getExecutedAction(agentInfo.getTime()-1);
      ActionRescue actionLoad2 = (ActionRescue) this.agentInfo.getExecutedAction(agentInfo.getTime()-2);
      if (actionLoad1.getTarget().equals(actionLoad2.getTarget())) {
        if (this.worldInfo.getEntity(actionLoad1.getTarget()) instanceof Human) {
          Human badHuman = (Human) this.worldInfo.getEntity(actionLoad1.getTarget());
          logger.debug("----- rescuing:" + badHuman + ":" + badHuman.getBuriedness());
          logger.debug("lastBuriedness:" + lastBuriedness);
          if (badHuman.getBuriedness() == lastBuriedness) {
            logger.debug("badHuman:" + badHuman);
            badHumans.add(badHuman);
          }
          lastBuriedness = badHuman.getBuriedness();
        }
      }
    }
  }

  @Override
  public HumanDetector calc() {

    EntityID agentPosition = this.agentInfo.getPosition();
    for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(CIVILIAN,AMBULANCE_TEAM,POLICE_FORCE,FIRE_BRIGADE)) {
      if (isValidHuman(standardEntity)) {
        Human h = (Human) standardEntity;
        if (!badHumans.contains(h)) {
          if (agentPosition.equals(h.getPosition()) && h.getBuriedness()>0) {
            this.result = h.getID();
            logger.debug("----- can rescue human: " + this.result);
            return this;
          }  
        }

      }
    }
    this.result = calcTarget();
    return this;

  }


  private EntityID calcTarget() {
    List<Human> rescueTargets = filterRescueTargets(
        this.worldInfo.getEntitiesOfType(CIVILIAN));
    List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
    List<Human> targets = rescueTargetsInCluster;   
    if (targets.isEmpty())
      targets = rescueTargets;
    if (!targets.isEmpty()) {
      targets.sort(new DistanceSorter(this.worldInfo, this.agentInfo.me()));
      Human selected = targets.get(0);
      logger.debug("Selected:" + selected);
      return selected.getID();
    }
    return null;
  }

  public boolean HasFB(Human human) {
    double pathLength = this.pathPlanning.getDistance(agentInfo.getPosition(), human.getPosition());
    int moveTime = (int)pathLength/15000;
    logger.debug("pathLength:" + pathLength);
    logger.debug("moveTime" + moveTime);
    int numOfFireBrigade = 0;
    for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)){
      MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
      if (messageFireBrigade.getAction()==MessageFireBrigade.ACTION_RESCUE&&messageFireBrigade.getTargetID().equals(human.getID())){
          numOfFireBrigade++;
      }
    }
    if (numOfFireBrigade!=0&&human.isBuriednessDefined()&&human.getBuriedness()/numOfFireBrigade<moveTime){
      return true;
    }
    if (numOfFireBrigade>=6) {
      return true;
    }
    return false;
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
      if (h.getBuriedness() == 0){
        logger.debug(next + "is unBuried");
        continue;
      }
      if (badHumans.contains(h)) {
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
      if (HasFB(h)) {
        continue;
      }
      if (this.pathHelper.getStayTime()>2) {
        continue;
      }
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

}