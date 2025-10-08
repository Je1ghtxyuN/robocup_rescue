package AIT_2022.module.complex.fb;

import static java.util.Comparator.comparing;
import static rescuecore2.standard.entities.StandardEntityURN.*;

import AIT_2022.module.complex.common.EntityFilter;
import AIT_2022.module.complex.common.LifeXpectancy;
import AIT_2022.module.complex.common.Stucked;
import adf.core.agent.action.Action;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.complex.HumanDetector;
import java.util.*;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

/**
 * FBの行動対象を決定
 */
public class AITFireBrigadeDetector extends HumanDetector {

  /**
   * FBの行動対象となるEntityのEntityID．nullとなる可能性もある
   */
  private EntityID result = null;

  /**
   * 各エージェントの行動範囲を決定するクラスタリングモジュール
   */
  private Clustering clusterer;

  /**
   * 自身の担当範囲のEntityのEntityIDの集合
   */
  private Set<EntityID> cluster = new HashSet<>();

  /**
   * 不明，使われていないランダム関数．
   */
  private final Random random = new Random();

  /***
   * 受信したMessageCivilianを付近のエージェントに伝播するモジュール
   */
  private final CivilianMessages civMessages;

  /**
   * 通信用の定義
   */
  private final boolean VOICE = false;
  private final boolean RADIO = true;
  final private int HELP_BURIED = 5;
  final private int HELP_BLOCKADE = 6;

  /**
   * エージェント自身が救助必要(埋まっている)か否かを表すboolean
   */
  private boolean needRescueForMe = false;
  /**
   * 救出する市民のEntityIDの集合
   */
  private Set<EntityID> rescueCivTask = new HashSet<>();
  /**
   * 救出するレスキューエージェントのEntityIDの集合
   */
  private Set<EntityID> rescueAgentTask = new HashSet<>();
  /**
   * 埋まっていないがダメージを負っている市民のEntityIDの集合
   */
  private Set<EntityID> loadCivTask = new HashSet<>();
  /**
   * 救出を避けたエージェントのEntityIDの集合
   */
  private Set<EntityID> avoidTasks = new HashSet<>();
  /**
   * 救出対象ではないエージェントのEntityIDの集合
   */
  private Set<EntityID> nonTarget = new HashSet<>();
  /**
   * メッセージで送られてきた救出対象のエージェントのEntityIDの集合
   */
  private Set<EntityID> sendTarget = new HashSet<>();
  /**
   * 自身が所属するクラスターの中心地(自身が所属するクラスター内のエージェントの座標の平均値)
   */
  private Pair<Integer, Integer> center = null;
  /**
   * 自身が所属するクラスター内のエージェントのEntityIDの集合
   */
  private Set<EntityID> allocatedEntities = new HashSet<>();

  /**
   * その時の知覚している範囲内のfbの数の配列
   */
  private int[] f = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  /**
   * 現在のカウント数．ステップごとにカウント数が増え，fの配列長-1になったらカウント数が0になる
   */
  final private int FB_COUNTER = f.length;
  /**
   * 救出する際の消防隊の最大人数
   */
  final private int RESCUE_MAX_FB = 3;

  private Clustering failedMove;
  private Clustering stuckedHumans;

  public AITFireBrigadeDetector(
      AgentInfo ai, WorldInfo wi,
      ScenarioInfo si, ModuleManager mm, DevelopData dd) {

    super(ai, wi, si, mm, dd);

    this.clusterer = mm.getModule("AIT.FB.HumanDetector.Clustering");
    this.registerModule(this.clusterer);

    this.civMessages = mm.getModule("AIT.FB.HumanDetector.CivilianMessages");
    this.registerModule(this.civMessages);

    this.failedMove = mm.getModule("AIT.FB.Search.FailedMove");
    this.registerModule(this.failedMove);

    this.stuckedHumans = mm.getModule("AIT.FB.Search.StuckHumans");
    this.registerModule(this.stuckedHumans);
  }

  /**
   * FBの行動対象を取得する
   * @return FBの行動対象であるEntityのEntityID
   */
  @Override
  public EntityID getTarget() {
    return this.result;
  }

