package AIT_2022.module.complex.fb;

import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandAmbulance;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.component.module.complex.Search;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import rescuecore2.standard.entities.*;
import rescuecore2.messages.Message;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.geometry.*;
import java.util.*;
import java.util.stream.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;
import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

// @ DEBUG {{
// import com.mrl.debugger.remote.VDClient;
// }}

public class AITFireBrigadeSearch extends Search {

  //探索対象のEntityID
  private EntityID result;

  //自身の担当範囲のEntityID
  private Set<EntityID> cluster = new HashSet<>();
  //key:建物のEntityID Value:その建物に埋没している可能性のある市民のEntityID集合
  private Map<EntityID, Set<EntityID>> potentials = new HashMap<>();
  //探索済みのEntityID
  private Set<EntityID> reached = new HashSet<>();

  //自身が動けず探索が遅れている場所
  private Set<EntityID> delayed = new HashSet<>();
  //delayedに与えるペナルティ自身のクラスタの対角線の長さつまり大きさ
  private double penalty = 0.0;

  //Cluster Module
  private Clustering clusterer;
  private Clustering failedMove;
  private Clustering stuckedHumans;

  private Set<EntityID> receiveRescueTask = new HashSet<>();
  // @ DEBUG {{{
  // private VDClient vdclient = VDClient.getInstance();
  // }}}

  public AITFireBrigadeSearch(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.clusterer = mm.getModule("AIT.FB.Search.Clustering");
    this.registerModule(this.clusterer);

    this.failedMove = mm.getModule("AIT.FB.Search.FailedMove");
    this.registerModule(this.failedMove);

    this.stuckedHumans = mm.getModule("AIT.FB.Search.StuckHumans");
    this.registerModule(this.stuckedHumans);

    // @ DEBUG {{{
    // this.vdclient.init("localhost", 1099);
    // }}}
  }

  /**
   * Calcで計算した探索対象を返す
   * @return 探索対象
   */
  @Override
  public EntityID getTarget() {
    return this.result;
  }

  /**
   * 探索対象を計算する 自身に割り当てられたクラスタが9割探索済みであれば，クラスタを拡張 自身のクラスタから行ったことのある建物を取り除く
   * そこからソートして優先度が一番高い建物を探索対象とする
   * @return このメソッド
   */
  @Override
  public Search calc() {
    if (this.needToExpandCluster()) {
      this.expandCluster();
    }

    Set<EntityID> candidates = new HashSet<>(this.cluster);
    candidates.removeAll(this.reached);
    this.result = candidates.stream().max(this.comparator()).orElse(null);
    final EntityID agentID = this.agentInfo.getID();
    return this;
  }

  /**
   * 担当範囲の割り当てをおこない結果をthis.clusterに代入する
   * this.penaltyに自身のクラスタの対角線の長さを代入する
   * またクラスタは建物のEntityIDのみ
   */
  private void initCluster() {
    this.clusterer.calc();

    final EntityID me = this.agentInfo.getID();
    final int index = this.clusterer.getClusterIndex(me);
    final Collection<EntityID> buildings =
        this.gatherBuildings(this.clusterer.getClusterEntityIDs(index));

    this.cluster.addAll(buildings);
    this.penalty = this.computeClusterDiagonal();
  }

  /**
   * 自身の担当範囲を拡張 拡張した担当範囲をthis.clusterへ追加
   */
  private void expandCluster() {
    final EntityID me = this.agentInfo.getID();

    final int n = this.clusterer.getClusterNumber();
    final int index = this.clusterer.getClusterIndex(me);

    final int size = this.cluster.size();
    for (int i = 1; i < n && size == this.cluster.size(); ++i) {
      final Collection<EntityID> buildings =
          this.gatherBuildings(
              this.clusterer.getClusterEntityIDs(index + i * n));
      this.cluster.addAll(buildings);
    }
  }

  /**
   * 自身のクラスタを9割以上探索済みであればTrueを返す
   * @return クラスタを広げる必要であるかないか
   */
  private boolean needToExpandCluster() {
    return this.reached.size() >= this.cluster.size() * 0.9;
  }

  /**
   * paramのcollectionからBuildingを取り出し返す
   * @param collection
   * @return 引数からBuildingだけを取り出したSet
   */
  private Set<EntityID> gatherBuildings(Collection<EntityID> collection) {
    if (collection == null) {
      Set<EntityID> ret = new HashSet<>();
      return ret;
    }

    final Stream<EntityID> ret =
        collection
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Building.class::isInstance)
            .map(StandardEntity::getID);

