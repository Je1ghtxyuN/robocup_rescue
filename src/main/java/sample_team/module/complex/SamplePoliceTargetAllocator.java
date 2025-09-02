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
import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SamplePoliceTargetAllocator extends PoliceTargetAllocator {

  // private Logger logger = DefaultLogger.getLogger(SamplePoliceTargetAllocator.class);
  private Map<EntityID, EntityID> result;
  private PathPlanning pathPlanning;
  private Set<EntityID> processedBlockades;
  private Map<EntityID, Integer> assignmentAttempts;
  private Logger logger;

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.logger = DefaultLogger.getLogger(ai.me()); 
    this.result = new HashMap<>();
    this.processedBlockades = new HashSet<>();
    this.assignmentAttempts = new HashMap<>();

    logger.debug("初始化警察目标分配器 - 代理ID: " + ai.getID());
    logger.debug("当前世界状态: " + wi.getAllEntities().size() + " 个实体");

    // 获取路径规划模块
    this.pathPlanning = moduleManager.getModule(
        "SamplePoliceTargetAllocator.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.pathPlanning);

    if (this.pathPlanning != null) {
      logger.debug("成功加载路径规划模块: " + pathPlanning.getClass().getSimpleName());
    } else {
      logger.error("无法加载路径规划模块!");
    }
  }

  @Override
  public PoliceTargetAllocator resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
    logger.debug("恢复预计算数据 - 计数: " + this.getCountResume());
    if (this.getCountResume() >= 2) {
      return this;
    }
    return this;
  }

  @Override
  public PoliceTargetAllocator preparate() {
    super.preparate();
    logger.debug("准备预计算 - 计数: " + this.getCountPrecompute());
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    return this;
  }

  @Override
  public Map<EntityID, EntityID> getResult() {
    logger.debug("获取分配结果: " + result);
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
    logger.debug("===== 开始计算目标分配 =====");
    logger.debug("代理位置: " + agentInfo.getPosition());
    result.clear();

    // 清理已处理的障碍物记录（如果障碍物已被清除）
    cleanupProcessedBlockades();

    // 1. 优先检查当前位置是否有障碍物需要立即处理
    logger.debug("检查当前位置的障碍物...");
    EntityID immediateTarget = findImmediateBlockade();
    if (immediateTarget != null) {
      logger.debug("发现当前位置障碍物: " + immediateTarget);
      assignBlockade(immediateTarget);
      logger.debug("===== 分配完成 (当前位置障碍) =====");
      return this;
    }

    // 2. 获取所有需要清理的障碍物
    Collection<StandardEntity> blockades = worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE);
    logger.debug("发现 " + blockades.size() + " 个障碍物需要处理");

    if (blockades.isEmpty()) {
      logger.debug("没有障碍物需要处理");
      logger.debug("===== 分配完成 (无任务) =====");
      return this;
    }

    // 3. 计算优先级并分配任务
    logger.debug("计算障碍物优先级...");
    List<BlockadePriority> priorities = calculateBlockadePriorities(blockades);
    priorities.sort((a, b) -> Double.compare(b.priority, a.priority));

    // 记录所有障碍物优先级
    logger.debug("障碍物优先级列表:");
    for (BlockadePriority p : priorities) {
      logger.debug("障碍 " + p.blockadeId + " - 优先级: " + p.priority);
    }

    // 分配最高优先级的有效障碍物
    EntityID assigned = null;
    for (BlockadePriority priority : priorities) {
      if (isValidAssignment(priority.blockadeId)) {
        assigned = priority.blockadeId;
        break;
      }
    }

    if (assigned != null) {
      logger.debug("分配最高优先级障碍物: " + assigned);
      assignBlockade(assigned);
    } else {
      logger.debug("未找到有效的障碍物分配!");
    }

    logger.debug("===== 分配完成 =====");
    return this;
  }

  // 查找当前位置的障碍物（最高优先级）
  private EntityID findImmediateBlockade() {
    EntityID myPosition = agentInfo.getPosition();
    logger.debug("搜索当前位置障碍物 - 位置: " + myPosition);

    // 获取当前位置的所有障碍物
    List<EntityID> blockadesHere = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE)) {
      Blockade blockade = (Blockade) entity;
      if (blockade.getPosition().equals(myPosition) &&
          !processedBlockades.contains(blockade.getID())) {
        blockadesHere.add(blockade.getID());
        logger.debug("发现当前位置障碍物: " + blockade.getID());
      }
    }

    // 选择最近未处理的障碍物（通常只有一个）
    if (!blockadesHere.isEmpty()) {
      logger.debug("找到 " + blockadesHere.size() + " 个当前位置障碍物");
      return blockadesHere.get(0);
    }
    logger.debug("未找到当前位置障碍物");
    return null;
  }

  // 分配障碍物并更新状态
  private void assignBlockade(EntityID blockadeId) {
    logger.debug("分配障碍物 " + blockadeId + " 给代理 " + agentInfo.getID());
    result.put(agentInfo.getID(), blockadeId);
    processedBlockades.add(blockadeId);
    assignmentAttempts.put(blockadeId, 1); // 初始化尝试次数
    logger.debug("已处理障碍物列表更新: " + processedBlockades);
  }

  // 验证任务分配是否有效（防止死循环）
  private boolean isValidAssignment(EntityID blockadeId) {
    logger.debug("验证障碍物 " + blockadeId + " 是否可分配");

    // 检查是否已处理
    if (processedBlockades.contains(blockadeId)) {
      logger.debug("障碍物 " + blockadeId + " 已在处理列表中");
      return false;
    }

    // 检查尝试次数（防止无限重试）
    int attempts = assignmentAttempts.getOrDefault(blockadeId, 0);
    boolean valid = attempts < 3; // 最多尝试3次

    if (!valid) {
      logger.debug("障碍物 " + blockadeId + " 尝试次数已达上限 (" + attempts + ")");
    } else {
      logger.debug("障碍物 " + blockadeId + " 尝试次数: " + attempts);
    }

    return valid;
  }

  // 清理已处理的障碍物记录
  private void cleanupProcessedBlockades() {
    logger.debug("清理已处理障碍物列表 - 当前数量: " + processedBlockades.size());
    int removedCount = 0;

    Iterator<EntityID> iterator = processedBlockades.iterator();
    while (iterator.hasNext()) {
      EntityID id = iterator.next();
      // 如果障碍物已被清除或尝试次数超限
      if (worldInfo.getEntity(id) == null) {
        logger.debug("移除已清除障碍物: " + id);
        iterator.remove();
        assignmentAttempts.remove(id);
        removedCount++;
      } else if (assignmentAttempts.getOrDefault(id, 0) >= 3) {
        logger.debug("移除尝试次数超限障碍物: " + id);
        iterator.remove();
        assignmentAttempts.remove(id);
        removedCount++;
      }
    }

    logger.debug("清理完成 - 移除 " + removedCount + " 个障碍物");
  }

  // 计算障碍物优先级
  private List<BlockadePriority> calculateBlockadePriorities(Collection<StandardEntity> blockades) {
    logger.debug("计算 " + blockades.size() + " 个障碍物的优先级");
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
        logger.debug("跳过无效障碍物: " + id);
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
    EntityID blockadePositionId = blockade.getPosition();

    logger.debug("计算障碍物 " + blockade.getID() + " 优先级 - 位置: " + blockadePositionId);

    // 1. 距离因子（距离越近优先级越高）
    int distance = worldInfo.getDistance(myPosition, blockadePositionId);
    double distanceFactor = Math.max(1, distance);
    double distanceScore = 1000.0 / distanceFactor;
    priority += distanceScore;
    logger.debug("距离得分: " + distanceScore + " (距离: " + distance + ")");

    // 2. 添加当前位置奖励（如果在同一位置）
    if (blockadePositionId.equals(myPosition)) {
      priority += 5000.0;
      logger.debug("当前位置奖励: +5000.0");
    }

    // 3. 道路重要性因子
    StandardEntity road = worldInfo.getEntity(blockade.getPosition());
    if (road != null) {
      int connectedImportantBuildings = countConnectedImportantBuildings(road);
      double importanceScore = connectedImportantBuildings * 100.0;
      priority += importanceScore;
      logger.debug("道路重要性得分: " + importanceScore + " (连接建筑: " + connectedImportantBuildings + ")");
    }

    // 4. 阻塞救援目标因子
    int blockedAgents = estimateBlockedAgents(blockade);
    double blockageScore = blockedAgents * 200.0;
    priority += blockageScore;
    logger.debug("阻塞影响得分: " + blockageScore + " (影响代理: " + blockedAgents + ")");

    logger.debug("障碍物 " + blockade.getID() + " 总优先级: " + priority);
    return priority;
  }

  // 计算道路连接的重要建筑数量
  private int countConnectedImportantBuildings(StandardEntity road) {
    // 简化实现：实际中需要查询道路连接的重要建筑
    // 这里返回一个模拟值
    logger.debug("计算道路 " + road.getID() + " 连接的重要建筑");
    // TODO: 实现实际逻辑
    return 1;
  }

  // 估计被阻塞的救援人员数量
  private int estimateBlockedAgents(Blockade blockade) {
    // 简化实现：实际中需要分析路径阻塞情况
    // 这里返回一个模拟值
    logger.debug("估计障碍物 " + blockade.getID() + " 阻塞的代理数量");
    // TODO: 实现实际逻辑
    return 1;
  }

  // 内部类用于存储障碍物优先级
  private static class BlockadePriority {
    EntityID blockadeId;
    double priority;

    BlockadePriority(EntityID blockadeId, double priority) {
      this.blockadeId = blockadeId;
      this.priority = priority;
    }
  }
}