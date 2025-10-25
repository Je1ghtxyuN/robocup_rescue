package SEU.extaction;

import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.police.ActionClear;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;

import java.awt.geom.Path2D;
import java.awt.geom.Ellipse2D;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

public class DefaultExtActionClear extends ExtAction {

  /**
   * PF当前行动目标（可以是道路、建筑或避难所）
   */
  private EntityID target;

  /**
   * 「可能成为清除对象的 EntityID 集合」
   * key = 土木队（PF, Police Force）可能行动的目标 EntityID
   * value = 针对该目标的任务（是否执行清除任务）
   */
  private Map<EntityID, Action> cache = new HashMap<>();

  /**
   * 用于决定移动路径的路径规划模块
   */
  private PathPlanning pathPlanner;

  /**
   * 在移动失败时，用于决定新的移动路径的聚类模块
   */
  private DynamicClustering failedMove;

  /**
   * 用于发现被堵在瓦砾中的Human的聚类模块
   */
  private DynamicClustering stuckedHumans;

  /**
   * false = 去啓开（清理）其他的瓦砾
   */
  private boolean needToEscape = true;

  /**
   * 当智能体自身被埋在瓦砾中时，
   * 将会对该瓦砾执行“收缩啓开”（逐步清理）的操作
   */
  private boolean needToShrink = true;

  public DefaultExtActionClear(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.pathPlanner = mm.getModule("DefaultExtActionClear.PathPlanning");

    this.failedMove = mm.getModule("DefaultExtActionClear.FailedMove");

    this.stuckedHumans = mm.getModule("DefaultExtActionClear.StuckHumans");
  }

  /**
   * 执行预计算，并保存希望在智能体之间共享的结果
   * 
   * @param pd 预计算结果
   */
  @Override
  public ExtAction precompute(PrecomputeData pd) {
    super.precompute(pd);
    this.pathPlanner.precompute(pd);
    this.failedMove.precompute(pd);
    this.stuckedHumans.precompute(pd);
    return this;
  }

  /**
   * 读取预计算的结果
   */
  @Override
  public ExtAction resume(PrecomputeData pd) {
    super.resume(pd);
    this.pathPlanner.resume(pd);
    this.failedMove.resume(pd);
    this.stuckedHumans.resume(pd);
    return this;
  }

  /**
   * 当预计算超时或未执行预计算时的处理
   */
  @Override
  public ExtAction preparate() {
    super.preparate();
    this.pathPlanner.preparate();
    this.failedMove.preparate();
    this.stuckedHumans.preparate();
    return this;
  }

  /**
   * 每个步骤都会执行，在智能体持有的信息更新（Calc()）之前执行。
   * 进行目标与目标候选集合的初始化。
   * 当智能体丢失目标时，获取该智能体的信息并在终端上显示。
   * 
   * @param mm 消息管理器
   * @return ExtAction 的 updateInfo
   */
  @Override
  public ExtAction updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    this.pathPlanner.updateInfo(mm);
    this.failedMove.updateInfo(mm);
    this.stuckedHumans.updateInfo(mm);

    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    this.target = null;
    this.cache.clear();

    if (this.needIdle()) {
      return this;
    }

    this.needToEscape = this.cannotReach();
    this.needToShrink &= this.isStucked();
    int time = this.agentInfo.getTime();
    EntityID myId = this.agentInfo.getID();
//    System.out.println("[ExtClear](" + 1 + "), " + time + "," + myId + "," + this.needToEscape + ","
//        + this.needToShrink);

