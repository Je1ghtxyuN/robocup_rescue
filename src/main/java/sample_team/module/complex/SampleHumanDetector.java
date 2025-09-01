package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import adf.core.debug.DefaultLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.apache.log4j.Logger;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

public class SampleHumanDetector extends HumanDetector {
  private PathPlanning pathPlanning; // 新增路线规划

  private long lastClusterUpdate = -1; // 周期性集群更新

  private Clustering clustering;

  private EntityID result;

  private Logger logger;

  public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
      DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
    this.pathPlanning = moduleManager.getModule(
        "SampleHumanDetector.PathPlanning",
        "adf.impl.module.algorithm.DijkstraPathPlanning");
    registerModule(this.pathPlanning);
  }

  // 添加周期性集群更新
  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time:" + agentInfo.getTime());
    super.updateInfo(messageManager);

    // 每100步更新一次集群
    if (agentInfo.getTime() - lastClusterUpdate > 100) {
      clustering.calc();
      lastClusterUpdate = agentInfo.getTime();
    }
    return this;
  }

  @Override
  public HumanDetector calc() {
    Human transportHuman = this.agentInfo.someoneOnBoard();
    if (transportHuman != null) {
      logger.debug("someoneOnBoard:" + transportHuman);
      this.result = transportHuman.getID();
      return this;
    }

    if (this.result != null) {
      Human target = (Human) this.worldInfo.getEntity(this.result);
      // 修改为使用新的 isValidTarget 方法
      if (!isValidTarget(target)) {
        logger.debug("Invalid target:" + target + " ==>reset target");
        this.result = null;
      }
    }

    if (this.result == null) {
      this.result = calcTarget();
    }
    return this;
  }

  // 统一的目标有效性检查
  private boolean isValidTarget(Human target) {
    if (target == null)
      return false;

    // 检查是否为救援人员（警察或消防员）
    if (target.getStandardURN() == POLICE_FORCE ||
        target.getStandardURN() == FIRE_BRIGADE) {
      return isAgentInDanger(target);
    }
    // 检查是否为平民
    else if (target.getStandardURN() == CIVILIAN) {
      return isValidCivilian(target) && target.getBuriedness() > 0;
    }

    // 其他类型的人类无效
    return false;
  }

  // 修改优先目标算法
  private EntityID calcTarget() {
    // 获取所有人类实体（包括CIVILIAN, POLICE_FORCE, FIRE_BRIGADE）
    List<Human> rescueTargets = filterRescueTargets(
        this.worldInfo.getEntitiesOfType(CIVILIAN, POLICE_FORCE, FIRE_BRIGADE)); // 修改这里，传入多个类型

    List<Human> rescueTargetsInCluster = filterInCluster(rescueTargets);
    List<Human> targets = rescueTargetsInCluster;
    if (targets.isEmpty()) {
      targets = rescueTargets;
    }

    logger.debug("Potential targets count: " + targets.size());
    if (!targets.isEmpty()) {
      // 使用优先级排序替代单纯距离排序
      targets.sort(new PrioritySorter(this.worldInfo, this.agentInfo.me()));
      logger.debug("Sorted targets: " + targets); // 需要Human类有好的toString方法，否则可输出ID

      // 检查路径可达性
      for (Human target : targets) {
        pathPlanning.setFrom(agentInfo.getPosition());
        pathPlanning.setDestination(target.getID());
        List<EntityID> path = pathPlanning.calc().getResult();

        if (path != null && !path.isEmpty()) {
          logger.debug("Selected target: " + target.getID() + " with path length: " + path.size());
          return target.getID();
        } else {
          logger.debug("No path to target: " + target.getID());
        }
      }
    } else {
      logger.debug("No valid rescue targets found.");
    }
    return null;
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  // 扩展支持救援其他类型人员
  private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {
    List<Human> rescueTargets = new ArrayList<>();
    for (StandardEntity next : list) {
      if (!(next instanceof Human))
        continue;
      Human h = (Human) next;

      // 扩展支持类型：救护员、消防员等
      if (h.getStandardURN() == POLICE_FORCE ||
          h.getStandardURN() == FIRE_BRIGADE) {
        // 特殊人员救援条件
        if (isAgentInDanger(h)) {
          rescueTargets.add(h);
        }
      }
      // 原有平民救援条件
      else if (isValidCivilian(h) && h.getBuriedness() > 0) { // 修改为 isValidCivilian
        rescueTargets.add(h);
      }
    }
    return rescueTargets;
  }

  private boolean isAgentInDanger(Human agent) {
    return agent.getBuriedness() > 0 ||
        agent.getHP() < 50 ||
        agent.getDamage() > 30;
  }

  // 专门检查平民是否有效
  private boolean isValidCivilian(Human civilian) {
    if (civilian == null)
      return false;

    // 基本状态检查
    if (!civilian.isHPDefined() || civilian.getHP() == 0)
      return false;
    if (!civilian.isPositionDefined())
      return false;
    // 注意：这里不再要求damage>0，因为平民伤害值可能为0但仍需救援（如仅被埋压）
    if (!civilian.isDamageDefined())
      return false;
    if (!civilian.isBuriednessDefined())
      return false;

    // 位置安全检查
    StandardEntity position = worldInfo.getPosition(civilian);
    if (position == null)
      return false;

    StandardEntityURN positionURN = position.getStandardURN();
    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM)
      return false;

    return true;
  }

  // 筛选属于当前集群的目标
  // 修改方法签名为接收 Human 集合
  private List<Human> filterInCluster(Collection<Human> humans) {
    List<Human> filter = new ArrayList<>();
    if (humans == null || humans.isEmpty())
      return filter;

    int clusterIndex = clustering.getClusterIndex(this.agentInfo.getID());
    Collection<StandardEntity> clusterEntities = clustering.getClusterEntities(clusterIndex);
    if (clusterEntities == null)
      return filter;

    HashSet<StandardEntity> inCluster = new HashSet<>(clusterEntities);

    for (Human h : humans) {
      if (!h.isPositionDefined())
        continue;

      StandardEntity position = this.worldInfo.getPosition(h);
      if (position == null)
        continue;

      if (inCluster.contains(position)) {
        filter.add(h);
      }
    }
    return filter;
  }

  // 添加优先级排序器（更新伤势严重程度）
  private class PrioritySorter implements Comparator<Human> {
    private StandardEntity reference;
    private WorldInfo worldInfo;

    PrioritySorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    public int compare(Human a, Human b) {
      // 计算优先级分数 = 伤势严重度/距离
      double priorityA = calculatePriority(a);
      double priorityB = calculatePriority(b);
      return Double.compare(priorityB, priorityA); // 降序排列
    }

    private double calculatePriority(Human human) {
      // 伤势权重：埋压值 > HP损失 > 伤害值
      double severity = 0;

      // 埋压值权重最高（如果有）
      if (human.isBuriednessDefined()) {
        severity += human.getBuriedness() * 100;
      }

      // 生命值权重
      if (human.isHPDefined()) {
        severity += (100 - human.getHP()) * 10;
      }

      // 伤害值权重
      if (human.isDamageDefined()) {
        severity += human.getDamage() * 5;
      }

      // 距离因子（避免除零）
      int distance = worldInfo.getDistance(reference, human);
      return severity / (distance + 1);
    }
  }
}