package SEU.extaction;

import SEU.module.algorithm.PathPlanning.PathHelper;
import SEU.world.SEUGeomTool;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
//import com.google.common.collect.Lists;
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import java.util.*;

import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

public class DefaultExtActionTransport extends ExtAction {

  private PathPlanning pathPlanning;
  private Logger logger;
  private int thresholdRest;
  private int kernelTime;
  private PathHelper pathHelper;
  private EntityID target;
  private MessageManager messageManager;
  private SEUGeomTool seuGeomTool;

  public DefaultExtActionTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo, ModuleManager moduleManager, DevelopData developData) {
    super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);    
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.target = null;
    this.pathHelper = new PathHelper(agentInfo, worldInfo);
    this.seuGeomTool = new SEUGeomTool(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    this.thresholdRest = developData
        .getInteger("adf.impl.extaction.DefaultExtActionTransport.rest", 100);

    switch (scenarioInfo.getMode()) {
      case PRECOMPUTATION_PHASE:
      case PRECOMPUTED:
      case NON_PRECOMPUTE:
        this.pathPlanning = moduleManager.getModule(
            "DefaultExtActionTransport.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
        break;
    }
  }


  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    logger.debug("Time:" + agentInfo.getTime());
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    this.pathPlanning.precompute(precomputeData);
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }


  public ExtAction resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.pathPlanning.resume(precomputeData);
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }


  public ExtAction preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.pathPlanning.preparate();
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }


  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.pathPlanning.updateInfo(messageManager);
    this.pathHelper.updateInfo(messageManager);
    this.messageManager = messageManager;
    return this;
  }


  @Override
  public ExtAction setTarget(EntityID target) {
    this.target = null;
    if (target != null) {
      StandardEntity entity = this.worldInfo.getEntity(target);
      if (entity instanceof Human || entity instanceof Area) {
        this.target = target;
        return this;
      }
    }
    return this;
  }


  @Override
  public ExtAction calc() {
    this.result = null;
    AmbulanceTeam agent = (AmbulanceTeam) this.agentInfo.me();
    Human transportHuman = this.agentInfo.someoneOnBoard();

    if (transportHuman != null) {
      this.result = this.calcUnload(agent, this.pathPlanning, transportHuman,
          this.target);
      if (this.result != null) {
        return this;
      }
    }
    if (this.needRest(agent)) {
      EntityID areaID = this.convertArea(this.target);
      ArrayList<EntityID> targets = new ArrayList<>();
      if (areaID != null) {
        targets.add(areaID);
      }
      this.result = this.calcRefugeAction(agent, this.pathPlanning, targets,
          false);
      if (this.result != null) {
        return this;
      }
    }
    if (this.target != null) {
      this.result = this.calcRescue(agent, this.pathPlanning, this.target);
    }
    return this;
  }


  private Action calcRescue(AmbulanceTeam agent, PathPlanning pathPlanning,
      EntityID targetID) {
    StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
    if (targetEntity == null) {
      return null;
    }
    EntityID agentPosition = agent.getPosition();
    if (targetEntity instanceof Human) {
      Human human = (Human) targetEntity;
      if (!human.isPositionDefined()) {
        return null;
      }
      if (human.isHPDefined() && human.getHP() == 0) {
        return null;
      }
      EntityID targetPosition = human.getPosition();
      if (agentPosition.equals(targetPosition)) {
        if ((human.getStandardURN() == CIVILIAN) &&
                (!human.isBuriednessDefined() || (human.isBuriednessDefined() && (human.getBuriedness() == 0)))) {
          logger.debug("======= Loading: " + targetEntity+ "=======");
          return new ActionLoad(human.getID());
        }
        else if(human.getBuriedness()>0){
          logger.debug("====== Waiting "+human+"======");
          return new ActionRest();
        }
      } else {
        List<EntityID> path = pathPlanning.getResult(agentPosition,
            targetPosition);
        if (path != null && path.size() > 0) {
          logger.debug("====== Moving to target "+human+"======");
          return new ActionMove(path);
        }
      }
      return null;
    }
    if (targetEntity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) targetEntity;
      if (blockade.isPositionDefined()) {
        targetEntity = this.worldInfo.getEntity(blockade.getPosition());
      }
    }
    if (targetEntity instanceof Area) {
      List<EntityID> path = pathPlanning.getResult(agentPosition,
          targetEntity.getID());
      if (path != null && path.size() > 0) {
        return new ActionMove(path);
      }
    }
    return null;
  }


  private Action calcUnload(AmbulanceTeam agent, PathPlanning pathPlanning,
      Human transportHuman, EntityID targetID) {
    if (transportHuman == null) {
      return null;
    }
    if (transportHuman.isHPDefined() && transportHuman.getHP() == 0) {
      return new ActionUnload();
    }
    EntityID agentPosition = agent.getPosition();
    if (targetID == null
        || transportHuman.getID().getValue() == targetID.getValue()) {
      StandardEntity position = this.worldInfo.getEntity(agentPosition);
      if (position != null && position.getStandardURN() == REFUGE) {
        return new ActionUnload();
      } else {
        pathPlanning.setFrom(agentPosition);
        if (getRefuge().isEmpty()) {
          pathPlanning.setDestination(this.worldInfo.getEntityIDsOfType(REFUGE));
        }else{
          pathPlanning.setDestination(getRefuge());
        }
        // pathPlanning.setDestination(this.worldInfo.getEntityIDsOfType(REFUGE));
        
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && path.size() > 0) {
          logger.debug("====== Transporting the target "+ targetID +"=======");
          if (this.pathHelper.getStayTime()>=4) {
            messageManager.addMessage( new CommandPolice( true, StandardMessagePriority.NORMAL,null,
                            this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR ) );
          }
          return new ActionMove(path);
        }
      }
    }
    if (targetID == null) {
      return null;
    }
    StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
    if (targetEntity != null && targetEntity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) targetEntity;
      if (blockade.isPositionDefined()) {
        targetEntity = this.worldInfo.getEntity(blockade.getPosition());
      }
    }
    if (targetEntity instanceof Area) {
      if (agentPosition.getValue() == targetID.getValue()) {
        return new ActionUnload();
      } else {
        pathPlanning.setFrom(agentPosition);
        pathPlanning.setDestination(targetID);
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && path.size() > 0) {
          return new ActionMove(path);
        }
      }
    } else if (targetEntity instanceof Human) {
      Human human = (Human) targetEntity;
      List<EntityID> list = new ArrayList<>();

      if (human.isPositionDefined()) {
        list.add(human.getPosition());
        return calcRefugeAction(agent, pathPlanning,
            list, true);
      }
      pathPlanning.setFrom(agentPosition);
      pathPlanning.setDestination(this.worldInfo.getEntityIDsOfType(REFUGE));
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        return new ActionMove(path);
      }
    }
    return null;
  }


  private boolean needRest(Human agent) {
    int hp = agent.getHP();
    int damage = agent.getDamage();
    if (hp == 0 || damage == 0) {
      return false;
    }
    int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
    if (this.kernelTime == -1) {
      try {
        this.kernelTime = this.scenarioInfo.getKernelTimesteps();
      } catch (NoSuchConfigOptionException e) {
        this.kernelTime = -1;
      }
    }
    return damage >= this.thresholdRest
        || (activeTime + this.agentInfo.getTime()) < this.kernelTime;
  }


  private EntityID convertArea(EntityID targetID) {
    StandardEntity entity = this.worldInfo.getEntity(targetID);
    if (entity == null) {
      return null;
    }
    if (entity instanceof Human) {
      Human human = (Human) entity;
      if (human.isPositionDefined()) {
        EntityID position = human.getPosition();
        if (this.worldInfo.getEntity(position) instanceof Area) {
          return position;
        }
      }
    } else if (entity instanceof Area) {
      return targetID;
    } else if (entity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) entity;
      if (blockade.isPositionDefined()) {
        return blockade.getPosition();
      }
    }
    return null;
  }


  private Action calcRefugeAction(Human human, PathPlanning pathPlanning,
      Collection<EntityID> targets, boolean isUnload) {
    EntityID position = human.getPosition();
    Collection<EntityID> refuges = this.worldInfo
        .getEntityIDsOfType(StandardEntityURN.REFUGE);
    int size = refuges.size();
    if (refuges.contains(position)) {
      return isUnload ? new ActionUnload() : new ActionRest();
    }
    List<EntityID> firstResult = null;
    while (refuges.size() > 0) {
      pathPlanning.setFrom(position);
      pathPlanning.setDestination(refuges);
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        if (firstResult == null) {
          firstResult = new ArrayList<>(path);
          if (targets == null || targets.isEmpty()) {
            break;
          }
        }
        EntityID refugeID = path.get(path.size() - 1);
        pathPlanning.setFrom(refugeID);
        pathPlanning.setDestination(targets);
        List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
        if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
          return new ActionMove(path);
        }
        refuges.remove(refugeID);
        // remove failed
        if (size == refuges.size()) {
          break;
        }
        size = refuges.size();
      } else {
        break;
      }
    }
    return firstResult != null ? new ActionMove(firstResult) : null;
  }
  
  /*
   * 选择庇护所
   */
  private Collection<EntityID> getRefuge() {
    Set<EntityID> refuges = new HashSet<>();
    Collection<EntityID> allRefuge = this.worldInfo.getEntityIDsOfType(REFUGE);
    for(EntityID entityID : allRefuge) {
      int counter = 0;
      for(StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(CIVILIAN)){
        if (!(standardEntity instanceof Civilian)) {
          continue;
        }
        Civilian civilian = (Civilian) standardEntity;
        if (civilian.getPosition().equals(entityID)) {
          counter++;

        }
        if(counter>=2){
          refuges.add(entityID);
          break;
        }
      }

      Set<EntityID> entrances = this.seuGeomTool.getAllEntrancesOfBuilding((Building) this.worldInfo.getEntity(entityID));
      for(EntityID e : entrances){
        if (!(this.worldInfo.getEntity(e) instanceof Road)) {
          continue;
        }
        Road road = (Road)this.worldInfo.getEntity(e);
        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
          refuges.add(entityID);
          break;
        }
      }

    }
    logger.debug("Refuges:"+refuges);
    return refuges;
  }

  public Boolean shouldWait(Human civilian){
    int numOfFireBrigade = 0;
    for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)){
        MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
        if (messageFireBrigade.getAction()==MessageFireBrigade.ACTION_RESCUE&&messageFireBrigade.getTargetID().equals(civilian.getID())){
            numOfFireBrigade++;
        }
    }
    if (numOfFireBrigade!=0&&civilian.isBuriednessDefined()&&civilian.getBuriedness()/numOfFireBrigade<10){
        return true;
    }
    return false;
  }
}