  /**
   * 各ステップで実行される，エージェント内部更新のためのメソッド．
   * @param mm メッセージマネージャ
   * @return 自身のインスタンス
   */
  @Override
  public HumanDetector updateInfo(MessageManager mm) {

    super.updateInfo(mm);

    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    this.random.setSeed(
        this.agentInfo.getID().getValue() + this.agentInfo.getTime());

    if (this.cluster.isEmpty()) {
      this.initCluster();
    }

    this.receiveMessage(mm);
    this.sendMessage(mm);

    this.updateTask();
    return this;
  }

  /**
   * 各ステップのエージェントの行動対象を決定するためのメソッド．
   * 市民を含む各エージェントに[座標のEntityID, エージェントの集合]の集合と救助必要（埋まっている）エージェントの集合を作る．
   * 救助必要なレスキューエージェントの集合から対象タスクを選択し，市民より優先して救助．
   * 救助必要なレスキューエージェントがなければ市民を救助．
   * @return 自身のインスタンス
   */
  @Override
  public HumanDetector calc() {
    this.civMessages.calc();

    final EntityID me = this.agentInfo.getID();
    this.needRescueForMe = this.rescueTarget(me);
    // 自身が瓦礫に埋まっているとき
    if (needRescueForMe) {
      return this;
    }

    // 知覚した情報
    Set<EntityID> changes = this.worldInfo.getChanged().getChangedEntities();
    changes.add(me);

    // 対象タスクが救助対象でない or 知覚範囲内に存在しているとき
    if (!rescueTarget(this.result) || changes.contains(this.result)) {
      this.result = null;
    }

    // 救助対象の市民を場所ごとに取得
    Map<EntityID, Set<EntityID>> civPos = new HashMap<>();
    Map<EntityID, Set<EntityID>> ambPos = new HashMap<>();
    Map<EntityID, Set<EntityID>> fbPos = new HashMap<>();
    Set<EntityID> buriedAMB = new HashSet<>();
    Set<EntityID> buriedFB = new HashSet<>();
    Set<EntityID> buriedPF = new HashSet<>();

    for (EntityID e : changes) {
      final StandardEntity se = this.worldInfo.getEntity(e);
      if (!(se instanceof Human)) {
        continue;
      }
      final StandardEntity targetPosition = this.worldInfo.getPosition(e);
      if (targetPosition.getStandardURN().equals(REFUGE)) {
        continue;
      }
      final boolean rescueTarget = this.rescueTarget(e);
      final boolean loadTarget = this.loadTarget(e);
      final StandardEntityURN targetURN = se.getStandardURN();
      final EntityID targetPositionID = targetPosition.getID();
      // 行動可能なATを場所ごとに記録
      if (targetURN.equals(AMBULANCE_TEAM)) {
        if (rescueTarget) {
          buriedAMB.add(e);
        } else {
          Set<EntityID> AMBs = ambPos.get(targetPositionID);
          if (AMBs == null) {
            AMBs = new HashSet<>();
          }
          AMBs.add(e);
          ambPos.put(targetPositionID, AMBs);
        }
      }
      // 行動可能なFBを場所ごとに記録
      else if (targetURN.equals(FIRE_BRIGADE)) {
        if (rescueTarget) {
          buriedFB.add(e);
        } else {
          Set<EntityID> FBs = fbPos.get(targetPositionID);
          if (FBs == null) {
            FBs = new HashSet<>();
          }
          FBs.add(e);
          fbPos.put(targetPositionID, FBs);
        }
      }
      // 行動可能なPFを場所ごとに記録
      else if (targetURN.equals(POLICE_FORCE)) {
        if (rescueTarget) {
          buriedPF.add(e);
        }
      } else if (targetURN.equals(CIVILIAN)) {
        if (rescueTarget) {
          Set<EntityID> CIVs = civPos.get(targetPositionID);
          if (CIVs == null) {
            CIVs = new HashSet<>();
          }
          CIVs.add(e);
          civPos.put(targetPositionID, CIVs);
        } else if (loadTarget) {
          this.loadCivTask.add(e);
        }
      }
    }

    //生存していて埋没しているエージェントが存在する
    this.result = this.getLowerAgentBury(buriedFB);
    if (this.result != null) {
      return this;
    }
    this.result = this.getLowerAgentBury(buriedPF);
    if (this.result != null) {
      return this;
    }
    this.result = this.getLowerAgentBury(buriedAMB);
    if (this.result != null) {
      return this;
    }

    // 知覚範囲内のFBの数を取得，記録
    int n = 0;
    for (Set<EntityID> es : fbPos.values()) {
      n += es.size();
    }
    final int idx = this.agentInfo.getTime() % FB_COUNTER;
    this.f[idx] = n;

    if (civPos.isEmpty()) {
      if (this.result == null) {
        this.result = this.selectReceiveTask();
      }
      return this;
    }

    // 自身の位置ID
    final StandardEntity position = this.worldInfo.getPosition(me);
    final EntityID myPositionID = position.getID();
    Set<EntityID> targets = civPos.get(myPositionID);
    // 自身の位置に救助対象の市民が存在するとき
    if (targets != null) {
      // 自身と同じ位置に存在するFBを取得
      final Set<EntityID> FBs = fbPos.get(position.getID());
      int cnt = 0;
      // 救助対象をID順にソート
      for (EntityID e : this.idSort(targets)) {
        // 自身が救助に必要な人数に含まれるとき
        if (this.joinRescue(FBs, cnt, e)) {
          this.result = e;
          return this;
        } else {
          if (FBs == null) {
            if (this.joinRescue(fbPos.get(myPositionID), n, e)) {
              this.result = e;
              return this;
            }
          } else {
            // 救助対象から除外
            this.nonTarget.add(e);
          }
        }
        cnt++;
      }
      civPos.remove(myPositionID);
    }

    // 自身の位置以外に存在する救助対象の市民から選択
    for (Map.Entry<EntityID, Set<EntityID>> es : civPos.entrySet()) {
      final Set<EntityID> FBs = fbPos.get(es.getKey());
      final int FBnum = (FBs == null) ? 1 : FBs.size() + 1;
      int cnt = 0;
      for (EntityID e : this.idSort(es.getValue())) {
        if (this.avoidTasks.contains(e)) {
          continue;
        }
        if (this.canSave(e, Math.max(FBnum, this.getMaxFBcounter()))) {
          if (this.joinRescue(FBs, cnt)) {
            this.result = e;
            return this;
          } else {
            this.avoidTasks.add(e);
          }
          cnt++;
        }
      }
    }

    // 救助対象がないとき，通信により受け取ったタスクから選択
    if (this.result == null) {
      this.result = this.selectReceiveTask();
    }
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
    this.setEntityInCluster(ids);
  }

