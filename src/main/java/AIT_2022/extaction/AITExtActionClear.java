package AIT_2022.extaction;

import AIT_2022.module.util.DebugUtil;
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

// @ DEBUG {{{

// }}}

public class AITExtActionClear extends ExtAction {

  /**
   * PFの行動対象となるEntityのEntityID.
   */
  private EntityID target;

  /**
   * 啓開対象となる可能性のあるEntityIDの集合
   * key = PFの行動対象となる可能性のあるEntityID
   * value = keyへのタスク（啓開するかどうか）
   */
  private Map<EntityID, Action> cache = new HashMap<>();

  /**
   * 移動経路を決定するパスプランニングモジュール
   */
  private PathPlanning pathPlanner;

  /**
   * 移動に失敗した時の新たな移動経路を決定するクラスタリングモジュール
   */
  private DynamicClustering failedMove;

  /**
   * Blockadeに詰まっているHumanを発見するクラスタリングモジュール
   */
  private DynamicClustering stuckedHumans;

  /**
   * false = 別の瓦礫を啓開する
   */
  private boolean needToEscape = true;

  /**
   * エージェント自身が瓦礫に埋まっている時
   * その瓦礫をシュリンク(縮まる啓開)する
   */
  private boolean needToShrink = true;

  // @ DEBUG {{{
  // private VDClient vdclient = VDClient.getInstance();
  DebugUtil debug;
  // }}}

  public AITExtActionClear(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.pathPlanner = mm.getModule("AIT.PF.ExtActionClear.PathPlanning");

    this.failedMove = mm.getModule("AIT.PF.ExtActionClear.FailedMove");

    this.stuckedHumans = mm.getModule("AIT.PF.ExtActionClear.StuckHumans");

    // @ DEBUG {{{
    // this.vdclient.init();
    this.debug = new DebugUtil(this.agentInfo, this.worldInfo, this.scenarioInfo);
    // }}}
  }

  /**
   * 事前計算をするまたエージェント間で共有したい結果を保存する
   * 
   * @param pd 事前計算の結果
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
   * 事前計算の結果を取り出す
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
   * 事前計算が制限時間を超過した場合または事前計算をしない場合の処理
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
   * 各ステップで実行され，エージェントが持つ情報を更新するCalc()より前に実行される
   * ターゲットとターゲット候補の集合の初期化をおこなう
   * エージェントが目的地を見失った時に，そのエージェントの情報を取得しターミナル上に表示する
   * 
   * @param mm メッセージマネージャ
   * @return ExtActionのupdateInfo
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
   * 取得したEntityIDが瓦礫かどうか判断する
   *
   * @param id 取得したEntityID
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
   * エージェントの動作決定
   * デバッグの表示
   * 1. 瓦礫候補から啓開する瓦礫を取得
   * 2. シュリンク（瓦礫を縮める）をする　　　エージェント自身が瓦礫に詰まっているとき
   * 3. 瓦礫で動けないとき，エージェントのポジションを取得
   * 4,5. 瓦礫を啓開する動作
   * 6,7. 担当範囲が終わり，新たにパスプランニングする
   * 
   * @return ExtActionのcalcメソッド
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
      this.debug.showAction(this.result, time, this.target.toString() + ", 1");
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
   * calcメソッドを動作させる必要のあるシミュレーションステップであるか否かを判定する
   * 
   * @return 現在のシミュレーションステップがcalcメソッドを動作させる必要のあるシミュレーションステップであるか　否かを表すboolean
   */

  private boolean needIdle() {
    final int time = this.agentInfo.getTime();
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    return time < ignored;
  }

  /**
   * エージェントのHPが残り100になったら停止する
   */
  private static final int DAMAGE_NEEDED_REST = 100;

  /**
   * エージェントが停止するかどうか（土木隊がダメージを受けるのは，前の火災ありのときだけかもしれない）
   * 
   * @return 残り時間とダメージで亡くなるであろう時間を比較し，booleanで表す
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
   * 最も近い避難所に逃避する
   * 
   * @return  nullではない場合は避難所を返す
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
   * 算出した経路にエージェントの位置するAreaのEntityIDが含まれない場合に，そのEntityIDを追加する
   * 
   * @param path エージェントの位置するAreaが含まれているか判定をおこなう対象となる経路であるEntityIDのList
   * @return pathにエージェントの位置するAreaのEntityIDを含めたpath(元から含まれていた場合にはそのまま)
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
   * 目的地の座標を取得する 
   * 
   * @param path エージェントの位置するAreaが含まれているか判定をおこなう対象となる経路であるEntityIDのList
   * @return makeConcretePathメソッドで返す　
   */
  // @ CONCRETE PATH {{{
  private Map<EntityID, List<Line2D>> makeConcretePath(List<EntityID> path) {
    final int n = path.size();
    final Area area = (Area) this.worldInfo.getEntity(path.get(n - 1));
    final Point2D centroid = getPoint(area);
    return this.makeConcretePath(path, centroid);
  }