    return this;
  }


  /**
   * 判断获取到的 EntityID 是否是Blockade。

  *@param id 获取到的实体 ID。
   */

  @Override
  public ExtAction setTarget(EntityID id) {
    final StandardEntity entity = this.worldInfo.getEntity(id);
    if (entity instanceof Blockade) {
      final StandardEntity position = this.worldInfo.getPosition(id);
      this.setTarget(position.getID());
      return this;
    }

    this.target = id;
    return this;
  }

  /**
   决定智能体（Agent）的动作，并进行调试信息显示。
  处理流程如下：
  从候选瓦砾列表中获取要清除的瓦砾；
  进行“收缩”操作（即缩小瓦砾范围）——当智能体自身被瓦砾卡住时；
  当被瓦砾阻挡而无法行动时，获取智能体当前的位置；
  4–5. 执行清除瓦砾的动作；
  6–7. 当负责的区域结束后，重新进行路径规划。

@return 调用 ExtAction 的 calc 方法（用于计算下一步动作）。
   
   */

  @Override
  public ExtAction calc() {
    this.result = null;

    int time = this.agentInfo.getTime();
    EntityID myId = this.agentInfo.getID();
    if (this.target == null) {
      // System.out.println("[ACTION] " + myId + " " + time + " target is null");
      return this;
    }

    if (this.cache.containsKey(this.target)) {
      this.result = this.cache.get(this.target);
      return this;
    }

    final EntityID position = this.agentInfo.getPosition();
    if (this.needToShrink) {
      this.result = this.makeActionToClear(position);
      this.cache.put(this.target, this.result);
      // this.debug.showAction(this.result, time, this.target.toString() + ", 2");
      return this;
    }

    if (this.needToEscape) {
      this.result = this.makeActionToAvoidError();
      this.cache.put(this.target, this.result);
      // this.debug.showAction(this.result, time, this.target.toString() + ", 3");
      return this;
    }

    //this.debug.showAction(this.result, time, this.target.toString() + ", 4");
    if (this.needIdle()) {
      return this;
    }
    if (this.needRest()) {
      final EntityID refuge = this.seekBestRefuge();
      if (refuge != null) {
        this.target = refuge;
      }
    }
    // this.debug.showAction(this.result, time, this.target.toString() + ", 5");
    if (this.target == null) {
      return this;
    }

    this.pathPlanner.setFrom(position);
    this.pathPlanner.setDestination(this.target);
    this.pathPlanner.calc();
    final List<EntityID> path = this.normalize(this.pathPlanner.getResult());

    Map<EntityID, List<Line2D>> concretePath = this.makeConcretePath(path);
    for (EntityID id : path) {
      List<Line2D> concrete = concretePath.get(id);
      if (!concrete.isEmpty()) {
        continue;
      }
      final List<Line2D> addition = this.seekConcretePathToStuckedHumans(id, concrete);
      concrete.addAll(addition);
    }

    List<EntityID> actualPath = new LinkedList<>();
    for (EntityID id : path) {
      actualPath.add(id);
      final List<Line2D> concrete = concretePath.get(id);
      final Line2D clearline = this.seekClearline(id, concrete);
      if (clearline == null) {
        continue;
      }

      this.result = this.makeActionToClear(actualPath, clearline);
      this.cache.put(this.target, this.result);
      // this.debug.showAction(this.result, time, this.target.toString() + ", 6");
      return this;
    }

    this.result = this.makeActionToMove(path);
    if (this.result == null) {
      this.result = this.makeActionToAvoidError();
    }
    this.cache.put(this.target, this.result);
    // this.debug.showAction(this.result, time, this.target.toString() + ", 7");
    return this;
  }
  /**
   * 判断当前仿真步是否需要执行 calc 方法
   * 
   * @return 当前仿真步是否需要执行 calc 方法的布尔值
   */

  private boolean needIdle() {
    final int time = this.agentInfo.getTime();
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    return time < ignored;
  }

  /**
   * 当Agent的HP剩余100时停止
   */
  private static final int DAMAGE_NEEDED_REST = 100;

  /**
   * 判断Agent是否需要停止（警察部队受到伤害的情况可能只发生在有火灾的情况下）
   * 
   * @return 比较剩余时间和根据伤害推算出的死亡时间，返回布尔值
   */
  private boolean needRest() {
    final PoliceForce me = (PoliceForce) this.agentInfo.me();
    final int hp = me.getHP();
    final int damage = me.getDamage();
    if (hp == 0) {
      return false;
    }
    if (damage == 0) {
      return false;
    }

    final int time = this.agentInfo.getTime();
    final int die = (int) Math.ceil((double) hp / damage);
    final int finish = 300;

    return damage >= DAMAGE_NEEDED_REST || (time + die) < finish;
  }

  /**
   * 逃往最近的避难所
   * 
   * @return 若不为 null，则返回最近的避难所的 EntityID
   */
  private EntityID seekBestRefuge() {
    final EntityID me = this.agentInfo.getID();
    final Optional<EntityID> ret = this.worldInfo.getEntityIDsOfType(REFUGE)
        .stream()
        .min((r1, r2) -> {
          final double d1 = this.worldInfo.getDistance(me, r1) +
              this.worldInfo.getDistance(r1, this.target);
          final double d2 = this.worldInfo.getDistance(me, r2) +
              this.worldInfo.getDistance(r2, this.target);
          return Double.compare(d1, d2);
        });

    return ret.orElse(null);
  }

  /**
   * 若路径中不包含Agent所在区域的 EntityID，则将其添加进去
   * 
   * @param path 要判断是否包含Agent当前位置的路径（EntityID列表）
   * @return 包含Agent所在区域EntityID的路径（若原本就包含则保持不变）
   */
  private List<EntityID> normalize(List<EntityID> path) {
    List<EntityID> ret = new ArrayList<>(path);

    final EntityID position = this.agentInfo.getPosition();
    if (ret.isEmpty() || !ret.get(0).equals(position)) {
      ret.add(0, position);
    }

    return ret;
  }

  /**
   * 获取目的地的坐标
   * 
   * @param path 要判断是否包含Agent当前位置的路径（EntityID列表）
   * @return 由 makeConcretePath 方法返回的路径信息
   */
  // @ CONCRETE PATH {{{
  private Map<EntityID, List<Line2D>> makeConcretePath(List<EntityID> path) {
    final int n = path.size();
    final Area area = (Area) this.worldInfo.getEntity(path.get(n - 1));
    final Point2D centroid = getPoint(area);
    return this.makeConcretePath(path, centroid);
  }

  /**
   * 从计算出的路径中生成下一步应前往的具体路径
   * 
   * @param path 含Agent所在区域的路径（EntityID列表）
   * @param dest 目标位置的坐标
   * @return 返回去除Agent当前位置和目标位置后的路径
   */
  private Map<EntityID, List<Line2D>> makeConcretePath(
      List<EntityID> path, Point2D dest) {
    Map<EntityID, List<Line2D>> ret = new HashMap<>();

    final int n = path.size();
    final EntityID s = path.get(0);
    final EntityID g = path.get(n - 1);

    if (n == 1) {
      final List<Line2D> concrete = this.makeConcretePath(s, this.getPoint(), dest);
      ret.put(s, this.cut(concrete));
      return ret;
    }

    for (int i = 1; i < n - 1; ++i) {
      final EntityID id = path.get(i);
      final EntityID prev = path.get(i - 1);
      final EntityID next = path.get(i + 1);
      final List<Line2D> concrete = this.makeConcretePath(id, prev, next);
      ret.put(id, this.cut(concrete));
    }
    List<Line2D> concrete = this.makeConcretePath(s, this.getPoint(), path.get(1));
    ret.put(s, this.cut(concrete));

    concrete = this.makeConcretePath(g, path.get(n - 2), dest);
    ret.put(g, this.cut(concrete));

    return ret;
  }

  /**
   * 计算下一个需要被清除障碍的路径
   * 
   * @param id Agent当前所在区域的EntityID
   * @param prev Agent上一个所在的EntityID
   * @param next Agent下一步要移动到的EntityID
   * @return 下一步应前往的路径
   */
  private List<Line2D> makeConcretePath(
      EntityID id, EntityID prev, EntityID next) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    final Edge pe = area.getEdgeTo(prev);
    final Edge ne = area.getEdgeTo(next);

    final Point2D centroid = getPoint(area);

    List<Line2D> ret = new ArrayList<>(2);
    ret.add(new Line2D(computeMiddlePoint(pe.getLine()), centroid));
    ret.add(new Line2D(centroid, computeMiddlePoint(ne.getLine())));
    return ret;
  }

  /**
   * 当只有一个清除对象路径时执行，
   * 判断是否存在其他可清除的路径
   * 
   * @param id 目的地的EntityID
   * @param from Agent当前的坐标
   * @param dest 目的地的坐标
   * @return 若存在新的可清除路径则加入list（若无则选择邻近的EntityID）
   */
  private List<Line2D> makeConcretePath(
      EntityID id, Point2D from, Point2D dest) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    final Point2D centroid = getPoint(area);

    List<Line2D> ret = new LinkedList<>();
    ret.add(new Line2D(from, centroid));
    ret.addAll(this.makeConcretePathToAllNeighbor(id, null));
    return ret;
  }

  /**
   * 比较Agent当前位置到当前区域中心点的连线，
   * 与当前区域中心点到下一个区域中心点的连线，
   * 选择最接近的中心点加入路径
   * 
   * @param id Agent当前所在的EntityID
   * @param from Agent当前的坐标
   * @param next 下一个目标EntityID
   * @return 含最近中心点的路径
   */

  private List<Line2D> makeConcretePath(
      EntityID id, Point2D from, EntityID next) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    final Point2D centroid = getPoint(area);
    final Edge ne = area.getEdgeTo(next);
    final Point2D np = computeMiddlePoint(ne.getLine());
    final Line2D cn = new Line2D(centroid, np);
    final Point2D closest = GeometryTools2D.getClosestPointOnSegment(cn, from);
    List<Line2D> ret = new LinkedList<>();
    if (closest.equals(centroid)) {
      ret.add(new Line2D(from, centroid));
      ret.add(cn);
    } else {
      ret.add(new Line2D(from, np));
    }
    return ret;
  }

  /**
   * 生成一条从前一个区域共享边的中点到当前区域中心点的路径
   * 
   * @param id 当前目标区域的EntityID
   * @param prev 路径中前一个区域的EntityID（path(n-2)）
   * @param dest 目标位置的坐标（未使用）
   * @return 返回路径线段列表
   */

  private List<Line2D> makeConcretePath(
      EntityID id, EntityID prev, Point2D dest) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    final Point2D centroid = getPoint(area);
    final Edge pe = area.getEdgeTo(prev);
    final Point2D pp = computeMiddlePoint(pe.getLine());
    List<Line2D> ret = new LinkedList<>();
    ret.add(new Line2D(pp, centroid));
    ret.addAll(this.makeConcretePathToAllNeighbor(id, prev));
    return ret;
  }


    /**
   * 计算到相邻 EntityID 的路径（有一定模糊性）
   * 
   * @param id 目标的 EntityID 
   * @param ignored 要忽略的上一个 EntityID（path(n-2)）
   * @return 若下一个要前进的 EntityID 是相邻的，则将其中心点加入路径
   */
  private List<Line2D> makeConcretePathToAllNeighbor(
      EntityID id, EntityID ignored) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    final Point2D centroid = getPoint(area);

    final List<Line2D> ret = new LinkedList<>();
    final List<EntityID> neighbors = area.getNeighbours();
    for (EntityID neighbor : neighbors) {
      if (neighbor.equals(ignored)) {
        continue;
      }
      final Edge ne = area.getEdgeTo(neighbor);
      final Point2D np = computeMiddlePoint(ne.getLine());
      ret.add(new Line2D(centroid, np));
    }
    return ret;
  }

  /**
   * 计算通往被瓦砾困住的市民的路径（path）的列表
   * 
   * @param id 路径经过的目标 EntityID
   * @param others 其他路径（此方法中未使用）
   * @return 若能找到通往被埋市民的路径，则将该路径加入 path
   */
  private List<Line2D> seekConcretePathToStuckedHumans(
      EntityID id, List<Line2D> others) {
    final List<Human> humans = this.seekStuckedHumansOn(id);
    List<Line2D> ret = new LinkedList<>();
    for (Human human : humans) {
      final Point2D point = getPoint(human);
      final Point2D agent = this.getPoint();
      final int d = this.scenarioInfo.getClearRepairDistance();
      final Line2D line = new Line2D(agent, point);
      final Vector2D vec = line.getDirection().normalised().scale(d);
      ret.add(new Line2D(agent, vec));
    }
    return this.cut(ret);
  }
  // }}}

  /**
   * 查找被瓦砾困住的 Humans（仅限救援队与消防队）
   * 
   * @param id 预计的移动路径对应的 EntityID
   * @return 返回被瓦砾埋住的 Humans 列表
   */
  private List<Human> seekStuckedHumansOn(EntityID id) {
    this.stuckedHumans.calc();

    final Stream<Human> ret = this.stuckedHumans.getClusterEntities(0)
        .stream().map(Human.class::cast)
        .filter(h -> h.getStandardURN() != POLICE_FORCE)
        .filter(h -> h.getStandardURN() != CIVILIAN)
        .filter(h -> h.getPosition().equals(id));
    return ret.collect(toList());
  }

  /**
   * 将多条直线（Line2D）按可清除距离分割，并返回所有分割后的直线列表
   * 
   * @param lines 需要清除的直线列表
   * @return 分割后的多条 Line2D 的列表 
   */
  private List<Line2D> cut(List<Line2D> lines) {
    List<Line2D> ret = new LinkedList<>();
    for (Line2D line : lines) {
      final double l = line.getDirection().getLength();
      final double d = this.scenarioInfo.getClearRepairDistance() * 0.3;
      final int n = (int) Math.ceil(l / d);

      for (int i = 0; i < n; ++i) {
        final Point2D op = line.getPoint(d * i / l);
        final Point2D ep = line.getPoint(Math.min(d * (i + 1) / l, 1.0));
        ret.add(new Line2D(op, ep));
      }
    }
    return ret;
  }

  /**
   * 从要清除的直线列表中，找出在某个 Area 内与瓦砾重叠的一条直线。
   * 如果存在延长方向上的相邻直线，则将它们合并。
   * 
   * @param id 对应的 Area 的 EntityID
   * @param concrete 要清除范围的直线列表
   * @return 返回与瓦砾重叠的一条 Line2D，若存在几乎重叠的连续线段，则合并后返回
   */
  private Line2D seekClearline(EntityID id, List<Line2D> concrete) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    if (!area.isBlockadesDefined()) {
      return null;
    }

    final List<EntityID> blockades = area.getBlockades();
    if (blockades.isEmpty()) {
      return null;
    }

    final Optional<java.awt.geom.Area> obstacle = blockades
        .stream()
        .map(this.worldInfo::getEntity)
        .map(Blockade.class::cast)
        .map(Blockade::getShape)
        .map(java.awt.geom.Area::new)
        .reduce((acc, v) -> {
          acc.add(v);
          return acc;
        });
    final int n = concrete.size();
    Line2D ret = null;
    int i;
    for (i = 0; i < n; ++i) {
      java.awt.geom.Area shape = computeShape(concrete.get(i));
      shape.intersect(obstacle.get());

      if (!shape.isEmpty()) {
        ret = concrete.get(i);
        break;
      }
    }
    if (ret == null) {
      return null;
    }

    for (++i; i < n; ++i) {
      final Line2D next = concrete.get(i);
      if (!canUnite(ret, next)) {
        break;
      }
      ret = new Line2D(ret.getOrigin(), next.getEndPoint());
    }
    return ret;
  }

  /**
   * 代理（Agent）的半径
   */
  private static final double AGENT_RADIUS = 500.0;

  /**
   * 将一条直线（Line2D）转换为二维几何图形（Area）
   * 
   * @param line 代表移动路径的直线
   * @return 基于代理半径生成的矩形区域（由上下左右顶点构成）
   */
  private static java.awt.geom.Area computeShape(Line2D line) {
    final double x1 = line.getOrigin().getX();
    final double x2 = line.getEndPoint().getX();
    final double y1 = line.getOrigin().getY();
    final double y2 = line.getEndPoint().getY();

    final double length = Math.hypot(x2 - x1, y2 - y1);
    final double ldx = (y2 - y1) * AGENT_RADIUS / length;
    final double ldy = (x1 - x2) * AGENT_RADIUS / length;
    final double rdx = (y1 - y2) * AGENT_RADIUS / length;
    final double rdy = (x2 - x1) * AGENT_RADIUS / length;

    final Point2D p1 = new Point2D(x1 + ldx, y1 + ldy);
    final Point2D p2 = new Point2D(x2 + ldx, y2 + ldy);
    final Point2D p3 = new Point2D(x2 + rdx, y2 + rdy);
    final Point2D p4 = new Point2D(x1 + rdx, y1 + rdy);

    return makeAWTArea(new Point2D[] { p1, p2, p3, p4 });
  }

  /**
   * 若要清除的瓦砾在代理的清除范围内，则执行清除；
   * 否则返回一个前往清除目标附近的移动动作。
   * 
   * @param path 要清除的瓦砾对应的 EntityID 列表
   * @param clearline 要清除的范围（直线）
   * @return 若目标在可清除范围内则执行清除，否则执行移动
   */
  private Action makeActionToClear(List<EntityID> path, Line2D clearline) {
    final Point2D op = clearline.getOrigin();
    final Point2D ep = clearline.getEndPoint();

    final double d = GeometryTools2D.getDistance(this.getPoint(), op);

    final Vector2D vec = clearline.getDirection();
    final int max = this.scenarioInfo.getClearRepairDistance();
    final Vector2D extvec = vec.normalised().scale(max);
    final Action clear = new ActionClear(this.agentInfo, extvec);
    if (d <= AGENT_RADIUS) {
      return clear;
    }

    final int x = (int) op.getX();
    final int y = (int) op.getY();
    final Action move = new ActionMove(path, x, y);
    return move;
  }


  /**
   * 对指定的 EntityID 执行“收缩（shrink）”操作。
   * 通常只在智能体自身被瓦砾掩埋时才会调用。
   * 
   * @param id 需要进行收缩处理的 EntityID
   * @return 如果检测到掩埋的瓦砾则返回清除动作（ActionClear），若没有瓦砾则返回 null
   */
  private Action makeActionToClear(EntityID id) {
    final EntityID myId = this.agentInfo.getID();
    final EntityID myPosition = this.worldInfo.getPosition(myId).getID();
    final Area area = (Area) this.worldInfo.getEntity(id);
    if (!area.isBlockadesDefined()) {
      return null;
    }
    final List<EntityID> blockades = area.getBlockades()
        .stream()
        .sorted(comparing(e -> this.worldInfo.getDistance(myPosition, e)))
        .collect(toList());

    final double rad = AGENT_RADIUS;
    final java.awt.geom.Area agent = makeAWTArea(this.getPoint(), rad);

    for (EntityID blockade : blockades) {
      final Blockade tmp = (Blockade) this.worldInfo.getEntity(blockade);
      java.awt.geom.Area shape = new java.awt.geom.Area(tmp.getShape());
      shape = (java.awt.geom.Area) shape.clone();
      shape.intersect(agent);
      if (!shape.isEmpty()) {
        return new ActionClear(blockade);
      }
    }

    return blockades.isEmpty() ? null : new ActionClear(blockades.get(0));
  }

  /**
   * 生成沿路径（path）移动智能体的动作。
   * 
   * @param path 要移动经过的 EntityID 列表
   * @return 若路径中包含两个及以上节点，则返回移动动作；
   *         若智能体已在目标位置，则返回 null。
   */
  private Action makeActionToMove(List<EntityID> path) {
    return path.size() < 2 ? null : new ActionMove(path);
  }

  /**
   * 获取智能体当前的坐标。
   */
  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  /**
   * 获取指定区域（area）的坐标。
   * @param area 指定的区域
   */
  private static Point2D getPoint(Area area) {
    final double x = area.getX();
    final double y = area.getY();
    return new Point2D(x, y);
  }

  /**
   * 获取指定人类（human）的坐标。
   * @param human 智能体（仅限消防队或救护队）
   */
  private static Point2D getPoint(Human human) {
    final double x = human.getX();
    final double y = human.getY();
    return new Point2D(x, y);
  }

  /**
   * 计算一条线段的中点。
   * 
   * @param line 从起点到终点的线段
   * @return 线段的中点（比例 0.5）
   */
  private static Point2D computeMiddlePoint(Line2D line) {
    return line.getPoint(0.5);
  }

  /**
   * 计算瓦砾顶点中距离智能体最近的一个点。
   * 
   * @param lines 瓦砾的顶点线段集合
   * @param p 智能体的坐标
   * @return 返回距离智能体最近的瓦砾顶点
   */
  private static Point2D computeClosestPoint(List<Line2D> lines, Point2D p) {
    final Optional<Point2D> ret = lines
        .stream()
        .map(l -> GeometryTools2D.getClosestPointOnSegment(l, p))
        .min((p1, p2) -> {
          final double d1 = GeometryTools2D.getDistance(p, p1);
          final double d2 = GeometryTools2D.getDistance(p, p2);
          return Double.compare(d1, d2);
        });

    return ret.orElse(null);
  }

  /**
   * 根据一组顶点坐标生成一个封闭的路径（Area）。
   * 
   * @param ps 基于智能体半径计算出的上下左右顶点
   * @return 返回一个封闭的几何区域
   */
  private static java.awt.geom.Area makeAWTArea(Point2D[] ps) {
    final int n = ps.length;
    Path2D path = new Path2D.Double();
    path.moveTo(ps[0].getX(), ps[0].getY());

    for (int i = 1; i < n; ++i) {
      path.lineTo(ps[i].getX(), ps[i].getY());
    }
    /** 将最后一点连接回起点，形成闭合路径 */
    path.closePath();
    return new java.awt.geom.Area(path);
  }

  /**
   * 根据智能体的半径计算其占据的面积（Area）。
   * 
   * @param p 智能体坐标
   * @param rad 智能体半径
   * @return 返回表示智能体形状的椭圆区域（Area）
   */
  public static java.awt.geom.Area makeAWTArea(Point2D p, double rad) {
    final double d = rad * 2.0;
    final double x = p.getX() - rad;
    final double y = p.getY() - rad;
    return new java.awt.geom.Area(new Ellipse2D.Double(x, y, d, d));
  }

  /**
   * 判断两条线段是否可以连接为一条连续的线。
   * 若 line1 的终点与 line2 的起点相同，
   * 且两条线段的方向向量（x,y 分量）几乎相等，则认为可以连接。
   * 
   * @param line1 第一条线段
   * @param line2 第二条线段
   * @return 若可合并则返回 true，否则返回 false
   */
  private static boolean canUnite(Line2D line1, Line2D line2) {
    if (!line1.getEndPoint().equals(line2.getOrigin())) {
      return false;
    }

    final Vector2D v1 = line1.getDirection().normalised();
    final Vector2D v2 = line2.getDirection().normalised();
    final boolean condx = GeometryTools2D.nearlyZero(v1.getX() - v2.getX());
    final boolean condy = GeometryTools2D.nearlyZero(v1.getY() - v2.getY());
    return condx && condy;
  }

  /**
   * 当智能体无法到达目标位置时调用。
   * @return 若智能体在 failedMove 聚类中存在条目，则返回 true。
   */
  private boolean cannotReach() {
    final EntityID me = this.agentInfo.getID();
    this.failedMove.calc();
    return this.failedMove.getClusterIndex(me) >= 0;
  }

  /**
   * 为避免错误而进行的回退逻辑。
   * @return 若地图上存在瓦砾，则重新规划路径并返回移动或清除动作；
   *         若无瓦砾，则返回 null。
   */
  private Action makeActionToAvoidError() {
    final EntityID position = this.agentInfo.getPosition();
    Set<EntityID> scope = new HashSet<>();
    scope.add(position);

    final Area area = (Area) this.worldInfo.getEntity(position);
    final List<EntityID> neighbors = area.getNeighbours();
    scope.addAll(neighbors);

    final Stream<EntityID> helpers = neighbors
        .stream()
        .map(this.worldInfo::getEntity).map(Area.class::cast)
        .map(Area::getNeighbours).flatMap(List::stream);
    scope.addAll(helpers.collect(toSet()));

    final EntityID blockade = scope
        .stream()
        .map(this.worldInfo::getEntity).map(Area.class::cast)
        .filter(Area::isBlockadesDefined)
        .map(Area::getBlockades).flatMap(List::stream)
        .min(comparing(this::computeDistance))
        .orElse(null);

    final int d = this.scenarioInfo.getClearRepairDistance();
    if (blockade == null) {
      return null;
    }
    if (this.computeDistance(blockade) <= d) {
      return new ActionClear(blockade);
    }

    final Blockade entity = (Blockade) this.worldInfo.getEntity(blockade);
    this.pathPlanner.setFrom(position);
    this.pathPlanner.setDestination(entity.getPosition());
    this.pathPlanner.calc();
    final List<EntityID> path = this.pathPlanner.getResult();

    final int x = entity.getX();
    final int y = entity.getY();

    return new ActionMove(path, x, y);
  }

  /**
   * 计算智能体到指定瓦砾的距离。
   * 
   * @param blockade 瓦砾的 EntityID
   * @return 返回智能体与该瓦砾之间的距离
   */
  private double computeDistance(EntityID blockade) {
    final Blockade entity = (Blockade) this.worldInfo.getEntity(blockade);

    final List<Line2D> lines = GeometryTools2D.pointsToLines(
        GeometryTools2D.vertexArrayToPoints(entity.getApexes()), true);

    final Point2D agent = this.getPoint();
    final Point2D closest = this.computeClosestPoint(lines, agent);
    return GeometryTools2D.getDistance(agent, closest);
  }

  /**
   * 判断智能体是否被瓦砾卡住。
   * @return 若 stuckedHumans 中包含智能体自身的 EntityID，则返回 true。
   */
  private boolean isStucked() {
    final EntityID me = this.agentInfo.getID();
    this.stuckedHumans.calc();
    return this.stuckedHumans.getClusterIndex(me) >= 0;
  }


}
