package AIT_2022.module.complex.at;

import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.component.module.complex.Search;
import adf.core.component.module.algorithm.Clustering;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.misc.geometry.*;

import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
import static java.util.Comparator.*;

// @ DEBUG {{
// private VDClient vdclient=VDClient.getInstance();
// }}

public class AITAmbulanceTeamSearch extends Search {

  private EntityID result;

  /**
   * 自身の担当範囲のEntityのEntityIDの集合
   * 自身负责范围内的实体的EntityID集合
   */
  private Set<EntityID> cluster = new HashSet<>();
  /**
   * key = 声が届く範囲内にある建物 value = 声をあげている市民のEntityIDの集合
   * key = 声音可到达范围内的建筑物，value = 发出声音的市民的EntityID集合
   */
  private Map<EntityID, Set<EntityID>> potentials = new HashMap<>();
  /**
   * 探索を終えた建物のEntityIDの集合
   * 已探索完毕的建筑物的EntityID集合
   */
  private Set<EntityID> reached = new HashSet<>();
  /**
   * 優先順位が低いEntityのEntityIDの集合
   * 优先级较低的实体的EntityID集合
   */
  private Set<EntityID> delayed = new HashSet<>();
  /**
   * 建物があるエリアの対角線の大きさ
   */
  private double penalty = 0.0;
  /**
   * Fire Brigadeがいる建物のEntityIDの集合
   */
  private Set<EntityID> highPriorityPosition = new HashSet<>();
  /**
   * 埋没していると判定された市民（key = 場所 value = 無視する時間）
   */
  private Map<EntityID, Integer> buriedCivilians = new HashMap<>();
  /**
   * 各エージェントの担当範囲を決定するクラスタリングモジュール
   */
  private Clustering clusterer;
  /**
   * 各エージェントが動くことができるかを判定するクラスタリングモジュール
   */
  private Clustering failedMove;
  /**
   * Blockadeに詰まっているHumanを発見するクラスタリングモジュール
   */
  private Clustering stuckedHumans;

  // @ DEBUG {{{
  // private VDClient vdclient = VDClient.getInstance();
  // }}}

