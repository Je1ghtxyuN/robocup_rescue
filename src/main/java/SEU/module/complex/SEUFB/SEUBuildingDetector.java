package SEU.module.complex.SEUFB;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import adf.core.agent.action.Action;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.information.*;
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
import adf.core.agent.action.ambulance.*;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

public class SEUBuildingDetector extends HumanDetector {

  private Clustering clustering;
  private EntityID result;
  private Logger logger;
  private MessageManager messageManager;
  private PathPlanning pathPlanning;
  private double lastPositionX;
  private double lastPositionY;
  private List<Human> blacklist;
  private Set<Human> badHumans;
  private int lastBuriedness = 0;


  public SEUBuildingDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
            "adf.impl.module.algorithm.KMeansClustering");
    this.pathPlanning = moduleManager.getModule(
            "SampleRoadDetector.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.clustering);
    registerModule(this.pathPlanning);

    lastPositionX=-10000;
    lastPositionY=-10000;
    blacklist = new ArrayList<>();
    this.badHumans = new HashSet<>();
  }


  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {

    super.updateInfo(messageManager);
    updateBadHumans();
    Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();

    for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
      Class<? extends CommunicationMessage> messageClass = message.getClass();
      if (messageClass == MessageCivilian.class) {
        updateCivilianMessage((MessageCivilian) message, changedEntities);
      } 
    }
    this.messageManager = messageManager;
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
    for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(CIVILIAN)) {
      if (isValidHuman(standardEntity)) {
        Human h = (Human) standardEntity;
        if (!(badHumans.contains(h))) {
          if (agentPosition.equals(h.getPosition()) || (Rescuing(h)&&near(agentPosition, h.getPosition()))) {
            if (h.getBuriedness()>0) {
              this.result = h.getID();
              logger.debug("----- can rescue human: " + this.result);
              return this;
            }
          }
        }
      }
    }
    
    Random random = new Random();

    if(this.result!=null&&isblocked()&&random.nextInt(10)>6){
      this.blacklist.add((Human) this.worldInfo.getEntity(this.result));
    }

    if(this.result!=null&&tooMuchFB()&&random.nextInt(10)>6){

      this.blacklist.add((Human) this.worldInfo.getEntity(this.result));

    }
    this.result = calcTarget();

    refreshInfo();
    return this;

  }


  private EntityID calcTarget() {

    List<Human> rescueTargets = filterRescueTargets(this.worldInfo.getEntitiesOfType(CIVILIAN,
            POLICE_FORCE,AMBULANCE_TEAM,FIRE_BRIGADE));
    List<Human> targets = filterInCluster(rescueTargets);

    targets.removeAll(this.blacklist);
    if (targets.isEmpty()) {
      targets = rescueTargets;
      targets.removeAll(this.blacklist);
    }
    if (targets.isEmpty()) targets = rescueTargets;

    if (!targets.isEmpty()) {
      targets.sort(new Sorter(this.worldInfo, this.agentInfo.me()));
      Human selected = targets.get(0);
      return selected.getID();
    }

    return null;

  }

  private boolean isblocked(){

    if(getLastAction()==null){
      return false;
    }

    if (getLastAction()!=null&&(getLastAction().getClass()==ActionRescue.class)){
      return false;
    }

    double positionX = this.agentInfo.getX();
    double positionY = this.agentInfo.getY();

    Collection<StandardEntity> policeForces = this.worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE);
    for(StandardEntity entity:policeForces){

      PoliceForce police = (PoliceForce) entity;
      if(getDistance(police.getX(),police.getY(),positionX,positionY)<17000){
        return false;
      }

    }

    if(getDistance(this.lastPositionX,this.lastPositionY,positionX,positionY)>3000){
      return false;
    }

    return true;

  }

  private boolean tooMuchFB(){

    Collection<StandardEntity> FBs = this.worldInfo.getEntitiesOfType(FIRE_BRIGADE);
    int sametarget = 0;
    for (StandardEntity entity : FBs){

      FireBrigade fireBrigade = (FireBrigade) entity;

      if (this.agentInfo.getPosition().equals((this.worldInfo.getPosition(fireBrigade)).getID())){
        sametarget++;
      }

      if((sametarget > 3)){
        return true;
      }

    }

    return false;
  }

  private void refreshInfo(){

    if(this.agentInfo.getTime()%15==0) this.blacklist.clear();
    this.lastPositionX = this.agentInfo.getX();
    this.lastPositionY = this.agentInfo.getY();

  }

  private Action getLastAction(){

    int time = this.agentInfo.getTime();
    return this.agentInfo.getExecutedAction(time-1);

  }

  private double getDistance(double fromX, double fromY, double toX, double toY) {
    double dx = toX - fromX;
    double dy = toY - fromY;
    return Math.hypot(dx, dy);
  }


  private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {

    List<Human> rescueTargets = new ArrayList<>();

    for (StandardEntity next : list) {

      if (!(next instanceof Human)) continue;

      Human human = (Human) next;

      if (!isValidHuman(human)) continue;

      if (human.getBuriedness() == 0){
        continue;
      }

      if (badHumans.contains(human)) {
        continue;
      }
      
      rescueTargets.add(human);
    }

    return rescueTargets;

  }


  private List<Human>
  filterInCluster(Collection<? extends StandardEntity> entities) {
    int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
    List<Human> filter = new ArrayList<>();

    HashSet<StandardEntity> inCluster = new HashSet<>(clustering.getClusterEntities(clusterIndex));

    for (StandardEntity next : entities) {

      if (!(next instanceof Human)) continue;

      Human human = (Human) next;

      if (!human.isPositionDefined()) continue;

      StandardEntity position = this.worldInfo.getPosition(human);

      if (position == null) continue;

      if (!inCluster.contains(position)) continue;

      filter.add(human);
    }

    return filter;

  }

  private static class Sorter implements Comparator<Human>{

    private StandardEntity reference;
    private WorldInfo worldInfo;

    Sorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    public int compare(Human a,Human b){

      int distance1 = this.worldInfo.getDistance(this.reference, a);
      int distance2 = this.worldInfo.getDistance(this.reference, b);

      return distance1-distance2;

    }


  }


  private void updateCivilianMessage(MessageCivilian messageCivilian, Collection<EntityID> changedEntities){
    if (!changedEntities.contains(messageCivilian.getAgentID())) {
      MessageUtil.reflectMessage(this.worldInfo, messageCivilian);
    }
  }

  private boolean isValidHuman(StandardEntity entity) {

    if (entity == null) {
      return false;
    }

    if (!(entity instanceof Human)) {
      return false;
    }

    Human target = (Human) entity;
    if (!target.isHPDefined() || target.getHP() == 0) {
      return false;
    }

    if (!target.isPositionDefined()){
      return false;
    }

    if (!target.isDamageDefined() || target.getDamage() == 0){
      return false;
    }

    if (!target.isBuriednessDefined()||target.getDamage()==0){
      return false;
    }


    StandardEntity position = worldInfo.getPosition(target);
    if (position == null) {
      return false;
    }

    StandardEntityURN positionURN = position.getStandardURN();

    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM) {
      return false;
    }

    return true;


  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  private boolean near(EntityID entityID1, EntityID entityID2) {
    int dis = this.worldInfo.getDistance(entityID1, entityID2);
    if (dis<150000) {
      return true;
    }
    return false;
  }

  public boolean Rescuing(Human human) {

    int numOfFireBrigade2 = 0;
    for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)){
      MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
      if (messageFireBrigade.getAction()==MessageFireBrigade.ACTION_RESCUE&&messageFireBrigade.getTargetID().equals(human.getID())){
          numOfFireBrigade2++;
      }
    }
    if (numOfFireBrigade2>1){
      return true;
    }
    return false;
  }

}
