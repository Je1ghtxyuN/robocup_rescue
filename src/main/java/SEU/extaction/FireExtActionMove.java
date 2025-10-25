package SEU.extaction;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

// 导入StuckHumans类
import SEU.module.algorithm.StuckHumans;

public class FireExtActionMove extends ExtAction {

  private PathPlanning pathPlanning;
  private StuckHumans stuckHumans; // 新增：卡住检测模块

  private int thresholdRest;
  private int kernelTime;

  private EntityID target;
  private boolean isStuck; // 新增：标记自身是否卡住
  private int stuckCheckCounter; // 新增：卡住检测计数器，避免频繁检测
  private EntityID lastCalledPolice; // 新增：记录上次呼叫的警察ID，避免重复呼叫
  private int lastStuckTime; // 新增：记录上次卡住的时间步

  public FireExtActionMove(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo, ModuleManager moduleManager, DevelopData developData) {
    super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
    this.target = null;
    this.isStuck = false;
    this.stuckCheckCounter = 0;
    this.lastCalledPolice = null;
    this.lastStuckTime = -1;
    this.thresholdRest = developData
        .getInteger("adf.impl.extaction.DefaultExtActionMove.rest", 100);

    switch (scenarioInfo.getMode()) {
      case PRECOMPUTATION_PHASE:
      case PRECOMPUTED:
      case NON_PRECOMPUTE:
        this.pathPlanning = moduleManager.getModule(
            "FireExtActionMove.PathPlanning",
            "adf.impl.module.algorithm.DijkstraPathPlanning");
        // 新增：初始化StuckHumans模块
        this.stuckHumans = new StuckHumans(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        break;
    }
  }

  @Override
  public ExtAction precompute(PrecomputeData precomputeData) {
    super.precompute(precomputeData);
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    this.pathPlanning.precompute(precomputeData);
    this.stuckHumans.precompute(precomputeData); // 新增：预计算卡住检测
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }

  @Override
  public ExtAction resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    this.pathPlanning.resume(precomputeData);
    this.stuckHumans.resume(precomputeData); // 新增：恢复卡住检测
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }

  @Override
  public ExtAction preparate() {
    super.preparate();
    if (this.getCountPreparate() >= 2) {
      return this;
    }
    this.pathPlanning.preparate();
    this.stuckHumans.preparate(); // 新增：准备卡住检测
    try {
      this.kernelTime = this.scenarioInfo.getKernelTimesteps();
    } catch (NoSuchConfigOptionException e) {
      this.kernelTime = -1;
    }
    return this;
  }

  @Override
  public ExtAction updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    this.pathPlanning.updateInfo(messageManager);
    this.stuckHumans.updateInfo(messageManager); // 新增：更新卡住检测信息
    