  public AITAmbulanceTeamSearch(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.clusterer = mm.getModule("AIT.AT.Search.Clustering");
    this.registerModule(this.clusterer);

    this.failedMove = mm.getModule("AIT.AT.Search.FailedMove");
    this.registerModule(this.failedMove);

    this.stuckedHumans = mm.getModule("AIT.AT.Search.StuckHumans");
    this.registerModule(this.stuckedHumans);

    // @ DEBUG {{{
    // this.vdclient.init("localhost", 1099);
    // }}}
  }
  /**
   * エージェントの目的地を取得するメソッド
   * 
   * @return エージェントの目的地であるEntityのEntityID
   */
  @Override
  public EntityID getTarget() {
    return this.result;
  }
  /**
   * 各ステップでエージェントが行動対象を決定するためのメソッド  各步骤中，代理决定行动目标的方法
   * 
   * @return Searchのcalcメソッド
   */
  @Override
  public Search calc() {
    if (this.needToExpandCluster()) {
      this.expandCluster();
    }
    if (this.needToClearReachd()) {
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
   * 担当範囲初期設定をクラスタリングモジュールを用いておこなうメソッド
   * 使用聚类模块进行责任区域的初始设置的方法
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
   * 設定された担当範囲内からタスクの候補が無くなった場合に， 別のエージェントの担当範囲を取り込むメソッド
   * 当在设定的责任范围内没有任务候选时，吸收其他代理的责任范围的方法
   */
  private void expandCluster() {
    final EntityID me = this.agentInfo.getID();

    final int n = this.clusterer.getClusterNumber();//获取聚类模块中的聚类数量
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
   * 範囲内の建物をすべて探索したかどうかを判定するメソッド（Refugeは除く）
   * 判断是否已经探索了范围内所有建筑物的方法（不包括避难所）
   * @return 全て探索していた場合はtrue，探索していない場合はfalseを返す  如果已经全部探索，则返回true；如果还有未探索的，则返回false。
   */
  private boolean needToClearReachd() {
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
   * 担当のクラスターを変更するかを判定するメソッド 判断是否需要改变负责的聚类的方法
   * 
   * 通过比较已处理实体的数量和责任聚类中总实体数量的90%来判断是否需要扩展责任聚类
   */
  private boolean needToExpandCluster() {
    return this.reached.size() >= this.cluster.size() * 0.9;
  }
  /**
   * 担当クラスター内の建物のEntityIDを取得するメソッド
   * 获取担当聚类内建筑物的EntityID的方法
   * @param collection クラスター内のEntityIDの集合  聚类内的EntityID集合
   * @return クラスター内の建物のEntityIDの集合  聚类内建筑物的EntityID集合
   */
  private Set<EntityID> gatherBuildings(Collection<EntityID> collection) {
    final Stream<EntityID> ret =
        collection
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Building.class::isInstance)
            .map(StandardEntity::getID);

    return ret.collect(toSet());
  }
  /**
   * 埋没している市民のなかで，消防隊がいる建物の中にいないかつ無視する時間が0であり，
   * 在被埋没的市民中，选择一个不在消防队所在的建筑内，并且忽视时间为0的市民
   * エージェントから最も近い市民を返すメソッド
   * 返回距离代理最近的市民的方法
   * 
   * @return　埋没している市民のEntityID 被埋没市民的EntityID
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
   * potentialsの要素をサイズ順に並び替えし，  将potentials的元素按大小排序
   * エージェントの場所とpotentialsの場所の距離を降順に並べる替えるメソッド  并根据代理的位置和potentials的位置的距离进行降序排序的方法
   * この際，delayedに含まれているものは建物の対角線の大きさを値に加算する．  在此过程中，如果包含在delayed中，则将建筑物的对角线大小加到值上
   * 
   * @return 並び替えられたpotentialsのcomparator  排序后的potentials的comparator
   */
  private Comparator<EntityID> comparator() {
    final EntityID me = this.agentInfo.getID();
    final Set<EntityID> empty = Collections.emptySet();

    final Comparator<EntityID> comparator1 = comparing(
        i -> this.potentials.getOrDefault(i, empty).size());//首先按集合大小排序
    final Comparator<EntityID> comparator2 = comparing(
        i -> this.worldInfo.getDistance(i, me)//然后按与代理位置的距离降序排序
            + (this.delayed.contains(i) ? this.penalty : 0.0));//如果元素在delayed集合中，则在距离上加上惩罚值

    return comparator1.thenComparing(comparator2.reversed());
  }
  /**
   * 各ステップで実行される，エージェントの内部情報更新のためのメソッド  在每个步骤中执行的，用于更新代理内部信息的方法
   * 
   * @param mm メッセージマネージャ
   * @retuen SearchのupdateInfo
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
   * Fire Brigadeがいる建物のEntityIDを行動対象候補としてhighPriorityPositionに登録する
   * 将有消防队（Fire Brigade）所在的建筑物的实体ID注册为行动目标候选，存入高优先级位置（highPriorityPosition）
   */
  private void updateHighPriorityPosition() {
    this.highPriorityPosition.clear();
    this.highPriorityPosition =
        this.worldInfo.getEntityIDsOfType(FIRE_BRIGADE)
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
   * 埋没している市民を無視する時間を更新するメソッド  更新被埋没市民的忽视时间的方法
   */
  private void updateBuriedCivilian() {
    for (EntityID buildingID : this.buriedCivilians.keySet()) {
      int time = Math.max(this.buriedCivilians.get(buildingID) - 1, 0);//对于每个建筑物ID，将其对应的忽视时间减1（如果大于0）
      this.buriedCivilians.put(buildingID, time);
    }

    Set<Civilian> inSightBuriedCivilian = this.worldInfo.getChanged().getChangedEntities()//筛选出被埋没的平民实体
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
      //如果平民的生命值（HP）已定义且为0或以下，则从buriedCivilians映射中移除该平民
      if (civilian.isHPDefined() && civilian.getHP() <= 0) {
        this.buriedCivilians.remove(civilian.getPosition());
      }
    }

    EntityID nowPosition = this.agentInfo.getPosition();
    if (this.buriedCivilians.containsKey(nowPosition)//如果代理当前位置包含在buriedCivilians映射中，
        && this.buriedCivilians.get(nowPosition) == 0) {//且对应的忽视时间为0
      this.buriedCivilians.remove(nowPosition);
    }
  }
  /**
   * 声が届く範囲内にある建物をkeyとして，声をあげている市民のEntityIDをpotentialsに登録する
   * 以声音能够到达的范围内的建筑物为键（key），将发出声音的市民的实体ID注册到潜在目标（potentials）中
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
   * 声が届く範囲にある建物のEntityIDを取得するメソッド  获取声音范围内的建筑物EntityID的方法
   * @return 建物のEntityIDの集合
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
   * 声をあげている市民のEntityIDの集合を取得するメソッド
   * 获取发出声音的市民的EntityID集合的方法
   * 
   * @return 声をあげている市民のEntityIDの集合
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
   * 探索した建物のEntityIDをreachedに登録するメソッド
   * また，potentialsからreachedに含まれる要素を削除
   * 将已探索建筑物的EntityID注册到reached集合中，并从potentials集合中移除已在reached中的元素的方法
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
   * 建物を知覚しているか判定するメソッド 判断是否感知到建筑物的方法
   * 
   * @param building 対象とする建物
   * @return 知覚していればtrue，知覚していなければfalse
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
   * 倒壊していない建物のEntityIDをreachedに登録するメソッド
   * また，potentialsからreachedに含まれる要素を削除
   * 注册未倒塌建筑物的EntityID到reached集合中，并从potentials中删除已在reached中的元素的方法
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
   * 燃えている建物のEntityIDをreachedに登録するメソッド
   * また，potentialsからreachedに含まれる要素を削除
   * 将燃烧中的建筑物的EntityID注册到reached集合中，并从potentials集合中删除已包含在reached中的元素
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
   * 自分自身の座標を取得するメソッド  获取自身坐标的方法
   * 
   * @return 自分自身の座標のPoint2D
   */
  private Point2D getPoint() {
    final double x = this.agentInfo.getX();
    final double y = this.agentInfo.getY();
    return new Point2D(x, y);
  }
  /**
   * 声をあげている市民を知覚した時，potentialsから削除するメソッド
   * 当感知到发出声音的市民时，从potentials中删除的方法
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
   * failedMoveとstuckedHumanの結果でエージェントが動くことができるかを判定するメソッド
   * 根据failedMove和stuckedHuman的结果判断代理是否能够移动的方法
   * (土木隊を除く)
   * 
   * @return failedMoveまたはstuckedHumanがtrueならtrueを返す
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

    this.failedMove.calc();//计算失败的移动
    this.stuckedHumans.calc();//计算被卡住的人类

    final boolean failed = this.failedMove.getClusterIndex(me) >= 0;//检查failedMove中是否有当前代理的聚类索引
    final boolean stucked = this.stuckedHumans.getClusterIndex(me) >= 0;
    return stucked || failed;
  }
  /**
   * 建物の対角線の長さを取得するメソッド  获取建筑物对角线长度的方法
   * 
   * @return 建物の対角線の長さ
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
   * 救助すべき市民かを判定するメソッド  判断是否应该救助市民的方法
   * (市民が建物の中におり，市民のhp，ダメージ，埋没度が０でない場合が救助すべき市民である)
   * （如果市民在建筑物内，且市民的生命值（HP）、损伤度、埋没度不为0，则应救助该市民）
   *
   * @param id 対象のEntityID
   * @return 救助する必要があるはtrue，救助する必要がないはfalseを返す
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
   * Humanかどうかを判定するメソッド
   * 
   * @param id 対象のEntityID
   * @return Humanならtrue，Humanじゃなければfalseを返す
   */
  private boolean isHuman(EntityID id) {
    if (this.worldInfo.getEntity(id) instanceof Human) {
      return true;
    }
    return false;
  }
  /**
   * MessageCivilianのメッセージをhandleMessageに渡すメソッド
   * 将MessageCivilian的消息传递给handleMessage的方法
   * 
   * @param mm メッセージマネージャ
   */
  private void receiveMessage(MessageManager mm) {
    mm.getReceivedMessageList(MessageCivilian.class)
        .stream()
        .filter(MessageCivilian.class::isInstance)
        .map(MessageCivilian.class::cast)
        .forEach(this::handleMessage);
  }
  /**
   * 埋没している市民のEntityIDをメッセージから受け取り，
   * 自分のクラスター内であればburiedCiviliansに登録するメソッド
   * 从消息中接收被埋没市民的EntityID，如果在自己的聚类内，则注册到buriedCivilians的方法
   * 
   * @param msg メッセージマネージャ
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
