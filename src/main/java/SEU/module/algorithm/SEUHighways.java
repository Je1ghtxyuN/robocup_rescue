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
import java.util.*;
// 显式导入Collectors
import java.util.stream.*;

import static java.util.Comparator.*;

/**
 * SEUHighways 类实现了一个基于高速公路的静态聚类算法
 * 该算法通过采样关键位置并在它们之间计算最短路径添加到地图中的"高速公路"静态聚类
 * 这些高速公路可用于优化救援路径规划
 */

public class SEUHighways extends StaticClustering {

  // 存储被识别为高速公路的实体ID集合
  private Set<EntityID> highways = new HashSet<>();

  // 路径规划器，用于计算两点之间的路径
  private PathPlanning pathplanner;

  // 采样点的数量，用于确定要计算路径的关键点数量
  protected static final int SAMPLE_NUMBER = 10;

  private static final String MODULE_NAME =
      "SEU.module.algorithm.SEUHighways";
  private static final String PD_HIGHWAYS = MODULE_NAME + ".highways";

  // Debug文件路径
  private static final String DEBUG_FILE_PATH = "./logs/highways_debug.txt";

  public SEUHighways(
      AgentInfo ai, WorldInfo wi, ScenarioInfo si,
      ModuleManager mm, DevelopData dd) {
    super(ai, wi, si, mm, dd);

    this.pathplanner = mm.getModule("SEU.Algorithm.Highways.PathPlanning");
    this.registerModule(this.pathplanner);
  }

  @Override
  public Clustering calc() {
    return this;
  }

  @Override
  public Clustering updateInfo(MessageManager mm) {
    super.updateInfo(mm);
    if (this.getCountUpdateInfo() > 1) {
      return this;
    }
    return this;
  }

   /**
   * 预计算阶段 - 构建高速公路网络
   */
  @Override
  public Clustering precompute(PrecomputeData pd) {
    super.precompute(pd);
    if (this.getCountPrecompute() > 1) {
      return this;
    }

    // 构建高速公路网络
    this.build();

    // 将高速公路信息保存到预计算数据中
    pd.setEntityIDList(PD_HIGHWAYS, new ArrayList<>(this.highways));
    
    // 输出Debug信息
    this.debugHighways();

    // 添加this引用检查
    System.out.println("SEUHighways实例: " + this);
    System.out.println("高速公路数量: " + this.highways.size());

    // 输出可视化结果
    visualizeHighways();

    return this;
  }

  public void visualizeHighways() {
    try {
        System.out.println("开始高速公路可视化...");
        
        // 获取所有实体用于可视化
        List<StandardEntity> allEntities = new ArrayList<>(
            this.worldInfo.getEntitiesOfType(
                BUILDING, GAS_STATION, REFUGE,
                FIRE_STATION, AMBULANCE_CENTRE, POLICE_OFFICE,
                ROAD, HYDRANT));
        
        System.out.println("获取到的实体数量: " + allEntities.size());
        System.out.println("高速公路数量: " + this.highways.size());
        
        if (allEntities.isEmpty()) {
            System.err.println("警告: 没有找到可可视化的实体");
            return;
        }
        
        if (this.highways.isEmpty()) {
            System.err.println("警告: 高速公路集合为空");
        }
        
        // 修复：传递正确的this引用，而不是null
        HighwayVisualizer visualizer = new HighwayVisualizer(this, allEntities);
        System.out.println("可视化器创建成功");
        
        // 启动可视化
        visualizer.visualize();
        System.out.println("可视化方法调用完成");
        
      } catch (Exception e) {
          System.err.println("可视化高速公路时出错: " + e.getMessage());
          e.printStackTrace();
      }
  }

  @Override
  public Clustering resume(PrecomputeData pd) {
    super.resume(pd);
    if (this.getCountResume() > 1) {
      return this;
    }

    this.highways = new HashSet<>(pd.getEntityIDList(PD_HIGHWAYS));
    return this;
  }

  @Override
  public Clustering preparate() {
    super.preparate();
    if (this.getCountPreparate() > 1) {
      return this;
    }
    return this;
  }

  /**
   * 获取聚类数量（这里只有1个聚类 - 高速公路本身）
   */
  @Override
  public int getClusterNumber() {
    return 1;
  }

  /**
   * 获取实体所属的聚类索引
   * @param entity 标准实体
   * @return 聚类索引（0表示高速公路，-1表示非高速公路）
   */
  @Override
  public int getClusterIndex(StandardEntity entity) {
    return this.getClusterIndex(entity.getID());
  }

  /**
   * 获取实体ID所属的聚类索引
   * @param id 实体ID
   * @return 聚类索引（0表示高速公路，-1表示非高速公路）
   */
  @Override
  public int getClusterIndex(EntityID id) {
    return this.highways.contains(id) ? 0 : -1;
  }

  @Override
  public Collection<EntityID> getClusterEntityIDs(int i) {
    return i == 0 ? new HashSet<>(this.highways) : null;
  }

  @Override
  public Collection<StandardEntity> getClusterEntities(int i) {
    Collection<EntityID> ids = this.getClusterEntityIDs(i);
    if (ids == null) {
      return null;
    }

    Stream<StandardEntity> entities =
        ids.stream().map(this.worldInfo::getEntity);
    return entities.collect(Collectors.toList());
  }

