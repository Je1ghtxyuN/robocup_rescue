package AIT_2022.extaction;

import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.HYDRANT;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import java.util.*;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.*;
import adf.core.agent.action.fire.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.*;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
//import rescuecore2.standard.entities.StandardEntityConstants.Fieryness;
import rescuecore2.worldmodel.EntityID;


public class AITExtActionFireFighting extends ExtAction {

  private final PathPlanning pathPlaner;
  private final Clustering stuckHumans;

  private final int THOLD_LACKOF_WATER;
  private final int THOLD_ENOUGH_WATER;
  private final int NUM_TRY_MOVE;
  private static final int THOLD_SHOULD_REST = 100;
  private static final int THOLD_STUCK_RANGE = 1800;
  private static final int THOLD_STUCK_TIME = 2;
  private static final int AVOID_TIME_CMDP = 2;
//    private static final int AVOID_TIME_BURNT = 1;

  private EntityID target = null;
  private Point2D stuckPoint = null;
  private Point2D lastPoint = null;
  private int endTime = -1;
  private int countStuck = 0;
  private int sentTimeCmdP = -1;
//    private int sentTimeBurnt = -1;

  public AITExtActionFireFighting(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
    this.pathPlaner = moduleManager.getModule("AIT.FB.ExtActionFireFighting.PathPlanning");
    this.stuckHumans = moduleManager.getModule("AIT.FB.ExtActionFireFighting.StuckHumans");

    this.THOLD_LACKOF_WATER = si.getFireExtinguishMaxSum();
    this.THOLD_ENOUGH_WATER = (int) (si.getFireTankMaximum() * .9);
    final Random r = new Random(ai.getID().getValue());
    this.NUM_TRY_MOVE = r.nextInt(3) + 2;
  }

  @Override
  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (this.getCountPrecompute() > 1) {
      return this;
    }
    this.pathPlaner.preparate();
    this.stuckHumans.preparate();
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
    this.pathPlaner.preparate();
    this.stuckHumans.preparate();
    try {
      this.endTime = this.scenarioInfo.getKernelTimesteps();
    } catch (Exception e) {
      this.endTime = -1;
    }
    return this;
  }

  @Override
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }
    this.pathPlaner.updateInfo(messageManager);
    this.stuckHumans.updateInfo(messageManager);

    Pair<Integer, Integer> p =
        this.worldInfo.getLocation(this.agentInfo.me());
    Point2D pos = new Point2D(p.first(), p.second());
    if ((this.inBlockades() || this.inBugstucks()) && this.countStuck == 0) {
      this.stuckPoint = (inBlockades()) ? pos : this.lastPoint;
    }
    this.countStuck = (this.isSamePos(pos, this.stuckPoint)) ?
        this.countStuck + 1 : 0;

    int time = this.agentInfo.getTime();
    if (this.countStuck > 0) {
      EntityID myposId = this.agentInfo.getPosition();
      if (this.sentTimeCmdP <= time) {
        // messageManager.addMessage(new CommandPolice(
        //     true, StandardMessagePriority.HIGH, null,
        //     myposId, CommandPolice.ACTION_CLEAR));
        this.sentTimeCmdP = time + AVOID_TIME_CMDP;
      }
      // messageManager.addMessage(new CommandPolice(
      //     false, StandardMessagePriority.HIGH, null,
      //     myposId, CommandPolice.ACTION_CLEAR));
    }

//        StringJoiner stuck = new StringJoiner(", ");
//        stuck.add("BLOCKADE?: " + inBlockades())
//            .add("BUGSTUCK?: " + inBugstucks())
//            .add("HOW-LONG?: " + this.countStuck);
//        this.out(stuck.toString());

    Action a = this.agentInfo.getExecutedAction(time - 1);
    ActionExtinguish ae = (a instanceof ActionExtinguish) ?
        (ActionExtinguish) a : null;
    Building lastExtghed = (ae != null) ?
        (Building) this.worldInfo.getEntity(ae.getTarget()) : null;
    if (lastExtghed != null && !lastExtghed.isOnFire()) {
//            this.out("EXTINGUISHED #" + lastExtghed);
      messageManager.addMessage(new MessageBuilding(
          true, StandardMessagePriority.HIGH, lastExtghed));
      messageManager.addMessage(new MessageBuilding(
          false, StandardMessagePriority.HIGH, lastExtghed));
    }