    return ret.collect(toSet());
  }

  /**
   * this.potentialsに含まれているかつ優先度順に返すComparator
   * 基本は距離が短い順となるが，自身が動けず動けない場合は探索対象としている建物に
   * ペナルティをつける
   * @return comparator
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
   * エージェントが持つ情報の更新をおこなう
   * @param mm MessageManager
   * @return このメソッド
   */
  @Override
  public Search updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    //自身の担当範囲がない場合はクラスタを初期化
    if (this.cluster.isEmpty()) {
      this.initCluster();
    }

    //自身は埋没していないが動けない場合this.delayedに自身の現在の探索対象を追加
    final Human human = (Human) this.agentInfo.me();
    if (this.cannotReach() && human.getBuriedness() == 0) {
      this.delayed.add(this.result);
    }

    //this.potentialsを更新する
    this.reflectVoiceToPotentials();
    //this.potentialsからthis.reachedを引いて，this.potentialsを更新
    this.ignoreReachedBuildings();
    //知覚した建物の倒壊度が0だったときthis.reachedに追加し，this.potentialsを更新
    this.ignoreUntaskableBuildings();
    //見つけた市民をthis.potentialsのvalueから除外する
    this.ignoreDiscoveredCivilians();
    //救助完了した市民をthis.potentialsのvalueから除外する
    this.ignoreRescuedCivilian();

    //Refuge以外の建物を探索し終えていればthis.reachedを全消し
    if (this.needToClearReached()) {
      this.reached.clear();
    }

    return this;
  }

  /**
   * this.potentialsのkeyに音声通信範囲の建物を代入し，
   * そのValueにヘルプをした市民のIDを代入
   */
  private void reflectVoiceToPotentials() {
    final Set<EntityID> candidates = this.gatherBuildingsInVoiceRange();
    final Set<EntityID> civilians = this.gatherHelpedCivilians();

    candidates.forEach(i ->
    {
      this.potentials
          .computeIfAbsent(i, k -> new HashSet<>())
          .addAll(civilians);
    });
  }

  /**
   * 音声通信の聞こえる範囲の建物のEntityIDをSetとして返す
   * @return 音声通信範囲の建物のEntityID
   */
  private Set<EntityID> gatherBuildingsInVoiceRange() {
    final int range = this.scenarioInfo.getRawConfig()
        .getIntValue("comms.channels.0.range");

    final EntityID me = this.agentInfo.getID();
    final Stream<EntityID> ret =
        this.worldInfo.getObjectsInRange(me, range)
            .stream()
            .filter(Building.class::isInstance)
            .map(StandardEntity::getID);

    return ret.collect(toSet());
  }

  /**
   * 市民のヘルプが聞こえた場合その市民のEntityIDを返す
   * @return ヘルプを出している市民のEntityIDの集合
   */
  private Set<EntityID> gatherHelpedCivilians() {
    final Set<EntityID> agents =
        new HashSet<>(this.worldInfo.getEntityIDsOfType(
            FIRE_BRIGADE, FIRE_STATION,
            AMBULANCE_TEAM, AMBULANCE_CENTRE,
            POLICE_FORCE, POLICE_OFFICE));

    final Stream<EntityID> ret =
        this.agentInfo.getHeard()
            .stream()
            .filter(AKSpeak.class::isInstance)
            .map(AKSpeak.class::cast)
            .filter(s -> s.getChannel() == 0)
            .map(AKSpeak::getAgentID)
            .filter(i -> !agents.contains(i));

    return ret.collect(toSet());
  }

  /**
   * 自分のいる位置が建物だった場合this.reachedに追加する
   * this.potentialsから到着したことのあるthis.reachedを引いて更新する
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
   * 建物内をしっかり近くできているか確認する
   * @param building
   * @return 確認できていればTrue
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

      final Point2D intersection =
          GeometryTools2D.getSegmentIntersectionPoint(
              line, edge.getLine());
      if (intersection != null) {
        return true;
      }
    }

    return false;
  }

  /**
   * 建物の情報が更新されたとき，建物の倒壊度が0の場合
   * その建物のEntityIDをthis.reachedに追加する
   * そしてthis.potentialsを更新する
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
   * エージェントの座標をPoint2Dとして返す
   * @return エージェントの座標(Point2D)
   */
  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }

  /**
   * this.potentialsから見つけた市民をvalueから除外する
   */
  private void ignoreDiscoveredCivilians() {
    final Collection<EntityID> changed =
        this.worldInfo.getChanged().getChangedEntities();

    final Set<EntityID> ignored =
        changed
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Civilian.class::isInstance)
            .map(StandardEntity::getID)
            .collect(toSet());

    this.potentials.values().forEach(vs -> vs.removeAll(ignored));
  }

  /**
   * this.potentialsから救助完了した市民を除外する
   */
  private void ignoreRescuedCivilian() {
    //key = building, value = civilian
    final Set<EntityID> ignored =
        this.worldInfo.getChanged().getChangedEntities()
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Civilian.class::isInstance)
            .map(Civilian.class::cast)
            .filter(Civilian::isBuriednessDefined)
            .filter(c -> c.getBuriedness() <= 0)
            .map(Civilian::getID)
            .collect(toSet());

    this.potentials.values().forEach(vs -> vs.removeAll(ignored));
  }

  /**
   * 自身が動けない場合にTrueを返す
   * @return 自身が動けない場合Trueを返す
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
   * 自身のクラスタの対角線の長さを計算し返す
   *
   * @return 自身のクラスタの対角線の長さ
   */
  private double computeClusterDiagonal() {
    final List<Building> buildings =
        this.cluster
            .stream()
            .map(this.worldInfo::getEntity)
            .map(Building.class::cast)
            .collect(toList());

    final int minX =
        buildings.stream().mapToInt(Area::getX).min().orElse(0);
    final int minY =
        buildings.stream().mapToInt(Area::getY).min().orElse(0);
    final int maxX =
        buildings.stream().mapToInt(Area::getX).max().orElse(0);
    final int maxY =
        buildings.stream().mapToInt(Area::getY).max().orElse(0);

    return Math.hypot(maxX - minX, maxY - minY);
  }

  /**
   * 避難所以外の建物を探索し終わった場合this.reachedをリセットする
   * @return リセットOKでTrue
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

    long reachedCount =
        this.reached.stream()
            .filter(r -> !(refuges.contains(r))).count();

    return reachedCount == buildings.size();
  }
}
