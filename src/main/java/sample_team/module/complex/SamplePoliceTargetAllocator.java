package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.complex.PoliceTargetAllocator;
import adf.core.component.module.algorithm.PathPlanning;

import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {

  private Map<EntityID, EntityID> result;
  private PathPlanning pathPlanning;
  private Set<EntityID> processedBlockades;
  private Map<EntityID, Integer> assignmentAttempts;

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.result = new HashMap<>();
    this.processedBlockades = new HashSet<>();
    this.assignmentAttempts = new HashMap<>();

    // 获取路径规划模块
    this.pathPlanning = moduleManager.getModule(
        "SamplePoliceTargetAllocator.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.pathPlanning);
  }

  @Override
  public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    if (this.getCountResume() >= 2) {
      return this;
    }
    return this;
  }

  @Override
  public PoliceTargetAllocator preparate() {
    super.preparate();
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    return result;
  }

  @Override
  public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    return this;
  }

  @Override
  public PoliceTargetAllocator calc() {
    result.clear();

    // 清理已处理的障碍物记录（如果障碍物已被清除）
    cleanupProcessedBlockades();

    // 1. 优先检查当前位置是否有障碍物需要立即处理
    EntityID immediateTarget = findImmediateBlockade();
    if (immediateTarget != null) {
      assignBlockade(immediateTarget);
      return this;
    }

    // 2. 获取所有需要清理的障碍物
    Collection<StandardEntity> blockades = worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE);
    if (blockades.isEmpty()) {
      return this;
    }

    // 3. 计算优先级并分配任务
    List<BlockadePriority> priorities = calculateBlockadePriorities(blockades);
    priorities.sort((a, b) -> Double.compare(b.priority, a.priority));

    // 分配最高优先级的有效障碍物
    for (BlockadePriority priority : priorities) {
      if (isValidAssignment(priority.blockadeId)) {
        assignBlockade(priority.blockadeId);
        break;
      }
    }

    return this;
  }

  // 查找当前位置的障碍物（最高优先级）
  private EntityID findImmediateBlockade() {
    EntityID myPosition = agentInfo.getPosition();

    // 获取当前位置的所有障碍物
    List<EntityID> blockadesHere = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
      Blockade blockade = (Blockade) entity;
      if (blockade.getPosition().equals(myPosition) &&
          !processedBlockades.contains(blockade.getID())) {
        blockadesHere.add(blockade.getID());
      }
    }

    // 选择最近未处理的障碍物（通常只有一个）
    if (!blockadesHere.isEmpty()) {
      return blockadesHere.get(0);
    }
    return null;
  }

  // 分配障碍物并更新状态
  private void assignBlockade(EntityID blockadeId) {
    result.put(agentInfo.getID(), blockadeId);
    processedBlockades.add(blockadeId);
    assignmentAttempts.put(blockadeId, 1); // 初始化尝试次数
  }

  // 验证任务分配是否有效（防止死循环）
  private boolean isValidAssignment(EntityID blockadeId) {
    // 检查是否已处理
    if (processedBlockades.contains(blockadeId)) {
      return false;
    }

    // 检查尝试次数（防止无限重试）
    int attempts = assignmentAttempts.getOrDefault(blockadeId, 0);
    return attempts < 3; // 最多尝试3次
  }

  // 清理已处理的障碍物记录
  private void cleanupProcessedBlockades() {
    Iterator<EntityID> iterator = processedBlockades.iterator();
    while (iterator.hasNext()) {
      EntityID id = iterator.next();
      // 如果障碍物已被清除或尝试次数超限
      if (worldInfo.getEntity(id) == null ||
          assignmentAttempts.getOrDefault(id, 0) >= 3) {
        iterator.remove();
        assignmentAttempts.remove(id);
      }
    }
  }

  // 计算障碍物优先级
  private List<BlockadePriority> calculateBlockadePriorities(Collection<StandardEntity> blockades) {
    List<BlockadePriority> priorities = new ArrayList<>();
    EntityID myPosition = agentInfo.getPosition();

    for (StandardEntity entity : blockades) {
      if (!(entity instanceof Blockade)) {
        continue;
      }

      Blockade blockade = (Blockade) entity;
      EntityID id = blockade.getID();

      // 跳过无效任务
      if (processedBlockades.contains(id) || !isValidAssignment(id)) {
        continue;
      }

      double priority = calculateBlockadePriority(blockade, myPosition);
      priorities.add(new BlockadePriority(blockade.getID(), priority));
    }

    return priorities;
  }

  // 计算单个障碍物的优先级权重
  private double calculateBlockadePriority(Blockade blockade, EntityID myPosition) {
    double priority = 0.0;

    // 1. 距离因子（距离越近优先级越高）
    EntityID blockadePositionId = blockade.getPosition();
    int distance = worldInfo.getDistance(myPosition, blockadePositionId);
    double distanceFactor = Math.max(1, distance);
    priority += 1000.0 / distanceFactor;

    // 2. 添加当前位置奖励（如果在同一位置）
    if (blockadePositionId.equals(myPosition)) {
      priority += 5000.0;
    }

    // 3. 道路重要性因子
    StandardEntity road = worldInfo.getEntity(blockade.getPosition());
    if (road != null) {
      int connectedImportantBuildings = countConnectedImportantBuildings(road);
      priority += connectedImportantBuildings * 100.0;
    }

    // 4. 阻塞救援目标因子
    int blockedAgents = estimateBlockedAgents(blockade);
    priority += blockedAgents * 200.0;

    return priority;
  }

  // 计算道路连接的重要建筑数量
  private int countConnectedImportantBuildings(StandardEntity road) {
    // 简化实现：实际中需要查询道路连接的重要建筑
    // 这里返回一个模拟值
    return 1;
  }

  // 估计被阻塞的救援人员数量
  private int estimateBlockedAgents(Blockade blockade) {
    // 简化实现：实际中需要分析路径阻塞情况
    // 这里返回一个模拟值
    return 1;
  }

  // 内部类用于存储障碍物优先级
  private class BlockadePriority {
    EntityID blockadeId;
    double priority;

    BlockadePriority(EntityID blockadeId, double priority) {
      this.blockadeId = blockadeId;
      this.priority = priority;
    }
  }
}