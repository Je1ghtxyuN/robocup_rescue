package AIT_2022.module.complex.pf;

import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.component.module.complex.RoadDetector;
import adf.core.component.module.algorithm.*;
import adf.core.component.communication.CommunicationMessage;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.*;
import adf.core.agent.communication.standard.bundle.information.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.misc.geometry.*;
import org.locationtech.jts.geom.*;

import AIT_2022.module.algorithm.GraphEdgeValidator;

import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

// @ DEBUG {{{
import AIT_2022.module.util.DebugUtil;
// }}}

public class AITPoliceForceDetector extends RoadDetector {

  /**
   * PFの行動対象となるEntityのEntityID．nullとなる可能性もある
   */
  private EntityID result = null;

  /**
   * 各エージェントの担当範囲を決定するクラスタリングモジュール
   */
  private Clustering clusterer;
  /**
   * 優先的に啓開・通行するRoadを決定するクラスタリングモジュール
   */
  private StaticClustering highways;
  /**
   * Blockadeに詰まっているHumanを発見するクラスタリングモジュール
   */
  private Clustering stuckedHumans;
  /**
   * 初期状態で災害救助隊が集中しているAreaを発見するクラスタリングモジュール
   */
  private StaticClustering overcrowdingArea;
  /**
   * 移動経路を決定するパスプランニングモジュール
   */
  private PathPlanning pathPlanner;
  /**
   * 移動経路を決定するパスプランニングモジュール
   */
  private PathPlanning astar;
  // TODO:経路決定のためのパスプランニングモジュールが複数設定されており，
  //      処理ごとに経路が異なってしまう可能性がある．
  //      各モジュールを比較し，より良い方のみを採用する．
  /**
   * 視覚から各建物にいる市民を認識して，その市民がRescueタスクなのか，Loadタスクなのかを判定するモジュール
   */
  private CivilianMessages civMessages;

  /**
   * PFの行動対象となる可能性のあるEntityのEntityIDの集合 key = PFの行動対象となる可能性のあるEntityのEntityID value =
   * keyのタスクの優先度（1（高）〜 8（低））
   */
  private Map<EntityID, Integer> tasks = new HashMap<>();
  /**
   * 自身の担当範囲のEntityのEntityIDの集合
   */
  private Set<EntityID> cluster = new HashSet<>();
  /**
   * 完了したタスクの集合
   */
  private Set<EntityID> completed = new HashSet<>();
  /**
   * 埋没していると判定された（自分以外の）PFの担当範囲 key = 埋没しているエージェント value = keyのPFが担当するクラスタのEntityのEntityID
   */
  //
  private Map<EntityID, Collection<EntityID>> buriedPoliceCluster = new HashMap<>();

  /**
   * 通信用の定義
   */
  private final boolean VOICE = false;
  private final boolean RADIO = true;
  final private int HELP_BURIED = 5;
  final private int HELP_BLOCKADE = 6;

  /**
   * Debug用のメッセージ & 描画をするためのモジュール
   */
  DebugUtil debug;

  public AITPoliceForceDetector(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.pathPlanner = mm.getModule("AIT.PF.RoadDetector.PathPlanning");
    this.registerModule(this.pathPlanner);

    this.clusterer = mm.getModule("AIT.PF.RoadDetector.Clustering");
    this.registerModule(this.clusterer);

    this.highways = mm.getModule("AIT.PF.RoadDetector.Highways");
    this.registerModule(this.highways);

    this.stuckedHumans = mm.getModule("AIT.PF.RoadDetector.StuckHumans");
    this.registerModule(this.stuckedHumans);

    this.overcrowdingArea = mm.getModule("AIT.PF.RoadDetector.OvercrowdingArea");
    this.registerModule(this.overcrowdingArea);

    this.astar = mm.getModule("AIT.PF.RoadDetector.AstarPathPlanning");
    this.registerModule(this.astar);

    this.civMessages = mm.getModule("AIT.PF.RoadDetector.CivilianMessages");
    this.registerModule(this.civMessages);

    // @ DEBUG {{{
    this.debug = new DebugUtil(this.agentInfo, this.worldInfo, this.scenarioInfo);
    // }}}
  }

