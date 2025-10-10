package AIT_2022.module.complex.pf;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_OFFICE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.Search;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;

// @ DEBUG {{
// private VDClient vdclient=VDClient.getInstance();
// }}

public class AITPoliceForceSearch extends Search {

  /**
   * PFの探索対象となるEntityのEntityID．nullとなる可能性もある
   */
  private EntityID result;

  /**
   * 各エージェントの担当範囲を決定するクラスタリングモジュール
   */
  private Set<EntityID> cluster = new HashSet<>();

  /**
   * key : 建物のEntityID
   * value : そこに埋没している可能性がある市民のEntityIDの集合
   */
  private Map<EntityID, Set<EntityID>> potentials = new HashMap<>();

  /**
   * PFがすでに探索を行ったEntityのEntityIDの集合
   */
  private Set<EntityID> reached = new HashSet<>();

  /**
   * 優先度にペナルティを与えるEntityのEntityIDの集合
   */
  private Set<EntityID> delayed = new HashSet<>();
  /**
   * delayedに与えるペナルティ
   */
  private double penalty = 0.0;

  /**
   * 消防隊がいるエリアのEntityIDの集合
   */
  private Set<EntityID> highPriorityPosition = new HashSet<>();

  /**
   * 埋没している市民のEntityIDの集合
   * key: 埋没している市民の場所のEntityID
   * value: 埋没している市民を無視しておく時間
   */
  private Map<EntityID, Integer> buriedCivilians = new HashMap<>();// key=position, value=ignoreTime

  /**
   * 各エージェントの担当範囲を決定するクラスタリングモジュール
   */
  private Clustering clusterer;

  /**
   * 自分自身が動けているかを検証するためのモジュール
   */
  private Clustering failedMove;

  /**
   * Blockadeに詰まっているHumanを発見するクラスタリングモジュール
   */
  private Clustering stuckedHumans;

  // @ DEBUG {{{
  // private VDClient vdclient = VDClient.getInstance();
  // }}}

