package AIT_2022.module.util;

// rescue

import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.Area;
import adf.core.agent.action.Action;
// AIT module
import AIT_2022.module.algorithm.ConvexHull;
// visual-debugger
//import com.mrl.debugger.remote.VDClient;
// java
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.awt.Polygon;
import java.awt.geom.Line2D;

/**
 * DebugUtil module make output for debug. If you want to add new debug message or visual-debugging,
 * you have to implement the method on this class.
 */
public class DebugUtil {

  private WorldInfo worldInfo;
  private AgentInfo agentInfo;
  private ScenarioInfo scenarioInfo;
  private EntityID myId;
  private StandardEntity myEntity;
  private StandardEntityURN myUrn;

  //private VDClient vdclient = VDClient.getInstance();

  public DebugUtil(AgentInfo ai, WorldInfo wi, ScenarioInfo si) {
    this.worldInfo = wi;
    this.agentInfo = ai;
    this.scenarioInfo = si;
    this.myId = this.agentInfo.getID();
    this.myEntity = this.worldInfo.getEntity(myId);
    this.myUrn = this.myEntity.getStandardURN();
  }

  /**
   * This method display which agent is using the module that called this method. Used to check if
   * the module specified in config.cfg is used.
   */
  public void whoCalledWhat() {
    System.out.println(Thread.currentThread().getStackTrace()[2].getClassName()
        + "is called by [" + this.myUrn + "(" + this.myId + ")]");
  }

  /**
   * This method stdout ids collection with prefix
   *
   * @param collection You want to display
   * @param time       when info
   * @param prefix     for identify
   */
  public void showIdCollection(Collection<EntityID> collection, int time, String prefix) {
    System.out.println("[" + prefix + "]" + "(" + this.myId + ")"
        + "@" + time + ":" + collection.toString());
  }

  /**
   * This method stdout one type ids collection with prefix
   *
   * @param collection You want to display
   * @param time       when info
   * @param prefix     for identify
   * @param urn        You want to see type
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
   * This method stdout message
   *
   * @param prefix  Prefix to specify the meaning of information
   * @param message The message you want to display
   */
  public void infoMessage(String prefix, String message) {
    System.out.println("[" + prefix + "]" + message + " @ "
        + Thread.currentThread().getStackTrace()[2].getClassName()
        + "(" + Thread.currentThread().getStackTrace()[2].getMethodName() + ")");
  }

  /**
   * This method stdout module result(EntityID)
   *
   * @param result     Result of EntityID
   * @param moduleName Module with result
   * @param time       Time to get results
   */
  public void showResult(EntityID result, String moduleName, int time) {
    this.showResult(result, moduleName, time, "");
  }

  /**
   * This method stdout module result(EntityID)
   *
   * @param result     Result of EntityID
   * @param moduleName Module with result
   * @param time       Time to get results
   * @param message    Information you want to add
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
   * This method stdout action
   *
   * @param action Executed action
   * @param time   Time when action was executed
   */
  public void showAction(Action action, int time) {
    String sMyId = String.format("%10d", this.myId.getValue());
    String sTime = String.format("%3d", time);
    System.out.println("[ACTION] " + sMyId + " " + sTime + " " + action.toString());
  }

  /**
   * This method stdout action
   *
   * @param action  Executed action
   * @param time    Time when action was executed
   * @param message Information you want to add
   */
  public void showAction(Action action, int time, String message) {
    if (action == null) {
      System.out.println(
          "[ERROR] showAction : action is null(" + time + " " + this.myId + " " + message + ")");
      return;
    }
    String sMyId = String.format("%10d", this.myId.getValue());
    String sTime = String.format("%3d", time);
    System.out.println("[ACTION] " + sMyId + " " + sTime + " " + action.toString());
  }

  /**
   * This method stdout csv format by passing the information you want to display as a list.
   *
   * @param prefix      Prefix to specify the meaning of information
   * @param messageList List of information you want to display
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

// Visual-Debugger

  /**
   * This method display the cluster of agent "myId" on visual-debugger NOTE:If you use this method,
   * you have to delete comment out vdclient
   *
   * @param cluster StandardEntity collection in cluster.
   */
  public void clusteringResult(Collection<StandardEntity> cluster) {
    // visual-debugger client connect to visual-debugger server
    //this.vdclient.init();

    // make convex Hull
    ConvexHull convexhull = new ConvexHull();
    for (StandardEntity entity : cluster) {
      convexhull.add((Area) entity);
    }

    //format data & draw
    ArrayList<Polygon> data = new ArrayList<>(1);
    data.add(convexhull.get());
    //this.vdclient.draw(this.myId.getValue(), "SamplePolygon", data);
  }

  /**
   * This method display the path on visual-debugger NOTE:If you use this method, you have to delete
   * comment out vdclient
   *
   * @param path List of EntityID in path
   */
  public void pathPlanningResult(List<EntityID> path) {
    // visual-debugger client connect to visual-debugger server
    //this.vdclient.init();

    // make collection of Line2D
    ArrayList<Line2D> lines = new ArrayList<>();
    for (int ind = 1; ind < path.size(); ++ind) {
      Area area1 = (Area) this.worldInfo.getEntity(path.get(ind));
      Area area2 = (Area) this.worldInfo.getEntity(path.get(ind - 1));
      Line2D line = new Line2D.Double(area1.getX(), area1.getY(),
          area2.getX(), area2.getY());
      lines.add(line);
    }

    //draw
    //this.vdclient.draw(this.myId.getValue(), "SampleLine", lines);
  }
}