//        Collection<Building> burnts = 
//                this.worldInfo.getChanged().getChangedEntities().stream()
//                .map(this.worldInfo::getEntity)
//                .filter(Building.class::isInstance)
//                .map(Building.class::cast)
//                .filter(Building::isFierynessDefined)
//                .filter(b -> b.getFierynessEnum() == Fieryness.BURNT_OUT)
//                .collect(Collectors.toList());
//        if (this.sentTimeBurnt <= time)
//        {
//            burnts.forEach(b -> {
//                messageManager.addMessage(new MessageBuilding(
//                        false, StandardMessagePriority.HIGH, b));
//            });
//            this.sentTimeBurnt = time + AVOID_TIME_BURNT;
//        }
    return this;
  }

  @Override
  public ExtAction setTarget(EntityID id) {
    this.target = null;
    if (id == null) {
      return this;
    }
    StandardEntity e = this.worldInfo.getEntity(id);
    if (e instanceof Building) {
      this.target = id;
    }
    return this;
  }

  @Override
  public ExtAction calc() {
    this.result = null;
    if (this.target == null) {
      return this;
    }
    this.result = this.calcRecover();
    this.result = this.calcExtinguish();
    return this;
  }

  private boolean needRest() {
    Human human = (Human) this.agentInfo.me();
    int hp = human.getHP();
    int dmg = human.getDamage();
    if (hp == 0 || dmg == 0) {
      return false;
    }
    int alive = (hp / dmg) + ((hp % dmg != 0) ? 1 : 0);

    int time = this.agentInfo.getTime();
    return dmg > THOLD_SHOULD_REST || alive + time < this.endTime;
  }

  private boolean needRefill() {
    int t = this.agentInfo.getTime();
    Action a = this.agentInfo.getExecutedAction(t - 1);
    FireBrigade f = (FireBrigade) this.agentInfo.me();
    return (a instanceof ActionRefill) ?
        f.getWater() < THOLD_ENOUGH_WATER : f.getWater() < THOLD_LACKOF_WATER;
  }

  private Action calcRecover() {
    if (!this.needRest() && !this.needRefill()) {
      return null;
    }

    StandardEntity mypos = this.worldInfo.getEntity(this.agentInfo.getPosition());
    if (mypos instanceof Refuge) {
      return (this.needRest()) ? new ActionRest() : new ActionRefill();
    }
    if (this.needRefill() && mypos instanceof Hydrant && !this.isOccupied(mypos)) {
      return new ActionRefill();
    }

    EntityID dest = this.findBestDestOf(REFUGE);
    if (this.needRefill() && dest == null) {
      dest = this.findBestDestOf(HYDRANT);
    }
    if (dest == null) {
      return null;
    }

    this.pathPlaner.setFrom(mypos.getID()).setDestination(dest);
    List<EntityID> path = this.pathPlaner.calc().getResult();
    return (!path.isEmpty()) ? new ActionMove(path) : null;
  }

  private Action calcExtinguish() {
    if (this.result != null) {
      return this.result;
    }

    int range = this.scenarioInfo.getFireExtinguishMaxDistance();
    int power = this.scenarioInfo.getFireExtinguishMaxSum();
    double dist = this.worldInfo.getDistance(this.agentInfo.getPosition(), this.target);
    if (this.countStuck >= THOLD_STUCK_TIME && dist < range
        && (this.inBugstucks() && this.countStuck % NUM_TRY_MOVE != 0)) {
      return new ActionExtinguish(this.target, power);
    }
    if (this.worldInfo.getChanged().getChangedEntities().contains(this.target)
        && dist < range) {
      return new ActionExtinguish(this.target, power);
    }

    EntityID myposId = this.agentInfo.getPosition();
    this.pathPlaner.setFrom(myposId);
    this.pathPlaner.setDestination(this.target);
    List<EntityID> path = this.pathPlaner.calc().getResult();
    if (path == null || path.size() < 2) {
      return null;
    }

    StandardEntity dest = this.worldInfo.getEntity(path.get(path.size() - 1));
    if (dest instanceof Building && !(dest instanceof Refuge)) {
      path.remove(path.size() - 1);
    }
    if (path.size() > 1) {
      path.remove(path.size() - 1);
    }
    return new ActionMove(path);
  }

  private int evaluateDest(EntityID entId) {
    EntityID myposId = this.agentInfo.getPosition();
    this.pathPlaner.setFrom(myposId).setDestination(entId);
    int cost1 = this.pathPlaner.calc().getResult().size();
    this.pathPlaner.setFrom(entId).setDestination(this.target);
    int cost2 = this.pathPlaner.calc().getResult().size();
    return (this.target != null) ? cost1 + cost2 : cost1;
  }

  private EntityID findBestDestOf(StandardEntityURN urn) {
    Optional<EntityID> ret = this.worldInfo.getEntitiesOfType(urn).stream()
        .filter(this::isOccupied).map(StandardEntity::getID)
        .sorted(Comparator.comparingInt(this::evaluateDest))
        .findFirst();
    return ret.orElse(null);
  }

  private Boolean inBugstucks() {
    if (this.inBlockades()) {
      return false;
    }

    int t = this.agentInfo.getTime();
    Action a = this.agentInfo.getExecutedAction(t - 1);
    if (!(a instanceof ActionMove)) {
      return false;
    }

    Human h = (Human) this.agentInfo.me();
    int[] hist = h.getPositionHistory();
    int size = (hist != null) ? hist.length : -1;
    if (size < 2) {
      Pair<Integer, Integer> p =
          this.worldInfo.getLocation(this.agentInfo.me());
      this.lastPoint = new Point2D(p.first(), p.second());
      return true;
    }

    Point2D first = new Point2D(hist[0], hist[1]);
    this.lastPoint = new Point2D(hist[size - 2], hist[size - 1]);
    return this.isSamePos(first, this.lastPoint);
  }

  private boolean isSamePos(Point2D p1, Point2D p2) {
    if (p1 == null || p2 == null) {
      return false;
    }
    int diff = (int) Math.hypot(p1.getX() - p2.getX(), p2.getY() - p2.getY());
    return diff <= THOLD_STUCK_RANGE;
  }

  private boolean inBlockades() {
    return this.stuckHumans.calc().getClusterIndex(this.agentInfo.getID()) == 0;
  }

  protected Boolean isOccupied(StandardEntity ent) {
    if (!(ent instanceof Hydrant)) {
      return true;
    }

    Optional<EntityID> opt =
        this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE).stream()
            .filter(i -> this.worldInfo.getPosition(i).equals(ent))
            .max(Comparator.comparingInt(EntityID::getValue));
    EntityID maxId = opt.orElse(null);
    return maxId != null && maxId.equals(this.agentInfo.getID());
  }

  private void out(String str) {
    String ret;
    ret = "🚒  [" + String.format("%10d", this.agentInfo.getID().getValue()) + "]";
    ret += " FIRE-FIGHTING ";
    ret += "@" + String.format("%3d", this.agentInfo.getTime());
    ret += " -> ";
    System.err.println(ret + str);
  }
}
