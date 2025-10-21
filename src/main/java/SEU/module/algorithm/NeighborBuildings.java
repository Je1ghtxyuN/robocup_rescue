package SEU.module.algorithm;

import adf.core.component.module.algorithm.*;
import adf.core.agent.info.*;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import rescuecore2.standard.entities.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

import rescuecore2.worldmodel.EntityID;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.stream.*;

import static java.util.stream.Collectors.*;
/**
 * 邻近建筑物聚类算法类
 * 继承自StaticClustering，用于计算每个建筑物周围一定范围内的邻近建筑物，并将其组织为聚类结构
 */
public class NeighborBuildings extends StaticClustering {
    /**
     * 存储每个建筑物实体与其邻近建筑物集合的映射关系
     * 键：建筑物实体ID；值：该建筑物的邻近建筑物ID集合
     */
  private final Map<EntityID, Set<EntityID>> neighbors = new HashMap<>();
    /**
     * 定义搜索范围的缩放因子，用于计算建筑物的搜索范围
     */
  private final static double SCALE = 1.5;
    /**
     * 构造函数，初始化邻近建筑物聚类算法
     * @param ai 智能体信息对象
     * @param wi 世界信息对象
     * @param si 场景信息对象
     * @param mm 模块管理器对象
     * @param dd 开发数据对象
     */
  public NeighborBuildings(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);
  }
    /**
     * 核心计算方法，计算每个建筑物的邻近建筑物
     * @return 当前对象实例（支持链式调用）
     */
  @Override
  public Clustering calc() {
      // 获取环境中所有建筑物实体的ID集合
    final Set<EntityID> buildings =
        this.worldInfo.getAllEntities()
            .stream()
            .filter(Building.class::isInstance)
            .map(StandardEntity::getID)
            .collect(toSet());
      // 清空现有的邻近建筑物映射，准备重新计算
    this.neighbors.clear();
      // 对每个建筑物计算其邻近建筑物
    for (EntityID id : buildings) {
        // 获取建筑物的矩形边界
      final Rectangle rect = this.toRect(id);
      // 计算建筑物的搜索范围，取其宽度和高度的较大值乘以缩放因子
      final int range = (int) (Math.max(rect.width, rect.height) * SCALE);
      // 过滤出在搜索范围内的建筑物实体ID
      final Stream<EntityID> tmp =
          this.worldInfo.getObjectIDsInRange(id, range)
              .stream()
              .filter(buildings::contains);
        // 存储计算结果
      this.neighbors.put(id, tmp.collect(toSet()));
    }
      // 返回当前实例，支持链式调用
    return this;
  }
    /**
     * 预计算阶段方法
     * @param pd 预计算数据对象
     * @return 当前对象实例
     */
  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    // 防止重复计算的优化：如果已经调用过至少2次，则直接返回
    if (this.getCountPrecompute() >= 2) {
      return this;
    }
    return this;
  }
    /**
     * 恢复阶段方法
     * @param precomputeData 预计算数据对象
     * @return 当前对象实例
     */
  @Override
  public Clustering resume(PrecomputeData precomputeData) {
    super.resume(precomputeData);
        // 防止重复计算的优化：如果已经调用过至少2次，则直接返回
    if (this.getCountResume() >= 2) {
      return this;
    }
      // 首次调用时执行实际计算
    this.calc();
    return this;
  }
    /**
     * 准备阶段方法
     * @return 当前对象实例
     */
  @Override
  public Clustering preparate() {
    super.preparate();
        // 防止重复计算的优化：如果已经调用过至少2次，则直接返回
    if (this.getCountPreparate() >= 2) {
      return this;
    }
      // 首次调用时执行实际计算
    this.calc();
    return this;
  }
    /**
     * 获取聚类数量方法
     * @return 聚类数量（即建筑物数量）
     */
  @Override
  public int getClusterNumber() {
    return this.neighbors.size();
  }
    /**
     * 获取实体所在聚类索引方法
     * @param entity 标准实体对象
     * @return 实体所在聚类索引（即建筑物ID）
     */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }
    /**
     * 获取实体所在聚类索引方法（根据实体ID）
     * @param id 实体ID对象
     * @return 实体所在聚类索引（即建筑物ID）
     */
  @Override
  public int getClusterIndex(EntityID id) {
    return id.getValue();
  }
    /**
     * 获取指定聚类索引对应的所有实体对象
     * @param i 聚类索引
     * @return 实体对象集合
     */
  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    final Stream<StandardEntity> tmp = this.getClusterEntityIDs(i)
        .stream()
        .map(this.worldInfo::getEntity);

    return tmp.collect(toList());
  }
    /**
     * 获取指定聚类索引对应的所有实体ID对象
     * @param i 聚类索引
     * @return 实体ID对象集合
     */
  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    return this.neighbors.get(new EntityID(i));
  }
    /**
     * 将实体ID转换为矩形边界方法
     * @param id 实体ID对象
     * @return 矩形边界对象
     */
  private Rectangle toRect(EntityID id) {
    final Area area = (Area) this.worldInfo.getEntity(id);
    return area.getShape().getBounds();
  }
}
