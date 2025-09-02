package sample_team.module.complex;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;

public class SampleSearch extends Search {

  private PathPlanning pathPlanning;
  private EntityID result;

  public SampleSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);

    // 根据智能体类型初始化路径规划
    StandardEntityURN agentURN = ai.me().getStandardURN();
    if (agentURN == StandardEntityURN.POLICE_FORCE) {
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Police",
          "sample_team.module.algorithm.AStarPathPlanning");
    } else {
      // 其他类型智能体的备用方案
      this.pathPlanning = moduleManager.getModule(
          "SampleSearch.PathPlanning.Police",
          "adf.impl.module.algorithm.DijkstraPathPlanning");
    }
  }

  @Override
  public Search updateInfo(MessageManager messageManager) {
    super.updateInfo(messageManager);
    return this;
  }

  @Override
  public Search calc() {
    result = null;

    // 修复：使用正确的getBuriedHumans方法
    Collection<Human> buriedHumans = new ArrayList<>();
    for (StandardEntity entity : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
      Human human = (Human) entity;
      if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
        buriedHumans.add(human);
      }
    }

    Map<EntityID, Integer> buildingBuriedCount = new HashMap<>();

    for (Human human : buriedHumans) {
      EntityID position = human.getPosition();
      if (position != null) {
        buildingBuriedCount.put(position, buildingBuriedCount.getOrDefault(position, 0) + 1);
      }
    }

    if (!buildingBuriedCount.isEmpty()) {
      // 找到有最多被困人员的建筑物
      EntityID targetBuilding = Collections.max(
          buildingBuriedCount.entrySet(),
          Map.Entry.comparingByValue()).getKey();

      // 计算路径
      this.pathPlanning.setFrom(agentInfo.getPosition());
      this.pathPlanning.setDestination(Collections.singleton(targetBuilding));
      this.pathPlanning.calc(); // 修复：直接调用calc()而不需要返回Search对象
      List<EntityID> path = this.pathPlanning.getResult();

      if (path != null && !path.isEmpty()) {
        // 选择路径中的下一个节点
        this.result = path.get(0);
        return this;
      }
    }

    // 修复：不能调用super.calc()，因为它是抽象的
    // 改为返回默认行为
    this.pathPlanning.setFrom(agentInfo.getPosition());

    // 获取所有建筑物作为备用目标
    Collection<StandardEntity> buildings = worldInfo.getEntitiesOfType(StandardEntityURN.BUILDING);
    if (!buildings.isEmpty()) {
      List<EntityID> buildingIDs = new ArrayList<>();
      for (StandardEntity building : buildings) {
        buildingIDs.add(building.getID());
      }

      this.pathPlanning.setDestination(buildingIDs);
      this.pathPlanning.calc();
      List<EntityID> path = this.pathPlanning.getResult();

      if (path != null && !path.isEmpty()) {
        this.result = path.get(0);
      }
    }

    return this;
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }
}