  /**
   * エージェントが送信するメッセージを処理します．
   *
   * @param mm メッセージの発信元となるメッセージマネージャ
   */
  private void sendMessage(MessageManager mm) {
    final EntityFilter filter = new EntityFilter(this.worldInfo, this.agentInfo);
    // 知覚範囲内のBLOCKADEを取得
    final Set<Blockade> blockades = filter.getBlockades();
    final Stucked Stucked = new Stucked(blockades, this.agentInfo.getX(), this.agentInfo.getY());
    // 自身の位置ID
    final EntityID positionID = this.agentInfo.getPosition();
    // 自身のStandardEntity
    FireBrigade me = (FireBrigade) this.worldInfo.getEntity(this.agentInfo.getID());
    // 自身がBLOCKADEに埋まっているとき
    if (Stucked.isStucked() || (this.cannotReach() && !this.isMyselfBuried())) {
      mm.addMessage(new MessageFireBrigade(RADIO, me, HELP_BLOCKADE, positionID));
      mm.addMessage(new MessageFireBrigade(VOICE, me, HELP_BLOCKADE, positionID));
      return;
    }
    // 自身が埋没しているとき
    if (this.isMyselfBuried()) {
      mm.addMessage(new MessageFireBrigade(RADIO, me, HELP_BURIED, this.agentInfo.getID()));
      mm.addMessage(new MessageFireBrigade(VOICE, me, HELP_BURIED, this.agentInfo.getID()));
      return;
    }

    // シミュレーションステップが1のとき
    if (this.agentInfo.getTime() == 1) return;
    // 前ステップで実行したコマンドを取得
    final Action action
        = this.agentInfo.getExecutedAction(this.agentInfo.getTime() - 1);

    // 実行したコマンドが"ActionRescue"のとき
    if (action.toString().contains("ActionRescue")) {
      StandardEntity en = this.worldInfo.getEntity(this.result);
      if(!(en instanceof Civilian)) return;
      Civilian civ = (Civilian)en;
      mm.addMessage(new MessageCivilian(VOICE, civ));
      mm.addMessage(new MessageCivilian(RADIO, civ));
    }
  }

