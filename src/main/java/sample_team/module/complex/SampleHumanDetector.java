package sample_team.module.complex;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_TEAM;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
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

  private Clustering clustering;
  private EntityID result;
  private Logger logger;

  public SampleHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, 
                            ModuleManager moduleManager, DevelopData developData) {
    super(ai, wi, si, moduleManager, developData);
    logger = DefaultLogger.getLogger(agentInfo.me());
    this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
        "adf.impl.module.algorithm.KMeansClustering");
    registerModule(this.clustering);
  }

  @Override
  public HumanDetector updateInfo(MessageManager messageManager) {
    logger.debug("Time: " + agentInfo.getTime());
    super.updateInfo(messageManager);
    return this;
  }

  @Override
  public HumanDetector calc() {
    // 检查是否正在运输伤员
    Human transportHuman = agentInfo.someoneOnBoard();
    if (transportHuman != null) {
      logger.debug("Transporting human: " + transportHuman);
      result = transportHuman.getID();
      return this;
    }

    // 重置无效目标
    if (result != null) {
      Human target = (Human) worldInfo.getEntity(result);
      if (!isValidHuman(target)) {
        logger.debug("Invalid target: " + target + " - resetting");
        result = null;
      }
    }

    // 计算新目标
    if (result == null) {
      result = calcOptimalTarget();
    }
    
    return this;
  }

  private EntityID calcOptimalTarget() {
    // 获取所有可能的救援目标（包括平民和其他救援人员）
    List<Human> potentialTargets = filterRescueTargets(
        worldInfo.getEntitiesOfType(CIVILIAN, AMBULANCE_TEAM, FIRE_BRIGADE, POLICE_FORCE)
    );

    // 优先处理聚类区域内的目标
    List<Human> clusterTargets = filterInCluster(potentialTargets);
    List<Human> finalTargets = !clusterTargets.isEmpty() ? clusterTargets : potentialTargets;

    logger.debug("Potential targets: " + finalTargets.size());
    
    if (finalTargets.isEmpty()) {
      logger.debug("No valid targets found");
      return null;
    }

    // 使用多因素评分系统选择最佳目标
    finalTargets.sort(new TargetPrioritySorter(worldInfo, agentInfo.me()));
    Human selected = finalTargets.get(0);
    
    // 记录详细选择依据
    logger.debug("Selected target: " + selected + 
                " | Buriedness: " + selected.getBuriedness() +
                " | HP: " + selected.getHP() +
                " | Distance: " + worldInfo.getDistance(agentInfo.me(), selected));
    
    return selected.getID();
  }

  @Override
  public EntityID getTarget() {
    return this.result;
  }

  private List<Human> filterRescueTargets(Collection<? extends StandardEntity> list) {
    List<Human> rescueTargets = new ArrayList<>();
    for (StandardEntity next : list) {
      if (!(next instanceof Human)) continue;
      
      Human h = (Human) next;
      if (isValidHuman(h)) {
        rescueTargets.add(h);
      }
    }
    return rescueTargets;
  }

  private List<Human> filterInCluster(Collection<? extends StandardEntity> entities) {
    int clusterIndex = clustering.getClusterIndex(agentInfo.getID());
    List<Human> filter = new ArrayList<>();
    HashSet<StandardEntity> inCluster = new HashSet<>(
        clustering.getClusterEntities(clusterIndex));
    
    for (StandardEntity next : entities) {
      if (!(next instanceof Human)) continue;
      
      Human h = (Human) next;
      if (!h.isPositionDefined()) continue;
      
      StandardEntity position = worldInfo.getPosition(h);
      if (position == null) continue;
      
      if (inCluster.contains(position)) {
        filter.add(h);
      }
    }
    return filter;
  }

  // 多因素优先级排序器
  private class TargetPrioritySorter implements Comparator<Human> {
    private final StandardEntity reference;
    private final WorldInfo worldInfo;

    TargetPrioritySorter(WorldInfo wi, StandardEntity reference) {
      this.reference = reference;
      this.worldInfo = wi;
    }

    @Override
    public int compare(Human a, Human b) {
      // 因素1: 被埋程度优先级（越高越优先）
      int buriednessPriority = Integer.compare(b.getBuriedness(), a.getBuriedness());
      if (buriednessPriority != 0) return buriednessPriority;
      
      // 因素2: 生命值优先级（越低越优先）
      int hpPriority = Integer.compare(a.getHP(), b.getHP());
      if (hpPriority != 0) return hpPriority;
      
      // 因素3: 距离优先级（越近越优先）
      return Integer.compare(
          worldInfo.getDistance(reference, a),
          worldInfo.getDistance(reference, b)
      );
    }
  }

  private boolean isValidHuman(StandardEntity entity) {
    if (entity == null) return false;
    if (!(entity instanceof Human)) return false;

    Human target = (Human) entity;
    
    // 基础属性检查
    if (!target.isHPDefined() || target.getHP() == 0) return false;
    if (!target.isPositionDefined()) return false;
    if (!target.isDamageDefined() || target.getDamage() == 0) return false;
    if (!target.isBuriednessDefined() || target.getBuriedness() == 0) return false;

    // 位置检查
    StandardEntity position = worldInfo.getPosition(target);
    if (position == null) return false;

    // 排除安全区域的目标
    StandardEntityURN positionURN = position.getStandardURN();
    if (positionURN == REFUGE || positionURN == AMBULANCE_TEAM) return false;

    return true;
  }
}