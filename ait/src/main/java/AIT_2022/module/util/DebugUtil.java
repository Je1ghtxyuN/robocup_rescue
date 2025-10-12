package AIT_2022.module.util;

// 救援相关导入

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.Area;
import adf.core.agent.action.Action;
// AIT模块
import AIT_2022.module.algorithm.ConvexHull;
// 可视化调试器
import com.mrl.debugger.remote.VDClient;
// Java相关
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.awt.Polygon;
import java.awt.geom.Line2D;

/**
 * DebugUtil模块用于生成调试输出。如果你想添加新的调试消息或可视化调试功能，
 * 必须在此类中实现相应的方法。
 */
public class DebugUtil {

  private WorldInfo worldInfo;
  private AgentInfo agentInfo;
  private ScenarioInfo scenarioInfo;
  private EntityID myId;
  private StandardEntity myEntity;
  private StandardEntityURN myUrn;

  private VDClient vdclient = VDClient.getInstance();

  public DebugUtil(AgentInfo ai, WorldInfo wi, ScenarioInfo si) {
    this.worldInfo = wi;
    this.agentInfo = ai;
    this.scenarioInfo = si;
    this.myId = this.agentInfo.getID();
    this.myEntity = this.worldInfo.getEntity(myId);
    this.myUrn = this.myEntity.getStandardURN();
  }

  /**
   * 此方法显示哪个智能体正在调用调用此方法的模块。
   * 用于检查config.cfg中指定的模块是否被使用。
   */
  public void whoCalledWhat() {
    System.out.println(Thread.currentThread().getStackTrace()[2].getClassName()
        + "被 [" + this.myUrn + "(" + this.myId + ")] 调用");
  }

  /**
   * 此方法使用前缀标准输出ID集合
   *
   * @param collection 你想要显示的集合
   * @param time       时间信息
   * @param prefix     用于标识的前缀
   */
  public void showIdCollection(Collection<EntityID> collection, int time, String prefix) {
    System.out.println("[" + prefix + "]" + "(" + this.myId + ")"
        + "@" + time + ":" + collection.toString());
  }

  /**
   * 此方法使用前缀标准输出特定类型的ID集合
   *
   * @param collection 你想要显示的集合
   * @param time       时间信息
   * @param prefix     用于标识的前缀
   * @param urn        你想要查看的类型
   */
  public void showIdCollectionPeekType(
      Collection<EntityID> collection, int time,
      String prefix, StandardEntityURN urn) {
    Collection<EntityID> peeked = collection.stream()
        .map(this.worldInfo::getEntity)
        .filter(e -> e.getStandardURN().equals(urn))
        .map(StandardEntity::getID)
        .collect(Collectors.toSet());
    this.showIdCollection(peeked, time, prefix);
  }

  /**
   * 此方法标准输出消息
   *
   * @param prefix  用于指定信息含义的前缀
   * @param message 你想要显示的消息
   */
  public void infoMessage(String prefix, String message) {
    System.out.println("[" + prefix + "]" + message + " @ "
        + Thread.currentThread().getStackTrace()[2].getClassName()
        + "(" + Thread.currentThread().getStackTrace()[2].getMethodName() + ")");
  }

  /**
   * 此方法标准输出模块结果(EntityID)
   *
   * @param result     实体ID结果
   * @param moduleName 产生结果的模块
   * @param time       获取结果的时间
   */
  public void showResult(EntityID result, String moduleName, int time) {
    this.showResult(result, moduleName, time, "");
  }

  /**
   * 此方法标准输出模块结果(EntityID)
   *
   * @param result     实体ID结果
   * @param moduleName 产生结果的模块
   * @param time       获取结果的时间
   * @param message    你想要添加的信息
   */
  public void showResult(EntityID result, String moduleName, int time, String message) {
    String sResult = "      NULL";
    if (result != null) {
      sResult = String.format("%10d", result.getValue());
    }
    String sMyId = String.format("%10d", this.myId.getValue());
    String sTime = String.format("%3d", time);
    System.out.println("[RESULT]" + sMyId + " " + sTime + " " + sResult
        + " " + moduleName + " " + message);
  }

  /**
   * 此方法标准输出动作
   *
   * @param action 执行的动作
   * @param time   动作执行的时间
   */
  public void showAction(Action action, int time) {
    String sMyId = String.format("%10d", this.myId.getValue());
    String sTime = String.format("%3d", time);
    System.out.println("[ACTION] " + sMyId + " " + sTime + " " + action.toString());
  }

  /**
   * 此方法标准输出动作
   *
   * @param action  执行的动作
   * @param time    动作执行的时间
   * @param message 你想要添加的信息
   */
  public void showAction(Action action, int time, String message) {
    if (action == null) {
      System.out.println(
          "[ERROR] showAction : 动作为空(" + time + " " + this.myId + " " + message + ")");
      return;
    }
    String sMyId = String.format("%10d", this.myId.getValue());
    String sTime = String.format("%3d", time);
    System.out.println("[ACTION] " + sMyId + " " + sTime + " " + action.toString());
  }

  /**
   * 此方法通过传递你想要显示的信息列表，以CSV格式标准输出
   *
   * @param prefix      用于指定信息含义的前缀
   * @param messageList 你想要显示的信息列表
   */
  public void csvFormatStdout(String prefix, List<String> messageList) {
    StringBuilder message = new StringBuilder("[");
    message.append(prefix);
    message.append("],");
    for (String m : messageList) {
      message.append(m);
      message.append(",");
    }
    message.deleteCharAt(message.length() - 1);
    System.out.println(message.toString());
  }

// 可视化调试器

  /**
   * 此方法在可视化调试器上显示智能体"myId"的聚类结果
   * 注意：如果你使用此方法，必须取消vdclient的注释
   *
   * @param cluster 聚类中的StandardEntity集合
   */
  public void clusteringResult(Collection<StandardEntity> cluster) {
    // 可视化调试器客户端连接到可视化调试器服务器
    this.vdclient.init();

    // 创建凸包
    ConvexHull convexhull = new ConvexHull();
    for (StandardEntity entity : cluster) {
      convexhull.add((Area) entity);
    }

    // 格式化数据并绘制
    ArrayList<Polygon> data = new ArrayList<>(1);
    data.add(convexhull.get());
    this.vdclient.draw(this.myId.getValue(), "SamplePolygon", data);
  }

  /**
   * 此方法在可视化调试器上显示路径
   * 注意：如果你使用此方法，必须取消vdclient的注释
   *
   * @param path 路径中的EntityID列表
   */
  public void pathPlanningResult(List<EntityID> path) {
    // 可视化调试器客户端连接到可视化调试器服务器
    this.vdclient.init();

    // 创建Line2D集合
    ArrayList<Line2D> lines = new ArrayList<>();
    for (int ind = 1; ind < path.size(); ++ind) {
      Area area1 = (Area) this.worldInfo.getEntity(path.get(ind));
      Area area2 = (Area) this.worldInfo.getEntity(path.get(ind - 1));
      Line2D line = new Line2D.Double(area1.getX(), area1.getY(),
          area2.getX(), area2.getY());
      lines.add(line);
    }

    // 绘制
    this.vdclient.draw(this.myId.getValue(), "SampleLine", lines);
  }
}