  /**
   * 构建高速公路网络的核心方法
   * 1. 采样关键位置
   * 2. 计算所有采样点之间的路径
   * 3. 将这些路径标记为高速公路
   */
  private void build() {
    final int n = SAMPLE_NUMBER;
    // 采样关键位置
    List<EntityID> samples = new ArrayList<>(this.sample(n));
    //this.highways.addAll(samples);

    // 计算所有采样点之间的路径
    for (int i = 0; i < n; ++i) {
      for (int j = i + 1; j < n; ++j) {
        this.pathplanner.setFrom(samples.get(i));
        this.pathplanner.setDestination(samples.get(j));
        this.pathplanner.calc();
        // 将路径中的所有点添加到高速公路集合
        this.highways.addAll(this.pathplanner.getResult());
      }
    }
  }

  /**
   * 采样方法 - 使用K-means++算法选择关键位置
   * n为采样数量
   * @return 采样得到的实体ID集合
   */
  private Set<EntityID> sample(int n) {
    List<StandardEntity> entities = new ArrayList<>(
        this.worldInfo.getEntitiesOfType(
            //ROAD, HYDRANT,
            BUILDING, GAS_STATION, REFUGE,
            FIRE_STATION, AMBULANCE_CENTRE, POLICE_OFFICE));
    entities.sort(comparing(e -> e.getID().getValue()));
    final int size = entities.size();

    EntityID[] is = new EntityID[size];
    double[] xs = new double[size];
    double[] ys = new double[size];

    for (int i = 0; i < size; ++i) {
      Area area = (Area) entities.get(i);
      is[i] = area.getID();
      xs[i] = area.getX();
      ys[i] = area.getY();
    }

    SEUKMeansPP kmeans = new SEUKMeansPP(is, xs, ys, n);
    kmeans.execute(10);

    Set<EntityID> ret = new HashSet<>();
    for (int i = 0; i < n; ++i) {
      final double cx = kmeans.getClusterX(i);
      final double cy = kmeans.getClusterY(i);

      Optional<Area> sampled = kmeans.getClusterMembers(i)
          .stream()
          .map(this.worldInfo::getEntity)
          .map(Area.class::cast)
          .min((m1, m2) -> {
            final double x1 = m1.getX();
            final double y1 = m1.getY();
            final double x2 = m2.getX();
            final double y2 = m2.getY();

            final double d1 = Math.hypot(x1 - cx, y1 - cy);
            final double d2 = Math.hypot(x2 - cx, y2 - cy);

            return Double.compare(d1, d2);
          });

      sampled.ifPresent(m -> ret.add(m.getID()));
    }

    return ret;
  }

  /**
   * Debug方法：输出高速公路信息到文件
   */
  private void debugHighways() {
    try {
      // 创建logs目录（如果不存在）
      java.io.File logsDir = new java.io.File("./logs");
      if (!logsDir.exists()) {
        logsDir.mkdirs();
      }
      
      java.io.PrintWriter writer = new java.io.PrintWriter(DEBUG_FILE_PATH, "UTF-8");
      
      writer.println("=== SEUHighways Debug 信息 ===");
      writer.println("生成时间: " + new java.util.Date());
      writer.println("高速公路总数: " + this.highways.size());
      writer.println();
      
      writer.println("采样点数量: " + SAMPLE_NUMBER);
      
      // 使用传统循环避免stream类型推断问题
      Map<String, Long> typeCounts = new HashMap<>();
      for (EntityID id : this.highways) {
          StandardEntity entity = this.worldInfo.getEntity(id);
          String type = entity != null ? String.valueOf(entity.getURN()) : "UNKNOWN";
          typeCounts.put(type, typeCounts.getOrDefault(type, 0L) + 1);
      }
      
      writer.println("高速公路实体类型分布:");
      for (Map.Entry<String, Long> entry : typeCounts.entrySet()) {
          writer.println("  " + entry.getKey() + ": " + entry.getValue() + " 个");
      }
      writer.println();
      
      // 详细列出所有高速公路实体
      writer.println("详细高速公路实体列表:");
      List<String> entityInfoList = new ArrayList<>();
      for (EntityID id : this.highways) {
          StandardEntity entity = this.worldInfo.getEntity(id);
          String info;
          if (entity instanceof Area) {
              Area area = (Area) entity;
              info = String.format("ID: %d, 类型: %s, 坐标: (%d, %d)", 
                  id.getValue(), entity.getURN(), area.getX(), area.getY());
          } else {
              info = String.format("ID: %d, 类型: %s", 
                  id.getValue(), entity != null ? entity.getURN() : "UNKNOWN");
          }
          entityInfoList.add(info);
      }
      
      Collections.sort(entityInfoList);
      for (String info : entityInfoList) {
          writer.println(info);
      }
      
      writer.close();
      
      System.out.println("高速公路Debug信息已输出到: " + DEBUG_FILE_PATH);
      System.out.println("高速公路总数: " + this.highways.size());
      
    } catch (Exception e) {
      System.err.println("写入Debug文件时出错: " + e.getMessage());
      // 如果文件写入失败，在控制台输出基本信息
      System.out.println("高速公路总数: " + this.highways.size());
      
      // 使用传统方式构建ID列表字符串
      List<Integer> idList = new ArrayList<>();
      for (EntityID id : this.highways) {
          idList.add(id.getValue());
      }
      Collections.sort(idList);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < idList.size(); i++) {
          if (i > 0) sb.append(", ");
          sb.append(idList.get(i));
      }
      System.out.println("高速公路实体ID列表: " + sb.toString());
    }
  }
}