  /**
   * 啓開対象となる経路のlistから，次に進むべき経路を算出
   * 
   * @param path エージェントの位置するAreaが含まれているか判定をおこなう対象となる経路であるEntityIDのList
   * @param dest 対象となる目的地の座標
   * @return　目的地までの経路からエージェントの位置と目的地の位置を除いた経路を返す
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
   * 次に啓開対象となる経路を算出
   * 
   * @param id エージェントのいるEntityID
   * @param prev エージェントが一つ前にいたEntityID
   * @param next エージェントが次に移動するEntityID
   * @return　次に進むべきpath
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
   * 啓開対象となる経路が一つの場合に実行
   * 他に啓開対象となる経路があるか否か判断する
   * 
   * @param id 目的地のEntityID
   * @param from エージェントの座標
   * @param dest 目的地の座標
   * @return　新たな啓開対象となる経路をlistに含める（ない場合は近くのEntityIDを対象にする）
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
   * エージェント自身の現時点座標からエージェントがいるEntityIDの中心点と
   * エージェントがいるEntityIDの中心点から次に進むべきEntityIDの中心点の中心点を比較
   * 
   * @param id エージェントがいるEntityID
   * @param from エージェント現時点の座標
   * @param next 次に進むEntityID
   * @return　最も近い中心点をpathに含ませる
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
   * 目的地のIDとその前のAreaのIDが共有する境界の真ん中と，
   * 目的地の真ん中を繋げるlineのlistを作成する(曖昧である)
   * 　
   * @param id 目的地のEntityIDのID 
   * @param prev path(n-2)のEntityID
   * @param dest 目的地となる座標（使われていない）
   * @return lineのlistを返す
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
   * 隣接するEntityIDまでの経路を算出(曖昧である)
   * 
   * @param id 目的地のEntityIDのID 
   * @param ignored path(n-2)のEntityID
   * @return 次に進むべきEntityIDが隣接にある場合は，その中心点をpathに含める
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
   * 近くに瓦礫に詰まっている市民までの経路(path)のlist
   * 
   * @param id 目的地までのpathが通るEntityID
   * @param others 他の経路の道?(このメソッドでは使っていない)
   * @return 瓦礫に埋まっている市民までの経路が割り出せたらpathに含む
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
   * 瓦礫に埋まっているHumansを算出する
   * Humansは救急隊と消防隊のみにする
   * 
   * @param id 移動経路となるであろうEntityID
   * @return 瓦礫に埋まっているhumansのlistを返す
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
   * 複数の直線(Line2D)を，啓開できる距離毎に分割，その全てをリストに入れ返す
   * 
   * @param lines 啓開対象となるlineのlist
   * @return 分割された，複数のLine2Dのリスト 
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
   * 啓開したい直線のリストから，あるArea内にある瓦礫と重なっている直線を１つだけ取り出す
   * ただし，その延長にある直線がある場合は，それらを結合する
   * 
   * @param id 移動経路（Area)となるであろうEntityID
   * @param concrete 啓開をしたい範囲を直線のリストで表した，Line2
   * @return concreteが表す直線のリストから，idが示すArea内にある瓦礫と重なっている直線を１つだけを取り出したLine2D
   * その直線とほとんど重なっている直線がリストにあれば，それらを統合した直線を表すLine2D
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
   * エージェントの半径
   */
  private static final double AGENT_RADIUS = 500.0;

  /**
   * 直線(Line2D)を２次元幾何学的図形に変換する
   * 
   * @param line　移動経路となるであろう経路のline
   * @return　エージェントの半径に基づく円の上下左右の頂点をmakeAWTAreaメソッドに返す
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
   * 啓開しようとする瓦礫がエージェントの啓開可能な範囲以内に入っていたら啓開する 
   * そうでなければ啓開の近くまで移動するという行動を返す
   * 
   * @param path 啓開する瓦礫のEntityIDのlist
   * @param clearline  啓開する範囲(line)
   * @return エージェントの啓開する範囲以内に入っているなら啓開する（入っていないなら次のEntityIDまで移動する）
   */
  private Action makeActionToClear(List<EntityID> path, Line2D clearline) {
    final Point2D op = clearline.getOrigin();
    final Point2D ep = clearline.getEndPoint();

    final double d = GeometryTools2D.getDistance(this.getPoint(), op);

    final Vector2D vec = clearline.getDirection();
    // final double l = vec.getLength();
    // final Vector2D extvec = vec.normalised().scale(l+AGENT_RADIUS);
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
   * 指定したEntityIDをシュリンクする
   * 基本的にエージェント自身が瓦礫に埋まっているときにしか実装されない
   * 
   * @param id シュリンクするEntityID
   * @return 埋まっている瓦礫を啓開する（瓦礫がない場合はnullを返す）
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
   * pathに沿ってエージェントを移動させるための行動を作成する
   * 
   * @param path 移動させるEntityID
   * @return 次のEntityIDに移動する（エージェントが目的地にいたら，nullを返す)
   */
  private Action makeActionToMove(List<EntityID> path) {
    return path.size() < 2 ? null : new ActionMove(path);
  }