  /**
   * 自分が埋まっているか判定
   * @return 埋まっているか否かのboolean
   */
  private boolean isMyselfBuried() {
    Human me = (Human) this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(this.agentInfo.getPositionArea() instanceof Building)) return false;
    if (!me.isBuriednessDefined()) return false;
    return me.getBuriedness() != 0;
  }

  /**
   * 引数で指定したエージェントの座標をPoint2Dで返す
   * @param human エージェント
   * @return エージェントの座標のPoint2D
   */
  public static Point2D getPoint(Human human) {
    final double x = human.getX();
    final double y = human.getY();
    return new Point2D(x, y);
  }

  /**
   * 対象タスクを返す
   * @return 対象タスク
   */
  public EntityID getResult() {
    return this.result;
  }

  /**
   * HashSetを用いて自身が所属するクラスター内のエージェントのEntityIDの集合を作成し，クラスターの中心を設定する関数
   * @param ids 自身が所属するクラスター内のエージェントのEntityIDの集合
   */
  public void setEntityInCluster(Collection<EntityID> ids) {
    this.allocatedEntities = new HashSet<>(ids);
    this.setClusterCenter();
  }

  /**
   * 自身が所属するクラスターのエージェントの座標の平均からクラスターの中心を設定する関数
   */
  private void setClusterCenter() {
    double x = 0.0;
    double y = 0.0;
    for (EntityID e : this.allocatedEntities) {
      final Pair<Integer, Integer> p = this.worldInfo.getLocation(e);
      x += p.first();
      y += p.second();
    }
    final int n = this.allocatedEntities.size();
    this.center = new Pair<>((int) (x / n), (int) (y / n));
  }

  /**
   * 引数で指定したエージェントが生存していて，かつ埋められているか判定する
   * @param e エージェントのEntityID
   * @return エージェントが生存していて埋められているときtrueを返す
   */
  private boolean rescueTarget(EntityID e) {
    if (e == null) {
      return false;
    }
    final Human h = (Human) this.worldInfo.getEntity(e);
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    return (hp > 0 && buried > 0);
  }

  /**
   * 市民が生存しており，埋められていなく，かつダメージがあるか判定する
   * @param e 市民のEntityID
   * @return 市民が生存しており，埋められていなく，ダメージがあるときtrueを返す
   */
  private boolean loadTarget(EntityID e) {
    final Human h = (Human) this.worldInfo.getEntity(e);
    if (!(h instanceof Civilian)) {
      return false;
    }
    final int hp = h.getHP();
    final int buried = h.getBuriedness();
    final int damage = h.getDamage();
    return (hp > 0 && buried == 0 && damage > 0);
  }

  /**
   * EntityIDを昇順にソートします．
   *
   * @param list EntityID型のSet
   * @return 昇順にソートされたSet
   */
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

  /**
   * 救助に必要な人数に自身が含まれるか判断します．
   *
   * @param list エージェントのリスト
   * @param n    選択対象タスクn個目
   * @return 選択可否
   */
  private boolean joinRescue(Set<EntityID> list, int n) {
    if (list == null) {
      return true;
    }
    list.add(this.agentInfo.getID());
    final int max = Math.min(list.size(), this.getMaxRescueNumber()
        + this.getMaxRescueNumber() * n);
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

  /**
   * 救助に必要な人数に自身が含まれるか判断します．
   *
   * @param list エージェントのリスト
   * @param n    選択対象タスクn個目
   * @param id   救助対象ID
   * @return 選択可否
   */
  private boolean joinRescue(Set<EntityID> list, int n, EntityID id) {
    if (list == null) {
      return true;
    }
    list.add(this.agentInfo.getID());
    final int needNum
        = this.getMaxRescueNumber(id) + this.getMaxRescueNumber(id) * n;
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

  /**
   * 救出する際の消防隊の最大人数を返す
   * @return 救出する際の消防隊の最大人数
   */
  private int getMaxRescueNumber() {
    return RESCUE_MAX_FB + (int) (this.agentInfo.getTime() * 0.01);
  }

  /**
   * 引数で指定したエージェントの救出に必要な消防隊の最大人数を返す
   * @param id 救出対象のエージェントのEntityID
   * @return エージェントの救出に必要な消防隊の最大人数
   */
  private int getMaxRescueNumber(EntityID id) {
    return this.getNeedFBnum(id) + (int) (this.agentInfo.getTime() * 0.01);
  }

  /**
   * 生存しているエージェントのEntityIDの集合の中で一番埋没度が低いエージェントのEntityIDを求める関数
   * @param agents エージェントのEntityIDの集合
   * @return 一番埋没度が低いエージェントのEntityID
   */
  private EntityID getLowerAgentBury(Set<EntityID> agents) {
    Human h = agents.stream()
        .map(this.worldInfo::getEntity)
        .filter(e -> e instanceof Human)
        .map(Human.class::cast)
        .filter(e -> e.getHP() > 0)
        .min(comparing(Human::getBuriedness))
        .orElse(null);
    return h == null ? null : h.getID();
  }

  /**
   * エージェントの救出が間に合うかどうか求める
   * @param id 救出対象のエージェントのEntityID
   * @param n 消防隊の数
   * @return 救出可能か否かのboolean
   */
  private boolean canSave(EntityID id, int n) {
    final StandardEntity s = this.worldInfo.getEntity(id);
    if (((Human) s).getBuriedness() == 0 || ((Human) s).getHP() == 0) {
      return false;
    }

    if (((Civilian) s).getBuriedness() > 0 && ((Civilian) s).getHP() > 0) {
      final LifeXpectancy lx = new LifeXpectancy((Human) s);
      final int ttl = lx.getLifeXpectancy();
      return (ttl - this.rescueTime(id, n) > 0);
    } else {
      return true;
    }
  }

  /**
   * 救出に必要なステップ数を返す
   * @param id エージェントのEntityID
   * @param n 消防隊の数
   * @return 救出に必要なステップ数
   */
  private int rescueTime(EntityID id, int n) {
    return ((Human) this.worldInfo.getEntity(id)).getBuriedness() / n;
  }

  /**
   * 引数で指定したエージェントの救出に必要な消防隊の数を求める
   * @param id エージェントのEntityID
   * @return 救出に必要な消防隊の数
   */
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

  /**
   * 過去で一番多い自分の周囲にいた消防隊の数を返す
   * @return 過去で一番多い自分の周囲にいた消防隊の数
   */
  private int getMaxFBcounter() {
    int max = 0;
    for (int i : this.f) {
      if (i > max) {
        max = i;
      }
    }
    return max;
  }

  /**
   * メッセージで受け取った市民が救助必要であればタスクの集合に追加する．救助必要でなければ救助対象外の集合に追加する．
   * @param msg 市民の情報を含むメッセージ
   */
  private void handleMessage(MessageCivilian msg) {
    final EntityID targetID = msg.getAgentID();
    if (this.rescueTarget(targetID)) {
      this.rescueCivTask.add(targetID);
    } else {
      this.nonTarget.add(targetID);
      this.rescueCivTask.remove(targetID);
    }
  }

  /**
   * メッセージで受け取った消防隊が救助必要であればタスクの集合に追加する．救助必要でなければ救助対象外の集合に追加する．
   * @param msg 消防隊の情報を含むメッセージ
   */
  private void handleMessage(MessageFireBrigade msg) {
    final EntityID targetID = msg.getAgentID();

    if(msg.getAction() != HELP_BURIED) return;

    if (this.rescueTarget(targetID)) {
      this.rescueAgentTask.add(targetID);
      this.nonTarget.add(targetID);
    } else {
      this.rescueAgentTask.remove(targetID);
    }
  }

  /**
   * メッセージで受け取った救急隊が救助必要であればタスクの集合に追加する．救助必要でなければ救助対象外の集合に追加する．
   * @param msg 救急隊の情報を含むメッセージ
   */
  private void handleMessage(MessageAmbulanceTeam msg) {
    final EntityID targetID = msg.getAgentID();

    if(msg.getAction() != HELP_BURIED) return;

    if (this.rescueTarget(targetID)) {
      this.rescueAgentTask.add(targetID);
      this.nonTarget.add(targetID);
    } else {
      this.rescueAgentTask.remove(targetID);
    }
  }

  /**
   * メッセージで受け取った土木隊が救助必要であればタスクの集合に追加する．救助必要でなければ救助対象外の集合に追加する．
   * @param msg 土木隊の情報を含むメッセージ
   */
  private void handleMessage(MessagePoliceForce msg) {
    final EntityID targetID = msg.getAgentID();

    if(msg.getAction() != HELP_BURIED) return;

    if (this.rescueTarget(targetID)) {
      this.rescueAgentTask.add(targetID);
      this.nonTarget.add(targetID);
    } else {
      this.rescueAgentTask.remove(targetID);
    }
  }

  /**
   * メッセージを受信し，handleMessage関数を実行する．
   * @param mm メッセージマネージャー
   */
  private void receiveMessage(MessageManager mm) {
    final List<CommunicationMessage> cmsg
        = mm.getReceivedMessageList(MessageCivilian.class);
    for (CommunicationMessage tmp : cmsg) {
      final MessageCivilian message = (MessageCivilian) tmp;
      this.handleMessage(message);
    }
    final List<CommunicationMessage> amsg
        = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    for (CommunicationMessage tmp : amsg) {
      final MessageAmbulanceTeam message = (MessageAmbulanceTeam) tmp;
      this.handleMessage(message);
    }
    final List<CommunicationMessage> fmsg
        = mm.getReceivedMessageList(MessageFireBrigade.class);
    for (CommunicationMessage tmp : fmsg) {
      final MessageFireBrigade message = (MessageFireBrigade) tmp;
      this.handleMessage(message);
    }
    final List<CommunicationMessage> pmsg
        = mm.getReceivedMessageList(MessagePoliceForce.class);
    for (CommunicationMessage tmp : pmsg) {
      final MessagePoliceForce message = (MessagePoliceForce) tmp;
      this.handleMessage(message);
    }
  }

  /**
   * タスクを更新する．救助対象タスクの集合，救助を避けたタスクの集合，メッセージから送られてきたタスクの集合に
   * 救助が必要でないタスクがあれば救助対象外タスクの集合に移動させる．
   */
  private void updateTask() {
    Iterator<EntityID> i = this.rescueCivTask.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!this.rescueTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
    i = this.rescueAgentTask.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!this.rescueTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
    i = this.loadCivTask.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!this.loadTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
    this.rescueCivTask.removeAll(this.avoidTasks);
    this.rescueCivTask.removeAll(this.nonTarget);
    this.rescueAgentTask.removeAll(this.nonTarget);
    i = this.avoidTasks.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!this.rescueTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
    i = this.sendTarget.iterator();
    while (i.hasNext()) {
      final EntityID e = i.next();
      if (!this.rescueTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
    if (this.rescueAgentTask.isEmpty()) {
      this.avoidTasks.clear();
    }
  }

  /**
   * 引数のタスクの集合の中でコストが一番低い(距離+救助優先度+クラスター範囲内外)タスクを返す
   * @param tasks タスクの集合
   * @return コストが一番低いタスクのEntityID
   */
  private EntityID getPriorityTask(Set<EntityID> tasks) {
    EntityID ret = null;
    double priority = Double.MAX_VALUE;
    for (EntityID e : tasks) {
      final Human h = (Human) this.worldInfo.getEntity(e);
      final Pair<Integer, Integer> p = this.worldInfo.getLocation(e);
      final int outOfArea = (this.allocatedEntities.contains(e) ? 0 :
          Math.abs(this.center.first() - p.first())
              + Math.abs(this.center.second() - p.second()));
      final double distance = Math.abs(this.agentInfo.getX() - p.first())
          + Math.abs(this.agentInfo.getY() - p.second());
      final double cost1 = Math.sqrt(distance);
      final LifeXpectancy lx = new LifeXpectancy(h);
      final double cost2 = lx.getLifeXpectancy() - (h.getDamage() * h.getBuriedness());
      final double cost = cost1 + cost2 + outOfArea;
      if (priority > cost) {
        priority = cost;
        ret = e;
      }
    }
    return ret;
  }

  /**
   * 受け取ったタスクの中から一番優先度が高い(コストが低い)タスクのEntityIDを返す．
受け取ったタスクがない場合はnullを返す．
   * @return 通信により受け取ったタスクのEntityIDもしくはnull
   */
  private EntityID selectReceiveTask() {
    if (!this.rescueAgentTask.isEmpty()) {
      return this.getPriorityTask(this.rescueAgentTask);
    }
    if (!this.rescueCivTask.isEmpty()) {
      return this.getPriorityTask(this.rescueCivTask);
    }
    return null;
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
}
