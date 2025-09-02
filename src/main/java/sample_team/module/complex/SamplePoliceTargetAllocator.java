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

  public SamplePoliceTargetAllocator(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    this.result = new HashMap<>();
    this.processedBlockades = new HashSet<>();

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
  public PoliceTargetAllocator calc() {
    result.clear();

    // 获取所有需要清理的障碍物
    Collection<StandardEntity> blockades = worldInfo.getEntitiesOfType(StandardEntityURN.BLOCKADE);
    if (blockades.isEmpty()) {
      return this;
    }

    // 计算每个障碍物的优先级权重
    List<BlockadePriority> priorities = calculateBlockadePriorities(blockades);

    // 按照优先级排序
    priorities.sort((a, b) -> Double.compare(b.priority, a.priority));

    // 分配任务（简化版：选择最高优先级的障碍物）
    if (!priorities.isEmpty()) {
      EntityID blockadeId = priorities.get(0).blockadeId;
      result.put(agentInfo.getID(), blockadeId);
      processedBlockades.add(blockadeId);
    }

    return this;
  }

  @Override
  public PoliceTargetAllocator updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    if (this.getCountUpdateInfo() >= 2) {
      return this;
    }
    return this;
  }

  /**
   * 计算障碍物优先级
   */
  private List<BlockadePriority> calculateBlockadePriorities(Collection<StandardEntity> blockades) {
    List<BlockadePriority> priorities = new ArrayList<>();
    EntityID myPosition = agentInfo.getPosition();

    for (StandardEntity entity : blockades) {
      if (!(entity instanceof Blockade) || processedBlockades.contains(entity.getID())) {
        continue;
      }

      Blockade blockade = (Blockade) entity;
      double priority = calculateBlockadePriority(blockade, myPosition);
      priorities.add(new BlockadePriority(blockade.getID(), priority));
    }

    return priorities;
  }

  /**
   * 计算单个障碍物的优先级权重
   * 考虑因素：距离、阻塞的道路重要性、阻塞的救援目标数量
   */
  private double calculateBlockadePriority(Blockade blockade, EntityID myPosition) {
    double priority = 0.0;

    // 1. 距离因子（距离越近优先级越高）
    EntityID blockadePositionId = blockade.getPosition();
    int distance = worldInfo.getDistance(myPosition, blockadePositionId);
    double distanceFactor = Math.max(1, distance); // 避免除以零
    priority += 1000.0 / distanceFactor;

    // 2. 道路重要性因子（根据道路连接的重要建筑数量）
    StandardEntity road = worldInfo.getEntity(blockade.getPosition());
    if (road != null) {
      int connectedImportantBuildings = countConnectedImportantBuildings(road);
      priority += connectedImportantBuildings * 100.0;
    }

    // 3. 阻塞救援目标因子
    int blockedAgents = estimateBlockedAgents(blockade);
    priority += blockedAgents * 200.0;

    return priority;
  }

  /**
   * 计算道路连接的重要建筑数量
   */
  private int countConnectedImportantBuildings(StandardEntity road) {
    // 简化实现：实际中需要查询道路连接的重要建筑
    // 这里返回一个模拟值
    return 1;
  }

  /**
   * 估计被阻塞的救援人员数量
   */
  private int estimateBlockedAgents(Blockade blockade) {
    // 简化实现：实际中需要分析路径阻塞情况
    // 这里返回一个模拟值
    return 1;
  }

  /**
   * 内部类用于存储障碍物优先级
   */
  private class BlockadePriority {
    EntityID blockadeId;
    double priority;

    BlockadePriority(EntityID blockadeId, double priority) {
      this.blockadeId = blockadeId;
      this.priority = priority;
    }
  }
}