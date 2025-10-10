package AIT_2022.extaction;

import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import java.util.Collection;
import java.util.List;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;


public class AITExtActionMove extends ExtAction {

  private PathPlanning pathPlanning = null;
  private Clustering stuckedHumans = null;

  private int thresholdRestDamage = -1;
  private int kernelTime = -1;

  private EntityID targetID = null;

  private boolean shouldSendFireBrigade = false;
  private int avoidTimeSendingFireBrigade = -1;
  private int sentTime = -1;
  private Action lastAction = null;
  private Point2D lastPoint = null;

  public AITExtActionMove(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.pathPlanning = moduleManager.getModule("AIT.FB.ExtActionMove.PathPlanning");
    this.stuckedHumans = moduleManager.getModule("AIT.FB.ExtActionMove.StuckHumans");
    this.thresholdRestDamage = 100;
    this.avoidTimeSendingFireBrigade = 5;
  }

  @Override
  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (this.getCountPrecompute() > 1) {
      return this;
    }
    return this;
  }

  @Override
  public ExtAction resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() > 1) {
      return this;
    }
    this.preparate();
    return this;
  }

  @Override
  public ExtAction preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }

    this.pathPlanning.preparate();
    this.stuckedHumans.preparate();

    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (Exception e) {
      this.kernelTime = -1;
    }

    return this;
  }

  @Override
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    this.pathPlanning.updateInfo(messageManager);
    this.stuckedHumans.updateInfo(messageManager);

    if (this.shouldSendFireBrigade && this.sentTime > this.agentInfo.getTime()) {
      this.sentTime = this.agentInfo.getTime() + this.avoidTimeSendingFireBrigade;
      this.shouldSendFireBrigade = false;
    }

    return this;
  }

  @Override
  public ExtAction setTarget(EntityID id) {
    if (id != null) {
      StandardEntity entity = this.worldInfo.getEntity(id);
      if (entity instanceof Area) {
        this.targetID = id;
      }
    }
    return this;
  }

  @Override
  public ExtAction calc() {
    this.result = null;
    StandardEntity agent = this.agentInfo.me();
    if (this.needRest(agent)) {
      this.result = this.calcRest(agent, this.targetID);
      if (this.result != null) {
        if (this.isStuckMoving(this.result)) {
          this.shouldSendFireBrigade = true;
        }
        lastAction = this.result;
        lastPoint = new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
        return this;
      }
    }

    if (this.targetID == null) {
      return this;
    }
    this.pathPlanning.setFrom(this.worldInfo.getPosition(agent.getID()).getID());
    this.pathPlanning.setDestination(this.targetID);
    List<EntityID> path = this.pathPlanning.calc().getResult();
    if (path != null && path.size() > 0) {
      this.result = new ActionMove(path);
    }

    if (this.isStuckMoving(this.result)) {
      this.shouldSendFireBrigade = true;
    }
    lastAction = this.result;
    lastPoint = new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
    return this;
  }

  private Boolean needRest(StandardEntity agent) {
    if (!(agent instanceof Human)) {
      return false;
    }
    Human human = (Human) agent;

    int hp = human.getHP();
    int damage = human.getDamage();
    if (hp == 0 || damage == 0) {
      return false;
    }
    int aliveTime = (hp / damage) + ((hp % damage != 0) ? 1 : 0);

    boolean cond1 = damage > this.thresholdRestDamage;
    boolean cond2 = aliveTime + this.agentInfo.getTime() < this.kernelTime;
    return cond1 || cond2;
  }

  private Action calcRest(StandardEntity agent, EntityID target) {
    StandardEntity agentPos = this.worldInfo.getPosition(agent.getID());
    if (agentPos instanceof Refuge) {
      return new ActionRest();
    }

    // for refuges
    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
    int currSizeRefuges = refuges.size();
    List<EntityID> path4Refuge = null;
    while (refuges.size() > 0) {
      this.pathPlanning.setFrom(agentPos.getID());
      this.pathPlanning.setDestination(refuges);
      List<EntityID> path2Refuge = this.pathPlanning.calc().getResult();
      if (path2Refuge != null && path2Refuge.size() > 0) {
        if (this.targetID == null) {
          path4Refuge = path2Refuge;
          break;
        }

        EntityID refugeID = path2Refuge.get(path2Refuge.size() - 1);
        this.pathPlanning.setFrom(refugeID);
        this.pathPlanning.setDestination(targetID);
        List<EntityID> path2Target = this.pathPlanning.calc().getResult();
        if (path2Target != null && path2Target.size() > 0) {
          path4Refuge = path2Refuge;
          break;
        }

        refuges.remove(refugeID);
        if (currSizeRefuges == refuges.size()) {
          break;
        }
        currSizeRefuges = refuges.size();
      } else {
        break;
      }
    }
    if (path4Refuge != null) {
      return new ActionMove(path4Refuge);
    }

    return null;
  }

  // for simulator bug
  private Boolean isStuckMoving(Action action) {
    if (!(action instanceof ActionMove) || !(this.lastAction instanceof ActionMove)) {
      return false;
    }
    if (isStuckInBlockades()) {
      return false;
    }
    Point2D myPoint = new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
    if (!myPoint.equals(lastPoint)) {
      return false;
    }
    List<EntityID> path = ((ActionMove) action).getPath();
    List<EntityID> lastPath = ((ActionMove) lastAction).getPath();
    if (path.equals(lastPath)) {
      return true;
    }
    return false;
  }

  private Boolean isStuckInBlockades() {
    return this.stuckedHumans.calc().getClusterIndex(this.agentInfo.getID()) == 0;
  }
}
