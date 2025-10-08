package AIT_2022.module.complex.at;

import java.util.*;
import java.util.stream.Collectors;

import AIT_2022.module.complex.common.EntityFilter;
import AIT_2022.module.complex.common.Stucked;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.component.module.complex.HumanDetector;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.communication.MessageManager;
import rescuecore2.misc.Pair;
import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class AITAmbulanceTeamDetector extends HumanDetector {

  /**
   * 移動経路を決定するパスプランニングモジュール
   */
  private PathPlanning pathPlanner;
  /**
   * 各エージェントの担当範囲を決定するクラスタリングモジュール
   */
  private Clustering clusterer;
  /**
   * クラスタリングモジュール（未使用）
   */
  private Clustering stuckedHumans;

  /**
   * 視覚から各建物にいる市民を認識して，その市民がRescueタスクなのか，Loadタスクなのかを判定するモジュール
   */
  private CivilianMessages civMessages;
  /**
   * 計算結果．救助対象のEntityID
   */
  private EntityID result = null;
  /**
   * 自分のクラスターの範囲内にあるEntityIDの集合
   */
  private Set<EntityID> cluster = new HashSet<>();

  private Set<EntityID> loadCivTask = new HashSet<>();

  private Set<EntityID> nonTarget = new HashSet<>();

  /**
   * ActionLoadに失敗したCivilianのEntityIDの集合
   */
  private Set<EntityID> failedTask = new HashSet<>();

  // private static final double AGENT_CAN_MOVE = 7000.0;

  /**
   * 通信用の定義
   */
  private final boolean VOICE = false;
  private final boolean RADIO = true;
//  private boolean followCommand = false;

  final private int HELP_BURIED = 5;
  final private int HELP_BLOCKADE = 6;

  public AITAmbulanceTeamDetector(
          AgentInfo ai, WorldInfo wi, ScenarioInfo si,
          ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.pathPlanner = mm.getModule("AIT.AT.HumanDetector.PathPlanning");
    this.registerModule(this.pathPlanner);

    this.clusterer = mm.getModule("AIT.AT.HumanDetector.Clustering");
    this.registerModule(this.clusterer);

    this.stuckedHumans = mm.getModule("AIT.AT.HumanDetector.StuckHumans");
    this.registerModule(this.stuckedHumans);

    this.civMessages = mm.getModule("AIT.AT.HumanDetector.CivilianMessages");
    this.registerModule(this.civMessages);
  }

  /**
   * 救助対象のEntityIDを返す．
   * @return 救助対象のEntityID
   */
  @Override
  public EntityID getTarget() {
    return this.result;
  }

  /**
   * エージェントの内部情報更新のためのメソッド.メッセージの送受信，クラスタの初期化をおこなう． 
   * 用于更新代理内部信息的方法。执行消息的发送和接收，以及类簇的初始化
   * @param mm 　メッセージマネージャ 消息管理器
   * @return HumanDetectorメソッド
   */
  @Override
  public HumanDetector updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }

    if (this.cluster.isEmpty()) {
      this.initCluster();
    }

    this.receiveMessage(mm);
    this.sendMessage(mm);

    this.updateTask();
    return this;
  }

  /**
   * 搬送対象の決定をおこなう．执行决定搬运对象的操作。
   * 市民を乗せている時はその市民のEntityIDを返す．または，受信したコマンドにしたがって行動する．そうでなければ，救助可能な市民を救助する．
   * 如果载有市民，则返回该市民的实体ID。或者，根据接收到的命令行动。否则，救助可以救助的市民。
   * @return calcメソッド
   */
  @Override
  public HumanDetector calc() {
    this.civMessages.calc();

    final Human onboard = this.agentInfo.someoneOnBoard();
    if (onboard != null) {
      this.result = onboard.getID();
      return this;
    }

    final Set<EntityID> change
            = this.worldInfo.getChanged().getChangedEntities();

    this.result = null;

    double min = Double.MAX_VALUE;
    EntityID taskID = null;
    for (EntityID e : this.loadCivTask) {//如果这个市民已经在避难所，不需要被搬运，因此循环继续到下一个市民
      if (this.worldInfo.getPosition(e).getStandardURN().equals(REFUGE)) {
        continue;
      }

      final Pair<Integer, Integer> p = this.worldInfo.getLocation(e);
      final double distance = Math.abs(this.agentInfo.getX() - p.first())
              + Math.abs(this.agentInfo.getY() - p.second());
      if (min > distance) {
        min = distance;
        taskID = e;//这个市民是目前找到的最近的
      }
    }

    if (taskID != null) {
      this.result = taskID;
      return this;
    }


    // 知覚範囲内から搬送可能な市民を取得 从感知范围内获取可以搬运的市民
    // 以下の条件に合致する市民の  获取符合以下条件的市民
    // - 埋没度が0  埋没度为0
    // - ダメージが0より大きい  损伤度大于0
    // - HPが0より大きい  生命值大于0
    for (EntityID e : change) {
      StandardEntity se = this.worldInfo.getEntity(e);
      if (!(se instanceof Civilian)) {
        continue;
      }
      Civilian c = (Civilian) se;
      EntityID targetID = c.getID();
      if (this.worldInfo.getPosition(c).getStandardURN().equals(REFUGE)) {
        continue;
      }
      if (!(this.loadTarget(targetID))) {
        continue;
      }
      this.result = targetID;
      break;
    }

    return this;
  }

  /**
   * メッセージを送信する．  发送消息
   * 自分が瓦礫に埋まっている時，近くのPFに対してACTION_CLEARを有線・無線で要請する．
   * 当自己被瓦砾埋住时，通过有线和无线方式向附近的PF（警察力量）请求ACTION_CLEAR（清理行动）。
   * 自分が埋没している時，近くのFBに対してACTION_CLEARを有線・無線で要請する．
   * 当自己被埋没时，通过有线和无线方式向附近的FB（消防队）请求ACTION_CLEAR（清理行动）。
   * @param mm メッセージマネージャ  消息管理器
   */
  private void sendMessage(MessageManager mm) {
    final EntityFilter filter = new EntityFilter(this.worldInfo, this.agentInfo);
    // 知覚範囲内のBLOCKADEをすべて取得 获取感知范围内所有的BLOCKADE（障碍物）实体
    final Set<Blockade> blockades = filter.getBlockades();
    final Stucked Stucked = new Stucked(blockades, this.agentInfo.getX(), this.agentInfo.getY());//判断代理是否被障碍物困住
    // 自身の位置ID 获取代理的位置ID
    final EntityID positionID = this.agentInfo.getPosition();
    // 自身のStandardEntity  获取代理的实体对象
    final AmbulanceTeam me = (AmbulanceTeam) this.worldInfo.getEntity(this.agentInfo.getID());
    // 自身がBLOCKADEに埋まっているとき  如果代理被障碍物困住
    if (Stucked.isStucked()) {
      //通过无线（RADIO）发送一个MessageAmbulanceTeam消息，请求清理障碍物。
      mm.addMessage(new MessageAmbulanceTeam(RADIO, me, HELP_BLOCKADE, positionID));
      //通过有线（VOICE）发送一个MessageAmbulanceTeam消息，请求清理障碍物
      mm.addMessage(new MessageAmbulanceTeam(VOICE, me, HELP_BLOCKADE, positionID));
      return;
    }
    // 自身が埋没しているとき 如果代理被埋没
    if (this.isMyselfBuried()) {
      mm.addMessage(new MessageAmbulanceTeam(RADIO, me, HELP_BURIED, this.agentInfo.getID()));
      mm.addMessage(new MessageAmbulanceTeam(VOICE, me, HELP_BURIED, this.agentInfo.getID()));
      return;
    }
  }

  /**
   * 自分が埋没しているか．  检查是否被埋没
   * @return 埋没している場合，True
   */
  private boolean isMyselfBuried() {
    Human me = (Human) this.worldInfo.getEntity(this.agentInfo.getID());
    if (!(this.agentInfo.getPositionArea() instanceof Building)) return false;
    if (!me.isBuriednessDefined()) return false;
    return me.getBuriedness() != 0;
  }

  /**
   * クラスターを計算し，自分の領域のEntityIDsをidsに格納する． 计算类簇，并将所在领域的EntityIDs存储到ids中
   */
  private void initCluster() {
    this.clusterer.calc(); //调用clusterer对象的calc方法来计算类簇

    final EntityID me = this.agentInfo.getID();
    final int index = this.clusterer.getClusterIndex(me);//获取当前代理所在类簇的索引
    final Collection<EntityID> ids =
            this.clusterer.getClusterEntityIDs(index);//获取当前类簇中所有实体的ID
    this.cluster.addAll(ids);
  }

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
   * 救急隊に対するメッセージのみを抽出し取得します． 仅提取并获取针对急救队的消息。
   *
   * @param mm メッセージの取得元となるメッセージマネージャ  用于获取消息的消息管理器
   */
  private void receiveMessage(MessageManager mm) {
    final List<CommunicationMessage> cmdAmb =
            mm.getReceivedMessageList(MessageCivilian.class);
    for (CommunicationMessage tmp : cmdAmb) {
      final MessageCivilian message = (MessageCivilian) tmp;
      this.handleMessage(message);
    }
  }

  //处理从平民接收到的消息
  private void handleMessage(MessageCivilian msg) {
    final EntityID targetID = msg.getAgentID();

    if (this.loadTarget(targetID)) {//检查这个平民是否符合搬运条件
      this.loadCivTask.add(targetID);
    } else {
      this.nonTarget.add(targetID);
      this.loadCivTask.remove(targetID);
    }
  }

  //更新急救队需要处理的平民任务
  private void updateTask(){
    //取所有发生变化的平民实体的ID
    final Set<EntityID> changedCivilianEntityIDs = this.worldInfo.getChanged().getChangedEntities().stream()
            .map(this.worldInfo::getEntity)
            .filter(Civilian.class::isInstance)
            .map(AbstractEntity::getID)
            .collect(Collectors.toSet());

    //因为某些原因未能被成功搬运的平民
    this.failedTask.addAll(this.loadCivTask.stream()
            .map(this.worldInfo::getEntity)
            .map(Civilian.class::cast)
            .filter(c -> this.agentInfo.getPosition().equals(c.getPosition()) && !changedCivilianEntityIDs.contains(c.getID()))
            .map(Civilian::getID)
            .collect(Collectors.toSet()));

    this.loadCivTask.removeAll(this.failedTask);//从待搬运平民集合loadCivTask中移除所有failedTask中的ID
    this.nonTarget.removeAll(this.failedTask);//从未搬运目标集合nonTarget中移除所有failedTask中的ID
    //获取所有避难所类型的实体ID
    Refuge anyRefuge = this.worldInfo.getEntityIDsOfType(REFUGE).stream()
            .findAny()
            .map(this.worldInfo::getEntity)
            .map(Refuge.class::cast)
            .get();
    //将failedTask中的每个平民实体设置为在找到的避难所位置
    this.failedTask.stream()
            .map(this.worldInfo::getEntity)
            .map(Civilian.class::cast)
            .forEach(c -> {
              c.setPosition(anyRefuge.getID());
              c.setX(anyRefuge.getX());
              c.setY(anyRefuge.getY());
            });
    Iterator<EntityID> i = this.loadCivTask.iterator(); //迭代器
    while (i.hasNext()) {//从loadCivTask（待搬运平民任务集合）中移除不再符合搬运条件的平民ID
      final EntityID e = i.next();
      if (!this.loadTarget(e)) {
        this.nonTarget.add(e);
        i.remove();
      }
    }
    i = this.nonTarget.iterator();
    while (i.hasNext()) {//从nonTarget（非目标集合）中找出符合搬运条件的平民ID，并将其移动到loadCivTask（待搬运平民任务集合）中
      final EntityID e = i.next();
      if (this.loadTarget(e)) {
        this.loadCivTask.add(e);
        i.remove();
      }
    }
  }
}