  /**
   * 各ステップでエージェントが行動対象を決定するためのメソッド tasksから優先度の高い5つの対象を抜き出し， そのうち最も距離が近い対象を自身の行動対象とする．
   *
   * @return RoadDetectorのcalcメソッド
   */
  @Override
  public RoadDetector calc() {
    this.civMessages.calc();
    this.result = null;
    if (this.needIdle()) {
      return this;
    }

    final Stream<EntityID> candidates =
        this.tasks.keySet()
            .stream()
            .filter(t -> !this.completed.contains(t))
            .sorted(this.comparator1()).limit(5);

    this.result =
        candidates.min(this.comparator2()).orElse(null);

    if (this.result == null) {
      final int size = this.cluster.size();
      this.expandCluster();
      this.renewTasksOnCluster();
      this.renewTasksWithOvercrowdingArea(1);
      if (size != this.cluster.size()) {
        return this.calc();
      }
    }
    return this;
  }

  /**
   * <EntityID, Integer> tasksに登録されているEntityIDを valueとなっている優先度に応じた順序に並び帰るためのComparator
   * valueの値が同じ場合はエージェントからの距離が近い順序になる
   *
   * @return このクラスのフィールドであるtasksに含まれるEntityIDのcomparator
   */
  private Comparator<EntityID> comparator1() {
    final EntityID me = this.agentInfo.getID();

    final Comparator<EntityID> comparator1 = comparing(this.tasks::get);
    final Comparator<EntityID> comparator2 = comparing(
        i -> this.worldInfo.getDistance(me, i));
    return comparator1.thenComparing(comparator2);
  }

  /**
   * <EntityID, Integer> tasksに登録されているEntityIDを valueとなっている優先度に応じた順序に並び帰るためのComparator
   * valueの値が同じ場合はエージェントのいるAreaからのの距離が近い順序になる
   *
   * @return このクラスのフィールドであるtasksに含まれるEntityIDのcomparator
   */
  private Comparator<EntityID> comparator2() {
    final EntityID position = this.agentInfo.getPosition();

    final Comparator<EntityID> comparator1 = comparing(this.tasks::get);
    final Comparator<EntityID> comparator2 = comparing(
        i -> this.computeDistance(position, i));
    return comparator1.thenComparing(comparator2);
  }

  /**
   * パスプランニングモジュールを用いて，エージェントが通過するAreaを算出し， 各エリアの中央を順に結んだ線分を用いて，移動距離を算出する．
   *
   * @param from        パスプランニングの開始地点となるAreaのEntityID
   * @param destination パスプランニングの終了地点となるAreaのEntityID
   * @return fromからdestinationを算出された経路に沿って結ぶ線分の長さ（double）
   */
  private double computeDistance(EntityID from, EntityID destination) {
    this.pathPlanner.setFrom(from);
    this.pathPlanner.setDestination(destination);
    this.pathPlanner.calc();
    final List<EntityID> path = this.pathPlanner.getResult();
    final int n = path.size();

    double ret = 0.0;
    for (int i = 1; i < n; ++i) {
      ret += this.worldInfo.getDistance(path.get(i - 1), path.get(i));
    }
    return ret;
  }

  /**
   * PFの行動対象候補となるEntityIDを優先度とともに登録する
   *
   * @param task     PFの行動対象候補であるEntityのEntityID
   * @param priority taskの優先度（1（高）〜8（低））のInteger
   */
  private void putTask(EntityID task, int priority) {
    final int current = this.tasks.getOrDefault(task, Integer.MAX_VALUE);
    this.tasks.put(task, Math.min(current, priority));
  }

  /**
   * PFの行動対象を取得する
   *
   * @return PFの行動対象であるEntityのEntityID
   */
  @Override
  public EntityID getTarget() {
    return this.result;
  }

  /**
   * 各ステップで実行される，エージェントの内部情報更新のためのメソッド タスクの候補の集合であるtasksの更新と，メッセージの送受信をおこなう
   * tasksの更新はupdate~やput~，renew~になどの名前のつけられたメソッドが更新をおこなう このとき，tasksへ入る候補は自身の担当範囲内のものが優先される．
   * メッセージの送受信ばreceiveMessageとsendMessageメソッドでおこなう
   *
   * @param mm メッセージマネージャ
   * @return RoadDetectorのupdateInfo
   */
  @Override
  public RoadDetector updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    final StandardEntity position
        = this.worldInfo.getPosition(this.agentInfo.getID());
    if (position instanceof Road) {
      final Area area = (Area) position;
      if (area.getBlockades() == null || area.getBlockades().isEmpty()) {
        this.tasks.remove(position.getID());
      }
    }

