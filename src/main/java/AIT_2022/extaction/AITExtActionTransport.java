package AIT_2022.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import com.google.common.collect.Lists;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Collectors;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class AITExtActionTransport extends ExtAction {

  /**
   * 移動経路を決定するパスプランニングモジュール
   */
  private PathPlanning pathPlanning;

  /**
   * ダメージの許容値．DevelopDataのActionTransport.restで設定でき，デフォルトは100
   */
  private int thresholdRest;

  /**
   * シミュレーションの実行時間．needRest計算のため
   */
  private int kernelTime;

  /**
   * メッセージマネージャ
   */
  private MessageManager messageManager;

  /**
   * 対象のEntityID(Refuge,Civなど)
   */
  private EntityID target;

  /**
   * RefugeのEntityIDと再度行き先候補となるまでのステップ数
   */
  private Map<EntityID, Integer> notGoRefuges = new HashMap<>();

  /**
   * 最適な避難所のメッセージを受け取り，市民をLoadしているとき最適な避難所のEntityIDが入る
   * 最適な避難所のメッセージを受け取っていないまたは，市民をLoadしていないときはNull
   */
  private EntityID optimalRefugeID = null;

  private final int TIME_TO_IGNORE_REFUGE = 100;
  private final double AGENT_CAN_MOVE = 7000.0;
  private final int OPT_REFUGE = 7;

  public AITExtActionTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
      ModuleManager moduleManager, DevelopData developData) {
    super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    this.target = null;
    this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);

    this.pathPlanning = moduleManager.getModule("AIT.AT.ExtActionTransport.PathPlanning");
  }

  /**
   * 事前計算を行う
   * 
   * @param precomputeData
   * @return precomputeメソッド
   */
  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    this.pathPlanning.precompute(precomputeData);
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }

  /**
   * 事前計算したデータを復帰する
   * 
   * @param precomputeData
   * @return resumeメソッド
   */
  public ExtAction resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.pathPlanning.resume(precomputeData);
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }

  /**
   * 事前計算なしの場合の初期化
   * 
   * @return preparateメソッド
   */
  public ExtAction preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.pathPlanning.preparate();
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }

  /**
   * エージェントの内部情報更新のためのメソッド.メッセージの送受信と，PathplanningとRefugeの更新をする．
   * 
   * @param messageManager mm メッセージマネージャ
   * @return updateInfoメソッド
   */
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.messageManager = messageManager;
    this.pathPlanning.updateInfo(messageManager);

    // worldInfoから避難所の情報を取得し変数を更新
    this.updateRefugeStatus();

    // AmbulanceCentreから最適な避難所を受け取る
    this.receiveMessage(messageManager);
    return this;
  }

  /**
   * targetがHumanかAreaのとき，EntityIDをセットする
   * 
   * @param target 対象のEntityID
   * @return setTargetメソッド
   */
  @Override
  public ExtAction setTarget(EntityID target) {
    this.target = null;
    if (target != null) {
      StandardEntity entity = this.worldInfo.getEntity(target);
      if (entity instanceof Human || entity instanceof Area) {
        this.target = target;
        return this;
      }
    }
    return this;
  }

  /**
   * Actionを選択する．
   * 人が乗っていればcalcUnload，Restが必要ならconvertArea，
   * calcRefugeActionでActionがnullならcalcRescue
   * 
   * @return ActionTransPortのcalcメソッド
   */
  @Override
  public ExtAction calc() {
    this.result = null;
    AmbulanceTeam agent = (AmbulanceTeam) this.agentInfo.me();
    Human transportHuman = this.agentInfo.someoneOnBoard();

    if (transportHuman != null) {
      this.result = this.calcUnload(agent, this.pathPlanning, transportHuman, this.target);
      if (this.result != null) {
        return this;
      }
    }
    if (this.needRest(agent)) {
      EntityID areaID = this.convertArea(this.target);
      ArrayList<EntityID> targets = new ArrayList<>();
      if (areaID != null) {
        targets.add(areaID);
      }
      this.result = this.calcRefugeAction(agent, this.pathPlanning, targets, false);
      if (this.result != null) {
        return this;
      }
    }
    if (this.target != null) {
      this.result = this.calcRescue(agent, this.pathPlanning, this.target);
    }
    return this;
  }

  // @ DEBUG
  @Override
  public Action getAction() {
    // System.out.println("🚑 ActionTransport AGENT(" + this.agentInfo.getID() + ")
    // " + this.result);
    return this.result;
  }

  /**
   * Refugeを知覚したとき，空きベッドが無いRefugeをnotGoRefuge変数に記録
   * ベッドを使用している市民の情報から，この避難所に来ないステップ数を算出してnotGoRefugeを更新
   * ステップが進むたびnotGoRefugeのvalueを引いていき，0になったらnotGoRefugeからそのKeyを削除する
   */
  private void updateRefugeStatus() {

    // OccupiedBedsがBedCapacityよりも大きいrefugeを格納
    Set<Refuge> refuges = this.worldInfo.getChanged().getChangedEntities()
        .stream()
        .map(this.worldInfo::getEntity)
        .filter(Refuge.class::isInstance)
        .map(Refuge.class::cast)
        .filter(Refuge::isOccupiedBedsDefined)
        .filter(Refuge::isBedCapacityDefined)
        .filter(r -> r.getOccupiedBeds() >= r.getBedCapacity())
        .collect(Collectors.toSet());

    if (!refuges.isEmpty()) {
      for (Refuge ref : refuges) {
        // damageとhpが0より大きく，位置がrefugeと同じ市民を格納
        Set<Civilian> civilians = this.worldInfo.getChanged().getChangedEntities()
            .stream()
            .map(this.worldInfo::getEntity)
            .filter(Civilian.class::isInstance)
            .map(Civilian.class::cast)
            .filter(Civilian::isDamageDefined)
            .filter(Civilian::isHPDefined)
            .filter(c -> 0 < c.getDamage())
            .filter(c -> 0 < c.getHP())
            .filter(c -> c.getPosition().equals(ref.getID()))
            .collect(Collectors.toSet());
        // System.out.println(this.agentInfo.getID() + " : " + civilians);

        if (civilians.size() <= 0 && !this.notGoRefuges.containsKey(ref.getID())) {
          this.notGoRefuges.put(ref.getID(), 100);
          continue;
        } else if (civilians.size() > 0) {
          ArrayList<Integer> civDamages = new ArrayList<>();
          for (Civilian civ : civilians) {
            civDamages.add(civ.getDamage());
          }
          Collections.sort(civDamages);
          int timeReCandidate = 0;
          int numWait = ref.getBedCapacity() + ref.getWaitingListSize();
          // System.out.println(civDamages.size() + " " + numWait);
          if (civDamages.size() < numWait) {
            numWait = civDamages.size();
          }
          for (int i = 0; i < numWait; i++) {
            timeReCandidate += civDamages.get(i);
          }
          if (this.notGoRefuges.get(ref.getID()) == null) {
            this.notGoRefuges.put(ref.getID(), timeReCandidate);
          }
          if (timeReCandidate < this.notGoRefuges.get(ref.getID()) || this.notGoRefuges.get(ref.getID()) == -1) {
            this.notGoRefuges.put(ref.getID(), timeReCandidate);
          }
        }
      }
    }

    Map<EntityID, Integer> ngr = this.notGoRefuges;
    Set<EntityID> ids = new HashSet<>();
    for (Map.Entry<EntityID, Integer> entry : ngr.entrySet()) {
      this.notGoRefuges.put(entry.getKey(), entry.getValue() - 1);
      // System.out.println("AgentID : " + this.agentInfo.getID() + " Time : " +
      // this.agentInfo.getTime());
      // System.out.println("notGoRefuge:" + this.notGoRefuges);
      if (entry.getValue() - 1 <= 0)
        ids.add(entry.getKey());
    }
    if (this.agentInfo.someoneOnBoard() == null) {
      for (EntityID id : ids)
        this.notGoRefuges.remove(id);
    }
  }

  /**
   * Actionを発行する．
   * targetがCIVで搬送可能ならLoad，または経路探索できる場合Move
   * targetがBlockadeならそのpositionをターゲットとする．
   * Areaがターゲットの場合，Move
   * 
   * @param agent        自分(AT)
   * @param pathPlanning 経路探索モジュール
   * @param targetID     対象のID
   * @return Action
   */
  private Action calcRescue(AmbulanceTeam agent, PathPlanning pathPlanning, EntityID targetID) {
    StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
    if (targetEntity == null) {
      return null;
    }
    EntityID agentPosition = agent.getPosition();
    if (targetEntity instanceof Human) {
      Human human = (Human) targetEntity;
      if (!human.isPositionDefined()) {
        return null;
      }
      if (human.isHPDefined() && human.getHP() == 0) {
        return null;
      }
      EntityID targetPosition = human.getPosition();
      if (agentPosition.getValue() == targetPosition.getValue()) {
        if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
          return null;
          // for Amb rescue command
          // return new ActionRescue(human);
        } else if (human.getStandardURN() == CIVILIAN) {
          // ロードしたときCentreへどこの避難所へ行くかメッセージを送信
          this.sendMessage(this.messageManager, this.getAvailableRefuge());
          return new ActionLoad(human.getID());
        }
      } else {
        List<EntityID> path = pathPlanning.getResult(agentPosition, targetPosition);
        if (path != null && path.size() > 0) {
          return new ActionMove(path);
        }
      }
      return null;
    }
    if (targetEntity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) targetEntity;
      if (blockade.isPositionDefined()) {
        targetEntity = this.worldInfo.getEntity(blockade.getPosition());
      }
    }
    if (targetEntity instanceof Area) {
      List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
      if (path != null && path.size() > 0) {
        return new ActionMove(path);
      }
    }
    return null;
  }

  /**
   * Actionを発行する．
   * 救助対象を乗せている場合，HPがない/REFUGEに到着した場合は降ろす(Unload)，または経路探索し，移動(Move)
   * Areaがターゲットの場合，MoveまたはUnload
   * Humanがターゲットの場合，calcRefugeActionまたはMove
   * 
   * @param agent          自分(AT)
   * @param pathPlanning   経路探索モジュール
   * @param transportHuman 救助対象
   * @param targetID       救助対象などのID
   * @return Action
   */
  private Action calcUnload(AmbulanceTeam agent, PathPlanning pathPlanning, Human transportHuman,
      EntityID targetID) {
    if (transportHuman == null) {
      return null;
    }
    if (transportHuman.isHPDefined() && transportHuman.getHP() == 0) {
      return new ActionUnload();
    }
    EntityID agentPosition = agent.getPosition();
    if (targetID == null || transportHuman.getID().getValue() == targetID.getValue()) {
      StandardEntity position = this.worldInfo.getEntity(agentPosition);
      if (position != null && position.getStandardURN() == REFUGE) {
        return new ActionUnload();
      } else {
        pathPlanning.setFrom(agentPosition);
        pathPlanning.setDestination(this.getAvailableRefuge());
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && path.size() > 0) {
          return new ActionMove(path);
        }
      }
    }
    if (targetID == null) {
      return null;
    }
    StandardEntity targetEntity = this.worldInfo.getEntity(targetID);
    if (targetEntity != null && targetEntity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) targetEntity;
      if (blockade.isPositionDefined()) {
        targetEntity = this.worldInfo.getEntity(blockade.getPosition());
      }
    }
    if (targetEntity instanceof Area) {
      if (agentPosition.getValue() == targetID.getValue()) {
        return new ActionUnload();
      } else {
        pathPlanning.setFrom(agentPosition);
        pathPlanning.setDestination(this.getAvailableRefuge());
        List<EntityID> path = pathPlanning.calc().getResult();
        if (path != null && path.size() > 0) {
          return new ActionMove(path);
        }
      }
    } else if (targetEntity instanceof Human) {
      Human human = (Human) targetEntity;
      if (human.isPositionDefined()) {
        return calcRefugeAction(agent, pathPlanning, Lists.newArrayList(human.getPosition()), true);
      }
      pathPlanning.setFrom(agentPosition);
      pathPlanning.setDestination(this.getAvailableRefuge());
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        return new ActionMove(path);
      }
    }
    return null;
  }

  /**
   * Refugeを探す．空きベッド数，ユークリッド距離を考慮．
   * 
   * @return RefugeのEntityID
   */
  private Collection<EntityID> getAvailableRefuge() {
    if (this.optimalRefugeID != null)
      return new ArrayList<>(List.of(this.optimalRefugeID));
    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
    refuges.removeAll(this.notGoRefuges.keySet());
    if (refuges.isEmpty()) {
      refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
    }

    Map<EntityID, Double> cost = new HashMap<>();
    int sumBedCapacity = 0;
    for (EntityID r : refuges) {
      sumBedCapacity += ((Refuge) this.worldInfo.getEntity(r)).getBedCapacity();
    }
    for (EntityID r : refuges) {
      double refugeUtility = 0.0;
      int bedCapacity = ((Refuge) this.worldInfo.getEntity(r)).getBedCapacity();
      refugeUtility = (1 / (this.worldInfo.getDistance(this.agentInfo.getID(), r) / AGENT_CAN_MOVE))
          + (((double) bedCapacity / (double) sumBedCapacity) / (this.worldInfo.getDistance(
              this.agentInfo.getID(), r)));
      cost.put(r, refugeUtility);
    }
    Double maxCost = Double.MIN_VALUE;
    EntityID reID = null;
    for (Map.Entry<EntityID, Double> entry : cost.entrySet()) {
      if (maxCost < entry.getValue()) {
        maxCost = entry.getValue();
        reID = entry.getKey();
      }
    }
    if (reID != null) {
      return new ArrayList<>(List.of(reID));
    } else {
      return this.worldInfo.getEntityIDsOfType(REFUGE);
    }
  }

  /**
   * ATにRestが必要か．
   * hpまたはdamageが0の場合false
   * ダメージがあってもシミュレーションが終わるまで動ける場合はfalse
   * 
   * @param agent 自分(AT)
   * @return ATにRestさせたほうが良い場合，True
   */
  private boolean needRest(Human agent) {
    int hp = agent.getHP();
    int damage = agent.getDamage();
    if (hp == 0 || damage == 0) {
      return false;
    }
    int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
    if (this.kernelTime == -1) {
      try {
        this.kernelTime = this.scenarioInfo.getKernelTimesteps();
      } catch (NoSuchConfigOptionException e) {
        this.kernelTime = -1;
      }
    }
    return damage >= this.thresholdRest
        || (activeTime + this.agentInfo.getTime()) < this.kernelTime;
  }

  /**
   * targetのpositionを取得する．(needRestな場合にRefugeに向かうため)
   * 
   * @param targetID 比較対象のEntityID
   * @return 対象の場所を表すEntityID
   */
  private EntityID convertArea(EntityID targetID) {
    StandardEntity entity = this.worldInfo.getEntity(targetID);
    if (entity == null) {
      return null;
    }
    if (entity instanceof Human) {
      Human human = (Human) entity;
      if (human.isPositionDefined()) {
        EntityID position = human.getPosition();
        if (this.worldInfo.getEntity(position) instanceof Area) {
          return position;
        }
      }
    } else if (entity instanceof Area) {
      return targetID;
    } else if (entity.getStandardURN() == BLOCKADE) {
      Blockade blockade = (Blockade) entity;
      if (blockade.isPositionDefined()) {
        return blockade.getPosition();
      }
    }
    return null;
  }

  /**
   * Refugeにいる時のActionを発行する．
   * 到着した時，isUnloadがTrueならUnload，FalseならRest
   * 
   * @param human        自分(AT)
   * @param pathPlanning 経路探索モジュール
   * @param targets      対象(Civなど)
   * @param isUnload     救助対象を降ろすか
   * @return Action
   */
  private Action calcRefugeAction(Human human, PathPlanning pathPlanning,
      Collection<EntityID> targets, boolean isUnload) {
    EntityID position = human.getPosition();
    Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
    int size = refuges.size();
    if (refuges.contains(position)) {
      return isUnload ? new ActionUnload() : new ActionRest();
    }
    List<EntityID> firstResult = null;
    while (refuges.size() > 0) {
      pathPlanning.setFrom(position);
      pathPlanning.setDestination(refuges);
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        if (firstResult == null) {
          firstResult = new ArrayList<>(path);
          if (targets == null || targets.isEmpty()) {
            break;
          }
        }
        EntityID refugeID = path.get(path.size() - 1);
        pathPlanning.setFrom(refugeID);
        pathPlanning.setDestination(targets);
        List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
        if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
          return new ActionMove(path);
        }
        refuges.remove(refugeID);
        // remove failed
        if (size == refuges.size()) {
          break;
        }
        size = refuges.size();
      } else {
        break;
      }
    }
    return firstResult != null ? new ActionMove(firstResult) : null;
  }

  /**
   * AmbulanceCentreからのメッセージを受け取り，optimalRefugeIDを更新
   * 
   * @param mm MessageManager
   */
  private void receiveMessage(MessageManager mm) {
    if (this.agentInfo.someoneOnBoard() == null)
      this.optimalRefugeID = null;
    final List<CommunicationMessage> msgAt = mm.getReceivedMessageList(MessageAmbulanceTeam.class);
    for (CommunicationMessage message : msgAt) {
      MessageAmbulanceTeam messageAmbulanceTeam = (MessageAmbulanceTeam) message;
      if (this.worldInfo.getEntity(messageAmbulanceTeam.getSenderID()) instanceof AmbulanceCentre) {
        if (messageAmbulanceTeam.getAgentID().equals(this.agentInfo.getID())) {
          if (messageAmbulanceTeam.getAction() == OPT_REFUGE) {
            if (this.worldInfo.getEntityIDsOfType(REFUGE).contains(messageAmbulanceTeam.getTargetID())) {
              this.optimalRefugeID = messageAmbulanceTeam.getTargetID();
              // System.out.println("[🚑" + this.agentInfo.getID() + " receive📨]" +
              // this.optimalRefugeID);
            }
          }
        }
      }
    }
  }

  private void sendMessage(MessageManager mm, Collection<EntityID> refugeID) {
    if (this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_CENTRE).size() > 0) {
      EntityID reID = null;
      for (EntityID id : refugeID)
        reID = id;
      mm.addMessage(new MessageAmbulanceTeam(
          true, (AmbulanceTeam) this.worldInfo.getEntity(this.agentInfo.getID()), OPT_REFUGE, reID));
      // System.out.println("[🚑" + this.agentInfo.getID() + " Send📨]" + reID);
    }
  }
}