  public AITPoliceForceSearch(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.clusterer = mm.getModule("AIT.PF.Search.Clustering");
    this.registerModule(this.clusterer);

    this.failedMove = mm.getModule("AIT.PF.Search.FailedMove");
    this.registerModule(this.failedMove);

    this.stuckedHumans = mm.getModule("AIT.PF.Search.StuckHumans");
    this.registerModule(this.stuckedHumans);

    // @ DEBUG {{{
    // this.vdclient.init("localhost", 1099);
    // }}}
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
   * 各ステップでエージェントが行動対象を決定するためのメソッド
   * buriedCiviliansから優先度の高い対象を行動対象とする,
   * buriedCiviliansが空であればclusterから優先度の高い対象を行動対象とする
   * 
   * @return Searchのcalcメソッド
   */
  @Override
  public Search calc() {
    if (this.needToExpandCluster()) {
      this.expandCluster();
    }
    if (this.needToClearReached()) {
      this.reached.clear();
    }

    Set<EntityID> candidates = new HashSet<>(this.cluster);
    candidates.removeAll(this.reached);

    this.result = chooseFromBuriedCivilian();
    if (this.result != null) {
      return this;
    }

    this.result = candidates.stream().max(this.comparator()).orElse(null);
    return this;
  }

  /**
   * 担当範囲初期設定をクラスタリングモジュールを用いておこなう
   */
  private void initCluster() {
    this.clusterer.calc();

    final EntityID me = this.agentInfo.getID();
    final int index = this.clusterer.getClusterIndex(me);
    final Collection<EntityID> buildings = this.gatherBuildings(this.clusterer.getClusterEntityIDs(index));

    this.cluster.addAll(buildings);
    this.penalty = this.computeClusterDiagonal();
  }

  /**
   * 設定された担当範囲内からタスクの候補が無くなった場合に， 別のエージェントの担当範囲を取り込む
   */
  private void expandCluster() {
    final EntityID me = this.agentInfo.getID();

    final int n = this.clusterer.getClusterNumber();
    final int index = this.clusterer.getClusterIndex(me);

    final int size = this.cluster.size();
    for (int i = 1; i < n && size == this.cluster.size(); ++i) {
      final Collection<EntityID> buildings = this.gatherBuildings(
          this.clusterer.getClusterEntityIDs(index + i * n));
      this.cluster.addAll(buildings);
    }
  }

  /**
   * すべての建物が探索済みであるかどうかを判定する
   * 
   * @return すべての建物が探索済みであるかどうか(Boolean)
   */
  private boolean needToClearReached() {
    Set<EntityID> buildings = this.worldInfo.getEntitiesOfType(BUILDING)
        .stream()
        .map(StandardEntity::getID)
        .collect(toSet());
    Collection<EntityID> refuges = this.worldInfo.getEntitiesOfType(REFUGE)
        .stream()
        .map(StandardEntity::getID)
        .collect(toSet());
    buildings.removeAll(refuges);

    long reachedCount = this.reached.stream().filter(r -> !(refuges.contains(r))).count();

    return reachedCount == buildings.size();
  }

  /**
   * クラスター内の9割以上が探索済みであるかどうかを判定する
   * 
   * @return クラスター内の9割以上が探索済みであるかどうか(Boolean)
   */
  private boolean needToExpandCluster() {
    return this.reached.size() >= this.cluster.size() * 0.9;
  }

  /**
   * シュミレーション内の全ての建物のEntityIDのSetを取得する
   * 
   * @return シュミレーション内の全ての建物のEntityIDのSet
   */
  private Set<EntityID> gatherBuildings(Collection<EntityID> collection) {
    final Stream<EntityID> ret = collection
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .map(StandardEntity::getID);

    return ret.collect(toSet());
  }

  /**
   * highPriorityPositionに含まれており、
   * かつ、無視しておく時間が0であり、
   * 距離が最も近い市民が埋没している場所のEntityIDを取得する
   * 
   * @return 埋没している場所のEntityID
   */
  private EntityID chooseFromBuriedCivilian() {
    EntityID me = this.agentInfo.getID();
    final Comparator<EntityID> comparator1 = comparing(
        i -> !(this.highPriorityPosition.contains(i)));
    final Comparator<EntityID> comparator2 = comparing(i -> this.worldInfo.getDistance(i, me));

    return this.buriedCivilians.keySet()
        .stream()
        .filter(b -> this.buriedCivilians.get(b) == 0)
        .min(comparator1.thenComparing(comparator2)).orElse(null);
  }

  /**
   * 市民がいる可能性が高いかつ、距離が近い順に並び替えるためのComparator
   * 
   * @return このクラスのフィールドであるpotentialsに含まれるEntityIDのcomparator
   */
  private Comparator<EntityID> comparator() {
    final EntityID me = this.agentInfo.getID();
    final Set<EntityID> empty = Collections.emptySet();

    final Comparator<EntityID> comparator1 = comparing(
        i -> this.potentials.getOrDefault(i, empty).size());
    final Comparator<EntityID> comparator2 = comparing(
        i -> this.worldInfo.getDistance(i, me)
            + (this.delayed.contains(i) ? this.penalty : 0.0));

    return comparator1.thenComparing(comparator2.reversed());
  }

  /**
   * 各ステップで実行される，エージェントの内部情報更新のためのメソッドなどの呼び出しやメッセージの受信をおこなう
   * メッセージの受信はreceiveMessageを呼び出す
   *
   * @param mm メッセージマネージャ
   * @return SearchのupdateInfo
   */
  @Override
  public Search updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    if (this.cluster.isEmpty()) {
      this.initCluster();
    }

    final Human human = (Human) this.agentInfo.me();
    if (this.cannotReach() && human.getBuriedness() == 0) {
      this.delayed.add(this.result);
    }

    // 情報の更新
    this.updateHighPriorityPosition();
    this.updateBuriedCivilian();
    this.reflectVoiceToPotentials();
    this.ignoreReachedBuildings();
    this.ignoreUntaskableBuildings();
    this.ignoreBuildingsOnFire();
    this.ignoreDiscoveredCivilians();

    this.receiveMessage(mm);

    return this;
  }