  /**
   * エージェントの座標を取得する
   */
  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  /**
   * areaの座標を取得する
   * @param area 指定したarea
   */
  private static Point2D getPoint(Area area) {
    final double x = area.getX();
    final double y = area.getY();
    return new Point2D(x, y);
  }

  /**
   * humanの座標を取得する
   * @param human エージェント（消防隊と救急隊のみ）
   */
  private static Point2D getPoint(Human human) {
    final double x = human.getX();
    final double y = human.getY();
    return new Point2D(x, y);
  }

  /**
   * 線(line)の中点を算出
   * 
   * @param line 始点から終点までの線(経路)
   * @return 線の中点を算出(中点を0.5と設定)
   */
  private static Point2D computeMiddlePoint(Line2D line) {
    return line.getPoint(0.5);
  }

  /**
   * 瓦礫の頂点を算出する
   * 
   * @param lines 瓦礫の各頂点の配列
   * @param p エージェントの座標
   * @return エージェントから距離が短い瓦礫の頂点を返す
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
   *  各頂点までのパスを算出する
   * 
   * @param ps エージェントの半径に基づく円の上下左右の頂点
   * @return　閉じたバスを返す
   */
  private static java.awt.geom.Area makeAWTArea(Point2D[] ps) {
    final int n = ps.length;
    Path2D path = new Path2D.Double();
    path.moveTo(ps[0].getX(), ps[0].getY());

    for (int i = 1; i < n; ++i) {
      path.lineTo(ps[i].getX(), ps[i].getY());
    }
    /**最後の座標まで直線を描画して現在のサブパスを閉じる*/
    path.closePath();
    return new java.awt.geom.Area(path);
  }

  /**
   * エージェント自体の大きさを算出（Areaの広さ）
   * @param p エージェントの座標
   * @param rad エージェントの半径
   * @return エージェントの形を２次元楕円形に変換したAreaを返す
   */
  public static java.awt.geom.Area makeAWTArea(Point2D p, double rad) {
    final double d = rad * 2.0;
    final double x = p.getX() - rad;
    final double y = p.getY() - rad;
    return new java.awt.geom.Area(new Ellipse2D.Double(x, y, d, d));
  }

  /**
   * 2直線を結合できるかどうかを判定するメソッド
   * line1の終点とline1の始点が同じかつ，２つのlineをそれぞれ２次元ベクトルと見たとき
   * 2ベクトルのx,y成分それぞれの差が0に近い場合，2つのlineが結合できると判定される 
   * 
   * @param line1 EntityIDまでのline(直線１)
   * @param line2 次のEntityIDまでのline(直線２)
   * @return line1の終点とline2の始点が同じ場合はfalseを返す（両方の長さがどちらかが0の値ぐらいに等しい場合はbooleanを返す)
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
   * エージェントが目的地まで到達できなかったとき
   * @return faildMoveのクラスタリングが実行され，結果を返す
   */
  private boolean cannotReach() {
    final EntityID me = this.agentInfo.getID();
    this.failedMove.calc();
    return this.failedMove.getClusterIndex(me) >= 0;
  }

  /**
   * エラーを避けるために実装される
   * @return 新たなパスプランニングを実行し，ActionMoveを返す(マップ上に瓦礫がない場合nullを返し，あった場合はその瓦礫まで移動し啓開する)
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
   * エージェントから啓開する対象となる瓦礫までの距離を算出 
   * @param blockade 瓦礫のEntityID
   * @return エージェントから瓦礫までの距離を返す
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
   * エージェントが詰まっているとき
   * @return stuckedhumansを実行し，エージェント自身のEntityIDが含まれている場合はtrueを返す.
   */
  private boolean isStucked() {
    final EntityID me = this.agentInfo.getID();
    this.stuckedHumans.calc();
    return this.stuckedHumans.getClusterIndex(me) >= 0;
  }

  // @ DEBUG {{{
  private static java.awt.geom.Line2D convertToAWTLine(Line2D line) {
    final double x1 = line.getOrigin().getX();
    final double x2 = line.getEndPoint().getX();
    final double y1 = line.getOrigin().getY();
    final double y2 = line.getEndPoint().getY();
    return new java.awt.geom.Line2D.Double(x1, y1, x2, y2);
  }

  private static java.awt.Polygon convertToAWTPolygon(Point2D[] ps) {
    final int n = ps.length;
    int[] xs = new int[n];
    int[] ys = new int[n];
    for (int i = 0; i < n; ++i) {
      xs[i] = (int) ps[i].getX();
      ys[i] = (int) ps[i].getY();
    }
    return new java.awt.Polygon(xs, ys, n);
  }
  // }}}
}