    // 担当するクラスタから行動対象候補が亡くなった場合に，クラスタを更新する
    if (this.cluster.isEmpty()) {
      this.initCluster();
      this.renewTasksOnCluster();
      this.renewTasksWithOvercrowdingArea(1);
    }

    //if the agent find buried agent, it add buried-agent-cluster in own cluster.
    this.updateBuriedPoliceWithPerception();
    this.updateCompletedTasksWithPercept();
    this.putRequestsWithPercept();
    this.putNeighborBlockadesWithPerception();

    // receiving & sending message
    this.receiveMessage(mm);
    this.sendMessage(mm);
    return this;
  }

  /**
   * 担当範囲初期設定をクラスタリングモジュールを用いておこなう
   */
  private void initCluster() {
    this.clusterer.calc();

    final EntityID me = this.agentInfo.getID();
    final int index = this.clusterer.getClusterIndex(me);
    final Collection<EntityID> ids =
        this.clusterer.getClusterEntityIDs(index);
    this.cluster.addAll(ids);
  }

  /**
   * 設定された担当範囲内からタスクの候補が亡くなった場合に， 別のエージェントの担当範囲を取り込む
   */
  private void expandCluster() {
    final EntityID me = this.agentInfo.getID();

    final int n = this.clusterer.getClusterNumber();
    final int index = this.clusterer.getClusterIndex(me);

    final int size = this.cluster.size();
    for (int i = 1; i < n && size == this.cluster.size(); ++i) {
      final Collection<EntityID> ids =
          this.clusterer.getClusterEntityIDs(index + i * n);
      this.cluster.addAll(ids);
    }
  }

  /**
   * 自身の担当範囲から，行動対象候補をtasksに登録する NOTE:単純にMapのputを用いて登録をおこなうため，優先度が降順となるようにメソッドは実行されなければならない
   */
  private void renewTasksOnCluster() {
    this.renewTasksWithCluster(8);
    this.renewTasksWithBuildings(7);
    this.renewTasksWithHighways(6);
    this.renewTasksWithBuildingsAgentAt(5);
    this.renewTasksWithRefuges(0);
  }

  // @ RENEW TASKS {{{

  /**
   * クラスタに含まれるEntityを行動対象候補として登録する
   *
   * @param priority 登録する対象の優先度を表すint
   */
  private void renewTasksWithCluster(int priority) {
    this.cluster.forEach(i -> this.putTask(i, priority));
  }

  /**
   * 優先道路としているAreaを行動対象候補として登録する
   *
   * @param priority 登録する対象の優先度を表すint
   */
  private void renewTasksWithBuildings(int priority) {
    final Stream<EntityID> buildings =
        this.cluster
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Building.class::isInstance)
            .map(StandardEntity::getID)
            .filter(this.cluster::contains);

    buildings.forEach(i -> this.putTask(i, priority));
  }

  /**
   * 担当範囲内の優先道路としているAreaを行動対象候補として登録する
   *
   * @param priority 登録する対象の優先度を表すint
   */
  private void renewTasksWithHighways(int priority) {
    final Stream<EntityID> highways =
        this.highways.getClusterEntityIDs(0)
            .stream()
            .filter(this.cluster::contains)
            .map(this.worldInfo::getEntity)
            .filter(Road.class::isInstance)
            .map(StandardEntity::getID);

    highways.forEach(i -> this.putTask(i, priority));
  }

  /**
   * 災害救助隊が初期位置としている建物を行動対象候補として登録する
   *
   * @param priority 登録する対象の優先度を表すint
   */
  // REVIEW:現在はbuildingを対象としているが，buildingはBlockadesは発生しない
  //        buildingのneighborとなっているEntityについてRoadであるかの確認をおこない，
  //        neighborを候補とするべきかもしれない
  private void renewTasksWithBuildingsAgentAt(int priority) {
    final EntityID me = this.agentInfo.getID();
    final Stream<StandardEntity> agentAt =
        this.worldInfo
            .getEntityIDsOfType(FIRE_BRIGADE, POLICE_FORCE, AMBULANCE_TEAM)
            .stream().filter(i -> !i.equals(me))
            .map(i -> this.worldInfo.getPosition(1, i));

    final Stream<EntityID> buildings =
        agentAt
            .filter(Building.class::isInstance)
            .map(StandardEntity::getID)
            .filter(this.cluster::contains);

    buildings.forEach(i -> this.putTask(i, priority));
  }

  /**
   * 担当範囲内のRefugeを行動対象候補として登録する
   *
   * @param priority 登録する対象の優先度を表すint
   */
  private void renewTasksWithRefuges(int priority) {
    final Stream<EntityID> refuges =
        this.cluster
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Refuge.class::isInstance)
            .map(StandardEntity::getID);

    refuges.forEach(i -> this.putTask(i, priority));
  }

  /**
   * 初期位置において，エージェントが集中して配置されているAreaを行動対象候補として登録する
   *
   * @param priority 登録する対象の優先度を表すint
   */
  private void renewTasksWithOvercrowdingArea(int priority) {
    EntityID myId = this.agentInfo.getID();
    EntityID myPosition = this.agentInfo.getPosition();
    int ind = this.overcrowdingArea.getClusterIndex(myId);
    Collection<EntityID> areas = this.overcrowdingArea.getClusterEntityIDs(ind);
    for (EntityID area : areas) {
      if (this.isSuperintendOvercrowdingArea(area)) {
        Collection<EntityID> path = this.astar.getResult(myPosition, area);
        path.removeAll(this.completed);
        path.forEach(id -> this.putTask(id, priority));
      }
    }
  }
  // }}}

  // @ CHECK COMPLETED {{{

  /**
   * 視野範囲内のAreaについて，タスクの対象とならないRoadをcompletedに登録する NOTE:エージェントが通行できればタスク完了と判定するため，
   * completedに登録されるものにはBlockadeが完全に除去されていないRoadも含まれる．
   */
  private void updateCompletedTasksWithPercept() {
    final Set<EntityID> changes =
        this.worldInfo.getChanged().getChangedEntities();
    final EntityID position = this.agentInfo.getPosition();

    Map<EntityID, EntityID> focused = new HashMap<>();//視野範囲内のEntityの内，タスク完了済の道路
    for (EntityID id : changes) {
      final StandardEntity entity = this.worldInfo.getEntity(id);
      if (!Area.class.isInstance(entity)) {
        continue;
      }

      final EntityID value =
          !id.equals(position) && Building.class.isInstance(entity) ?
              this.seekEntranceOnBuilding(id) : id;

      if (this.isRoadCompleted(value)) {
        focused.put(id, value);
      }
    }

    Set<EntityID> scope = new HashSet<>();
    Queue<EntityID> queue = new LinkedList<>();
    queue.add(position);

    while (!queue.isEmpty()) {
      final EntityID id = queue.remove();
      if (!focused.containsKey(id)) {
        continue;
      }
      if (scope.contains(id)) {
        continue;
      }

      scope.add(id);
      final Area area = (Area) this.worldInfo.getEntity(id);
      area.getNeighbours().forEach(queue::add);
    }

    final Stream<EntityID> updates = focused.keySet()
        .stream().filter(k -> scope.contains(focused.get(k)));
    updates.forEach(this.completed::add);
  }

  /**
   * 対象とする建物のエントランスとなっているRoadを抜き出す エージェントが現在の位置から，対象となるBuildingまでの経路を算出し， 対象のBuilding -->
   * エージェントの現在の位置 の経路上で，最初のRoadをエントランスとして抜き出す
   *
   * @param building 対象とするBuilding
   * @return buildingのエントランスであるRoad
   */
  private EntityID seekEntranceOnBuilding(EntityID building) {
    final EntityID position = this.agentInfo.getPosition();

    this.pathPlanner.setFrom(position);
    this.pathPlanner.setDestination(building);
    this.pathPlanner.calc();
    final List<EntityID> path =
        this.normalize(this.pathPlanner.getResult());

    final int n = path.size();
    for (int i = n - 1; i >= 0; --i) {
      final EntityID id = path.get(i);
      final StandardEntity entity = this.worldInfo.getEntity(id);
      if (entity instanceof Road) {
        return id;
      }
    }

    return null;
  }

  /**
   * 算出した経路にエージェントの位置するAreaのEntityIDが含まれない場合に，そのEntityIDを追加する
   *
   * @param path エージェントの位置するAreaが含まれているか判定をおこなう対象となる経路であるEntityIDのList
   * @return pathにエージェントの位置するAreaのEntityIDを含めたpath（元から含まれていた場合にはそのまま）
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
   * エージェントの半径
   */
  private static final double AGENT_RADIUS = 500.0;

  /**
   * 対象となるRoadをエージェントが通行可能であるかを判定する （タスクの完了判定）
   *
   * @param id 対象とするRoadのEntityID
   * @return idのRoadが通行可能であるかどうかを表すboolean（trueであれば通行可能であり，タスクが完了している）
   */
  private boolean isRoadCompleted(EntityID id) {
    if (id == null) {
      return false;
    }

    final Area area = (Area) this.worldInfo.getEntity(id);
    if (!area.isBlockadesDefined()) {
      return true;
    }

    final Collection<Blockade> blockades =
        area.getBlockades()
            .stream()
            .map(this.worldInfo::getEntity)
            .map(Blockade.class::cast)
            .collect(toList());

    final Geometry passable =
        GraphEdgeValidator.computePassable(area, blockades);

    final List<Line2D> edges =
        area.getEdges()
            .stream()
            .filter(Edge::isPassable)
            .map(Edge::getLine)
            .collect(toList());

    final int n = edges.size();
    for (int i = 0; i < n; ++i) {
      for (int j = i + 1; j < n; ++j) {
        final Line2D l1 = edges.get(i);
        final Line2D l2 = edges.get(j);
        if (l1.getDirection().getLength() < AGENT_RADIUS * 2) {
          continue;
        }
        if (l2.getDirection().getLength() < AGENT_RADIUS * 2) {
          continue;
        }
        if (!GraphEdgeValidator.validate(passable, l1, l2)) {
          return false;
        }
      }
    }

    return true;
  }
  // }}}

  /**
   * 視野範囲内のFB，ATの行動を妨げているBlockadeを行動対象候補として登録する（優先度は2）
   */
  private void putRequestsWithPercept() {
    final Set<EntityID> changes =
        this.worldInfo.getChanged().getChangedEntities();

    this.stuckedHumans.calc();
    final Collection<EntityID> stucks =
        this.stuckedHumans.getClusterEntityIDs(0);
    for (EntityID id : changes) {
      if (!stucks.contains(id)) {
        continue;
      }

      final StandardEntity entity = this.worldInfo.getEntity(id);
      if (!(entity instanceof Human)) {
        continue;
      }
      if (entity.getStandardURN() == POLICE_FORCE) {
        continue;
      }
      if (entity.getStandardURN() == CIVILIAN) {
        continue;
      }

      final Human human = (Human) entity;
      final EntityID position = human.getPosition();

      this.putTask(position, 2);
      this.completed.remove(position);
    }
  }

  /**
   * 視野範囲内のHumanの行動の妨げとなりうるBlockadeを行動対象候補として登録する（優先度は2）
   */
  private void putNeighborBlockadesWithPerception() {
    Collection<Human> humans = this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Human.class::isInstance)
        .map(Human.class::cast)
        .filter(human -> !(this.worldInfo.getPosition(human) instanceof AmbulanceTeam))
        .filter(human -> !(human instanceof PoliceForce))
        .collect(toSet());

    for (Human human : humans) {
      Area area = (Area) this.worldInfo.getPosition(human);
      area.getNeighbours()
          .stream()
          .map(this.worldInfo::getEntity)
          .filter(Area.class::isInstance)
          .map(Area.class::cast)
          .filter(a -> !(this.completed.contains(a)))
          .filter(Area::isBlockadesDefined)
          .map(Area::getID)
          .forEach(neighbor -> this.putTask(neighbor, 2));
    }
  }

  /**
   * calcメソッドを動作させる必要のあるシミュレーションステップであるか否かを判定する
   *
   * @return 現在のシミュレーションステップがcalcメソッドを動作させる必要のあるシミュレーションステップであるか 否かを表すboolean
   */
  private boolean needIdle() {
    final int time = this.agentInfo.getTime();
    final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();
    return time < ignored;
  }

  /**
   * エージェントが受け取ったメッセージを整理し，各々のメッセージをthis.handleMessage()で処理をする
   * @param mm MessageManager
   */
  private void receiveMessage(MessageManager mm) {
    //AmbulanceTeamが瓦礫で動けないというメッセージを取得
    final List<CommunicationMessage> helpAmbulance = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    for(CommunicationMessage mes : helpAmbulance) {
      final MessageAmbulanceTeam message = (MessageAmbulanceTeam) mes;
      this.handleMessage(message);
    }
    //FireBrigadeが瓦礫で動けないというメッセージを取得
    final List<CommunicationMessage> helpFire = mm.getReceivedMessageList(MessageFireBrigade.class);
    for(CommunicationMessage mes : helpFire) {
      final MessageFireBrigade message = (MessageFireBrigade) mes;
      this.handleMessage(message);
    }
  }

  /**
   * MessageFireBrigadeから自身のタスク候補へ追加
   * @param message エージェントが受け取ったMessageFireBrigade
   */
  private void handleMessage(MessageFireBrigade message) {
    final EntityID sender = message.getSenderID();
    final StandardEntity senderEntity = this.worldInfo.getEntity(sender);
    final EntityID targetPosition = message.getTargetID();

    if(message.getAction() != HELP_BLOCKADE) return;
    if(!message.isTargetDefined()) return;

    if(this.worldInfo.getDistance(this.agentInfo.getID(), sender)
            <= this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range")) {
      this.putTask(targetPosition, 0);
      this.completed.remove(targetPosition);
    }

    if(!this.cluster.contains(targetPosition)) return;

    this.putTask(targetPosition, 0);
    this.completed.remove(targetPosition);
  }

  /**
   * MessageAmbulanceから自身のタスク候補へ追加
   * @param message エージェントが受け取ったMessageAmbulance
   */
  private void handleMessage(MessageAmbulanceTeam message) {
    final EntityID sender = message.getSenderID();
    final StandardEntity senderEntity = this.worldInfo.getEntity(sender);
    final EntityID targetPosition = message.getTargetID();

    if(message.getAction() != HELP_BLOCKADE) return;
    if(!message.isTargetDefined()) return;

    if(this.worldInfo.getDistance(this.agentInfo.getID(), sender)
            <= this.scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range")) {
      this.putTask(targetPosition, 0);
      this.completed.remove(targetPosition);
    }

    if(!this.cluster.contains(targetPosition)) return;

    this.putTask(targetPosition, 0);
    this.completed.remove(targetPosition);
  }

  /**
   * 自身が埋没しているときメッセージを送信して助けを求める
   * @param mm MessageManager
   */
  private void sendMessage(MessageManager mm) {
    final PoliceForce me = (PoliceForce) this.worldInfo.getEntity(this.agentInfo.getID());
    // 自身が埋没しているとき
    if (this.isMyselfBuried()) {
      mm.addMessage(new MessagePoliceForce(RADIO, me, HELP_BURIED, this.agentInfo.getID()));
      mm.addMessage(new MessagePoliceForce(VOICE, me, HELP_BURIED, this.agentInfo.getID()));
      return;
    }
  }

  private boolean isMyselfBuried() {
    Human me = (Human) this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(this.agentInfo.getPositionArea() instanceof Building)) return false;
    if (!me.isBuriednessDefined()) return false;
    return me.getBuriedness() != 0;
  }

  private void showResult() {
    final EntityID me = this.agentInfo.getID();
    String out = "";
    out += String.format("🚜 [%10d] ", me.getValue());
    out += String.format("ROAD-DETECTOR -> %10d", this.result.getValue());
    System.out.println(out);
  }


  private final Double MAX_DISTANCE =
      Math.max(this.worldInfo.getBounds().getMaxX(), this.worldInfo.getBounds().getMaxY());
  private final Double ONE_SIDE =
      this.scenarioInfo.getScenarioAgentsPf() != 0 ? Math.floor(
          Math.sqrt(this.scenarioInfo.getScenarioAgentsPf())) : 1;
  private final Double INCLUDE_DISTANCE = MAX_DISTANCE / ONE_SIDE;

  private boolean judgeAddClusterWithBuriedPolice(PoliceForce policeForce) {

    Integer ownClusterIndex = this.clusterer.getClusterIndex(this.agentInfo.getID());
    EntityID ownArea =
        this.clusterer.getClusterEntityIDs(ownClusterIndex)
            .stream()
            .min(Comparator.comparing(EntityID::getValue))
            .orElse(new EntityID(-1));

    if (ownArea.equals(new EntityID(-1))) {
      return false;
    }

    Integer someoneClusterIndex = this.clusterer.getClusterIndex(policeForce.getID());
    EntityID someoneArea =
        this.clusterer.getClusterEntityIDs(someoneClusterIndex)
            .stream()
            .min(Comparator.comparing(EntityID::getValue))
            .orElse(new EntityID(-1));

    if (someoneArea.equals(new EntityID(-1))) {
      return false;
    }

    return this.worldInfo.getDistance(ownArea, someoneArea) < INCLUDE_DISTANCE;
  }

  //if buried-Police is in agent's field of view, agent add buried-police's cluster.
  private void updateBuriedPoliceWithPerception() {
    EntityID myPosition = this.agentInfo.getPosition();
    //peek police force
    Collection<PoliceForce> policeForces =
        this.worldInfo.getChanged().getChangedEntities().stream()
            .map(this.worldInfo::getEntity)
            .filter(e -> e instanceof PoliceForce)
            .map(PoliceForce.class::cast)
            .filter(PoliceForce::isBuriednessDefined)
            .collect(toSet());

    for (PoliceForce policeForce : policeForces) {
      // remove not-buried-police
      if (policeForce.getID().equals(this.agentInfo.getID())) {
        continue;
      }
      if (policeForce.getBuriedness() == 0) {
        if (this.buriedPoliceCluster.containsKey(policeForce.getID())) {
          this.cluster.removeAll(this.buriedPoliceCluster.get(policeForce.getID()));
        }
        this.buriedPoliceCluster.remove(policeForce.getID());
      }
      // set buried-police
      else if (!this.buriedPoliceCluster.containsKey(policeForce.getID())) {
        if (this.judgeAddClusterWithBuriedPolice(policeForce)) {
          //add someone's cluster
          this.addSomeoneClusterInMyCluster(policeForce.getID());
          //add someone's superintend overcrowding area
          Integer overcrowdingAreaInd = this.overcrowdingArea.getClusterIndex(
              policeForce.getID());
          Collection<EntityID> areas = this.overcrowdingArea.getClusterEntityIDs(
              overcrowdingAreaInd);
          areas.stream().sorted(Comparator.comparing(EntityID::getValue))
              .forEach(area ->
              {
                if (this.isSuperintendOvercrowdingArea(area, policeForce.getID())) {
                  Collection<EntityID> path = this.astar.getResult(myPosition, area);
                  path.removeAll(this.completed);
                  path.forEach(id -> this.putTask(id, 1));
                }
              });
        } else//If agent are not in charge, set size 0
        {
          this.buriedPoliceCluster.put(policeForce.getID(), new HashSet<>(0));
        }
      }
    }
  }

  private void addSomeoneClusterInMyCluster(EntityID someone) {
    int ind = this.clusterer.getClusterIndex(someone);
    Collection<EntityID> someoneCluster = this.clusterer.getClusterEntityIDs(ind);
    this.buriedPoliceCluster.put(someone, someoneCluster);
    this.cluster.addAll(someoneCluster);
  }

  private boolean isSuperintendOvercrowdingArea(EntityID area) {
    if (this.completed.contains(area)) {
      return false;
    }
    int ownClusterIndex = this.clusterer.getClusterIndex(this.agentInfo.getID());
    EntityID ownArea =
        this.clusterer.getClusterEntityIDs(ownClusterIndex)
            .stream()
            .min(Comparator.comparing(EntityID::getValue))
            .orElse(null);
    if (ownArea == null) {
      return false;
    }

    return this.worldInfo.getDistance(ownArea, area) < INCLUDE_DISTANCE;
  }

  private boolean isSuperintendOvercrowdingArea(EntityID area, EntityID someone) {
    if (this.completed.contains(area)) {
      return false;
    }
    int someoneClusterIndex = this.clusterer.getClusterIndex(someone);
    EntityID ownArea =
        this.clusterer.getClusterEntityIDs(someoneClusterIndex)
            .stream()
            .min(Comparator.comparing(EntityID::getValue))
            .orElse(null);
    if (ownArea == null) {
      return false;
    }

    return this.worldInfo.getDistance(ownArea, area) < INCLUDE_DISTANCE;
  }
}
