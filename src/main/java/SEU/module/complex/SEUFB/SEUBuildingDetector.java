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
import SEU.module.complex.common.LifeXpectancy;
import rescuecore2.misc.Pair;

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

    // 从AITFireBrigadeDetector移植的防聚集变量
  private Set<EntityID> avoidTasks = new HashSet<>();
  private Set<EntityID> nonTarget = new HashSet<>();
  private Set<EntityID> rescueCivTask = new HashSet<>();
  private Set<EntityID> rescueAgentTask = new HashSet<>();
  
  // 防聚集计数器
  private int[] f = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  final private int FB_COUNTER = f.length;
  final private int RESCUE_MAX_FB = 3;


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
            // 从AITFireBrigadeDetector移植的消息处理
      else if (messageClass == MessageFireBrigade.class) {
        handleMessage((MessageFireBrigade) message);
      } else if (messageClass == MessageAmbulanceTeam.class) {
        handleMessage((MessageAmbulanceTeam) message);
      } else if (messageClass == MessagePoliceForce.class) {
        handleMessage((MessagePoliceForce) message);
      }
    }
    this.messageManager = messageManager;

    updateTask();
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
    // 从AITFireBrigadeDetector移植的防聚集逻辑：更新FB数量统计
    int n = 0;
    Collection<StandardEntity> FBs = this.worldInfo.getEntitiesOfType(FIRE_BRIGADE);
    for (StandardEntity entity : FBs) {
        if (entity instanceof FireBrigade) {
            n++;
        }
    }
    final int idx = this.agentInfo.getTime() % FB_COUNTER;
    this.f[idx] = n;

    EntityID agentPosition = this.agentInfo.getPosition();
    
    // 从AITFireBrigadeDetector移植：按位置分组处理目标
    Map<EntityID, Set<EntityID>> civPos = new HashMap<>();
    Map<EntityID, Set<EntityID>> ambPos = new HashMap<>();
    Map<EntityID, Set<EntityID>> fbPos = new HashMap<>();
    
    // 填充位置分组数据
    for (StandardEntity entity : this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, AMBULANCE_TEAM, FIRE_BRIGADE)) {
        if (!(entity instanceof Human)) continue;
        
        Human human = (Human) entity;
        if (!isValidHuman(human)) continue;
        if (badHumans.contains(human)) continue;
        
        EntityID positionID = human.getPosition();
        StandardEntityURN urn = human.getStandardURN();
        
        if (rescueTarget(human.getID())) {
            if (urn == CIVILIAN) {
                civPos.computeIfAbsent(positionID, k -> new HashSet<>()).add(human.getID());
            } else if (urn == AMBULANCE_TEAM) {
                ambPos.computeIfAbsent(positionID, k -> new HashSet<>()).add(human.getID());
            } else if (urn == FIRE_BRIGADE) {
                fbPos.computeIfAbsent(positionID, k -> new HashSet<>()).add(human.getID());
            }
        }
    }

    // 优先处理当前位置的目标（从AITFireBrigadeDetector移植）
    Set<EntityID> targetsAtMyPosition = civPos.get(agentPosition);
    if (targetsAtMyPosition != null) {
        Set<EntityID> FBsAtPosition = fbPos.get(agentPosition);
        int cnt = 0;
        for (EntityID e : idSort(targetsAtMyPosition)) {
            if (joinRescue(FBsAtPosition, cnt, e)) {
                this.result = e;
                logger.debug("----- can rescue human at my position: " + this.result);
                return this;
            } else {
                this.nonTarget.add(e);
            }
            cnt++;
        }
        civPos.remove(agentPosition);
    }

    // 处理其他位置的目标（从AITFireBrigadeDetector移植）
    for (Map.Entry<EntityID, Set<EntityID>> es : civPos.entrySet()) {
        Set<EntityID> FBsAtPosition = fbPos.get(es.getKey());
        final int FBnum = (FBsAtPosition == null) ? 1 : FBsAtPosition.size() + 1;
        int cnt = 0;
        for (EntityID e : idSort(es.getValue())) {
            if (this.avoidTasks.contains(e)) {
                continue;
            }
            if (canSave(e, Math.max(FBnum, getMaxFBcounter()))) {
                if (joinRescue(FBsAtPosition, cnt)) {
                    this.result = e;
                    return this;
                } else {
                    this.avoidTasks.add(e);
                }
                cnt++;
            }
        }
    }

    // 如果没有找到目标，从通信任务中选择（从AITFireBrigadeDetector移植）
    if (this.result == null) {
        this.result = selectReceiveTask();
    }

    refreshInfo();
    return this;
  }

  // 从AITFireBrigadeDetector移植的方法
  private boolean rescueTarget(EntityID e) {
    if (e == null) {
      return false;
    }
    final Human h = (Human) this.worldInfo.getEntity(e);
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    return (hp > 0 && buried > 0);
  }

  private Set<EntityID> idSort(Set<EntityID> list) {
    if (list == null) {
      return null;
    }
    if (list.isEmpty()) {
      return null;
    }
    int[] ids = new int[list.size()];
    int idx = 0;
    for (EntityID e : list) {
      ids[idx++] = e.getValue();
    }
    Arrays.sort(ids);
    Set<EntityID> ret = new HashSet<>();
    for (int i : ids) {
      ret.add(new EntityID(i));
    }
    return ret;
  }

  private boolean joinRescue(Set<EntityID> list, int n) {
    if (list == null) {
      return true;
    }
    list.add(this.agentInfo.getID());
    final int max = Math.min(list.size(), getMaxRescueNumber() + getMaxRescueNumber() * n);
    int[] ids = new int[list.size()];
    int idx = 0;
    for (EntityID e : list) {
      ids[idx++] = e.getValue();
    }
    Arrays.sort(ids);
    final int myID = this.agentInfo.getID().getValue();
    for (int i = 0; i < max; ++i) {
      if (ids[i] == myID) {
        return true;
      }
    }
    return false;
  }

  private boolean joinRescue(Set<EntityID> list, int n, EntityID id) {
    if (list == null) {
      return true;
    }
    list.add(this.agentInfo.getID());
    final int needNum = getMaxRescueNumber(id) + getMaxRescueNumber(id) * n;
    final int max = Math.min(list.size(), needNum);
    int[] ids = new int[list.size()];
    int idx = 0;
    for (EntityID e : list) {
      ids[idx++] = e.getValue();
    }
    Arrays.sort(ids);
    final int myID = this.agentInfo.getID().getValue();
    for (int i = 0; i < max; ++i) {
      if (ids[i] == myID) {
        return true;
      }
    }
    return false;
  }

  private int getMaxRescueNumber() {
    return RESCUE_MAX_FB + (int) (this.agentInfo.getTime() * 0.01);
  }

  private int getMaxRescueNumber(EntityID id) {
    return getNeedFBnum(id) + (int) (this.agentInfo.getTime() * 0.01);
  }

  private int getNeedFBnum(EntityID id) {
    LifeXpectancy lx = new LifeXpectancy((Human) this.worldInfo.getEntity(id));
    final int b = ((Human) this.worldInfo.getEntity(id)).getBuriedness();
    final int l = lx.getLifeXpectancy();
    int f = 1;
    while (l - b / f <= 0) {
      ++f;
    }
    return f + 1;
  }

  private boolean canSave(EntityID id, int n) {
    final StandardEntity s = this.worldInfo.getEntity(id);
    if (((Human) s).getBuriedness() == 0 || ((Human) s).getHP() == 0) {
      return false;
    }
    if (((Civilian) s).getBuriedness() > 0 && ((Civilian) s).getHP() > 0) {
      final LifeXpectancy lx = new LifeXpectancy((Human) s);
      final int ttl = lx.getLifeXpectancy();
      return (ttl - rescueTime(id, n) > 0);
    } else {
      return true;
    }
  }

  private int rescueTime(EntityID id, int n) {
    return ((Human) this.worldInfo.getEntity(id)).getBuriedness() / n;
  }

  private int getMaxFBcounter() {
    int max = 0;
    for (int i : this.f) {
      if (i > max) {
        max = i;
      }
    }
    return max;
  }

  private void handleMessage(MessageFireBrigade msg) {
    final EntityID targetID = msg.getAgentID();
    if(msg.getAction() != 5) return; // HELP_BURIED
    if (rescueTarget(targetID)) {
      rescueAgentTask.add(targetID);
      nonTarget.add(targetID);
    } else {
      rescueAgentTask.remove(targetID);
    }
  }

  private void handleMessage(MessageAmbulanceTeam msg) {
    final EntityID targetID = msg.getAgentID();
    if(msg.getAction() != 5) return; // HELP_BURIED
    if (rescueTarget(targetID)) {
      rescueAgentTask.add(targetID);
      nonTarget.add(targetID);
    } else {
      rescueAgentTask.remove(targetID);
    }
  }

  private void handleMessage(MessagePoliceForce msg) {
    final EntityID targetID = msg.getAgentID();
    if(msg.getAction() != 5) return; // HELP_BURIED
    if (rescueTarget(targetID)) {
      rescueAgentTask.add(targetID);
      nonTarget.add(targetID);
    } else {
      rescueAgentTask.remove(targetID);
    }
  }

  private void updateTask() {
    Iterator<EntityID> i = rescueCivTask.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!rescueTarget(e)) {
        nonTarget.add(e);
        i.remove();
      }
    }
    i = rescueAgentTask.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!rescueTarget(e)) {
        nonTarget.add(e);
        i.remove();
      }
    }
    rescueCivTask.removeAll(avoidTasks);
    rescueCivTask.removeAll(nonTarget);
    rescueAgentTask.removeAll(nonTarget);
    i = avoidTasks.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!rescueTarget(e)) {
        nonTarget.add(e);
        i.remove();
      }
    }
    if (rescueAgentTask.isEmpty()) {
      avoidTasks.clear();
    }
  }

  private EntityID selectReceiveTask() {
    if (!rescueAgentTask.isEmpty()) {
      return getPriorityTask(rescueAgentTask);
    }
    if (!rescueCivTask.isEmpty()) {
      return getPriorityTask(rescueCivTask);
    }
    return null;
  }

  private EntityID getPriorityTask(Set<EntityID> tasks) {
    EntityID ret = null;
    double priority = Double.MAX_VALUE;
    for (EntityID e : tasks) {
      final Human h = (Human) this.worldInfo.getEntity(e);
      final Pair<Integer, Integer> p = this.worldInfo.getLocation(e);
      final double distance = Math.abs(this.agentInfo.getX() - p.first()) + Math.abs(this.agentInfo.getY() - p.second());
      final double cost1 = Math.sqrt(distance);
      final LifeXpectancy lx = new LifeXpectancy(h);
      final double cost2 = lx.getLifeXpectancy() - (h.getDamage() * h.getBuriedness());
      final double cost = cost1 + cost2;
      if (priority > cost) {
        priority = cost;
        ret = e;
      }
    }
    return ret;
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