  /**
   * 消防隊がいるエリアを更新する
   */
  private void updateHighPriorityPosition() {
    this.highPriorityPosition.clear();
    this.highPriorityPosition = this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE)
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(FireBrigade.class::isInstance)
        .map(FireBrigade.class::cast)
        .filter(FireBrigade::isPositionDefined)
        .map(FireBrigade::getPosition)
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .map(StandardEntity::getID)
        .collect(toSet());
  }

  /**
   * 埋没している市民を無視する時間を更新する
   * 自分の視界内に埋没している市民がいる場合は、無視する時間を埋没度で更新を行う
   * 
   */
  private void updateBuriedCivilian() {
    for (EntityID buildingID : this.buriedCivilians.keySet()) {
      int time = Math.max(this.buriedCivilians.get(buildingID) - 1, 0);
      this.buriedCivilians.put(buildingID, time);
    }

    Set<Civilian> inSightBuriedCivilian = this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(e -> e instanceof Civilian)
        .map(Civilian.class::cast)
        .filter(Civilian::isBuriednessDefined)
        .filter(c -> c.getBuriedness() > 0)
        .filter(c -> !(this.worldInfo.getEntity(c.getPosition()) instanceof Refuge))
        .collect(toSet());
    for (Civilian civilian : inSightBuriedCivilian) {
      this.buriedCivilians.put(civilian.getPosition(), civilian.getBuriedness());
      if (civilian.isHPDefined() && civilian.getHP() <= 0) {
        this.buriedCivilians.remove(civilian.getPosition());
      }
    }

    EntityID nowPosition = this.agentInfo.getPosition();
    if (this.buriedCivilians.containsKey(nowPosition)
        && this.buriedCivilians.get(nowPosition) == 0) {
      this.buriedCivilians.remove(nowPosition);
    }
  }

  /**
   * 市民の声が聞こえる範囲にある建物をkeyとして、助けを求めた市民のIDをvalueとしたpotentialsを更新する
   */
  private void reflectVoiceToPotentials() {
    final Set<EntityID> candidates = this.gatherBuildingsInVoiceRange();
    final Set<EntityID> civilians = this.gatherHelpedCivilians();

    candidates.forEach(i -> {
      this.potentials
          .computeIfAbsent(i, k -> new HashSet<>())
          .addAll(civilians);
    });
  }

  /**
   * 音声通信が聞こえる範囲内の建物のEntityIDのSetを取得する
   * 
   * @return 音声通信が聞こえる範囲内の建物のEntityIDのSet
   */
  private Set<EntityID> gatherBuildingsInVoiceRange() {
    final int range = this.scenarioInfo.getRawConfig()
        .getIntValue("comms.channels.0.range");

    final EntityID me = this.agentInfo.getID();
    final Stream<EntityID> ret = this.worldInfo.getObjectsInRange(me, range)
        .stream()
        .filter(Building.class::isInstance)
        .map(StandardEntity::getID);

    return ret.collect(toSet());
  }

  /**
   * 市民の声を取得し、その市民のEntityIDのSetを取得する
   * 
   * @return 市民のEntityIDのSet
   */
  private Set<EntityID> gatherHelpedCivilians() {
    final Set<EntityID> agents = new HashSet<>(this.worldInfo.getEntityIDsOfType(
        FIRE_BRIGADE, FIRE_STATION,
        AMBULANCE_TEAM, AMBULANCE_CENTRE,
        POLICE_FORCE, POLICE_OFFICE));

    final Stream<EntityID> ret = this.agentInfo.getHeard()
        .stream()
        .filter(AKSpeak.class::isInstance)
        .map(AKSpeak.class::cast)
        .filter(s -> s.getChannel() == 0)
        .map(AKSpeak::getAgentID)
        .filter(i -> !agents.contains(i));

    return ret.collect(toSet());
  }

  /**
   * reachedの更新を行い、potentialsからreachedを除外する
   */
  private void ignoreReachedBuildings() {
    final EntityID position = this.agentInfo.getPosition();
    if (this.worldInfo.getEntity(position) instanceof Building) {
      this.reached.add(position);
    }

    this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .map(Building.class::cast)
        .filter(this::isReached)
        .map(Building::getID)
        .forEach(this.reached::add);

    this.potentials.keySet().removeAll(this.reached);
  }

  /**
   * 建物を知覚しているかどうかを返す
   * 
   * @param building 建物
   */
  private boolean isReached(Building building) {
    final int max = this.scenarioInfo.getPerceptionLosMaxDistance();
    final Line2D line = new Line2D(
        this.getPoint(),
        new Point2D(building.getX(), building.getY()));

    if (line.getDirection().getLength() >= max * 0.8) {
      return false;
    }
    for (Edge edge : building.getEdges()) {
      if (!edge.isPassable()) {
        continue;
      }

      final Point2D intersection = GeometryTools2D.getSegmentIntersectionPoint(
          line, edge.getLine());
      if (intersection != null) {
        return true;
      }
    }

    return false;
  }

  /**
   * 倒壊していない建物を知覚した場合に、その建物のIDをreachedに追加する
   * potentialsからreachedを除外し、更新を行う
   */
  private void ignoreUntaskableBuildings() {
    this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .map(Building.class::cast)
        .filter(Building::isBrokennessDefined)
        .filter(b -> b.getBrokenness() == 0)
        .map(Building::getID)
        .forEach(this.reached::add);

    this.potentials.keySet().removeAll(this.reached);
  }

  /**
   * 燃えている建物を知覚した場合に、その建物のIDをreachedに追加する
   * potentialsからreachedを除外し、更新を行う
   */
  private void ignoreBuildingsOnFire() {
    this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Building.class::isInstance)
        .map(Building.class::cast)
        .filter(Building::isOnFire)
        .map(Building::getID)
        .forEach(this.reached::add);

    this.potentials.keySet().removeAll(this.reached);
  }

  /**
   * 自分自身の位置をPoint2Dで取得する
   * 
   * @return 自分自身の位置をPoint2Dに変換したもの
   */
  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  /**
   * 声を聞いていた市民が発見された場合に、potentialsのvalueからその市民のEntityIDを除外する
   */
  private void ignoreDiscoveredCivilians() {
    final Collection<EntityID> changed = this.worldInfo.getChanged().getChangedEntities();

    final Set<EntityID> ignored = changed
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Civilian.class::isInstance)
        .map(StandardEntity::getID)
        .collect(toSet());

    this.potentials.values().forEach(vs -> vs.removeAll(ignored));
  }

  /**
   * 自分自身が動けているかどうかを返す
   * 
   * @return 自分自身が動けるているかどうか(boolean)
   */
  private boolean cannotReach() {
    final EntityID me = this.agentInfo.getID();
    final StandardEntityURN urn = this.agentInfo.me().getStandardURN();

    if (this.result == null) {
      return false;
    }
    if (urn == POLICE_FORCE) {
      return false;
    }

    this.failedMove.calc();
    this.stuckedHumans.calc();

    final boolean failed = this.failedMove.getClusterIndex(me) >= 0;
    final boolean stucked = this.stuckedHumans.getClusterIndex(me) >= 0;
    return stucked || failed;
  }

  /**
   * クラスターの対角線の長さを取得する
   * 
   * @return クラスターの対角線の長さ(Double)
   */
  private double computeClusterDiagonal() {
    final List<Building> buildings = this.cluster
        .stream()
        .map(this.worldInfo::getEntity)
        .map(Building.class::cast)
        .collect(toList());

    final int minX = buildings.stream().mapToInt(Area::getX).min().orElse(0);
    final int minY = buildings.stream().mapToInt(Area::getY).min().orElse(0);
    final int maxX = buildings.stream().mapToInt(Area::getX).max().orElse(0);
    final int maxY = buildings.stream().mapToInt(Area::getY).max().orElse(0);

    return Math.hypot(maxX - minX, maxY - minY);
  }

  /**
   * 救助を必要としているかどうかを取得する
   * 
   * @param id 検証するEntityID
   * @return 救助が必要なHumanであるかをBooleanで返す
   */
  private boolean needToRescue(EntityID id) {
    if (!this.isHuman(id)) {
      return false;
    }
    if (this.worldInfo.getPosition(id).getStandardURN() != BUILDING) {
      return false;
    }
    final Human h = (Human) this.worldInfo.getEntity(id);
    final int hp = h.getHP();
    final int damage = h.getDamage();
    final int buried = h.getBuriedness();
    if (hp * damage * buried == 0) {
      return false;
    }
    return true;
  }

  /**
   * EntityIDからEntityがHumanか検証する
   *
   * @param id 検証するEntityID
   * @return idのEntityがHumanならtrue、そうでなければfalse
   */
  private boolean isHuman(EntityID id) {
    if (this.worldInfo.getEntity(id) instanceof Human) {
      return true;
    }
    return false;
  }

  /**
   * classがMessageCivilianのMessageを取り出し、それぞれをhundleMessageに渡す
   * 
   * @param mm MessageManager
   */
  private void receiveMessage(MessageManager mm) {
    mm.getReceivedMessageList(MessageCivilian.class)
        .stream()
        .filter(MessageCivilian.class::isInstance)
        .map(MessageCivilian.class::cast)
        .forEach(this::handleMessage);
  }

  /**
   * MessageCivilianのMessageを受け取って、Messageから埋まっているtarget(EntityID)を取り出し、
   * そのEntityが自分の担当のクラスター内にいるのであれば、埋まっている市民として追加する
   * 
   * @param msg MessageCivilian
   */
  private void handleMessage(MessageCivilian msg) {
    final EntityID me = this.agentInfo.getID();
    final EntityID targetID = msg.getPosition();

    final int myClsNum = this.clusterer.getClusterIndex(me);
    final Collection<EntityID> entities = this.clusterer.getClusterEntityIDs(myClsNum);
    if (!entities.contains(targetID)) {
      return;
    }
    this.buriedCivilians.put(targetID, msg.getBuriedness() - 1);
  }
}
