package AIT_2022.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionRescue;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;

import java.util.ArrayList;
import java.util.List;

import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;

public class AITExtActionFireRescue extends ExtAction {

  /**
   * 移動経路を決定するパスプランニングモジュール
   */
  private PathPlanning pathPlanning;

  /**
   * 対象エージェントが休む必要があるのかを判断する閾値を表すint 自分が受けているダメージが閾値以上であれば休む
   * 値はコンストラクタで，config/develop.jsonのActionFireRescue.restから取得する
   */
  private int thresholdRest;
  /**
   * シナリオのステップ数を保持するintだが，使えない
   * サーバ側のマップファイルにある，config/kernel-inline.cfgのkernel.timestepsを取ろうとしているが，エージェントからは値を取れない仕様
   */
  private int kernelTime;
  /**
   * 行動対象を表すEntityID いない場合はnull
   */
  private EntityID target;

  /**
   * コンストラクタ
   * パラメータとパスプランニングモジュールの取得をする
   */
  public AITExtActionFireRescue(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
      ModuleManager moduleManager, DevelopData developData) {
    super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    this.target = null;
    this.thresholdRest = developData.getInteger("ActionFireRescue.rest", 100);

    this.pathPlanning = moduleManager.getModule("AIT.FB.ExtActionFireRescue.PathPlanning");
  }

  /**
   * 事前計算の処理をするメソッド
   * パスプランニングモジュールの事前計算の実行と，kernelTimeの取得をする
   * 
   * @return ExtActionのprecomputeメソッド
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
   * 事前計算ありモードの初期化処理をするメソッド
   * パスプランニングモジュールの事前計算の実行と，kernelTimeの取得をする
   * 
   * @return ExtActionのresumeメソッド
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
   * 事前計算なしモードの初期化処理をするメソッド
   * パスプランニングモジュールの事前計算の実行と，kernelTimeの取得をする
   * 
   * @return ExtActionのpreparateメソッド
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
   * 各ステップで実行される，エージェントの内部情報更新のためのメソッド
   * 
   * @param messageManager メッセージマネージャ
   * @return ExtActionのupdateInfoメソッド
   */
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.pathPlanning.updateInfo(messageManager);
    return this;
  }

  /**
   * this.targetを，引数のEntityIDに設定する
   * HumanかAreaのインスタンスを想定
   * AITCommandExecutorFireから呼び出される
   * 
   * @param target アクション対象を表すEntityID
   * @return ExtActionのsetTargetメソッド
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
   * 各ステップでエージェントがおこなう行動を決定して，this.resultに書き込むメソッド
   * 消防隊エージェント自身に休む必要があるはその場に留まる
   * 休む必要が無く，アクション対象がいる場合は，対象に対応するActionを取得してthis.resultに書き込む
   * 
   * @return ExtActionのcalcメソッド
   */
  @Override
  public ExtAction calc() {
    this.result = null;
    FireBrigade agent = (FireBrigade) this.agentInfo.me();

    if (this.needRest(agent)) {
      EntityID areaID = this.convertArea(this.target);
      ArrayList<EntityID> targets = new ArrayList<>();
      if (areaID != null) {
        targets.add(areaID);
      }
    }
    if (this.target != null) {
      this.result = this.calcRescue(agent, this.pathPlanning, this.target);
    }
    return this;
  }

  /**
   * アクション対象に対応するActionを返すメソッド
   * 対象がHumanのインスタンスで，消防隊と同じ場所にいる場合はActionRescue，違う場所でかつ到達可能な場合はActionMoveを返す
   * 対象がBlockadeのインスタンスかつ到達可能な場合はActionMoveを返す
   * その他の場合はnullを返す
   * 
   * @param agent 消防隊エージェント自身を表すFireBrigade
   * @param pathPlanning パスプランニングモジュール
   * @param targetID アクション対象を表すEntityID
   * @return アクション対象に対応するAction
   */
  private Action calcRescue(FireBrigade agent, PathPlanning pathPlanning, EntityID targetID) {
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
          return new ActionRescue(human);
        }
      } else {
        List<EntityID> path = pathPlanning.getResult(agentPosition,
            targetPosition);
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
      List<EntityID> path = pathPlanning.getResult(agentPosition,
          targetEntity.getID());
      if (path != null && path.size() > 0) {
        return new ActionMove(path);
      }
    }
    return null;
  }

  /**
   * 消防隊エージェント自身が休む必要があるのかを判断するメソッド
   * 対象が受けているダメージが閾値以上の場合に休む
   * 
   * @param agent 消防隊エージェント自身を表すHuman
   * @return 対象が休む必要があるのかを表すboolean
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
   * EntityIDを，EntityIDが示すエリアか，EntityIDの示すHumanがいるエリアのEntityIDに変換するメソッド
   * 対象としてHuman，Area，Blockadeのどれかを想定
   * Areaの場合はそのEntityIDを返す
   * HumanかBlockadeの場合は，対象がいるAreaのEntityIDを返す
   * 
   * @param targetID 対象を表すEntityID
   * @return 対象のエリアを表すEntityID
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
}