    // 新增：每5个时间步检测一次卡住状态，避免频繁检测
    if (this.stuckCheckCounter++ % 5 == 0) {
      this.stuckHumans.calc(); // 计算卡住状态
      boolean wasStuck = this.isStuck;
      this.isStuck = this.stuckHumans.getClusterIndex(this.agentInfo.getID()) == 0;
      
      // 如果从非卡住状态变为卡住状态，发送警察清理命令
      if (this.isStuck && !wasStuck) {
        EntityID policeID = findAvailablePolice();
        if (policeID != null) {
          // 只发送给一个特定的警察，而不是广播
          messageManager.addMessage(new CommandPolice(true, StandardMessagePriority.HIGH, 
              policeID, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
          messageManager.addMessage(new CommandPolice(false, StandardMessagePriority.HIGH, 
              policeID, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
          
          this.lastCalledPolice = policeID;
          this.lastStuckTime = this.agentInfo.getTime();
        }
      }
      
      // 如果仍然卡住且超过一定时间没有收到救援，尝试呼叫其他警察
      if (this.isStuck && wasStuck) {
        int currentTime = this.agentInfo.getTime();
        if (currentTime - this.lastStuckTime > 20) { // 20个时间步后尝试重新呼叫
          EntityID newPolice = findAvailablePolice(this.lastCalledPolice);
          if (newPolice != null) {
            messageManager.addMessage(new CommandPolice(true, StandardMessagePriority.HIGH, 
                newPolice, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
            messageManager.addMessage(new CommandPolice(false, StandardMessagePriority.HIGH, 
                newPolice, this.agentInfo.getPosition(), CommandPolice.ACTION_CLEAR));
            
            this.lastCalledPolice = newPolice;
            this.lastStuckTime = currentTime;
          }
        }
      }
      
      // 如果从卡住状态恢复正常，重置状态
      if (!this.isStuck && wasStuck) {
        this.lastCalledPolice = null;
        this.lastStuckTime = -1;
      }
    }
    
    return this;
  }

  @Override
  public ExtAction setTarget(EntityID target) {
    this.target = null;
    StandardEntity entity = this.worldInfo.getEntity(target);
    if (entity != null) {
      if (entity.getStandardURN().equals(StandardEntityURN.BLOCKADE)) {
        entity = this.worldInfo.getEntity(((Blockade) entity).getPosition());
      } else if (entity instanceof Human) {
        entity = this.worldInfo.getPosition((Human) entity);
      }
      if (entity != null && entity instanceof Area) {
        this.target = entity.getID();
      }
    }
    return this;
  }

  @Override
  public ExtAction calc() {
    this.result = null;
    Human agent = (Human) this.agentInfo.me();

    // 新增：如果自身卡住，优先发送休息动作并等待救援
    if (this.isStuck) {
      this.result = new ActionRest();
      return this;
    }

    if (this.needRest(agent)) {
      this.result = this.calcRest(agent, this.pathPlanning, this.target);
      if (this.result != null) {
        return this;
      }
    }
    if (this.target == null) {
      return this;
    }
    this.pathPlanning.setFrom(agent.getPosition());
    this.pathPlanning.setDestination(this.target);
    List<EntityID> path = this.pathPlanning.calc().getResult();
    if (path != null && path.size() > 0) {
      this.result = new ActionMove(path);
    }
    return this;
  }

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

  private Action calcRest(Human human, PathPlanning pathPlanning,
      EntityID target) {
    EntityID position = human.getPosition();
    Collection<EntityID> refuges = this.worldInfo
        .getEntityIDsOfType(StandardEntityURN.REFUGE);
    int currentSize = refuges.size();
    if (refuges.contains(position)) {
      return new ActionRest();
    }
    List<EntityID> firstResult = null;
    while (refuges.size() > 0) {
      pathPlanning.setFrom(position);
      pathPlanning.setDestination(refuges);
      List<EntityID> path = pathPlanning.calc().getResult();
      if (path != null && path.size() > 0) {
        if (firstResult == null) {
          firstResult = new ArrayList<>(path);
          if (target == null) {
            break;
          }
        }
        EntityID refugeID = path.get(path.size() - 1);
        pathPlanning.setFrom(refugeID);
        pathPlanning.setDestination(target);
        List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
        if (fromRefugeToTarget != null && fromRefugeToTarget.size() > 0) {
          return new ActionMove(path);
        }
        refuges.remove(refugeID);
        // remove failed
        if (currentSize == refuges.size()) {
          break;
        }
        currentSize = refuges.size();
      } else {
        break;
      }
    }
    return firstResult != null ? new ActionMove(firstResult) : null;
  }
  
  /**
   * 查找可用的警察（优先选择距离较近的）
   * @return 警察的EntityID，如果没有可用警察则返回null
   */
  private EntityID findAvailablePolice() {
    return findAvailablePolice(null);
  }
  
  /**
   * 查找可用的警察，排除指定的警察
   * @param excludePolice 要排除的警察ID
   * @return 警察的EntityID，如果没有可用警察则返回null
   */
  private EntityID findAvailablePolice(EntityID excludePolice) {
    Collection<EntityID> policeIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.POLICE_FORCE);
    EntityID selectedPolice = null;
    double minDistance = Double.MAX_VALUE;
    
    for (EntityID policeID : policeIDs) {
      // 排除指定的警察
      if (excludePolice != null && policeID.equals(excludePolice)) {
        continue;
      }
      
      PoliceForce police = (PoliceForce) this.worldInfo.getEntity(policeID);
      if (police != null && police.isPositionDefined()) {
        // 简单距离计算（可以优化为使用路径规划计算实际距离）
        double distance = calculateDistance(this.agentInfo.getPosition(), police.getPosition());
        if (distance < minDistance) {
          minDistance = distance;
          selectedPolice = policeID;
        }
      }
    }
    
    return selectedPolice;
  }
  
  /**
   * 计算两个位置之间的直线距离（简化版）
   */
  private double calculateDistance(EntityID pos1, EntityID pos2) {
    StandardEntity entity1 = this.worldInfo.getEntity(pos1);
    StandardEntity entity2 = this.worldInfo.getEntity(pos2);
    
    if (entity1 instanceof Area && entity2 instanceof Area) {
      Area area1 = (Area) entity1;
      Area area2 = (Area) entity2;
      if (area1.isXDefined() && area1.isYDefined() && 
          area2.isXDefined() && area2.isYDefined()) {
        double dx = area1.getX() - area2.getX();
        double dy = area1.getY() - area2.getY();
        return Math.sqrt(dx * dx + dy * dy);
      }
    }
    
    return Double.MAX_VALUE;
  }
  
  // 新增：获取当前卡住状态（用于调试或其他模块）
  public boolean isStuck() {
    return this.isStuck;
  }
}